/* Copyright (c) 2025, Vladimir Ivanovskiy
 * All rights reserved.
 *
 * This software is licensed under 
 *      GNU GENERAL PUBLIC LICENSE
 *      Version 3, 29 June 2007.
 *
 * ------------------------------------------------------------------------------------
 * Created on 3/6/25.
 */


import cats.effect.IO
import com.google.cloud.vertexai.VertexAI
import com.google.protobuf.util.Values
import com.google.protobuf.Struct
import llmtest.torpa2.ChatLoop.ChatLogger
import llmtest.torpa2.tools.WebCrawler
import llmtest.torpa2.{ExtApi, PyScriptTool}
import munit.CatsEffectSuite
import org.slf4j.LoggerFactory
import java.net.{URI, URL}
import scala.concurrent.duration.*


class ToolTests extends CatsEffectSuite {

  private val extApi = new ExtApi
  private val scripter = new PyScriptTool(extApi)
  private val logger = LoggerFactory.getLogger(getClass.getName)
  private val chatLogger = new ChatLogger
  private val vai = new VertexAI()

  override val munitTimeout: Duration = 200.seconds

  override def beforeAll(): Unit = super.beforeAll()

  override def afterAll(): Unit = {
    vai.close()
  }

  private def dumpChatLogs(): Unit = {
    for l <- chatLogger.logs do
      l.level match {
        case 1 => logger.info(l.msg)
        case 2 => logger.warn(l.msg)
        case _ => logger.error(l.msg)
      }
  }

  private def runScript(script: String): IO[String] = {
    val params = Struct.newBuilder().putFields("script", Values.of(script)).build()
    scripter.call(chatLogger, params).map(r => {
      dumpChatLogs()
      r match {
        case Left(err) => sys.error(err)
        case Right(s) => s
      }
    })
  }

  test("Run domain restricted web search") {
    val script =
      """def script(api):
        |    results = api.webSearch("Buick", site="wikipedia.org")
        |    if len(results) < 1:
        |        raise RuntimeError("No search results")
        |    return results[0]["snippet"]
        |""".stripMargin
    val iob: IO[Boolean] = runScript(script).map(r => {
      // println("Result: " + r)
      r.contains("Buick is positioned as a premium automobile brand")
    })
    assertIOBoolean(iob)
  }

  test("Web page summary") {
    def pageFilter(url: URL, level: Int): Boolean = level < 1 // process only root page
    val wpp = WebCrawler(chatLogger, vai, pageFilter)
    val prompt = """Analyze content and give answer to the question: "What Buick model is most popular and why"."""
    val link = URI.create("https://en.wikipedia.org/wiki/Buick").toURL
    val checkWords = List("popular", "Buick")
    val iob: IO[Boolean] = wpp.process(prompt, link).map(answer => {
      dumpChatLogs()
      // println(answer.mkString("\n"))
      val s = answer.fold[String]("")(_ + _)
      checkWords.forall(s.contains)
    })
    assertIOBoolean(iob)
  }

  test("Web page tree summary") {
    def pageFilter(url: URL, level: Int): Boolean = {
      val uri = url.toURI
      level <= 1 && uri.getHost.contains("circe.github.io")
    }
    val wpp = WebCrawler(chatLogger, vai, pageFilter)
    val prompt = """Analyze content and give answer to the question: How to parse JSON?"""
    val link = URI.create("https://circe.github.io/circe/").toURL
    val iob: IO[Boolean] = wpp.process(prompt, link).map(answer => {
      dumpChatLogs()
      // println(answer.mkString("\n"))
      val s = answer.fold[String]("")(_ + _)
      s.contains("circe-parser")
      true
    })
    assertIOBoolean(iob)
  }
}
