package booktest

import os.Path
import fansi.Color

case class RunConfig(
  outputDir: Path = os.pwd / "books",
  snapshotDir: Path = os.pwd / "books",
  verbose: Boolean = false,
  interactive: Boolean = false,
  testFilter: Option[String] = None,
  diffMode: DiffMode = DiffMode.Unified,
  batchReview: Boolean = false
)

case class RunResult(
  totalTests: Int,
  passedTests: Int,
  failedTests: Int,
  results: List[TestResult],
  totalDurationMs: Long = 0
) {
  def success: Boolean = failedTests == 0
  
  def summary: String = {
    if (success) {
      s"$totalTests/$totalTests test succeeded in $totalDurationMs ms"
    } else {
      s"$failedTests/$totalTests test failed in $totalDurationMs ms"
    }
  }
}

class TestRunner(config: RunConfig = RunConfig()) {
  private val cacheDir = config.outputDir / ".cache"
  private val dependencyCache = new DependencyCache(cacheDir)
  
  def runSuite(suite: TestSuite): RunResult = {
    val suiteName = suite.suiteName
    val testCases = suite.testCases
    
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
    val outDir = config.outputDir / ".out" / suiteName
    
    // Ensure directories exist
    os.makeDir.all(outDir)
    
    val testRun = new TestCaseRun(
      testName = testCase.name,
      testPath = testPath,
      outputDir = outputDir,
      snapshotDir = snapshotDir,
      outDir = outDir
    )
    
    val startTime = System.currentTimeMillis()
    
    try {
      if (!config.verbose) {
        print(s"  ${suiteName}/${testCase.name}..")
      } else {
        println(s"  Running: ${testCase.name}")
      }
      
      val returnValue = executeTestWithDependencies(testCase, testRun)
      
      val endTime = System.currentTimeMillis()
      val duration = endTime - startTime
      
      val snapshotResult = SnapshotManager.compareTest(testRun, config.diffMode)

      // Check if test explicitly failed via t.fail()
      val result = if (testRun.isFailed) {
        val failMsg = testRun.failMessage.map(m => s": $m").getOrElse("")
        snapshotResult.copy(
          testName = s"$suiteName/${testCase.name}",
          passed = false,
          diff = Some(s"Test explicitly failed$failMsg"),
          returnValue = Some(returnValue),
          durationMs = duration
        )
      } else {
        snapshotResult.copy(
          testName = s"$suiteName/${testCase.name}",  // Store with suite prefix
          returnValue = Some(returnValue),
          durationMs = duration
        )
      }
      testRun.writeOutput()
      
      // Write log file
      os.write.over(testRun.logFile, s"Test completed in ${duration}ms\n")
      
      // Write report file
      testRun.writeReport(s"Test '${testCase.name}' completed in ${duration}ms")
      
      if (result.passed) {
        dependencyCache.put(testCase.name, returnValue)
      }
      
      val shouldAccept = if (config.verbose || !result.passed) {
        SnapshotManager.printTestResult(result, config.interactive)
      } else {
        if (result.passed) {
          println(s"${duration} ms")
        } else {
          println(s"FAILED in ${duration} ms")
        }
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
        val endTime = System.currentTimeMillis()
        val duration = endTime - startTime
        val errorMessage = s"Test '${testCase.name}' failed with exception: ${e.getMessage}"
        
        // Write error log
        os.write.over(testRun.logFile, s"ERROR: ${e.getMessage}\nDuration: ${duration}ms\n")
        
        // Write error report
        testRun.writeReport(s"Test '${testCase.name}' FAILED: ${e.getMessage}\nDuration: ${duration}ms")
        
        if (!config.verbose) {
          println(s"FAILED in ${duration} ms")
        } else {
          println(Color.Red(errorMessage))
          if (config.verbose) {
            e.printStackTrace()
          }
        }
        
        TestResult(
          testName = s"$suiteName/${testCase.name}",  // Store with suite prefix
          passed = false,
          output = "",
          diff = Some(errorMessage),
          durationMs = duration
        )
    }
  }
  
  def runMultipleSuites(suites: List[TestSuite]): RunResult = {
    val startTime = System.currentTimeMillis()
    
    // Print header
    println()
    println("# test results:")
    println()
    
    val allResults = suites.map(runSuite)
    
    val endTime = System.currentTimeMillis()
    val totalDuration = endTime - startTime
    
    val totalTests = allResults.map(_.totalTests).sum
    val totalPassed = allResults.map(_.passedTests).sum
    val totalFailed = allResults.map(_.failedTests).sum
    val allTestResults = allResults.flatMap(_.results)
    
    // Handle batch review mode
    val finalResults = if (config.batchReview && totalFailed > 0) {
      val reviewedResults = SnapshotManager.batchReviewResults(allTestResults)
      // Update snapshots for accepted changes
      reviewedResults.foreach { result =>
        if (result.passed && allTestResults.find(_.testName == result.testName).exists(!_.passed)) {
          // This test was accepted during review - update its snapshot
          val parts = result.testName.split("/")
          if (parts.length >= 2) {
            val suiteName = parts(0)
            val testName = parts(1)
            val outFile = config.outputDir / ".out" / suiteName / s"$testName.md"
            val snapshotFile = config.snapshotDir / suiteName / s"$testName.md"
            if (os.exists(outFile)) {
              os.makeDir.all(snapshotFile / os.up)
              os.copy.over(outFile, snapshotFile)
            }
          }
        }
      }
      reviewedResults
    } else {
      allTestResults
    }
    
    // Write case reports for review functionality
    writeCaseReports(finalResults)
    
    // Recalculate counts after potential review updates
    val finalPassedCount = finalResults.count(_.passed)
    val finalFailedCount = finalResults.count(!_.passed)
    
    RunResult(
      totalTests = totalTests,
      passedTests = finalPassedCount,
      failedTests = finalFailedCount,
      results = finalResults,
      totalDurationMs = totalDuration
    )
  }
  
  private def writeCaseReports(results: List[TestResult]): Unit = {
    val caseReports = results.map { result =>
      val status = if (result.passed) "OK" else "DIFF"
      // Store test name with suite prefix for proper review lookup
      val fullTestName = result.testName
      CaseReport(fullTestName, status, result.durationMs)
    }
    
    val reports = new CaseReports(caseReports)
    val casesFile = config.outputDir / ".out" / "cases.txt"
    reports.writeToFile(casesFile)
  }
  
  def reviewResults(suites: List[TestSuite]): Int = {
    val outDir = config.outputDir / ".out"
    val reports = CaseReports.fromDir(outDir)
    
    println()
    println("# Review Results:")
    println()
    
    var allAccepted = true
    
    reports.cases.foreach { caseReport =>
      val parts = caseReport.testName.split("/")
      if (parts.length >= 2) {
        val suiteName = parts(0)
        val testName = parts(1)
        
        val outFile = outDir / suiteName / s"$testName.md"
        val snapshotFile = config.snapshotDir / suiteName / s"$testName.md"
        
        if (os.exists(outFile)) {
          val output = os.read(outFile)
          val hasSnapshot = os.exists(snapshotFile)
          val snapshot = if (hasSnapshot) Some(os.read(snapshotFile)) else None
          
          val passed = snapshot.exists(_ == output)
          
          if (!passed) {
            println(s"${caseReport.testName} - ${caseReport.result}")
            
            if (config.interactive) {
              print("Accept changes? (y/N): ")
              val input = scala.io.StdIn.readLine()
              if (input != null && input.toLowerCase == "y") {
                // Accept the snapshot
                os.makeDir.all(snapshotFile / os.up)
                os.copy.over(outFile, snapshotFile)
                println("Changes accepted.")
              } else {
                allAccepted = false
                println("Changes rejected.")
              }
            } else {
              allAccepted = false
              if (hasSnapshot) {
                val diff = SnapshotManager.generateDiff(snapshot.get, output)
                println(diff)
              } else {
                println("No snapshot found - new test")
              }
            }
            println()
          }
        }
      }
    }
    
    if (allAccepted) 0 else 1
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
    testCase.originalFunction match {
      case Some(function) =>
        // New API: Use the stored function with proper type-safe dependency injection
        if (testCase.dependencies.isEmpty) {
          // No dependencies - function is (TestCaseRun) => T
          function.asInstanceOf[(TestCaseRun) => Any](testRun)
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
          
          // Call the function with proper arguments based on number of dependencies
          testCase.dependencies.size match {
            case 1 =>
              function.asInstanceOf[(TestCaseRun, Any) => Any](testRun, dependencyValues(0))
            case 2 =>
              function.asInstanceOf[(TestCaseRun, Any, Any) => Any](testRun, dependencyValues(0), dependencyValues(1))
            case 3 =>
              function.asInstanceOf[(TestCaseRun, Any, Any, Any) => Any](testRun, dependencyValues(0), dependencyValues(1), dependencyValues(2))
            case n =>
              throw new IllegalArgumentException(s"Too many dependencies ($n). Maximum supported is 3.")
          }
        }
        
      case None =>
        // Old API: Use reflection-based approach for backward compatibility
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
}