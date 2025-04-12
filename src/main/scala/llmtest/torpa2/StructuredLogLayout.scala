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


import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.LayoutBase
import io.circe.generic.auto._
import io.circe.syntax._
import java.io.{PrintWriter, StringWriter}
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import scala.jdk.CollectionConverters._
import scala.util.control.NonFatal


object StructuredLogLayout {
  val pid: String = ProcessHandle.current().pid.toString

  private val tsFormatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME
  def nowTs: String = {
    val date = LocalDateTime.now()
    date.format(tsFormatter)
  }

  val emptyLabels: Map[String, String] = Map.empty[String, String]

  case class Event(time: String, severity: String, message: String, labels: Map[String, String])
}


class StructuredLogLayout extends LayoutBase[ILoggingEvent] {
  import StructuredLogLayout._

  private def event2event(e: ILoggingEvent): Event = {
    // https://cloud.google.com/logging/docs/reference/v2/rest/v2/LogEntry#LogSeverity
    // Possible values: DEFAULT, DEBUG, INFO, NOTICE, WARNING, ERROR, CRITICAL, ALERT, EMERGENCY.
    val sev = e.getLevel match {
      case Level.OFF => "DEFAULT"
      case Level.TRACE => "DEBUG"
      case Level.DEBUG => "DEBUG"
      case Level.INFO => "INFO"
      case Level.WARN => "WARNING"
      case Level.ERROR => "ERROR"
      case _ => "ALERT"
    }
    val m1 = {
      val m = e.getFormattedMessage
      if (m != null) m else "NULL message"
    }
    val m2 = {
      val tp = e.getThrowableProxy
      if(tp == null) m1 else {
        val sb = new StringBuilder(m1)
        sb.append("\nCaused by: [").append(tp.getClassName).append("] ").append(tp.getMessage)
        val st = tp.getStackTraceElementProxyArray
        if (st != null)
          st.foreach(ste => sb.append("\n\t").append(ste.getSTEAsString))
        sb.toString()
      }
    }
    val labels = Map(
      "pid" -> pid,
      "thread" -> e.getThreadName
    )
    val extraLabels = {
      val m = e.getMDCPropertyMap
      if(m != null) m.asScala
      else emptyLabels
    }
    Event(nowTs, sev, m2, labels ++ extraLabels)
  }

  override def doLayout(event: ILoggingEvent): String = try {
    event2event(event).asJson.noSpaces + "\n"
  } catch {
    case NonFatal(t) => {
      val sw = new StringWriter(1024)
      sw.append("PANIC: ").append(t.getMessage).append("\n")
      val pw = new PrintWriter(sw)
      t.printStackTrace(pw)
      pw.flush()
      sw.flush()
      s"""{"severity": "CRITICAL", "messages": "Log layout error: ${sw.toString}"}"""
    }
  }
}
