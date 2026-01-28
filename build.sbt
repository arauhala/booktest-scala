ThisBuild / version := "0.2.0-SNAPSHOT"
ThisBuild / scalaVersion := "3.3.1"

ThisBuild / organization := "io.github.arauhala"
ThisBuild / organizationName := "arauhala"
ThisBuild / organizationHomepage := Some(url("https://github.com/arauhala"))

// Project metadata for Maven Central
ThisBuild / description := "A review-driven snapshot testing framework for Scala"
ThisBuild / homepage := Some(url("https://github.com/arauhala/booktest-scala"))
ThisBuild / licenses := List("Apache-2.0" -> url("https://www.apache.org/licenses/LICENSE-2.0"))

// Developer information
ThisBuild / developers := List(
  Developer(
    id = "arauhala",
    name = "arauhala",
    email = "", // Add your email if you want it public
    url = url("https://github.com/arauhala")
  )
)

// Source control information
ThisBuild / scmInfo := Some(
  ScmInfo(
    url("https://github.com/arauhala/booktest-scala"),
    "scm:git@github.com:arauhala/booktest-scala.git"
  )
)

// Publishing configuration for Sonatype Central Portal (central.sonatype.com)
ThisBuild / sonatypeCredentialHost := "central.sonatype.com"
ThisBuild / sonatypeRepository := "https://central.sonatype.com/api/v1/publisher"
ThisBuild / publishTo := sonatypePublishToBundle.value

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