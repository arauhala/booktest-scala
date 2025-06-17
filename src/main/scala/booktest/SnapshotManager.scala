package booktest

import fansi.Color

case class TestResult(
  testName: String,
  passed: Boolean,
  output: String,
  snapshot: Option[String] = None,
  diff: Option[String] = None
)

object SnapshotManager {
  
  def compareTest(testRun: TestCaseRun): TestResult = {
    val output = testRun.getOutput
    val snapshot = testRun.getSnapshot
    
    snapshot match {
      case Some(snapshotContent) =>
        val passed = output.trim == snapshotContent.trim
        val diff = if (!passed) Some(generateDiff(snapshotContent, output)) else None
        
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
  
  private def generateDiff(expected: String, actual: String): String = {
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
  
  def printTestResult(result: TestResult): Unit = {
    if (result.passed) {
      println(Color.Green(s"✓ ${result.testName}"))
    } else {
      println(Color.Red(s"✗ ${result.testName}"))
      result.diff.foreach { diff =>
        println(s"\nDiff for ${result.testName}:")
        println(diff)
        println()
      }
    }
  }
}