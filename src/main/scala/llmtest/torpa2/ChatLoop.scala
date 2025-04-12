/* Copyright (c) 2025, Vladimir Ivanovskiy
 * All rights reserved.
 *
 * This software is licensed under
 *      GNU GENERAL PUBLIC LICENSE
 *      Version 3, 29 June 2007.
 *
 * ------------------------------------------------------------------------------------
 * Created on 2/20/25.
 */


package llmtest.torpa2


import cats.effect.{IO, Resource}
import com.google.cloud.vertexai.VertexAI
import com.google.cloud.vertexai.api.*
import com.google.cloud.vertexai.api.SafetySetting.HarmBlockThreshold
import com.google.cloud.vertexai.generativeai.{ChatSession, ContentMaker, GenerativeModel, PartMaker}
import com.google.common.collect.ImmutableList
import com.google.protobuf.Struct
import java.util as ju
import scala.io.Source
import scala.jdk.CollectionConverters.*
import scala.util.Using


object ChatLoop {
  // Levels: 1 - info, 2 - warning, 3 - error.
  case class LogRecord(level: Int, msg: String)
  case class Output(text: Option[String], logs: Seq[LogRecord])   // TODO: remove option

  class ChatLogger {
    private var records = Vector.empty[LogRecord]
    private def log(level: Int, msg: String): Unit = records.synchronized {
      records = records.appended(LogRecord(level, msg))
    }
    def info(msg: => String): Unit = log(1, msg)
    def warn(msg: => String): Unit = log(2, msg)
    def error(msg: => String): Unit = log(3, msg)
    def logs: Seq[LogRecord] = records.synchronized {
      val rs = records
      records = Vector.empty
      rs
    }
  }

  trait NamedTool {
    def tool: Tool
    def name: String
    def call(logger: ChatLogger, args: Struct): IO[Either[String, String]]
  }

  val modelName: String = sys.env.getOrElse(
    "GOOGLE_VERTEXAI_MODEL_PRO",
    sys.error("Expect to have env var GOOGLE_VERTEXAI_MODEL!")
  )
  private val generationConfig = GenerationConfig.newBuilder()
      .setMaxOutputTokens(4096)
      .setTemperature(0.5f)
      .setTopP(1)
      .build()
  private val safetySettings: ImmutableList[SafetySetting] = {
    def ss(c: HarmCategory, t: HarmBlockThreshold): SafetySetting =
      SafetySetting.newBuilder().setCategory(c).setThreshold(t).build()
    ImmutableList.of(
      ss(HarmCategory.HARM_CATEGORY_HARASSMENT, HarmBlockThreshold.OFF),
      ss(HarmCategory.HARM_CATEGORY_HATE_SPEECH, HarmBlockThreshold.OFF),
      ss(HarmCategory.HARM_CATEGORY_DANGEROUS_CONTENT, HarmBlockThreshold.OFF),
      ss(HarmCategory.HARM_CATEGORY_SEXUALLY_EXPLICIT, HarmBlockThreshold.OFF),
      ss(HarmCategory.HARM_CATEGORY_UNSPECIFIED, HarmBlockThreshold.OFF)
    )
  }
  private val toolMap: Map[String, NamedTool] = {   // TODO:  Move it out
    val toolList: List[NamedTool] = List(
      new PyScriptTool(new ExtApi)
    )
    toolList.map(nt => nt.name -> nt).toMap
  }
  private val systemInstruction: Content = {
    val prompt = Using(Source.fromResource("systemPrompt.txt"))(_.mkString).get
    Content.newBuilder().addParts(
      Part.newBuilder().setText(prompt).build()
    ).build()
  }

  def apply(): Resource[IO, ChatLoop] = {
    require(sys.env.contains("GOOGLE_CLOUD_PROJECT"), "Expect to have env var GOOGLE_CLOUD_PROJECT!")
    require(sys.env.contains("GOOGLE_CLOUD_REGION"), "Expect to have env var GOOGLE_CLOUD_REGION!")
    ContextStorage.inFiles[ImmutableList[Content]].flatMap(stm => {
      val aquire = IO.blocking {
        val ai = new VertexAI()
        new ChatLoop(ai, modelName, stm, toolMap)
      }
      Resource.make(aquire)(_.close())
    })
  }
}


