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
import cats.syntax.all.*
import com.comcast.ip4s.{Host, Port}
import fs2.Stream
import fs2.concurrent.SignallingRef
import org.http4s.HttpRoutes
import org.http4s.dsl.io.*
import org.http4s.ember.client.EmberClientBuilder
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.server.websocket.WebSocketBuilder2


object Server {

  private def shutdownRoutes(S: SignallingRef[IO, Boolean]): HttpRoutes[IO] = HttpRoutes.of[IO] {
    case POST -> Root / "admin" / "shutdown" =>
      for {
        _ <- S.set(true)
        resp <- NoContent()
      } yield resp
  }

  private def buildRoutes(cl: ChatLoop): IO[WebSocketBuilder2[IO] => HttpRoutes[IO]] = IO {
    (wsb: WebSocketBuilder2[IO]) => {
      val localRoutes = new Routes(cl)
      localRoutes.routes(wsb) <+> Routes.static[IO]
    }
  }

  def run(host: String, port: Int): IO[Unit] = {
    val filterChain = List(LocalContext.filter)
    val s = for {
      shutdown <- Stream.eval(SignallingRef[IO, Boolean](false))
      http <- Stream.resource(EmberClientBuilder.default[IO].build)
      chatLoop <- Stream.resource(ChatLoop())
      routeBuilder <- Stream.eval(buildRoutes(chatLoop))
      appBuilder = (wsb: WebSocketBuilder2[IO]) => {
        val routes = routeBuilder(wsb)
        val app1 = (shutdownRoutes(shutdown) <+> routes).orNotFound
        val app2 = filterChain.foldLeft(app1)((app, filter) => filter(app))
        app2
      }
      _ <- Stream.resource(EmberServerBuilder.default[IO]
        .withHost(Host.fromString(host).getOrElse(sys.error(s"Cannot parse host $host")))
        .withPort(Port.fromInt(port).getOrElse(sys.error(s"Cannot parse port $port")))
        .withHttpWebSocketApp(appBuilder)
        .build
      )
      r <- shutdown.discrete.takeWhile(!_)
    } yield r
    s.compile.drain
  }
}
