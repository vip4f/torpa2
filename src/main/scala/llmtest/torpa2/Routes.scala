/* Copyright (c) 2025, Vladimir Ivanovskiy
 * All rights reserved.
 *
 * This software is licensed under
 *      GNU GENERAL PUBLIC LICENSE
 *      Version 3, 29 June 2007.
 *
 * ------------------------------------------------------------------------------------
 * Created on 1/17/25.
 */


package llmtest.torpa2


import cats.effect.IO
import cats.effect.kernel.Sync
import cats.effect.std.Queue
import com.google.cloud.vertexai.api.Content
import com.google.common.collect.ImmutableList
import fs2.{Pipe, Stream}
import io.circe.generic.auto.*
import io.circe.parser.*
import io.circe.syntax.*
import java.util.UUID
import org.http4s.{Charset, HttpDate, HttpRoutes, MediaType, Request, Response, ResponseCookie, StaticFile, Uri}
import org.http4s.dsl.Http4sDsl
import org.http4s.dsl.io.*
import org.http4s.headers.`Content-Type`
import org.http4s.server.middleware.AutoSlash
import org.http4s.server.websocket.WebSocketBuilder2
import org.http4s.websocket.WebSocketFrame
import org.slf4j.{Logger, LoggerFactory}
import scala.concurrent.duration.*
import scala.io.Source
import scala.jdk.CollectionConverters.*
import scala.util.Using


object Routes {
  private val staticTypes = List(".ico", ".png", ".js", ".css", ".map", ".html", ".webm")

  def static[F[_] : Sync]: HttpRoutes[F] = {
    val dsl = new Http4sDsl[F] {}
    import dsl._
    HttpRoutes.of[F] {
      case request@GET -> "static" /: path if staticTypes.exists(path.toString.endsWith) =>
        org.http4s.StaticFile.fromResource("/static/" + path, Some(request)).getOrElseF(NotFound())
      case request@GET -> Root / file if staticTypes.exists(file.endsWith) =>
        StaticFile.fromResource("/static/" + file, Some(request)).getOrElseF(NotFound())
    }
  }

  sealed trait ChatRequest
  case class QueryRequest(sid: String, hid: String, query: String) extends ChatRequest

  // Levels: 1 - info, 2 - warning, 3 - error.
  case class LogRecord(level: Int, msg: String)
  case class ChatResponse(text: Option[String], logs: Seq[LogRecord])
}

class Routes(cl: ChatLoop) {
  import Routes.*

  private given logger: Logger = LoggerFactory.getLogger(getClass.getName)

  private val chatCookieName = "torpa2sid"

  private def getChatKey(req: Request[IO]): String = {
    req.cookies.find(_.name == chatCookieName) match {
      case None => UUID.randomUUID().toString
      case Some(c) => c.content
    }
  }

  private val chatPageBase: String = {
    Using(Source.fromResource("chat.html"))(s => {
      s.mkString
        .replace("~version~", BuildInfo.version)
        .replace("~model~", ChatLoop.modelName)
    }).toOption.getOrElse(sys.error("Cannot build chat page!"))
  }

  private def chatPage(history: ImmutableList[Content]): String = {
    val sb = new StringBuilder()
    val appendUser = (s: String) => sb.append(s"""<div><img src="static/hu.png" alt="human"/>$s</div><br/>""")
    val appendModel = (s: String) => sb.append(s"""<div><img src="static/ai.png" alt="model"/>$s</div><br/>""")
    for h <- history.iterator().asScala do {
      val append = if h.getRole == "user" then appendUser else appendModel
      for p <- h.getPartsList.iterator().asScala do {
        if p.hasText then append(p.getText)
      }
    }
    chatPageBase.replace("~history~", sb.toString)
  }

  private def chatPage(req: Request[IO]): IO[Response[IO]] = {
    val key = getChatKey(req)
    for {
      history <- cl.chatHistory(key)
      response <- Ok(chatPage(history))
    } yield {
      val expDate = HttpDate.unsafeFromEpochSecond(System.currentTimeMillis() / 1000 + 365 * 24 * 3600)
      response.withContentType(`Content-Type`(MediaType.text.html, Charset.`UTF-8`))
        .addCookie(ResponseCookie(chatCookieName, key, domain = None, path = Some("/"), expires = Some(expDate)))
    }
  }

  private def sendChatWs(queue: Queue[IO, WebSocketFrame]): Stream[IO, WebSocketFrame] = {
    Stream.fromQueueUnterminated(queue).concurrently(
      Stream.awakeEvery[IO](22.seconds).foreach(_ => queue.offer(WebSocketFrame.Ping()))
    )
  }

  private def receiveChatWs(queue: Queue[IO, WebSocketFrame]): Pipe[IO, WebSocketFrame, Unit] = {
    def sendOutput(output: ChatLoop.Output): IO[Unit] = {
      val js = output.asJson.noSpaces
      val frame = WebSocketFrame.Text(js, true)
      queue.offer(frame)
    }
    (in: Stream[IO, WebSocketFrame]) => {
      in.evalMap({
        case WebSocketFrame.Pong(_) => IO {
          logger.debug("Web socket pong")
        }
        case WebSocketFrame.Close(d) => IO {
          logger.info("Web socket is closed, data: " + d.toString)
        }
        case WebSocketFrame.Text(text, last) => {
          val qr = decode[QueryRequest](text).toOption.getOrElse(sys.error("Cannot decode chat query"))
          def lc = LocalContext.Context(Map("sid" -> qr.sid, "hid" -> qr.hid))
          if last then LocalContext.inContext(lc) {
            cl.iterate(qr.sid, qr.query, sendOutput).handleErrorWith(t => {
              logger.error(t.getMessage, t)
              sendOutput(ChatLoop.Output(Some("Error happened"), List(ChatLoop.LogRecord(3, t.getMessage))))
            })
          } else {
            cl.appendUserInput(qr.sid, qr.query)
          }
        }
        case f@_ => IO{ logger.warn("Unexpected WebSocketFrame: " + f)}
      })
    }
  }

  private def chatReset(req: Request[IO]): IO[Response[IO]] = for {
    _ <- cl.clearChatHistory(getChatKey(req))
    r <- NoContent()
  } yield r

  def routes(wsb: WebSocketBuilder2[IO]): HttpRoutes[IO] = AutoSlash(
    HttpRoutes.of[IO] {
      case req @ GET -> Root / "health" => Ok(s"OK ${BuildInfo.name}, v.${BuildInfo.version}")
      case req @ POST -> Root / "reset" => chatReset(req)
      case req@GET -> Root / "chat" => chatPage(req)
      case GET -> Root / "chatws" =>
        Queue.unbounded[IO, WebSocketFrame].flatMap(queue =>
          wsb.build(sendChatWs(queue), receiveChatWs(queue))
        )
    }
  )
}
