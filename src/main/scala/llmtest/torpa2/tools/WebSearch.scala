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


package llmtest.torpa2.tools


import cats.effect.IO
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.customsearch.v1.CustomSearchAPI
import com.google.api.services.customsearch.v1.model.Result
import java.util as ju


class WebSearch(appName: String) {

  private val engineId: String =
    sys.env.getOrElse("GOOGLE_SEARCH_ENGINE_ID", sys.error("GOOGLE_SEARCH_ENGINE_ID not set"))
  private val searchKey: String =
    sys.env.getOrElse("GOOGLE_SEARCH_API_KEY", sys.error("GOOGLE_SEARCH_API_KEY not set"))

  def search(query: String,
             start: Int = 1, count: Int = 10,
             site: Option[String] = None): IO[ju.List[Result]] = IO.blocking {
    val customsearch = new CustomSearchAPI
      .Builder(GoogleNetHttpTransport.newTrustedTransport, GsonFactory.getDefaultInstance, null)
      .setApplicationName(appName)
      .build()
    val cs = customsearch.cse().list()
    cs.setKey(searchKey)
    cs.setCx(engineId)
    cs.setQ(query)
    cs.setNum(count)
    cs.setStart(start.toLong)
    site.foreach(s => cs.setSiteSearch(s))
    cs.execute().getItems
  }
}
