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


import org.scalajs.dom
import org.scalajs.dom.{CloseEvent, Event, MessageEvent, WebSocket}
import org.scalajs.dom.{HttpMethod, RequestCredentials, RequestInit, console, html}
import scala.reflect.ClassTag
import scala.scalajs.js.annotation.{JSExport, JSExportTopLevel}
import scala.scalajs.js.Dynamic.global as g
import scala.scalajs.js.URIUtils.decodeURIComponent
import scalatags.JsDom.all.*
import scala.annotation.nowarn
import scala.scalajs.js
import scala.scalajs.js.JSON
import scala.util.{Failure, Random, Success}


trait LogRecord extends js.Object {
  val level: Int
  val msg: String
}

trait ChatQueryOutput extends js.Object {
  val text: String
  val logs: js.Array[LogRecord]
}

class QueryRequest(val sid: String, val hid: String, val query: String) extends js.Object

@JSExportTopLevel("ChatJS")
object ChatJS {

  private var openSocket = false
  private lazy val socket = new WebSocket("/chatws")

  given scala.concurrent.ExecutionContext = scala.concurrent.ExecutionContext.global

  sealed trait ChatContent {
    def value: String
  }
  case class UserContent(value: String) extends ChatContent
  case class ModelContent(value: String) extends ChatContent
  case class ErrorContent(value: String) extends ChatContent

  private def alert(s: String): Unit = g.alert(s)

  private def getElemById[T: ClassTag](id: String): T = {
    dom.document.getElementById(id).asInstanceOf[T]
  }

  private lazy val resetBtn = getElemById[html.Button]("resetBtn")
  private lazy val queryBtn = getElemById[html.Button]("queryBtn")
  private lazy val queryTextArea = getElemById[html.TextArea]("queryTa")
  private lazy val chatDiv = getElemById[html.Div]("chatDiv")

  private def addChatContent(c: ChatContent): Unit = {
    val i = c match {
      case UserContent(v) => """<img src="static/hu.png" alt="human"/> """
      case ModelContent(v) => """<img src="static/ai.png" alt="model"/> """
      case ErrorContent(v) => """<img src="static/er.png" alt="error"/> """
    }
    val d = div().render
    d.innerHTML = i + marked.parse(c.value)
    chatDiv.append(d) 
    chatDiv.scrollTop = chatDiv.scrollHeight
  }

  @nowarn("msg=unused explicit parameter")
  private def onClickReset(e: dom.MouseEvent): Unit = {
    dom.fetch("/reset", new RequestInit {
      method = HttpMethod.POST
      credentials = RequestCredentials.`same-origin`
    }).toFuture.onComplete({
      case Failure(exception) => alert("ERROR: " + exception.getMessage)
      case Success(response) =>
        while chatDiv.childNodes.nonEmpty do
          chatDiv.removeChild(chatDiv.childNodes.head)
        queryTextArea.focus()
    })
  }

  private def queryResponse(content: ChatContent): Unit = {
    addChatContent(content)
    queryTextArea.value = ""
    dom.document.body.style = "cursor: default;"
    queryBtn.disabled = false
    queryTextArea.disabled = false
    queryTextArea.focus()
  }

  private lazy val sid: String = {
    val decoded = decodeURIComponent(dom.document.cookie)
    val cookieName = "torpa2sid" // TODO : match cookie name with server side in Routes.scala.
    decoded.split(";").map(_.trim)
      .find(_.startsWith(s"$cookieName="))
      .map(_.substring(cookieName.length+1))
      .getOrElse("UNKNOWN_SID")
  }
  private val hid: String = {
    val rnd = new Random
    "%016X%016X".format(rnd.nextLong(), rnd.nextLong())
  }
  private var hidCount: Int = 0

  private def queryRequest(query: String): QueryRequest = {
    hidCount += 1
    new QueryRequest(sid, hid + s".$hidCount", query)
  }

  @nowarn("msg=unused explicit parameter")
  private def onClickQuery(e: dom.MouseEvent): Unit = {
    val query = queryTextArea.value
    queryTextArea.value = ""
    addChatContent(UserContent(query))
    dom.document.body.style = "cursor: progress;"
    queryBtn.disabled = true
    queryTextArea.disabled = true
    if openSocket then {
      socket.send(JSON.stringify(queryRequest(query)))
    } else console.error("Socket is closed.")
  }
  
  private def onQueryPress(e: dom.KeyboardEvent): Unit = {
    val key = e.key
    if key == "Enter" then onClickQuery(null)
  }

  @nowarn("msg=unused explicit parameter")
  private def onSocketOpen(e: Event): Unit = {
    console.info("Socket opened")
    openSocket = true
  }

  @nowarn("msg=unused explicit parameter")
  private def onSocketClose(e: CloseEvent): Unit = {
    console.error("Socket closed")  // Unexpected?
    queryTextArea.value = "Session terminated. Reload the page."
    queryTextArea.disabled = true
    openSocket = false
  }

  private def onSocketMessage(e: MessageEvent): Unit = {
    val cqo = js.JSON.parse(e.data.asInstanceOf[String]).asInstanceOf[ChatQueryOutput]
    cqo.logs.foreach(lr => lr.level match {
      case 0 => console.debug(lr.msg)
      case 1 => console.info(lr.msg)
      case 2 => console.warn(lr.msg)
      case 3 => console.error(lr.msg)
    })
    if cqo.text != null then queryResponse(ModelContent(cqo.text))
  }
  private def onSocketError(e: Event): Unit = {
    console.error("Socket error: " + e)
  }

  @JSExport
  def init(): Unit = {
    socket.onopen = onSocketOpen(_)
    socket.onclose = onSocketClose(_)
    socket.onmessage = onSocketMessage(_)
    socket.onerror = onSocketError(_)
    resetBtn.onclick = onClickReset(_)
    queryBtn.onclick = onClickQuery(_)
    queryTextArea.onkeypress = onQueryPress(_)
    chatDiv.scrollTop = chatDiv.scrollHeight
    queryTextArea.focus()
  }
}
