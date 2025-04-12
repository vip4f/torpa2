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


import scopt.OParser


object Args {
  sealed trait Cmd
  object UnknownCmd extends Cmd
  case class ServiceCmd(host: String = "0.0.0.0", port: Int = 8080) extends Cmd

  private val builder = OParser.builder[Cmd]
  private val parser: OParser[?, Cmd] = {
    import builder.*
    OParser.sequence(
      programName(BuildInfo.name),
      head(BuildInfo.name, BuildInfo.version),
      help("help").text("prints this usage text"),
      note(
        """
          |""".stripMargin
      ),
      cmd("service").action((_, _) => ServiceCmd())
        .text("Run app as a service.")
        .children(
          opt[String]("host")
            .action((h, cmd) => cmd.asInstanceOf[ServiceCmd].copy(host = h))
            .text("Service host"),
          opt[Int]("port")
            .action((p, cmd) => cmd.asInstanceOf[ServiceCmd].copy(port = p))
            .text("Service port")
        ),
      note(""),
      checkConfig({
        case UnknownCmd => failure("Arguments were not provided")
        case _ => success
      })
    )
  }

  def usage: String = OParser.usage(parser)
  def parse(args: List[String]): Option[Cmd] = OParser.parse(parser, args, UnknownCmd)
}
