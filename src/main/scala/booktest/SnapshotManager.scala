package booktest

import fansi.Color
import fansi.Color.{LightRed, LightGreen, LightYellow, LightBlue}

// Interactive prompt responses
sealed trait InteractiveResponse
object InteractiveResponse {
  case object Accept extends InteractiveResponse
  case object Reject extends InteractiveResponse
  case object Skip extends InteractiveResponse
  case object Quit extends InteractiveResponse
  case object AcceptAndQuit extends InteractiveResponse
}

// Diff display modes
sealed trait DiffMode
object DiffMode {
  case object Unified extends DiffMode    // Traditional unified diff (default)
  case object SideBySide extends DiffMode // Side-by-side comparison
  case object Inline extends DiffMode     // Inline character-level changes
  case object Minimal extends DiffMode    // Only show line numbers of differences
}

// Success state matching Python booktest's two-dimensional result
sealed trait SuccessState
object SuccessState {
  case object OK extends SuccessState     // Test passed, output matches
  case object DIFF extends SuccessState   // Output differs from snapshot (test-level diffs)
  case object FAIL extends SuccessState   // Test logic failed (explicit fail, exceptions)
}

case class TestResult(
  testName: String,
  passed: Boolean,
  successState: SuccessState = SuccessState.OK,
  output: String,
  snapshot: Option[String] = None,
  diff: Option[String] = None,
  returnValue: Option[Any] = None,
  durationMs: Long = 0,
  testRun: Option[TestCaseRun] = None
)

object SnapshotManager {

  /** Compare test output against snapshot.
    * Uses TestCaseRun's internal token-by-token comparison stats.
    * Like Python booktest: diffs/errors/infoDiffs are tracked during execution. */
  def compareTest(testRun: TestCaseRun, diffMode: DiffMode = DiffMode.Unified): TestResult = {
    val testOutput = testRun.getTestOutput
    val snapshot = testRun.getSnapshot

    val hasDiffs = testRun.diffs > 0
    val hasErrors = testRun.errors > 0 || testRun.isFailed
    val hasNoSnapshot = snapshot.isEmpty && testOutput.trim.nonEmpty

    val (passed, successState) = if (hasErrors) {
      (false, SuccessState.FAIL)
    } else if (hasDiffs || hasNoSnapshot) {
      (false, SuccessState.DIFF)
    } else {
      (true, SuccessState.OK)
    }

    // Use inline diff report from TestCaseRun (generated during execution with
    // proper info/diff/error markers), falling back to post-hoc diff
    val diff = if (!passed) {
      val inlineDiff = testRun.getDiffReport
      if (inlineDiff.trim.nonEmpty && snapshot.isDefined) {
        Some(inlineDiff)
      } else {
        snapshot match {
          case Some(snapshotContent) if testOutput.trim != snapshotContent.trim =>
            Some(generateDiff(snapshotContent, testOutput, diffMode))
          case None =>
            Some(s"No snapshot found for test '${testRun.testName}'")
          case _ if hasErrors =>
            testRun.failMessage.map(m => s"Test explicitly failed: $m")
          case _ =>
            None
        }
      }
    } else {
      None
    }

    TestResult(
      testName = testRun.testName,
      passed = passed,
      successState = successState,
      output = testOutput,
      snapshot = snapshot,
      diff = diff
    )
  }

  def updateSnapshot(testRun: TestCaseRun): Unit = {
    // Copy main test output (markdown)
    os.makeDir.all(testRun.snapshotFile / os.up)
    os.write.over(testRun.snapshotFile, testRun.getOutput)

    // Copy test assets directory if it exists (images, generated files)
    val assetsDir = testRun.assetsDir
    val snapshotAssetsDir = testRun.snapshotDir / testRun.testName
    if (os.exists(assetsDir) && os.list(assetsDir).nonEmpty) {
      os.makeDir.all(snapshotAssetsDir)
      os.list(assetsDir).foreach { file =>
        val name = file.last
        os.copy.over(file, snapshotAssetsDir / name)
      }
    }

    // Copy snapshots.json if it exists (HTTP/function snapshots)
    if (os.exists(testRun.snapshotsFile)) {
      val snapshotJsonDest = testRun.snapshotDir / s"${testRun.testName}.snapshots.json"
      os.copy.over(testRun.snapshotsFile, snapshotJsonDest)
    }
  }

