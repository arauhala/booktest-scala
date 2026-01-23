ThisBuild / version := "0.1.0-SNAPSHOT"
ThisBuild / scalaVersion := "3.3.1"

// TODO: Replace with your actual organization (e.g., "io.github.lumoa-oss")
ThisBuild / organization := "io.github.lumoa-oss"
ThisBuild / organizationName := "Lumoa Oss"
ThisBuild / organizationHomepage := Some(url("https://github.com/lumoa-oss"))

// Project metadata for Maven Central
ThisBuild / description := "A review-driven snapshot testing framework for Scala"
ThisBuild / homepage := Some(url("https://github.com/lumoa-oss/booktest-scala"))
ThisBuild / licenses := List("Apache-2.0" -> url("https://www.apache.org/licenses/LICENSE-2.0"))

// Developer information
ThisBuild / developers := List(
  Developer(
    id = "lumoa-oss",
    name = "Lumoa Oss",
    email = "oss@lumoa.me",
    url = url("https://github.com/lumoa-oss")
  )
)

// Source control information
ThisBuild / scmInfo := Some(
  ScmInfo(
    url("https://github.com/lumoa-oss/booktest-scala"),
    "scm:git@github.com:lumoa-oss/booktest-scala.git"
  )
)

// Publishing configuration for Sonatype/Maven Central
ThisBuild / publishTo := sonatypePublishToBundle.value
ThisBuild / sonatypeCredentialHost := "s01.oss.sonatype.org"
ThisBuild / sonatypeRepository := "https://s01.oss.sonatype.org/service/local"

lazy val root = (project in file("."))
  .settings(
    name := "booktest-scala",
    libraryDependencies ++= Seq(
      "com.lihaoyi" %% "os-lib" % "0.9.1",
      "com.lihaoyi" %% "fansi" % "0.4.0",
      "com.lihaoyi" %% "upickle" % "3.1.3",
      "com.softwaremill.sttp.client3" %% "core" % "3.9.1",
      "com.softwaremill.sttp.client3" %% "okhttp-backend" % "3.9.1",
      "org.scalameta" %% "munit" % "0.7.29" % Test
    ),
    scalacOptions ++= Seq(
      "-deprecation",
      "-feature",
      "-unchecked",
      "-Wunused:all"
    )
  )