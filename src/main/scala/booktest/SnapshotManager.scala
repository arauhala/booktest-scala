package booktest

import fansi.Color
import fansi.Color.{LightRed, LightGreen, LightYellow, LightCyan, LightBlue}

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

    // Generate diff for display (post-hoc line comparison for readable output)
    val diff = if (!passed) {
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

  private def generateUnifiedDiff(expected: String, actual: String): String = {
    val expectedLines = expected.split("\n")
    val actualLines = actual.split("\n")
    val maxLines = math.max(expectedLines.length, actualLines.length)

    val diffLines = (0 until maxLines).map { i =>
      val expectedLine = if (i < expectedLines.length) expectedLines(i) else ""
      val actualLine = if (i < actualLines.length) actualLines(i) else ""

      if (expectedLine == actualLine) {
        s"  $actualLine"
      } else {
        val result = new StringBuilder
        if (expectedLine.nonEmpty) {
          result.append(LightRed(s"- $expectedLine")).append("\n")
        }
        if (actualLine.nonEmpty) {
          result.append(LightGreen(s"+ $actualLine"))
        }
        result.toString
      }
    }

    diffLines.mkString("\n")
  }

  private def generateSideBySideDiff(expected: String, actual: String): String = {
    val expectedLines = expected.split("\n")
    val actualLines = actual.split("\n")
    val maxLines = math.max(expectedLines.length, actualLines.length)
    val maxWidth = 40

    val header = s"${"Expected".padTo(maxWidth, ' ')} | ${"Actual".padTo(maxWidth, ' ')}"
    val separator = s"${"-" * maxWidth} | ${"-" * maxWidth}"

    val diffLines = (0 until maxLines).map { i =>
      val expectedLine = if (i < expectedLines.length) expectedLines(i) else ""
      val actualLine = if (i < actualLines.length) actualLines(i) else ""

      val expectedDisplay = if (expectedLine.length > maxWidth) expectedLine.take(maxWidth - 3) + "..." else expectedLine
      val actualDisplay = if (actualLine.length > maxWidth) actualLine.take(maxWidth - 3) + "..." else actualLine

      if (expectedLine == actualLine) {
        s"${expectedDisplay.padTo(maxWidth, ' ')} | ${actualDisplay.padTo(maxWidth, ' ')}"
      } else {
        val ef = if (expectedLine.nonEmpty) LightRed(expectedDisplay.padTo(maxWidth, ' ')) else "".padTo(maxWidth, ' ')
        val af = if (actualLine.nonEmpty) LightGreen(actualDisplay.padTo(maxWidth, ' ')) else "".padTo(maxWidth, ' ')
        s"$ef | $af"
      }
    }

    (List(header, separator) ++ diffLines).mkString("\n")
  }

  private def generateInlineDiff(expected: String, actual: String): String = {
    val expectedLines = expected.split("\n")
    val actualLines = actual.split("\n")
    val maxLines = math.max(expectedLines.length, actualLines.length)

    val diffLines = (0 until maxLines).flatMap { i =>
      val expectedLine = if (i < expectedLines.length) expectedLines(i) else ""
      val actualLine = if (i < actualLines.length) actualLines(i) else ""

      if (expectedLine == actualLine) {
        List(f"${i + 1}%3d: $actualLine")
      } else {
        val result = List.newBuilder[String]
        result += s"${LightBlue(s"@@ Line ${i + 1} @@")}"
        if (expectedLine.nonEmpty) result += s"${LightRed(s"- $expectedLine")}"
        if (actualLine.nonEmpty) result += s"${LightGreen(s"+ $actualLine")}"
        result.result()
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

  def printTestResult(result: TestResult, interactive: Boolean = false): Boolean = {
    if (result.passed) {
      println(LightGreen(s"✓ ${result.testName}"))
      false
    } else {
      println(LightRed(s"✗ ${result.testName}"))
      result.diff.foreach { diff =>
        println(s"\nDiff for ${result.testName}:")
        println(diff)
        println()
      }

      if (interactive) {
        print(s"Accept changes for ${result.testName}? (y/N): ")
        val input = scala.io.StdIn.readLine().trim.toLowerCase
        input == "y" || input == "yes"
      } else {
        false
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
          print(s"${LightYellow("Options:")} [y]es / [n]o / [s]kip / [q]uit: ")
          val input = scala.io.StdIn.readLine().trim.toLowerCase

          input match {
            case "y" | "yes" =>
              shouldAccept = true
              validInput = true
              println(LightGreen("Changes accepted"))

            case "n" | "no" =>
              shouldAccept = false
              validInput = true
              println(LightRed("Changes rejected"))

            case "s" | "skip" =>
              shouldSkip = true
              validInput = true
              println(LightYellow("Skipped"))

            case "q" | "quit" =>
              println(LightBlue("Review session terminated."))
              quit = true

            case _ =>
              println(LightRed("Invalid input. Please enter 'y', 'n', 's', or 'q'."))
          }
        }

        if (!quit) {
          if (shouldAccept) {
            updatedResults += result.copy(passed = true)
          } else if (!shouldSkip) {
            updatedResults += result
          }
        }
      }

      val updatedResultsMap = updatedResults.map(r => r.testName -> r).toMap
      results.map(r => updatedResultsMap.getOrElse(r.testName, r))
    }
  }
}
