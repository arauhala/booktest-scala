package booktest

import os.Path
import fansi.Color

case class RunConfig(
  outputDir: Path = os.pwd / "books",
  snapshotDir: Path = os.pwd / "books",
  verbose: Boolean = false,
  interactive: Boolean = false
)

case class RunResult(
  totalTests: Int,
  passedTests: Int,
  failedTests: Int,
  results: List[TestResult]
) {
  def success: Boolean = failedTests == 0
  
  def summary: String = {
    val status = if (success) Color.Green("PASSED") else Color.Red("FAILED")
    s"$status: $passedTests passed, $failedTests failed, $totalTests total"
  }
}

class TestRunner(config: RunConfig = RunConfig()) {
  
  def runSuite(suite: TestSuite): RunResult = {
    val suiteName = suite.suiteName
    val testCases = suite.testCases
    
    if (config.verbose) {
      println(s"Running test suite: $suiteName")
      println(s"Found ${testCases.length} test(s)")
    }
    
    val results = testCases.map(runTestCase(_, suiteName))
    
    val passedCount = results.count(_.passed)
    val failedCount = results.count(!_.passed)
    
    RunResult(
      totalTests = results.length,
      passedTests = passedCount,
      failedTests = failedCount,
      results = results
    )
  }
  
  def runTestCase(testCase: TestCase, suiteName: String): TestResult = {
    val testPath = os.pwd / "src" / "test" / "scala" / "booktest" / "examples" / suiteName
    val outputDir = config.outputDir / suiteName
    val snapshotDir = config.snapshotDir / suiteName
    
    val testRun = new TestCaseRun(
      testName = testCase.name,
      testPath = testPath,
      outputDir = outputDir,
      snapshotDir = snapshotDir
    )
    
    try {
      if (config.verbose) {
        println(s"  Running: ${testCase.name}")
      }
      
      testCase.testFunction(testRun)
      
      val result = SnapshotManager.compareTest(testRun)
      testRun.writeOutput()
      
      if (config.verbose || !result.passed) {
        SnapshotManager.printTestResult(result)
      }
      
      result
      
    } catch {
      case e: Exception =>
        val errorMessage = s"Test '${testCase.name}' failed with exception: ${e.getMessage}"
        println(Color.Red(errorMessage))
        if (config.verbose) {
          e.printStackTrace()
        }
        
        TestResult(
          testName = testCase.name,
          passed = false,
          output = "",
          diff = Some(errorMessage)
        )
    }
  }
  
  def runMultipleSuites(suites: List[TestSuite]): RunResult = {
    val allResults = suites.map(runSuite)
    
    val totalTests = allResults.map(_.totalTests).sum
    val totalPassed = allResults.map(_.passedTests).sum
    val totalFailed = allResults.map(_.failedTests).sum
    val allTestResults = allResults.flatMap(_.results)
    
    RunResult(
      totalTests = totalTests,
      passedTests = totalPassed,
      failedTests = totalFailed,
      results = allTestResults
    )
  }
}