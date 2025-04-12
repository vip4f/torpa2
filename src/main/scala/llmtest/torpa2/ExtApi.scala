/* Copyright (c) 2025, Vladimir Ivanovskiy
 * All rights reserved.
 *
 * This software is licensed under
 *      GNU GENERAL PUBLIC LICENSE
 *      Version 3, 29 June 2007.
 *
 * ------------------------------------------------------------------------------------
 * Created on 3/5/25.
 */


package llmtest.torpa2


import cats.effect.IO
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import org.python.core.*
import scala.concurrent.duration.*


class ExtApi {
  import tools.*

  private val apiTimeout: FiniteDuration = 30.seconds

  private def timedSync[A](io: IO[A]): A = {
    import cats.effect.unsafe.implicits.global
    io.timeout(apiTimeout).unsafeRunSync()
  }

  private val localDtFormatter = DateTimeFormatter.ofPattern("E, d MMM yyyy HH:mm:ss")

  def localDateTime(): String = {
    val now = LocalDateTime.now()
    now.format(localDtFormatter)
  }

  private val webSearchTool = new WebSearch(BuildInfo.name)

  def webSearch(args: Array[PyObject], kws: Array[String]): PyList = {
    def af(d: PyDictionary, key: String, value: String): Unit = {
      if value != null then
        d.getMap.putIfAbsent(new PyString(key), new PyUnicode(value))
    }
    val arguments = Array("query", "start", "count", "site")
    val ap = new ArgParser("webSearch", args, kws, arguments, 1)
    val query = ap.getString(0)
    val start = ap.getInt(1, 1)
    val count = ap.getInt(2, 5)
    val site = ap.getString(3, "")
    val io = webSearchTool.search(query, start, count, if site.isEmpty then None else Some(site))
    val result = timedSync(io)
    val pyl = new PyList()
    var i = 0
    while i < result.size() do {
      val d = new PyDictionary()
      val item = result.get(i)
      af(d, "title", item.getTitle)
      af(d, "snippet", item.getSnippet)
      af(d, "mime", item.getMime)
      af(d, "link", item.getLink)
      af(d, "fileFormat", item.getFileFormat)
      pyl.append(d)
      i += 1
    }
    pyl
  }
}