  def generateDiff(expected: String, actual: String, mode: DiffMode = DiffMode.Unified): String = {
    mode match {
      case DiffMode.Unified => generateUnifiedDiff(expected, actual)
      case DiffMode.SideBySide => generateSideBySideDiff(expected, actual)
      case DiffMode.Inline => generateInlineDiff(expected, actual)
      case DiffMode.Minimal => generateMinimalDiff(expected, actual)
    }
  }

  private val DiffLineWidth = 60
  private def gray(text: String): String = s"\u001b[38;2;140;140;140m$text\u001b[0m"

  private def generateUnifiedDiff(expected: String, actual: String): String = {
    val expectedLines = expected.split("\n", -1)
    val actualLines = actual.split("\n", -1)
    val maxLines = math.max(expectedLines.length, actualLines.length)

    val diffLines = (0 until maxLines).map { i =>
      val expectedLine = if (i < expectedLines.length) Some(expectedLines(i)) else None
      val actualLine = if (i < actualLines.length) Some(actualLines(i)) else None

      (actualLine, expectedLine) match {
        case (Some(act), Some(exp)) if act == exp =>
          s"  ${act.take(DiffLineWidth)}"
        case (Some(act), Some(exp)) =>
          val padded = act.take(DiffLineWidth).padTo(DiffLineWidth, ' ')
          s"${LightYellow("?")} ${LightYellow(padded)} | ${gray(exp)}"
        case (Some(act), None) =>
          val padded = act.take(DiffLineWidth).padTo(DiffLineWidth, ' ')
          s"${LightYellow("?")} ${LightYellow(padded)} | ${gray("EOF")}"
        case (None, Some(exp)) =>
          val padded = "".padTo(DiffLineWidth, ' ')
          s"${LightYellow("?")} ${LightYellow(padded)} | ${gray(exp)}"
        case (None, None) =>
          ""
      }
    }

    diffLines.mkString("\n")
  }

  private def generateSideBySideDiff(expected: String, actual: String): String = {
    val expectedLines = expected.split("\n", -1)
    val actualLines = actual.split("\n", -1)
    val maxLines = math.max(expectedLines.length, actualLines.length)

    val header = s"  ${"Actual".padTo(DiffLineWidth, ' ')} | Expected"
    val separator = s"  ${"-" * DiffLineWidth}-+-${"-" * DiffLineWidth}"

    val diffLines = (0 until maxLines).map { i =>
      val expectedLine = if (i < expectedLines.length) expectedLines(i) else ""
      val actualLine = if (i < actualLines.length) actualLines(i) else ""

      if (expectedLine == actualLine) {
        s"  ${actualLine.take(DiffLineWidth).padTo(DiffLineWidth, ' ')} | ${expectedLine.take(DiffLineWidth)}"
      } else {
        val padded = actualLine.take(DiffLineWidth).padTo(DiffLineWidth, ' ')
        s"${LightYellow("?")} ${LightYellow(padded)} | ${gray(expectedLine.take(DiffLineWidth))}"
      }
    }

    (List(header, separator) ++ diffLines).mkString("\n")
  }

  private def generateInlineDiff(expected: String, actual: String): String = {
    val expectedLines = expected.split("\n", -1)
    val actualLines = actual.split("\n", -1)
    val maxLines = math.max(expectedLines.length, actualLines.length)

    val diffLines = (0 until maxLines).map { i =>
      val expectedLine = if (i < expectedLines.length) expectedLines(i) else ""
      val actualLine = if (i < actualLines.length) actualLines(i) else ""

      if (expectedLine == actualLine) {
        f"${i + 1}%3d:   $actualLine"
      } else {
        val padded = actualLine.take(DiffLineWidth).padTo(DiffLineWidth, ' ')
        f"${i + 1}%3d: ${LightYellow("?")} ${LightYellow(padded)} | ${gray(expectedLine)}"
      }
    }

    diffLines.mkString("\n")
  }

  private def generateMinimalDiff(expected: String, actual: String): String = {
    val expectedLines = expected.split("\n")
    val actualLines = actual.split("\n")
    val maxLines = math.max(expectedLines.length, actualLines.length)

    val diffLineNums = (0 until maxLines).filter { i =>
      val expectedLine = if (i < expectedLines.length) expectedLines(i) else ""
      val actualLine = if (i < actualLines.length) actualLines(i) else ""
      expectedLine != actualLine
    }

    if (diffLineNums.isEmpty) {
      "No differences found"
    } else {
      val lineNumbers = diffLineNums.map(i => (i + 1).toString).mkString(", ")
      s"Differences found on lines: $lineNumbers\n" +
      s"Total lines different: ${diffLineNums.length} out of $maxLines"
    }
  }

