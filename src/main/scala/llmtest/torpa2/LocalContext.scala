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


import cats.data.Kleisli
import cats.effect.{IO, IOLocal}
import org.http4s.HttpApp
import org.slf4j.LoggerFactory


object LocalContext {

  val ridField = "rid"

  case class Context(fields: Map[String, String]) {
  }

  private var localContext: Option[IOLocal[Context]] = None
  val init: IO[Unit] = IOLocal[Context](Context(Map.empty[String, String])).map(lc => synchronized {
    if (localContext.nonEmpty)
      LoggerFactory.getLogger(getClass.getName).warn("ContextLog has already been initialized")
    else localContext = Some(lc)
  })

  def get: IO[Context] = localContext match {
    case None => IO.pure(Context(Map.empty))
    case Some(iol) => iol.get
  }

  def set(context: Context): IO[Unit] = localContext.get.set(context)
  def add(kv: Map[String, String]): IO[Unit] = localContext.get.update(old => Context(old.fields ++ kv))
  def add(k: String, v: String): IO[Unit] = localContext.get.update(old => Context(old.fields.updated(k, v)))

  def inContext[A](context: Context)(io: IO[A]): IO[A] = for {
    old <- localContext.get.get
    _ <- localContext.get.set(context)
    r <- io.attempt
    _ <- localContext.get.set(old)
  } yield r match {
    case Left(t) => throw t
    case Right(a) => a
  }
  def inContext[A](context: Map[String, String])(io: IO[A]): IO[A] = inContext[A](Context(context))(io)

  def filter(httpApp: HttpApp[IO]): HttpApp[IO] = Kleisli { req => {
    inContext(Context(Map(
      "SELF_METHOD" -> req.method.name,
      "SELF_URI" -> req.uri.renderString
    )))(httpApp(req))
  }}
}
