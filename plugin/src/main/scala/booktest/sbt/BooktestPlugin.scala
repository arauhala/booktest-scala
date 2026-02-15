package booktest.sbt

import sbt._
import sbt.Keys._

object BooktestPlugin extends AutoPlugin {

  override def trigger = allRequirements

  object autoImport {
    // Settings
    val booktestVersion = settingKey[String]("Booktest library version")
    val booktestConfig = settingKey[File]("Path to booktest.conf")
    val booktestOutputDir = settingKey[File]("Output directory for test results")
    val booktestSnapshotDir = settingKey[File]("Snapshot directory")
    val booktestDefaultGroup = settingKey[String]("Default test group to run")
    val booktestParallel = settingKey[Int]("Number of parallel threads (0 = sequential)")

    // Tasks
    val booktest = inputKey[Unit]("Run booktest tests")
    val booktestOnly = inputKey[Unit]("Run specific booktest test class")
    val booktestUpdate = inputKey[Unit]("Run booktest and auto-accept changes (-s)")
    val booktestRecapture = inputKey[Unit]("Run booktest and force regenerate snapshots (-S)")
    val booktestReview = inputKey[Unit]("Review previous test results")
    val booktestList = taskKey[Unit]("List available booktest test classes")
  }

  import autoImport._

  override lazy val projectSettings: Seq[Setting[_]] = Seq(
    // Default settings
    booktestVersion := "0.2.1",
    booktestConfig := baseDirectory.value / "booktest.conf",
    booktestOutputDir := baseDirectory.value / "books",
    booktestSnapshotDir := baseDirectory.value / "books",
    booktestDefaultGroup := "default",
    booktestParallel := 1,

    // Add booktest dependency to Test configuration
    libraryDependencies += "io.github.arauhala" %% "booktest-scala" % booktestVersion.value % Test,

    // booktest task - run default group or specified group
    booktest := {
      val args = Def.spaceDelimited("<group>").parsed
      val group = args.headOption.getOrElse(booktestDefaultGroup.value)
      runBooktest(
        (Test / runner).value,
        (Test / fullClasspath).value,
        booktestOutputDir.value,
        booktestSnapshotDir.value,
        booktestParallel.value,
        extraArgs = Seq(group),
        streams.value.log
      )
    },

    // booktestOnly task - run specific test class
    booktestOnly := {
      val args = Def.spaceDelimited("<class>").parsed
      if (args.isEmpty) {
        throw new IllegalArgumentException("Usage: booktestOnly <fully.qualified.ClassName>")
      }
      runBooktest(
        (Test / runner).value,
        (Test / fullClasspath).value,
        booktestOutputDir.value,
        booktestSnapshotDir.value,
        booktestParallel.value,
        extraArgs = args,
        streams.value.log
      )
    },

    // booktestUpdate task - run with -s flag
    booktestUpdate := {
      val args = Def.spaceDelimited("<group>").parsed
      val group = args.headOption.getOrElse(booktestDefaultGroup.value)
      runBooktest(
        (Test / runner).value,
        (Test / fullClasspath).value,
        booktestOutputDir.value,
        booktestSnapshotDir.value,
        booktestParallel.value,
        extraArgs = Seq("-s", group),
        streams.value.log
      )
    },

    // booktestRecapture task - run with -S flag
    booktestRecapture := {
      val args = Def.spaceDelimited("<group>").parsed
      val group = args.headOption.getOrElse(booktestDefaultGroup.value)
      runBooktest(
        (Test / runner).value,
        (Test / fullClasspath).value,
        booktestOutputDir.value,
        booktestSnapshotDir.value,
        booktestParallel.value,
        extraArgs = Seq("-S", group),
        streams.value.log
      )
    },

    // booktestReview task - review mode
    booktestReview := {
      val args = Def.spaceDelimited("<group>").parsed
      val group = args.headOption.getOrElse(booktestDefaultGroup.value)
      runBooktest(
        (Test / runner).value,
        (Test / fullClasspath).value,
        booktestOutputDir.value,
        booktestSnapshotDir.value,
        booktestParallel.value,
        extraArgs = Seq("-w", group),
        streams.value.log
      )
    },

    // booktestList task - list tests
    booktestList := {
      runBooktest(
        (Test / runner).value,
        (Test / fullClasspath).value,
        booktestOutputDir.value,
        booktestSnapshotDir.value,
        booktestParallel.value,
        extraArgs = Seq("-l"),
        streams.value.log
      )
    }
  )

  private def runBooktest(
    runner: ScalaRun,
    classpath: Classpath,
    outputDir: File,
    snapshotDir: File,
    parallel: Int,
    extraArgs: Seq[String],
    log: Logger
  ): Unit = {
    val baseArgs = Seq(
      "--output-dir", outputDir.getAbsolutePath,
      "--snapshot-dir", snapshotDir.getAbsolutePath
    ) ++ (if (parallel > 1) Seq(s"-j$parallel") else Seq.empty)

    val allArgs = baseArgs ++ extraArgs

    log.info(s"Running: booktest ${allArgs.mkString(" ")}")

    runner.run(
      mainClass = "booktest.BooktestMain",
      classpath = classpath.files,
      options = allArgs,
      log = log
    ).get  // Throws exception on failure
  }
}