  def runDiffTool(expectedFile: os.Path, actualFile: os.Path): Unit = {
    val tool = sys.env.getOrElse("BOOKTEST_DIFF_TOOL", "diff")
    val cmd = s"$tool ${expectedFile} ${actualFile}"
    os.proc("sh", "-c", cmd).call(
      stdin = os.Inherit,
      stdout = os.Inherit,
      stderr = os.Inherit,
      check = false
    ).exitCode
  }

  def promptInteractive(): InteractiveResponse = {
    var response: InteractiveResponse = InteractiveResponse.Reject
    var validInput = false
    while (!validInput) {
      print(s"  (a)ccept, (c)ontinue, (aq) accept & quit or (q)uit? ")
      val input = scala.io.StdIn.readLine()
      if (input == null) {
        response = InteractiveResponse.Quit
        validInput = true
      } else {
        input.trim.toLowerCase match {
          case "a" | "accept" =>
            response = InteractiveResponse.Accept
            validInput = true
          case "c" | "continue" | "" =>
            response = InteractiveResponse.Reject
            validInput = true
          case "aq" =>
            response = InteractiveResponse.AcceptAndQuit
            validInput = true
          case "q" | "quit" =>
            response = InteractiveResponse.Quit
            validInput = true
          case _ =>
            println(LightRed("  Invalid input. Please enter 'a', 'c', 'aq', or 'q'."))
        }
      }
    }
    response
  }

  def printTestResult(result: TestResult, interactive: Boolean = false): InteractiveResponse = {
    if (result.passed) {
      println(LightGreen(s"✓ ${result.testName}"))
      InteractiveResponse.Reject
    } else {
      println(LightRed(s"✗ ${result.testName}"))
      result.diff.foreach { diff =>
        println(s"\nDiff for ${result.testName}:")
        println(diff)
        println()
      }

      if (interactive) {
        promptInteractive()
      } else {
        InteractiveResponse.Reject
      }
    }
  }

  def batchReviewResults(results: List[TestResult]): List[TestResult] = {
    val failedResults = results.filter(!_.passed)

    if (failedResults.isEmpty) {
      println(LightGreen("All tests passed! No review needed."))
      results
    } else {
      println(LightBlue(s"\n Batch Review Mode: ${failedResults.length} failed tests found"))
      println("=" * 60)

      val updatedResults = scala.collection.mutable.ListBuffer[TestResult]()
      var quit = false

      failedResults.zipWithIndex.takeWhile(_ => !quit).foreach { case (result, index) =>
        println(LightBlue(s"\n[${index + 1}/${failedResults.length}] Reviewing: ${result.testName}"))
        println("-" * 40)

        result.diff.foreach { diff =>
          println(s"Diff for ${result.testName}:")
          println(diff)
          println()
        }

        var validInput = false
        var shouldAccept = false
        var shouldSkip = false

        while (!validInput && !quit) {
          print(s"  (a)ccept, (c)ontinue, (s)kip, (aq) accept & quit or (q)uit? ")
          val input = scala.io.StdIn.readLine()

          if (input == null) {
            quit = true
          } else {
            input.trim.toLowerCase match {
              case "a" | "accept" =>
                shouldAccept = true
                validInput = true
                println(LightGreen("  Changes accepted"))

              case "c" | "continue" =>
                shouldAccept = false
                validInput = true

              case "s" | "skip" =>
                shouldSkip = true
                validInput = true
                println(LightYellow("  Skipped"))

              case "aq" =>
                shouldAccept = true
                validInput = true
                quit = true
                println(LightGreen("  Changes accepted. Quitting review."))

              case "q" | "quit" =>
                println(LightBlue("  Review session terminated."))
                quit = true

              case _ =>
                println(LightRed("  Invalid input. Please enter 'a', 'c', 's', 'aq', or 'q'."))
            }
          }
        }

        if (shouldAccept) {
          updatedResults += result.copy(passed = true)
        } else if (!shouldSkip && !quit) {
          updatedResults += result
        }
      }

      val updatedResultsMap = updatedResults.map(r => r.testName -> r).toMap
      results.map(r => updatedResultsMap.getOrElse(r.testName, r))
    }
  }
}
