/* Copyright (c) 2025, Vladimir Ivanovskiy
 * All rights reserved.
 *
 * This software is licensed under
 *      GNU GENERAL PUBLIC LICENSE
 *      Version 3, 29 June 2007.
 *
 * ------------------------------------------------------------------------------------
 * Created on 3/10/25.
 */


package llmtest.torpa2.tools


import cats.effect.{IO, Resource}
import com.google.cloud.vertexai.VertexAI
import com.google.cloud.vertexai.api.SafetySetting.HarmBlockThreshold
import com.google.cloud.vertexai.api.*
import com.google.cloud.vertexai.generativeai.GenerativeModel
import com.google.common.collect.ImmutableList
import llmtest.torpa2.ChatLoop.ChatLogger
import java.net.{URI, URL}
import org.htmlunit.WebClient
import org.htmlunit.html.HtmlPage
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal


class WebCrawler(logger: ChatLogger, vai: VertexAI,
                 recursiveFilter: (URL, Int) => Boolean) {

  private val rWebClient: Resource[IO, WebClient] = {
    Resource.make(IO.blocking(new WebClient()))(wc => IO.blocking(wc.close()))
  }

  private val modelName: String = sys.env.getOrElse(
    "GOOGLE_VERTEXAI_MODEL_FAST",
    sys.error("Expect to have env var GOOGLE_VERTEXAI_MODEL!")
  )

  private val generationConfig = GenerationConfig.newBuilder()
    .setMaxOutputTokens(4096)
    .setTemperature(0.5f)
    .setTopP(1)
    .build()
  private val safetySettings: ImmutableList[SafetySetting] = {
    def ss(c: HarmCategory, t: HarmBlockThreshold): SafetySetting =
      SafetySetting.newBuilder().setCategory(c).setThreshold(t).build()

    ImmutableList.of(
      ss(HarmCategory.HARM_CATEGORY_HARASSMENT, HarmBlockThreshold.OFF),
      ss(HarmCategory.HARM_CATEGORY_HATE_SPEECH, HarmBlockThreshold.OFF),
      ss(HarmCategory.HARM_CATEGORY_DANGEROUS_CONTENT, HarmBlockThreshold.OFF),
      ss(HarmCategory.HARM_CATEGORY_SEXUALLY_EXPLICIT, HarmBlockThreshold.OFF),
      ss(HarmCategory.HARM_CATEGORY_UNSPECIFIED, HarmBlockThreshold.OFF)
    )
  }
  private def systemInstruction(prompt: String): Content = {
    Content.newBuilder().addParts(
      Part.newBuilder().setText(prompt).build()
    ).build()
  }
  private val tools: ImmutableList[Tool] = ImmutableList.of
  private val toolConfig: ToolConfig = ToolConfig.newBuilder().setFunctionCallingConfig(
    FunctionCallingConfig.newBuilder()
      .setMode(FunctionCallingConfig.Mode.NONE)
      .build()
  ).build()
  private val model: GenerativeModel = {
    val b = new GenerativeModel.Builder()
    b.setModelName(modelName)
      .setVertexAi(vai)
      .setGenerationConfig(generationConfig)
      .setSafetySettings(safetySettings)
      .setTools(tools)
      .setToolConfig(toolConfig)
      .build()
  }

  case class WalkState(parts: Vector[Part], visited: Set[URL])
  private def walkThePage(wc: WebClient)
                         (level: Int, link: URL, state0: WalkState): WalkState = {
    if recursiveFilter(link, level) then {
      assert(!state0.visited(link))
      logger.info("Read page: " + link.toString)
      val page: HtmlPage = wc.getPage(link).asInstanceOf[HtmlPage]
      val baseUrl = page.getBaseURL
      val s = "URL: " + baseUrl.toString + "\nTitle: " + page.getTitleText + "\nContent: " + page.getVisibleText
      val state1 = WalkState(
        state0.parts :+ Part.newBuilder().setText(s).build(),
        state0.visited + link)
      val subLinks = page.getAnchors.asScala.map(a => {
        val u = URI.create(a.getHrefAttribute)
        if u.isAbsolute then u.toURL else baseUrl.toURI.resolve(u).toURL
      })
      subLinks.foldLeft(state1)((s, l) => try {
        // TODO: think about images
        if s.visited(l) then s else walkThePage(wc)(level + 1, l, s)
      } catch {
        case NonFatal(t) =>
          logger.error(s"Error processing $l: " + t.getMessage)
          s.copy(visited = s.visited + l)
      })
    } else state0.copy(visited = state0.visited + link)
  }

  def process(prompt: String, link: URL): IO[List[String]] = rWebClient.use(wc => IO.blocking {
    wc.getOptions.setJavaScriptEnabled(false) // TODO: this option requires more testing
    wc.getOptions.setCssEnabled(false)
    val walkState = walkThePage(wc)(0, link, WalkState(Vector.empty, Set.empty))
    val content = Content.newBuilder().addAllParts(walkState.parts.asJava).setRole("user").build()
    val m = model.withSystemInstruction(systemInstruction(prompt))
    m.generateContent(content).getCandidatesList.iterator().asScala.flatMap(candidate =>
      candidate.getContent.getPartsList.iterator().asScala).map(_.getText).toList
  })

}
