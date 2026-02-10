ThisBuild / version := "0.2.1"
ThisBuild / organization := "io.github.arauhala"

lazy val root = (project in file("."))
  .enablePlugins(SbtPlugin)
  .settings(
    name := "sbt-booktest",
    description := "SBT plugin for booktest snapshot testing framework",

    // SBT plugin must be built with Scala 2.12
    scalaVersion := "2.12.18",

    // Plugin metadata
    pluginCrossBuild / sbtVersion := "1.9.0",

    // Publishing
    publishMavenStyle := true,
    licenses := List("Apache-2.0" -> url("https://www.apache.org/licenses/LICENSE-2.0")),
    homepage := Some(url("https://github.com/arauhala/booktest-scala")),
    developers := List(
      Developer(
        id = "arauhala",
        name = "arauhala",
        email = "",
        url = url("https://github.com/arauhala")
      )
    )
  )
