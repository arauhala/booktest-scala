package booktest

import os.Path
import fansi.Color

case class RunConfig(
  outputDir: Path = os.pwd / "books",
  snapshotDir: Path = os.pwd / "books",
  verbose: Boolean = false,
  interactive: Boolean = false,
  testFilter: Option[String] = None
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
  private val cacheDir = config.outputDir / ".cache"
  private val dependencyCache = new DependencyCache(cacheDir)
  
  def runSuite(suite: TestSuite): RunResult = {
    val suiteName = suite.suiteName
    val testCases = suite.testCases
    
    if (config.verbose) {
      println(s"Running test suite: $suiteName")
      println(s"Found ${testCases.length} test(s)")
    }
    
    val filteredTests = config.testFilter match {
      case Some(pattern) => testCases.filter(_.name.contains(pattern))
      case None => testCases
    }
    
    val sortedTests = resolveDependencyOrder(filteredTests)
    val results = sortedTests.map(runTestCase(_, suiteName))
    
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
      
      val returnValue = executeTestWithDependencies(testCase, testRun)
      
      val result = SnapshotManager.compareTest(testRun).copy(returnValue = Some(returnValue))
      testRun.writeOutput()
      
      if (result.passed) {
        dependencyCache.put(testCase.name, returnValue)
      }
      
      val shouldAccept = if (config.verbose || !result.passed) {
        SnapshotManager.printTestResult(result, config.interactive)
      } else {
        false
      }
      
      if (shouldAccept && !result.passed) {
        SnapshotManager.updateSnapshot(testRun)
        result.copy(passed = true)
      } else {
        result
      }
      
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
  
  private def resolveDependencyOrder(testCases: List[TestCase]): List[TestCase] = {
    val testMap = testCases.map(tc => tc.name -> tc).toMap
    val visited = scala.collection.mutable.Set[String]()
    val result = scala.collection.mutable.ListBuffer[TestCase]()
    
    def visit(testName: String): Unit = {
      if (!visited.contains(testName)) {
        visited += testName
        testMap.get(testName) match {
          case Some(testCase) =>
            testCase.dependencies.foreach(visit)
            result += testCase
          case None =>
            throw new IllegalArgumentException(s"Dependency '$testName' not found")
        }
      }
    }
    
    testCases.foreach(tc => visit(tc.name))
    result.toList
  }
  
  private def executeTestWithDependencies(testCase: TestCase, testRun: TestCaseRun): Any = {
    testCase.method match {
      case Some(method) if testCase.testInstance.isDefined =>
        val instance = testCase.testInstance.get
        val paramCount = method.getParameterCount
        
        if (paramCount == 1) {
          // No dependencies, just TestCaseRun
          method.invoke(instance, testRun)
        } else {
          // Has dependencies - need to inject cached values
          if (config.verbose) {
            println(s"    Looking for dependencies: ${testCase.dependencies}")
          }
          val dependencyValues = testCase.dependencies.map { depName =>
            val cached = dependencyCache.get[Any](depName)
            if (config.verbose) {
              println(s"    Dependency '$depName': ${cached.isDefined}")
            }
            cached.getOrElse {
              throw new IllegalStateException(s"Dependency '$depName' not found in cache for test '${testCase.name}'")
            }
          }
          
          // Create arguments array: TestCaseRun + dependency values
          val args = Array[AnyRef](testRun) ++ dependencyValues.map(_.asInstanceOf[AnyRef])
          method.invoke(instance, args: _*)
        }
        
      case _ =>
        // Fallback to original function
        testCase.testFunction(testRun)
    }
  }
}