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


import cats.effect.unsafe.IORuntimeConfig
import cats.effect.{ExitCode, IO, IOApp}
import cats.effect.std.Console
import java.lang.management.ManagementFactory
import org.slf4j.{Logger, LoggerFactory}
import scala.jdk.CollectionConverters.*


object Main extends IOApp {

    val logger: Logger = LoggerFactory.getLogger(getClass.getName)

    private def logGcInfo(): Unit = {
      val gcMxBeans = ManagementFactory.getGarbageCollectorMXBeans.asScala
      for bean <- gcMxBeans do {
        logger.info("GC name: {}", bean.getName)
        logger.info("GC object name: {}", bean.getObjectName)
        logger.info("GC memory pool names: {}", bean.getMemoryPoolNames.mkString(", "))
      }
    }

    private val envCheck: IO[Unit] = {
      val checkVars = Set(
        "GOOGLE_CLOUD_PROJECT",
        "GOOGLE_CLOUD_REGION",
        "GOOGLE_APPLICATION_CREDENTIALS",
        "GOOGLE_SEARCH_API_KEY",
        "GOOGLE_SEARCH_ENGINE_ID",
        "GOOGLE_VERTEXAI_MODEL_PRO",
        "GOOGLE_VERTEXAI_MODEL_FAST"
      )
      lazy val error =
        s"""Expected the following environment variables:
           |  * ${checkVars.mkString("\n  * ")}
           |""".stripMargin
      if (checkVars -- sys.env.keySet).isEmpty then IO.unit else IO.println(error) *> IO.raiseError(
        new RuntimeException(error)
      )
    }

    def run(args: List[String]): IO[ExitCode] = {
      logGcInfo()
      val iortc = IORuntimeConfig()
      logger.info("CPU starvation check interval: {}", iortc.cpuStarvationCheckInterval)
      logger.info("CPU starvation check initial delay: {}", iortc.cpuStarvationCheckInitialDelay)
      import Args.*
      def io: IO[Unit] = parse(args) match {
        case Some(ServiceCmd(host, port)) => envCheck *> Server.run(host, port)
        case _ => Console[IO].error(usage)
      }
      val startTs = System.currentTimeMillis()
      def runtime = (System.currentTimeMillis - startTs) / 1000.0
      logger.info(s"Application ${BuildInfo.name}, v.${BuildInfo.version} has started.")
      LocalContext.init *> io.attempt.map({
        case Left(t) =>
          logger.error(t.getMessage, t)
          logger.info(s"Application ${BuildInfo.name}, v.${BuildInfo.version} has stopped, run time: $runtime seconds.")
          ExitCode.Error
        case Right(_) =>
          logger.info(s"Application ${BuildInfo.name}, v.${BuildInfo.version} has stopped, run time: $runtime seconds.")
          ExitCode.Success
        })
    }
}