class ChatLoop(vai: VertexAI, modelName: String,
               stm: ContextStorage[ImmutableList[Content]],
               toolMap: Map[String, ChatLoop.NamedTool]) {
  import ChatLoop.*

  private val tools: ImmutableList[Tool] = ImmutableList.builder().addAll(
    toolMap.values.map(_.tool).asJava
  ).build()

  private val toolConfig: ToolConfig = ToolConfig.newBuilder().setFunctionCallingConfig(
    FunctionCallingConfig.newBuilder()
      .setMode(FunctionCallingConfig.Mode.AUTO)
      .build()
  ).build()

  private val model: GenerativeModel = {
    val b = new GenerativeModel.Builder()
    b.setModelName(modelName)
      .setVertexAi(vai)
      .setGenerationConfig(generationConfig)
      .setSafetySettings(safetySettings)
      .setSystemInstruction(systemInstruction)
      .setTools(tools)
      .setToolConfig(toolConfig)
      .build()
  }

  private def doFunctionCall(session: ChatSession, out: StringBuilder, logger: ChatLoop.ChatLogger, nTry: Int,
                             part: Part): IO[Unit] = {
    assert(part.hasFunctionCall)
    val fCall = part.getFunctionCall
    val fName = fCall.getName
    val tool = toolMap.getOrElse(fName, sys.error("No function found: " + fName))
    val args = fCall.getArgs
    tool.call(logger, args).flatMap(result => {
      val content = result match {
        case Left(s) => ContentMaker.fromString(s)
        case Right(s) => Content.newBuilder().setRole("user").addParts(
          PartMaker.fromFunctionResponse(fName, ju.Collections.singletonMap("result", s))
        ).build()
      }
      callModel(session, out, logger, nTry, content)
    })
  }

  private def processParts(chat: ChatSession, out: StringBuilder, logger: ChatLogger, nTry: Int,
                           parts: Iterator[Part]): IO[Unit] = {
    if parts.hasNext then {
      val doPart: IO[Unit] = parts.next() match {
        case p@_ if p.hasFileData => IO(logger.warn("File data is not supported yet!"))
        case p@_ if p.hasInlineData => IO(logger.warn("Inline data is not supported yet!"))
        case p@_ if p.hasVideoMetadata => IO(logger.warn("Video metadata is not supported yet!"))
        case p@_ if p.hasFunctionCall => doFunctionCall(chat, out, logger, nTry, p)
        case p@_ if p.hasText => IO(out.append(p.getText))
        case p@_ if p.hasFunctionResponse => sys.error("Function response is not expected!")
        case p@_ => sys.error("Part was not recognized: " + p.toString)
      }
      doPart >> processParts(chat, out, logger, nTry, parts)
    } else IO.unit
  }
  private def processCandidates(chat: ChatSession, out: StringBuilder, logger: ChatLogger, nTry: Int,
                                candidates: Iterator[Candidate]): IO[Unit] = {
    if candidates.hasNext then {
      val candidate = candidates.next()
      val parts = candidate.getContent.getPartsList.iterator().asScala
      // TODO : process other data inside candidate
      processParts(chat, out, logger, nTry, parts) >> processCandidates(chat, out, logger, nTry, candidates)
    } else IO.unit
  }

  private def callModel(chat: ChatSession, out: StringBuilder, logger: ChatLogger, nTry: Int,
                        request: Content): IO[Unit] = {
    if nTry > 0 then {
      IO.blocking(
        chat.sendMessage(request)
      ).flatMap(response => {
        val candidates: Iterator[Candidate] = response.getCandidatesList.iterator().asScala
        processCandidates(chat, out, logger, nTry - 1, candidates)
      })
    } else {
      out.append("Sorry, some error happened. Try later again.")
      logger.error("Out of re-tries!")
      IO.unit
    }
  }

  def close(): IO[Unit] = IO.blocking {
    vai.close()
  }

  def chatHistory(key: String): IO[ImmutableList[Content]] = stm.get(key).map(_.getOrElse(ImmutableList.of))

  def clearChatHistory(key: String): IO[Unit] = stm.set(key, ImmutableList.of)

  def appendUserInput(key: String, query: String): IO[Unit] = for {
    context1 <- stm.get(key).map(_.getOrElse(ImmutableList.of))
    c = Content.newBuilder().addParts(Part.newBuilder().setText(query).build()).setRole("user").build()
    context2 = ImmutableList.builder[Content]().addAll(context1).add(c).build()
    _ <- stm.set(key, context2)
  } yield ()

  def iterate(key: String, query: String, out: Output => IO[Unit]): IO[Unit] = {
    val chat = model.startChat()
    val logger = new ChatLogger
    val sb = new StringBuilder
    for {
      history <- stm.get(key)
      _ = history.foreach(chat.setHistory)
      _ <- callModel(chat, sb, logger, 5, ContentMaker.fromString(query))
      _ <- stm.set(key, chat.getHistory)
      _ <- out(Output(Some(sb.toString()), logger.logs))
    } yield ()
  }
}
