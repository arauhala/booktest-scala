package booktest

import fansi.Color

// Diff display modes
sealed trait DiffMode
object DiffMode {
  case object Unified extends DiffMode    // Traditional unified diff (default)
  case object SideBySide extends DiffMode // Side-by-side comparison
  case object Inline extends DiffMode     // Inline character-level changes
  case object Minimal extends DiffMode    // Only show line numbers of differences
}

case class TestResult(
  testName: String,
  passed: Boolean,
  output: String,
  snapshot: Option[String] = None,
  diff: Option[String] = None,
  returnValue: Option[Any] = None,
  durationMs: Long = 0
)

object SnapshotManager {
  
  def compareTest(testRun: TestCaseRun, diffMode: DiffMode = DiffMode.Unified): TestResult = {
    val output = testRun.getOutput
    val snapshot = testRun.getSnapshot
    
    snapshot match {
      case Some(snapshotContent) =>
        val passed = output.trim == snapshotContent.trim
        val diff = if (!passed) Some(generateDiff(snapshotContent, output, diffMode)) else None
        
        TestResult(
          testName = testRun.testName,
          passed = passed,
          output = output,
          snapshot = Some(snapshotContent),
          diff = diff
        )
        
      case None =>
        TestResult(
          testName = testRun.testName,
          passed = false,
          output = output,
          snapshot = None,
          diff = Some(s"No snapshot found for test '${testRun.testName}'")
        )
    }
  }
  
  def updateSnapshot(testRun: TestCaseRun): Unit = {
    os.makeDir.all(testRun.snapshotFile / os.up)
    os.write.over(testRun.snapshotFile, testRun.getOutput)
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
          result.append(Color.Red(s"- $expectedLine")).append("\n")
        }
        if (actualLine.nonEmpty) {
          result.append(Color.Green(s"+ $actualLine"))
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
    val maxWidth = 40 // Maximum width for each side
    
    val header = s"${"Expected".padTo(maxWidth, ' ')} | ${"Actual".padTo(maxWidth, ' ')}"
    val separator = s"${"-" * maxWidth} | ${"-" * maxWidth}"
    
    val diffLines = (0 until maxLines).map { i =>
      val expectedLine = if (i < expectedLines.length) expectedLines(i) else ""
      val actualLine = if (i < actualLines.length) actualLines(i) else ""
      
      val expectedDisplay = if (expectedLine.length > maxWidth) {
        expectedLine.take(maxWidth - 3) + "..."
      } else {
        expectedLine
      }
      
      val actualDisplay = if (actualLine.length > maxWidth) {
        actualLine.take(maxWidth - 3) + "..."
      } else {
        actualLine
      }
      
      if (expectedLine == actualLine) {
        s"${expectedDisplay.padTo(maxWidth, ' ')} | ${actualDisplay.padTo(maxWidth, ' ')}"
      } else {
        val expectedFormatted = if (expectedLine.nonEmpty) Color.Red(expectedDisplay.padTo(maxWidth, ' ')) else "".padTo(maxWidth, ' ')
        val actualFormatted = if (actualLine.nonEmpty) Color.Green(actualDisplay.padTo(maxWidth, ' ')) else "".padTo(maxWidth, ' ')
        s"$expectedFormatted | $actualFormatted"
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
        result += s"${Color.Blue(s"@@ Line ${i + 1} @@")}"
        if (expectedLine.nonEmpty) {
          result += s"${Color.Red(s"- $expectedLine")}"
        }
        if (actualLine.nonEmpty) {
          result += s"${Color.Green(s"+ $actualLine")}"
        }
        result.result()
      }
    }
    
    diffLines.mkString("\n")
  }
  
  private def generateMinimalDiff(expected: String, actual: String): String = {
    val expectedLines = expected.split("\n")
    val actualLines = actual.split("\n")
    val maxLines = math.max(expectedLines.length, actualLines.length)
    
    val diffLines = (0 until maxLines).filter { i =>
      val expectedLine = if (i < expectedLines.length) expectedLines(i) else ""
      val actualLine = if (i < actualLines.length) actualLines(i) else ""
      expectedLine != actualLine
    }
    
    if (diffLines.isEmpty) {
      "No differences found"
    } else {
      val lineNumbers = diffLines.map(i => (i + 1).toString).mkString(", ")
      s"Differences found on lines: $lineNumbers\n" +
      s"Total lines different: ${diffLines.length} out of $maxLines"
    }
  }
  
  def printTestResult(result: TestResult, interactive: Boolean = false): Boolean = {
    if (result.passed) {
      println(Color.Green(s"✓ ${result.testName}"))
      false
    } else {
      println(Color.Red(s"✗ ${result.testName}"))
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
      println(Color.Green("All tests passed! No review needed."))
      results
    } else {
      println(Color.Blue(s"\n📋 Batch Review Mode: ${failedResults.length} failed tests found"))
      println("=" * 60)
      
      val updatedResults = scala.collection.mutable.ListBuffer[TestResult]()
      var quit = false
      
      failedResults.zipWithIndex.takeWhile(_ => !quit).foreach { case (result, index) =>
        println(Color.Blue(s"\n[${index + 1}/${failedResults.length}] Reviewing: ${result.testName}"))
        println("-" * 40)
        
        result.diff.foreach { diff =>
          println(s"Diff for ${result.testName}:")
          println(diff)
          println()
        }
        
        // Interactive prompt with more options
        var validInput = false
        var shouldAccept = false
        var shouldSkip = false
        
        while (!validInput && !quit) {
          print(s"${Color.Yellow("Options:")} [y]es / [n]o / [s]kip / [q]uit: ")
          val input = scala.io.StdIn.readLine().trim.toLowerCase
          
          input match {
            case "y" | "yes" =>
              shouldAccept = true
              validInput = true
              println(Color.Green("✓ Changes accepted"))
              
            case "n" | "no" =>
              shouldAccept = false
              validInput = true
              println(Color.Red("✗ Changes rejected"))
              
            case "s" | "skip" =>
              shouldSkip = true
              validInput = true
              println(Color.Yellow("⏭ Skipped"))
              
            case "q" | "quit" =>
              println(Color.Blue("Review session terminated."))
              quit = true
              
            case _ =>
              println(Color.Red("Invalid input. Please enter 'y', 'n', 's', or 'q'."))
          }
        }
        
        if (!quit) {
          if (shouldAccept) {
            // Update the snapshot and mark as passed
            updatedResults += result.copy(passed = true)
          } else if (!shouldSkip) {
            updatedResults += result
          }
        }
      }
      
      // Replace failed results with updated ones
      val updatedResultsMap = updatedResults.map(r => r.testName -> r).toMap
      results.map(r => updatedResultsMap.getOrElse(r.testName, r))
    }
  }
}