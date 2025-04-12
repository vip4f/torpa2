/* Copyright (c) 2025, Vladimir Ivanovskiy
 * All rights reserved.
 *
 * This software is licensed under
 *      GNU GENERAL PUBLIC LICENSE
 *      Version 3, 29 June 2007.
 *
 * ------------------------------------------------------------------------------------
 * Created on 2/28/25.
 */


package llmtest.torpa2


import cats.effect.{IO, Resource}
import com.google.cloud.vertexai.api.{FunctionDeclaration, Schema, Tool, Type}
import com.google.protobuf.Struct
import io.circe.*
import llmtest.torpa2.ChatLoop.ChatLogger
import org.python.core.{PyCode, PyObject}
import org.python.util.PythonInterpreter
import scala.io.Source
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal
import scala.util.Using


class PyScriptTool(extApi: ExtApi) extends ChatLoop.NamedTool {

  val name: String = "runPyScriptV2"
  val tool: Tool = {
    val fd = Using(Source.fromResource("pyScriptV2.txt"))(_.mkString).get
    val script = Schema.newBuilder()
      .setType(Type.STRING)
      .setDescription("Python code to execute")
      .build()
    val params = Schema.newBuilder()
      .setType(Type.OBJECT)
      .putProperties("script", script)
      .addRequired("script")
      .build()
    val func = FunctionDeclaration.newBuilder()
      .setName(name)
      .setDescription(fd)
      .setParameters(params)
      .build()
    Tool.newBuilder().addFunctionDeclarations(func).build()
  }
  def call(logger: ChatLogger, args: Struct): IO[Either[String, String]] = {
    if args.containsFields("script") then {
      val script = args.getFieldsOrThrow("script").getStringValue
      logger.info("Try to execute code: " + script) // TBR
      runScript(script).map(result => {
        result match { // TBR
          case Left(s) => logger.error("Code execution failed. Details: " + s)
          case Right(r) => logger.info("Code execution result: " + r)
        }
        result
      })
    } else IO.raiseError(new RuntimeException("No script argument!"))
  }

  private def interpreter: Resource[IO, PythonInterpreter] = {
    val acquire = IO.blocking {
      val i = new PythonInterpreter
      i.set("extApi", extApi)
      i.exec("# coding=utf-8")
      i
    }
    def release(i: PythonInterpreter) = IO.blocking(i.close())
    Resource.make(acquire)(release)
  }

  private def compile(i: PythonInterpreter, script: String): IO[Either[String, PyCode]] = IO.blocking {
    try
      Right(i.compile(script))
    catch
      case NonFatal(t) => Left(
        s"""The following code failed compilation:
           |
           |$script
           |
           |with error message ${t.getMessage}.
           |
           |Fix the code and re-try calling the function.
           |Do not use markdown. Provide only plain text.""".stripMargin
      )
  }

  private def pyObj2Json(po: PyObject): Json = po.getType.getName match {
    case "int" => Json.fromInt(po.asInt)
    case "long" => Json.fromLong(po.asLong)
    case "str" => Json.fromString(po.asString)
    case "unicode" => Json.fromString(po.asString)
    case "bool" => Json.fromBoolean(po.__tojava__(classOf[Boolean]).asInstanceOf[Boolean])
    case "list" => Json.fromValues(po.asIterable().asScala.map(pyObj2Json))
    case "dict" => Json.fromFields(po.asIterable().asScala.map(k => (k.asString(), pyObj2Json(po.__finditem__(k)))))
    case "tuple" => Json.fromFields((1 to po.__len__()).map(n => (s"_$n", pyObj2Json(po.__getitem__(n - 1)))))
    case "float" => Json.fromDouble(po.asDouble).getOrElse(
      sys.error(s"Value ${po.__str__().asString()} cannot be represented as double number"))
    case "NoneType" => Json.Null
    case t@_ => sys.error(s"Type $t was not implemented yet.")
  }

  private def execute(i: PythonInterpreter, code: PyCode): IO[Either[String, String]] = IO.blocking {
    try {
      i.exec(code)
      i.exec("result = script(extApi)")
      val result = i.get("result")
      val json = pyObj2Json(result)
      Right(json.spaces2)
    } catch {
      case NonFatal(t) => Left(
        s"""The python code execution failed with the following exception:
           |
           |${t.getMessage}
           |
           |Fix the code and re-try calling the function.
           |Do not use markdown. Provide only plain text.""".stripMargin
      )
    }
  }

  private def runScript(script: String): IO[Either[String, String]] = interpreter.use(i => for {
    errOrCode <- compile(i, script)
    result <- errOrCode match {
      case Left(err) => IO.pure(Left(err))
      case Right(code) => execute(i, code)
    }
  } yield result)

}
