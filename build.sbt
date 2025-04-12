
lazy val Version = new {
  val circe = "0.14.10"
  // val commonmark = "0.17.0"
  val http4s = "0.23.30"
  val http4sJdkCli = "0.10.0"
  val jython = "2.7.4"
  val htmlunit = "4.10.0"
  val logback = "1.5.17"
  val munit = "0.7.29"
  //val munitScalacheck = "1.1.0"
  val munitCatsEffect = "1.0.7"
  val scopt = "4.1.0"
  val searchApi = "v1-rev20240821-2.0.0"
  val vertexai = "1.19.0"
  val xml = "2.3.0"
}

lazy val commonSettings = Seq(
  organization := "llmtest",
  version := "0.0.3",
  scalaVersion := "3.6.4",
  Compile / scalacOptions ++= Seq(
    "-deprecation",
    "-encoding", "UTF-8",
    "-feature",
    "-unchecked",
    "-language:higherKinds",
    "-language:existentials",
    "-Wunused:all",
    "-Xmax-inlines", "64"
  )
)

lazy val js = project.in(file("js"))
  .enablePlugins(ScalaJSPlugin)
  .settings(
    name := "chat",
    coverageEnabled := false,
    commonSettings,
    libraryDependencies ++= Seq(
      "org.scala-js" %%% "scalajs-dom" % "2.8.0",
      "com.lihaoyi" %%% "scalatags" % "0.13.1"
    )
  )

lazy val jsDependencies = Seq(
  Compile / resourceGenerators += Def.task {
    //val src = (js / Compile / fullOptJS / scalaJSLinkedFile).value.data
    val src = (js / Compile / fastOptJS / scalaJSLinkedFile).value.data
    val dst = (Compile / resourceManaged).value / "static" / "chat.js"
    IO.copyFile(src, dst)
    Seq(dst)
  }.taskValue
)

lazy val root = (project in file("."))
  .enablePlugins(BuildInfoPlugin, JavaAppPackaging, DockerPlugin)
  .settings(
    name := "torpa2",
    commonSettings,
    buildInfoKeys := Seq[BuildInfoKey](name, version, scalaVersion, sbtVersion),
    buildInfoPackage := "llmtest.torpa2",
    libraryDependencies ++= Seq(
      "ch.qos.logback"            % "logback-classic"                  % Version.logback,
      //"com.atlassian.commonmark"  % "commonmark"                       % Version.commonmark,
      //"com.atlassian.commonmark"  % "commonmark-ext-gfm-tables"        % Version.commonmark,
      "com.github.scopt"         %% "scopt"                            % Version.scopt,
      "com.google.apis"           % "google-api-services-customsearch" % Version.searchApi,
      "com.google.cloud"          % "google-cloud-vertexai"            % Version.vertexai,
      "io.circe"                 %% "circe-core"                       % Version.circe,
      "io.circe"                 %% "circe-generic"                    % Version.circe,
      "io.circe"                 %% "circe-literal"                    % Version.circe,
      "io.circe"                 %% "circe-parser"                     % Version.circe,
      "org.htmlunit"              % "htmlunit"                         % Version.htmlunit,
      "org.http4s"               %% "http4s-ember-server"              % Version.http4s,
      "org.http4s"               %% "http4s-ember-client"              % Version.http4s,
      "org.http4s"               %% "http4s-jdk-http-client"           % Version.http4sJdkCli,
      "org.http4s"               %% "http4s-circe"                     % Version.http4s,
      "org.http4s"               %% "http4s-dsl"                       % Version.http4s,
      "org.python"                % "jython-standalone"                % Version.jython,
      "org.scala-lang.modules"   %% "scala-xml"                        % Version.xml,
      "org.scalameta"            %% "munit"                            % Version.munit           % Test,
      "org.scalameta"            %% "munit-scalacheck"                 % Version.munit           % Test,
      "org.typelevel"            %% "munit-cats-effect-3"              % Version.munitCatsEffect % Test
    ),
    jsDependencies,
    // The following is required for correct java serialization...
    Compile / classLoaderLayeringStrategy := ClassLoaderLayeringStrategy.Flat,
    testFrameworks += new TestFramework("munit.Framework"),
    Test / parallelExecution := false,
    Test / javaOptions ++= Seq("-Djava.awt.headless=true"),
    Test / fork := true
  )
