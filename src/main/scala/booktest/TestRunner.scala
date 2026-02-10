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
  batchReview: Boolean = false,
  summaryMode: Boolean = true,  // Python-style: show diffs at end (default)
  recaptureAll: Boolean = false,  // -S: force regenerate all snapshots
  updateSnapshots: Boolean = false,  // -s: auto-accept snapshot changes
  threads: Int = 1,  // -j N: number of threads for parallel execution
  booktestConfig: BooktestConfig = BooktestConfig.empty  // test-root and groups config
) {
  /** Get the test path for a suite, applying test-root stripping */
  def getSuitePath(suiteName: String): String = {
    booktestConfig.classNameToPath(suiteName)
  }
}

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
  private val dependencyCache = new DependencyCache()
  private val resourceManager = new ResourceManager()

  /** Get the resource manager for tests that need ports/resources */
  def resources: ResourceManager = resourceManager
  
  def runSuite(suite: TestSuite): RunResult = {
    val suiteName = suite.suiteName
    val fullClassName = suite.fullClassName  // For path computation with test-root
    val testCases = suite.testCases

    val filteredTests = config.testFilter match {
      case Some(pattern) => testCases.filter(_.name.contains(pattern))
      case None => testCases
    }

    // Call beforeAll hook
    try {
      suite.getBeforeAll()
    } catch {
      case e: Exception =>
        println(s"Warning: beforeAll failed for $suiteName: ${e.getMessage}")
    }

    val results = try {
      if (config.threads > 1) {
        runTestsParallel(filteredTests, fullClassName, suite)
      } else {
        val sortedTests = resolveDependencyOrder(filteredTests)
        sortedTests.map(runTestCase(_, fullClassName, suite))
      }
    } finally {
      // Call afterAll hook
      try {
        suite.getAfterAll()
      } catch {
        case e: Exception =>
          println(s"Warning: afterAll failed for $suiteName: ${e.getMessage}")
      }
    }

    val passedCount = results.count(_.passed)
    val failedCount = results.count(!_.passed)

    RunResult(
      totalTests = results.length,
      passedTests = passedCount,
      failedTests = failedCount,
      results = results
    )
  }
  
  def runTestCase(testCase: TestCase, suiteName: String, suite: TestSuite = null): TestResult = {
    // Apply test-root stripping to get the filesystem path
    val suitePath = config.getSuitePath(suiteName)
    val suitePathSegments = os.RelPath(suitePath)

    val testPath = os.pwd / "src" / "test" / "scala" / suitePathSegments
    val outputDir = config.outputDir / suitePathSegments
    val snapshotDir = config.snapshotDir / suitePathSegments
    val outDir = config.outputDir / ".out" / suitePathSegments

    // Ensure directories exist
    os.makeDir.all(outDir)

    val testRun = new TestCaseRun(
      testName = testCase.name,
      testPath = testPath,
      outputDir = outputDir,
      snapshotDir = snapshotDir,
      outDir = outDir
    )
    testRun.setFlags(config.recaptureAll, config.updateSnapshots)

    // Clear temp directory from previous runs (tmp files persist for dependent tests but are cleared on re-run)
    testRun.clearTmpDir()

    val startTime = System.currentTimeMillis()
    // Use the simplified path in test output
    val fullTestName = s"${suitePath}/${testCase.name}"

    // Print test name before capture starts
    if (!config.verbose) {
      print(s"  ${fullTestName}..")
    } else {
      println(s"test $fullTestName")
      println()
    }

    // Acquire resource locks (for tests that share state)
    val locks = if (suite != null) suite.getResourceLocks else List.empty
    locks.foreach(lock => resourceManager.locks.acquire(lock))

    // Capture stdout/stderr during test execution
    val logCapture = new LogCapture(testRun.logFile)
    logCapture.start()

    try {
      // Call setup hook
      if (suite != null) {
        try { suite.getSetup(testRun) }
        catch { case e: Exception => testRun.iln(s"Setup failed: ${e.getMessage}") }
      }

      val returnValue = try {
        executeTestWithDependencies(testCase, testRun)
      } finally {
        // Call teardown hook (always, even if test fails)
        if (suite != null) {
          try { suite.getTeardown(testRun) }
          catch { case e: Exception => testRun.iln(s"Teardown failed: ${e.getMessage}") }
        }
      }

      val endTime = System.currentTimeMillis()
      val duration = endTime - startTime

      val snapshotResult = SnapshotManager.compareTest(testRun, config.diffMode)

      // Check if test explicitly failed via t.fail()
      val result = if (testRun.isFailed) {
        val failMsg = testRun.failMessage.map(m => s": $m").getOrElse("")
        snapshotResult.copy(
          testName = fullTestName,
          passed = false,
          diff = Some(s"Test explicitly failed$failMsg"),
          returnValue = Some(returnValue),
          durationMs = duration
        )
      } else {
        snapshotResult.copy(
          testName = fullTestName,
          returnValue = Some(returnValue),
          durationMs = duration
        )
      }
      testRun.writeOutput()

      // Stop log capture (writes to log file)
      logCapture.stop()

      // Write report file
      testRun.writeReport(s"Test '${testCase.name}' completed in ${duration}ms")

      // Handle -S (recapture all) and -s (update snapshots) flags
      val autoAccept = config.recaptureAll || config.updateSnapshots

      // Cache the return value if test passed OR if in auto-accept mode (where we'll accept the new snapshot)
      // This allows dependent tests to use the return value even when creating new snapshots
      if (result.passed || (autoAccept && !testRun.isFailed)) {
        dependencyCache.put(testRun, returnValue)
      }

      val shouldAccept = if (autoAccept && !result.passed) {
        // Auto-accept mode: update snapshot silently
        if (config.verbose) {
          // Print test output in verbose mode
          printVerboseOutput(testRun)
          println(s"$fullTestName ${Color.Yellow("UPDATED")} ${duration} ms.")
          println()
        } else if (config.summaryMode) {
          println(s"${Color.Yellow("UPDATED")} ${duration} ms")
        } else {
          println(s"${Color.Yellow("snapshot updated")} ${duration} ms")
        }
        true
      } else if (config.verbose) {
        // Verbose mode: show test output content like Python
        printVerboseOutput(testRun)
        if (result.passed) {
          println(s"$fullTestName ${Color.Green("ok")} ${duration} ms.")
        } else {
          println(s"$fullTestName ${Color.Red("DIFF")} ${duration} ms.")
          // Show diff inline in verbose mode
          result.diff.foreach { diff =>
            println()
            println(diff)
          }
        }
        println()
        !result.passed && config.interactive
      } else if (config.summaryMode) {
        // Python-style: show status inline, collect diffs for end
        if (result.passed) {
          println(s"${Color.Green("ok")} ${duration} ms")
        } else {
          println(s"${Color.Red("DIFF")} ${duration} ms")
        }
        false
      } else if (!result.passed) {
        SnapshotManager.printTestResult(result, config.interactive)
      } else {
        println(s"${duration} ms")
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
        // Stop log capture on error
        logCapture.stop()

        val endTime = System.currentTimeMillis()
        val duration = endTime - startTime
        val errorMessage = s"Test '${testCase.name}' failed with exception: ${e.getMessage}"

        // Write error report
        testRun.writeReport(s"Test '${testCase.name}' FAILED: ${e.getMessage}\nDuration: ${duration}ms")

        if (config.verbose) {
          // Print any partial output in verbose mode
          val output = testRun.getTestOutput
          if (output.nonEmpty) {
            output.linesIterator.foreach { line =>
              println(s"  $line")
            }
            println()
          }
          println(s"$suiteName/${testCase.name} ${Color.Red("ERROR")} ${duration} ms.")
          println()
          println(Color.Red(errorMessage))
          e.printStackTrace()
          println()
        } else {
          println(s"FAILED in ${duration} ms")
        }

        TestResult(
          testName = fullTestName,
          passed = false,
          output = "",
          diff = Some(errorMessage),
          durationMs = duration
        )
    } finally {
      // Release resource locks
      locks.foreach(lock => resourceManager.locks.release(lock))
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

    // Python-style: show all diffs at the end (but not in verbose mode where they're already shown)
    if (config.summaryMode && !config.verbose && finalFailedCount > 0) {
      printCollectedDiffs(finalResults)
    }

    RunResult(
      totalTests = totalTests,
      passedTests = finalPassedCount,
      failedTests = finalFailedCount,
      results = finalResults,
      totalDurationMs = totalDuration
    )
  }
  
  private def printCollectedDiffs(results: List[TestResult]): Unit = {
    val failedResults = results.filter(!_.passed)
    if (failedResults.nonEmpty) {
      println()
      println("=" * 60)
      println(Color.Red(s"# ${failedResults.length} test(s) with differences:"))
      println("=" * 60)

      failedResults.foreach { result =>
        println()
        println(Color.Yellow(s"## ${result.testName}"))
        println("-" * 40)
        result.diff.foreach { diff =>
          println(diff)
        }
      }

      println()
      println("=" * 60)
    }
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

  /** Run tests in parallel using Python booktest's architecture:
    * - Scheduler thread handles test submission and dependency resolution
    * - Worker threads execute tests and put results in a queue
    * - Main thread consumes results from queue and handles all output
    * This ensures no output interleaving.
    */
  private def runTestsParallel(testCases: List[TestCase], suiteName: String, suite: TestSuite = null): List[TestResult] = {
    import java.util.concurrent.{LinkedBlockingQueue, TimeUnit}

    val testMap = testCases.map(tc => tc.name -> tc).toMap
    val completed = scala.collection.mutable.Set[String]()
    val inProgress = scala.collection.mutable.Set[String]()
    val results = scala.collection.mutable.ListBuffer[TestResult]()

    // Queue for completed test reports (thread-safe)
    val reportQueue = new LinkedBlockingQueue[TestReportMessage]()

    val executor = new TestExecutor(config.threads, resourceManager)

    // Track if scheduler is done
    @volatile var schedulerDone = false
    @volatile var schedulerError: Option[Throwable] = None

    // Lock for scheduler state
    val schedulerLock = new Object()

    // Scheduler thread: submits tests to pool, respects dependencies
    val schedulerThread = new Thread(() => {
      try {
        while (completed.size < testCases.size && schedulerError.isEmpty) {
          // Find tests that are ready to run
          val ready = schedulerLock.synchronized {
            testCases.filter { tc =>
              !completed.contains(tc.name) &&
              !inProgress.contains(tc.name) &&
              tc.dependencies.forall(dep => completed.contains(dep) || !testMap.contains(dep))
            }
          }

          if (ready.isEmpty && inProgress.isEmpty && completed.size < testCases.size) {
            schedulerError = Some(new IllegalStateException("Circular dependency detected or missing dependency"))
          } else if (ready.nonEmpty) {
            // Submit ready tests to worker pool
            ready.foreach { testCase =>
              schedulerLock.synchronized {
                inProgress += testCase.name
              }

              // Send "starting" message to main thread
              reportQueue.put(TestReportMessage.Starting(testCase.name, suiteName))

              // Submit test execution to thread pool
              executor.submit {
                try {
                  val result = executeTestSilently(testCase, suiteName, suite)
                  reportQueue.put(TestReportMessage.Completed(testCase.name, result))
                } catch {
                  case e: Exception =>
                    val errorResult = TestResult(
                      testName = s"${config.getSuitePath(suiteName)}/${testCase.name}",
                      passed = false,
                      output = "",
                      diff = Some(s"Test '${testCase.name}' failed with exception: ${e.getMessage}"),
                      durationMs = 0
                    )
                    reportQueue.put(TestReportMessage.Completed(testCase.name, errorResult))
                }
              }
            }
          }

          // Wait a bit for workers to complete
          Thread.sleep(10)
        }
      } catch {
        case e: Exception =>
          schedulerError = Some(e)
      } finally {
        schedulerDone = true
        reportQueue.put(TestReportMessage.Done)
      }
    })

    schedulerThread.start()

    // Main thread: consume reports and handle all output
    try {
      var done = false
      while (!done) {
        val message = reportQueue.poll(100, TimeUnit.MILLISECONDS)
        if (message != null) {
          message match {
            case TestReportMessage.Starting(testName, suitePath) =>
              // In parallel mode, don't print "starting" message to avoid interleaving
              // The result line will include the full test name
              ()

            case TestReportMessage.Completed(testName, result) =>
              // Mark as completed
              schedulerLock.synchronized {
                inProgress -= testName
                completed += testName
              }
              results += result

              // Handle output - always include test name for parallel execution clarity
              val autoAccept = config.recaptureAll || config.updateSnapshots
              val finalResult = if (autoAccept && !result.passed) {
                // Update snapshot
                updateSnapshotForResult(result)
                if (config.verbose) {
                  printVerboseResultOutput(result)
                  println(s"${result.testName} ${Color.Yellow("UPDATED")} ${result.durationMs} ms.")
                  println()
                } else {
                  println(s"  ${result.testName}..${Color.Yellow("UPDATED")} ${result.durationMs} ms")
                }
                result.copy(passed = true)
              } else if (config.verbose) {
                printVerboseResultOutput(result)
                if (result.passed) {
                  println(s"${result.testName} ${Color.Green("ok")} ${result.durationMs} ms.")
                } else {
                  println(s"${result.testName} ${Color.Red("DIFF")} ${result.durationMs} ms.")
                  result.diff.foreach { diff =>
                    println()
                    println(diff)
                  }
                }
                println()
                result
              } else if (result.passed) {
                println(s"  ${result.testName}..${Color.Green("ok")} ${result.durationMs} ms")
                result
              } else {
                println(s"  ${result.testName}..${Color.Red("DIFF")} ${result.durationMs} ms")
                result
              }

              // Replace result in list with final result (for auto-accept case)
              if (finalResult ne result) {
                results.remove(results.length - 1)
                results += finalResult
              }

            case TestReportMessage.Done =>
              done = true
          }
        }

        // Check for scheduler error
        schedulerError.foreach(e => throw e)
      }

      // Wait for scheduler thread to finish
      schedulerThread.join(1000)

      results.toList
    } finally {
      executor.shutdown()
    }
  }

  /** Message types for communication between scheduler/workers and main thread */
  private sealed trait TestReportMessage
  private object TestReportMessage {
    case class Starting(testName: String, suiteName: String) extends TestReportMessage
    case class Completed(testName: String, result: TestResult) extends TestReportMessage
    case object Done extends TestReportMessage
  }

  /** Execute a test without any output (for parallel execution) */
  private def executeTestSilently(testCase: TestCase, suiteName: String, suite: TestSuite): TestResult = {
    val suitePath = config.getSuitePath(suiteName)
    val suitePathSegments = os.RelPath(suitePath)

    val testPath = os.pwd / "src" / "test" / "scala" / suitePathSegments
    val outputDir = config.outputDir / suitePathSegments
    val snapshotDir = config.snapshotDir / suitePathSegments
    val outDir = config.outputDir / ".out" / suitePathSegments

    // Ensure directories exist
    this.synchronized { os.makeDir.all(outDir) }

    val testRun = new TestCaseRun(
      testName = testCase.name,
      testPath = testPath,
      outputDir = outputDir,
      snapshotDir = snapshotDir,
      outDir = outDir
    )
    testRun.setFlags(config.recaptureAll, config.updateSnapshots)
    testRun.clearTmpDir()

    val startTime = System.currentTimeMillis()
    val fullTestName = s"$suitePath/${testCase.name}"

    // Acquire resource locks (for tests that share state)
    val locks = if (suite != null) suite.getResourceLocks else List.empty
    locks.foreach(lock => resourceManager.locks.acquire(lock))

    val logCapture = new LogCapture(testRun.logFile)
    logCapture.start()

    try {
      // Setup
      if (suite != null) {
        try { suite.getSetup(testRun) }
        catch { case e: Exception => testRun.iln(s"Setup failed: ${e.getMessage}") }
      }

      val returnValue = try {
        executeTestWithDependencies(testCase, testRun)
      } finally {
        if (suite != null) {
          try { suite.getTeardown(testRun) }
          catch { case e: Exception => testRun.iln(s"Teardown failed: ${e.getMessage}") }
        }
      }

      val endTime = System.currentTimeMillis()
      val duration = endTime - startTime

      val snapshotResult = SnapshotManager.compareTest(testRun, config.diffMode)

      val result = if (testRun.isFailed) {
        val failMsg = testRun.failMessage.map(m => s": $m").getOrElse("")
        snapshotResult.copy(
          testName = fullTestName,
          passed = false,
          diff = Some(s"Test explicitly failed$failMsg"),
          returnValue = Some(returnValue),
          durationMs = duration
        )
      } else {
        snapshotResult.copy(
          testName = fullTestName,
          returnValue = Some(returnValue),
          durationMs = duration
        )
      }

      testRun.writeOutput()
      logCapture.stop()
      testRun.writeReport(s"Test '${testCase.name}' completed in ${duration}ms")

      // Cache return value (saves to testName.bin)
      val autoAccept = config.recaptureAll || config.updateSnapshots
      if (result.passed || (autoAccept && !testRun.isFailed)) {
        this.synchronized {
          dependencyCache.put(testRun, returnValue)
        }
      }

      // Store testRun reference for snapshot updates
      result.copy(testRun = Some(testRun))

    } catch {
      case e: Exception =>
        logCapture.stop()
        val endTime = System.currentTimeMillis()
        val duration = endTime - startTime
        testRun.writeReport(s"Test '${testCase.name}' FAILED: ${e.getMessage}\nDuration: ${duration}ms")

        TestResult(
          testName = fullTestName,
          passed = false,
          output = "",
          diff = Some(s"Test '${testCase.name}' failed with exception: ${e.getMessage}"),
          durationMs = duration
        )
    } finally {
      // Release resource locks
      locks.foreach(lock => resourceManager.locks.release(lock))
    }
  }

  /** Update snapshot for a test result (used in parallel execution) */
  private def updateSnapshotForResult(result: TestResult): Unit = {
    result.testRun.foreach { testRun =>
      SnapshotManager.updateSnapshot(testRun)
    }
  }

  /** Print verbose output from a test result */
  private def printVerboseResultOutput(result: TestResult): Unit = {
    result.testRun.foreach { testRun =>
      val output = testRun.getTestOutput
      if (output.nonEmpty) {
        output.linesIterator.foreach { line =>
          println(s"  $line")
        }
        println()
      }
    }
  }

  /** Load a dependency value from memory cache or bin file */
  private def loadDependencyValue(depName: String, testRun: TestCaseRun): Option[Any] = {
    // Try memory cache first
    dependencyCache.get[Any](depName).orElse {
      // Fall back to loading from the dependency's bin file (same suite directory)
      val depBinFile = testRun.outDir / s"$depName.bin"
      if (os.exists(depBinFile)) {
        try {
          val serialized = os.read(depBinFile)
          val result: Any = serialized.split(":", 2) match {
            case Array("NULL", _) => null
            case Array("UNIT", _) => ()
            case Array("STRING", value) => value
            case Array("INT", value) => value.toInt
            case Array("LONG", value) => value.toLong
            case Array("DOUBLE", value) => value.toDouble
            case Array("BOOLEAN", value) => value.toBoolean
            case Array("OBJECT", value) => value
            case _ => serialized
          }
          // Cache in memory for subsequent lookups
          dependencyCache.put(depName, result)
          Some(result)
        } catch {
          case _: Exception => None
        }
      } else {
        None
      }
    }
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
            val cached = loadDependencyValue(depName, testRun)
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
                val cached = loadDependencyValue(depName, testRun)
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

  /** Print test output content in verbose mode (like Python booktest) */
  private def printVerboseOutput(testRun: TestCaseRun): Unit = {
    val output = testRun.getTestOutput
    if (output.nonEmpty) {
      // Indent each line with 2 spaces for readability
      output.linesIterator.foreach { line =>
        println(s"  $line")
      }
      println()
    }
  }
}