ThisBuild / version := "0.1.0-SNAPSHOT"
ThisBuild / scalaVersion := "3.3.1"
ThisBuild / organization := "booktest"

lazy val root = (project in file("."))
  .settings(
    name := "booktest-scala",
    libraryDependencies ++= Seq(
      "com.lihaoyi" %% "os-lib" % "0.9.1",
      "com.lihaoyi" %% "fansi" % "0.4.0",
      "org.scalameta" %% "munit" % "0.7.29" % Test
    ),
    scalacOptions ++= Seq(
      "-deprecation",
      "-feature",
      "-unchecked",
      "-Wunused:all"
    )
  )