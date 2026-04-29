package booktest

import os.Path
import fansi.Color
import fansi.Color.{LightRed, LightGreen, LightYellow, LightCyan}
import scala.util.control.NonFatal

private[booktest] object Colors {
  val Orange = Color.True(255, 165, 0)
}

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
  autoAcceptDiff: Boolean = false,  // -a: auto-accept DIFF tests (not FAIL)
  continueMode: Boolean = false,  // -c: continue from last run, skip successful tests
  threads: Int = 1,  // -p N: number of threads for parallel execution
  output: java.io.PrintStream = System.out,  // Output stream (redirect for meta tests)
  booktestConfig: BooktestConfig = BooktestConfig.empty,  // test-root and groups config
  invalidateLiveOnFail: Boolean = false,  // --invalidate-live-on-fail
  // --trace / BOOKTEST_TRACE=1: also write the structured event log to
  // <outputDir>/.booktest.log. The runner always keeps a bounded
  // in-memory ring buffer (used to attach a "Trace context" block to
  // failure reports) regardless of this flag.
  trace: Boolean = sys.env.contains("BOOKTEST_TRACE")
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
      val diffCount = results.count(r => !r.passed && r.successState == SuccessState.DIFF)
      val failCount = results.count(r => !r.passed && r.successState == SuccessState.FAIL)
      val parts = List.newBuilder[String]
      if (diffCount > 0) parts += s"$diffCount differed"
      if (failCount > 0) parts += s"$failCount failed"
      val detail = parts.result().mkString(" and ")
      s"$failedTests/$totalTests test $detail in $totalDurationMs ms"
    }
  }

  def printSummary(out: java.io.PrintStream = System.out): Unit = {
    out.println()
    if (success) {
      out.println(summary)
    } else {
      out.println(s"$summary:")
      out.println()
      results.filter(!_.passed).foreach { r =>
        val status = r.successState match {
          case SuccessState.FAIL => LightRed("FAIL")
          case _ => LightYellow("DIFF")
        }
        out.println(s"  ${r.testName} - $status")
      }
    }
  }
}

object TestRunner {
  /** Unwrap reflection wrappers so reports show the underlying cause. */
  private[booktest] def unwrapInvocationTarget(t: Throwable): Throwable = t match {
    case ite: java.lang.reflect.InvocationTargetException if ite.getCause != null =>
      unwrapInvocationTarget(ite.getCause)
    case _ => t
  }

  /** Format an exception with full stack trace (and chained causes) so
    * race-condition–style failures are diagnosable from logs/CI output. */
  private[booktest] def formatStackTrace(t: Throwable): String = {
    val sw = new java.io.StringWriter()
    t.printStackTrace(new java.io.PrintWriter(sw))
    sw.toString
  }
}

class TestRunner(config: RunConfig = RunConfig()) {
  private val dependencyCache = new DependencyCache()
  private val resourceManager = ResourceManager.fromEnv()
  /** Always-on bounded ring buffer of trace events; used to attach a
    * "Trace context" block to failure reports without requiring the
    * user to opt in to tracing first. */
  private val ringBuffer = new RingBufferSink()
  /** Optional extra logfile sink, opened lazily on first emit. */
  private val logfile: Option[LogfileSink] =
    if (config.trace) Some(new LogfileSink(config.outputDir / ".booktest.log"))
    else None
  /** The trace bus the runner and the live-resource manager both write
    * to. */
  private val trace: TaskTrace =
    logfile match {
      case Some(lf) => new BroadcastTrace(ringBuffer, lf)
      case None     => ringBuffer
    }
  private val liveResources = {
    val mgr = new LiveResourceManager(trace = trace)
    if (config.verbose) {
      mgr.listener = new LiveResourceListener {
        override def onBuild(name: String, durationMs: Long): Unit =
          config.output.println(s"  [build $name] ${durationMs} ms")
        override def onClose(name: String, durationMs: Long): Unit =
          config.output.println(s"  [close $name] ${durationMs} ms")
        override def onReset(name: String, durationMs: Long): Unit =
          config.output.println(s"  [reset $name] ${durationMs} ms")
      }
    }
    mgr
  }
  @volatile private var interactiveQuit = false
  /** Qualified test names ("<suitePath>/<testName>") that are scheduled
    * to run in the current `runMultipleSuites` invocation. Populated by
    * the pre-pass; consulted by `loadDependencyValue` so the disk
    * `.bin` fallback never preempts a producer that's about to run.
    *
    * Issue 1 Fix B from `.ai/plan/task-graph.md`: even with the
    * scheduler's producer-aware readiness predicate (Fix A), the cache
    * is the second line of defense. If a producer is in this run, only
    * the in-memory cache (which is populated when the producer
    * completes) may answer for it; disk `.bin` reflects a *prior*
    * invocation and would silently serve stale data. */
  @volatile private var scheduledInThisRun: Set[String] = Set.empty
  private val out = config.output

  /** Get the resource manager for tests that need ports/resources */
  def resources: ResourceManager = resourceManager

  /** Inspect the trace events recorded so far. Intended for meta-tests
    * that want to assert on lifecycle ordering / dep resolution
    * sources; the production runner attaches a trace block to failing
    * results automatically and doesn't need callers to query this. */
  def traceBuffer: RingBufferSink = ringBuffer
  
  /** Run a single suite (without continue mode filtering).
    * For continue mode support, use runMultipleSuites instead.
    */
  def runSuite(suite: TestSuite): RunResult = {
    // Mirror the runMultipleSuites pre-pass for the single-suite path,
    // so live resources are registered and the cache run-set
    // (Issue 1 Fix B) reflects exactly what's about to run.
    suite.liveResources.foreach(liveResources.register)
    val suitePath = config.getSuitePath(suite.fullClassName)
    val tests = config.testFilter match {
      case Some(pattern) => suite.testCases.filter(_.name.contains(pattern))
      case None => suite.testCases
    }
    scheduledInThisRun = tests.map(tc => s"$suitePath/${tc.name}").toSet
    runSuiteWithFilter(suite, Set.empty)
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
      outDir = outDir,
      resourceManager = Some(resourceManager)
    )
    testRun.setFlags(config.recaptureAll, config.updateSnapshots)
    testRun.start()

    // Clear temp directory from previous runs (tmp files persist for dependent tests but are cleared on re-run)
    testRun.clearTmpDir()

    val startTime = System.currentTimeMillis()
    // Use the simplified path in test output
    val fullTestName = s"${suitePath}/${testCase.name}"

    // Print test name before capture starts
    if (!config.verbose) {
      out.print(s"  ${fullTestName}..")
    } else {
      out.println(s"test $fullTestName")
      out.println()
    }

    // Acquire resource locks (for tests that share state)
    val locks = if (suite != null) suite.getResourceLocks else List.empty
    locks.foreach(lock => resourceManager.locks.acquire(lock))

    // Capture stdout/stderr during test execution
    val logCapture = new LogCapture(testRun.logFile)
    logCapture.start()

    // Tracks whether the test failed for live-resource invalidation in
    // the finally block.
    var consumerFailed = false

    try {
      // Call setup hook
      if (suite != null) {
        try { suite.getSetup(testRun) }
        catch { case e: Exception =>
          testRun.iln(s"Setup failed: ${e.getMessage}")
          testRun.iln(TestRunner.formatStackTrace(e))
        }
      }

      val returnValue = try {
        executeTestWithDependencies(testCase, testRun, suitePath, suite)
      } finally {
        // Call teardown hook (always, even if test fails)
        if (suite != null) {
          try { suite.getTeardown(testRun) }
          catch { case e: Exception =>
            testRun.iln(s"Teardown failed: ${e.getMessage}")
            testRun.iln(TestRunner.formatStackTrace(e))
          }
        }
      }

      val endTime = System.currentTimeMillis()
      val duration = endTime - startTime

      // Finalize output (flush remaining content, close files)
      testRun.end()

      val snapshotResult = SnapshotManager.compareTest(testRun, config.diffMode)

      // Check if test explicitly failed via t.fail()
      val result = if (testRun.isFailed) {
        val failMsg = testRun.failMessage.map(m => s": $m").getOrElse("")
        consumerFailed = true
        snapshotResult.copy(
          testName = fullTestName,
          passed = false,
          successState = SuccessState.FAIL,
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

      // Stop log capture (writes to log file)
      logCapture.stop()

      // Write report file
      testRun.writeReport(s"Test '${testCase.name}' completed in ${duration}ms")

      // Handle -S (recapture all), -s (update snapshots), -a (accept diffs) flags
      val autoAccept = config.recaptureAll || config.updateSnapshots ||
        (config.autoAcceptDiff && result.successState == SuccessState.DIFF)

      // Cache the return value on OK or DIFF (test ran successfully).
      // On FAIL (exception/t.fail()), delete any stale .bin file.
      // Matches Python booktest: .bin is for dependency injection, not snapshot status.
      if (!testRun.isFailed) {
        dependencyCache.put(fullTestName, returnValue)
        testRun.saveReturnValue(returnValue)
      } else {
        testRun.deleteReturnValue()
      }

      val response: InteractiveResponse = if (autoAccept && !result.passed) {
        // Auto-accept mode: update snapshot silently
        if (config.verbose) {
          // Print test output in verbose mode
          printVerboseOutput(testRun)
          out.println(s"$fullTestName ${LightYellow("UPDATED")} ${duration} ms.")
          out.println()
        } else if (config.summaryMode) {
          out.println(s"${LightYellow("UPDATED")} ${duration} ms")
        } else {
          out.println(s"${LightYellow("snapshot updated")} ${duration} ms")
        }
        InteractiveResponse.Accept
      } else if (config.verbose) {
        // Verbose mode: show test output content like Python
        printVerboseOutput(testRun)
        if (result.passed) {
          out.println(s"$fullTestName ${LightGreen("ok")} ${duration} ms.")
          out.println()
          InteractiveResponse.Reject
        } else {
          out.println(s"$fullTestName ${LightYellow("DIFF")} ${duration} ms.")
          // Show diff inline in verbose mode
          result.diff.foreach { diff =>
            out.println()
            out.println(diff)
          }
          out.println()
          if (config.interactive) {
            val isFailed = result.successState == SuccessState.FAIL
            SnapshotManager.interact(testRun.snapshotFile, testRun.outFile, testRun.logFile, isFailed, fullTestName)
          } else {
            InteractiveResponse.Reject
          }
        }
      } else if (config.summaryMode) {
        // Python-style: show status inline, collect diffs for end
        if (result.passed) {
          out.println(s"${LightGreen("ok")} ${duration} ms")
        } else {
          out.println(s"${LightYellow("DIFF")} ${duration} ms")
        }
        InteractiveResponse.Reject
      } else if (!result.passed) {
        SnapshotManager.printTestResult(result, config.interactive,
          testRun.snapshotFile, testRun.outFile, testRun.logFile)
      } else {
        out.println(s"${duration} ms")
        InteractiveResponse.Reject
      }

      val shouldAccept = response == InteractiveResponse.Accept || response == InteractiveResponse.AcceptAndQuit
      if (response == InteractiveResponse.Quit || response == InteractiveResponse.AcceptAndQuit) {
        interactiveQuit = true
      }

      if (shouldAccept && !result.passed) {
        SnapshotManager.updateSnapshot(testRun)
        result.copy(passed = true)
      } else {
        result
      }

    } catch {
      case NonFatal(rawErr) =>
        // Reflection wraps test-method exceptions in InvocationTargetException;
        // unwrap so reports show the underlying cause, not "exception: null".
        val e = TestRunner.unwrapInvocationTarget(rawErr)
        consumerFailed = true
        // Write exception to output like Python: t.iln(traceback.format_exc())
        // NonFatal catches Errors like NotImplementedError/AssertionError too, so a
        // single broken test doesn't poison the whole batch.
        testRun.iln()
        testRun.fail()
        testRun.iln(s"test raised exception ${e.getClass.getName}: ${e.getMessage}")
        testRun.iln(TestRunner.formatStackTrace(e))

        // Finalize output on error
        testRun.end()
        // Stop log capture on error
        logCapture.stop()

        val endTime = System.currentTimeMillis()
        val duration = endTime - startTime

        // Compare snapshot (exception is now part of output)
        val snapshotResult = SnapshotManager.compareTest(testRun, config.diffMode)
        val errorMessage = s"Test '${testCase.name}' failed with exception: ${e.getMessage}"

        // Write error report
        testRun.writeReport(s"Test '${testCase.name}' FAILED: ${e.getMessage}\nDuration: ${duration}ms")

        // Delete cached return value on failure
        testRun.deleteReturnValue()

        if (config.verbose) {
          // Print test output in verbose mode
          printVerboseOutput(testRun)
          out.println(s"$suiteName/${testCase.name} ${LightRed("FAIL")} ${duration} ms.")
          out.println()
          out.println(LightRed(errorMessage))
          out.println()
        } else {
          out.println(s"FAILED in ${duration} ms")
        }

        snapshotResult.copy(
          testName = fullTestName,
          passed = false,
          successState = SuccessState.FAIL,
          diff = snapshotResult.diff.orElse(Some(errorMessage)),
          durationMs = duration
        )
    } finally {
      // Release resource locks
      locks.foreach(lock => resourceManager.locks.release(lock))
      // Release every transitively-reserved live resource for this
      // consumer (in dep order — nested last). Direct dep gets the
      // failure flag so SharedWithReset / invalidate-on-fail trigger;
      // transitive nested deps don't need that signal.
      val reach = liveResources.transitiveResourceClosure(testCase.dependencies)
      val direct = testCase.dependencies.toSet
      reach.foreach { name =>
        liveResources.release(name, testCase.name,
          failed = consumerFailed && direct.contains(name),
          invalidateOnFail = config.invalidateLiveOnFail)
      }
    }
  }

  def runMultipleSuites(suites: List[TestSuite]): RunResult = {
    val startTime = System.currentTimeMillis()

    // Print header
    out.println()
    out.println("# test results:")
    out.println()

    // Register live-resource declarations from every suite so dep resolution
    // can detect "this dep name is a live resource, not a test cache".
    suites.foreach(_.liveResources.foreach(liveResources.register))

    // Load previous case reports for continue mode
    val outDir = config.outputDir / ".out"
    val oldReports = CaseReports.fromDir(outDir)

    // Build list of all selected test names (for continue mode calculation)
    val allSelectedTestNames = suites.flatMap { suite =>
      val fullClassName = suite.fullClassName
      val suitePath = config.getSuitePath(fullClassName)
      val testCases = suite.testCases
      val filteredTests = config.testFilter match {
        case Some(pattern) => testCases.filter(_.name.contains(pattern))
        case None => testCases
      }
      filteredTests.map(tc => s"$suitePath/${tc.name}")
    }

    // Apply continue mode logic
    val (doneCaseReports, todoTestNames) = oldReports.casesToDoneAndTodo(allSelectedTestNames, config.continueMode)
    val todoSet = todoTestNames.toSet

    // Issue 1 Fix B: tell the cache which tests are scheduled in this
    // invocation, so its disk `.bin` fallback can defer to the in-memory
    // cache for producers that are about to run.
    scheduledInThisRun = todoSet

    // Track results from skipped tests (continue mode)
    val skippedResults = if (config.continueMode && doneCaseReports.nonEmpty) {
      out.println(LightCyan(s"Continue mode: skipping ${doneCaseReports.length} successful tests"))
      out.println()
      doneCaseReports.map { report =>
        TestResult(
          testName = report.testName,
          passed = true,
          output = "",
          durationMs = report.durationMs
        )
      }
    } else {
      List.empty
    }

    // Pre-pass: pre-reserve refcount for each (test, direct-resource-dep)
    // edge so a shared resource doesn't close between tests that need it.
    // Also collect the set of reachable live resources for capacity
    // validation and the locality scheduler.
    val reachableLiveResources = scala.collection.mutable.Set[String]()
    suites.foreach { suite =>
      val suitePath = config.getSuitePath(suite.fullClassName)
      val testCases = suite.testCases
      val filtered = config.testFilter match {
        case Some(pattern) => testCases.filter(_.name.contains(pattern))
        case None => testCases
      }
      val selected = if (config.continueMode)
        filtered.filter(tc => todoSet.contains(s"$suitePath/${tc.name}"))
      else filtered
      selected.foreach { tc =>
        // Reserve transitively: every live resource reachable from this
        // test's direct deps gets +1 on its refcount, so nested
        // resources stay alive across all transitive consumers.
        val reach = liveResources.transitiveResourceClosure(tc.dependencies)
        reach.foreach { name =>
          liveResources.reserve(name, tc.name)
          reachableLiveResources += name
        }
      }
    }
    // Pre-pass: capacity validation. Reject only the case that's
    // statically guaranteed to deadlock — a single resource reserves more
    // than the capacity total. Cross-resource sums depend on actual
    // concurrency (test ordering, -pN), and the runtime acquire path
    // already blocks safely if real demand exceeds capacity.
    reachableLiveResources.foreach { name =>
      liveResources.lookup(name).foreach { defn =>
        defn.deps.foreach {
          case CapacityDep(cap, amount) =>
            val capName = cap match { case d: DoubleCapacity => d.name; case _ => "?" }
            val amt = amount match { case d: Double => d; case _ => 0.0 }
            val avail = cap.capacity match { case d: Double => d; case _ => Double.PositiveInfinity }
            if (amt > avail) {
              throw new IllegalStateException(
                s"Capacity '$capName' over-committed: '$name' reserves $amt > capacity $avail. " +
                s"Override with --capacity $capName=<larger> or BOOKTEST_CAPACITY_${capName.toUpperCase}.")
            }
          case _ => ()
        }
      }
    }

    // Run suites (filtering out tests that were skipped in continue mode)
    val allResults = try {
      if (config.threads > 1) {
        runSuitesParallel(suites, todoSet)
      } else {
        suites.takeWhile(_ => !interactiveQuit).map { suite =>
          runSuiteWithFilter(suite, todoSet)
        }
      }
    } finally {
      // Force teardown of any still-alive live resources at end of run.
      liveResources.shutdownAll()
    }

    val endTime = System.currentTimeMillis()
    val totalDuration = endTime - startTime

    // Combine skipped results with new results
    val newTestResults = allResults.flatMap(_.results)
    val allTestResults = skippedResults ++ newTestResults

    val totalTests = allTestResults.length
    val totalFailed = allTestResults.count(!_.passed)
    
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

    // End-of-run live-resource summary (verbose only).
    if (config.verbose) printLiveResourceSummary()

    RunResult(
      totalTests = totalTests,
      passedTests = finalPassedCount,
      failedTests = finalFailedCount,
      results = finalResults,
      totalDurationMs = totalDuration
    )
  }

  private def printLiveResourceSummary(): Unit = {
    val stats = liveResources.statsSnapshot.filter(_.builds > 0)
    if (stats.isEmpty) return
    out.println()
    out.println("# live resources:")
    out.println()
    stats.sortBy(_.name).foreach { s =>
      out.println(s"  ${s.name}: builds=${s.builds} closes=${s.closes} " +
        s"resets=${s.resets} alive=${s.totalAliveMs} ms " +
        s"(build ${s.totalBuildMs} ms, close ${s.totalCloseMs} ms)")
    }
  }
  
  private def printCollectedDiffs(results: List[TestResult]): Unit = {
    val failedResults = results.filter(!_.passed)
    if (failedResults.nonEmpty) {
      out.println()
      out.println("=" * 60)
      out.println(LightRed(s"# ${failedResults.length} test(s) with differences:"))
      out.println("=" * 60)

      failedResults.foreach { result =>
        out.println()
        out.println(LightYellow(s"## ${result.testName}"))
        out.println("-" * 40)
        result.diff.foreach { diff =>
          out.println(diff)
        }
      }

      out.println()
      out.println("=" * 60)
    }
  }

  private def writeCaseReports(results: List[TestResult]): Unit = {
    val caseReports = results.map { result =>
      val status = if (result.passed) "OK"
        else if (result.successState == SuccessState.FAIL) "FAIL"
        else "DIFF"
      // Store test name with suite prefix for proper review lookup
      val fullTestName = result.testName
      CaseReport(fullTestName, status, result.durationMs)
    }

    val reports = new CaseReports(caseReports)
    val outDir = config.outputDir / ".out"
    os.makeDir.all(outDir)
    // Write both formats for compatibility
    reports.writeToNdjsonFile(outDir / "cases.ndjson")
    reports.writeToFile(outDir / "cases.txt")
  }

  /** Run a suite, optionally filtering to only run tests in todoSet.
    *
    * Design: Test selection depends ONLY on:
    *   - config.testFilter (name pattern filtering)
    *   - config.continueMode (whether to skip passed tests)
    *   - todoSet (which tests need to run in continue mode)
    *
    * Reporting options like config.verbose do NOT affect test selection.
    */
  private def runSuiteWithFilter(suite: TestSuite, todoSet: Set[String]): RunResult = {
    val suiteName = suite.suiteName
    val fullClassName = suite.fullClassName
    val suitePath = config.getSuitePath(fullClassName)
    val testCases = suite.testCases

    // Step 1: Apply name pattern filter, but include transitive dependencies
    val filteredTests = config.testFilter match {
      case Some(pattern) =>
        val matched = testCases.filter(_.name.contains(pattern)).map(_.name).toSet
        val testMap = testCases.map(tc => tc.name -> tc).toMap
        val needed = scala.collection.mutable.LinkedHashSet[String]()
        def collectDeps(name: String): Unit = {
          if (!needed.contains(name)) {
            testMap.get(name).foreach { tc =>
              tc.dependencies.foreach(collectDeps)
            }
            needed += name
          }
        }
        matched.foreach(collectDeps)
        testCases.filter(tc => needed.contains(tc.name))
      case None => testCases
    }

    // Step 2: Apply continue mode filter (if enabled)
    val testsToRun = if (config.continueMode) {
      // Continue mode: only run tests that are in the todoSet
      filteredTests.filter { tc =>
        val fullTestName = s"$suitePath/${tc.name}"
        todoSet.contains(fullTestName)
      }
    } else {
      // Normal mode: run all filtered tests
      filteredTests
    }

    if (testsToRun.isEmpty) {
      // All tests were skipped
      return RunResult(
        totalTests = 0,
        passedTests = 0,
        failedTests = 0,
        results = List.empty
      )
    }

    // Call beforeAll hook
    try {
      suite.getBeforeAll()
    } catch {
      case e: Exception =>
        out.println(s"Warning: beforeAll failed for $suiteName: ${e.getMessage}")
        out.println(TestRunner.formatStackTrace(e))
    }

    val results = try {
      if (config.threads > 1) {
        runTestsParallel(testsToRun, fullClassName, suite)
      } else {
        val sortedTests = applyLocalityGrouping(resolveDependencyOrder(testsToRun))
        sortedTests.takeWhile(_ => !interactiveQuit).map(runTestCase(_, fullClassName, suite))
      }
    } finally {
      // Call afterAll hook
      try {
        suite.getAfterAll()
      } catch {
        case e: Exception =>
          out.println(s"Warning: afterAll failed for $suiteName: ${e.getMessage}")
          out.println(TestRunner.formatStackTrace(e))
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

  /** Run multiple suites in parallel, each suite sequential internally.
    * Output may interleave but test results are correct.
    */
  private def runSuitesParallel(suites: List[TestSuite], todoSet: Set[String]): List[RunResult] = {
    import java.util.concurrent.{Executors, Callable, Future => JFuture}

    val executor = Executors.newFixedThreadPool(config.threads)

    try {
      val futures: List[JFuture[RunResult]] = suites.map { suite =>
        executor.submit(new Callable[RunResult] {
          override def call(): RunResult = {
            runSuiteWithFilter(suite, todoSet)
          }
        })
      }

      futures.map(_.get())
    } finally {
      executor.shutdown()
    }
  }

  /** Review previous test results without re-running tests.
    * Like Python booktest's -w: reprints all results and offers interactive
    * acceptance for diffs/failures, so you can examine results from a
    * non-interactive run without waiting for tests to execute again.
    */
  /** Review previous test results without re-running tests.
    * Like Python booktest's -w: reprints all results and offers interactive
    * acceptance for diffs/failures, so you can examine results from a
    * non-interactive run without waiting for tests to execute again.
    */
  def reviewResults(suites: List[TestSuite]): Int = {
    val outDir = config.outputDir / ".out"
    val allReports = CaseReports.fromDir(outDir)

    if (allReports.cases.isEmpty) {
      out.println("No previous test results found. Run tests first.")
      return 1
    }

    // Filter case reports to only selected suites (like Python booktest)
    val selectedPrefixes = suites.map(s => config.getSuitePath(s.fullClassName))
    val selectedTestNames = suites.flatMap { suite =>
      val suitePath = config.getSuitePath(suite.fullClassName)
      suite.testCases.map(tc => s"$suitePath/${tc.name}")
    }.toSet

    val reports = if (selectedPrefixes.nonEmpty) {
      new CaseReports(allReports.cases.filter { c =>
        selectedTestNames.contains(c.testName) ||
          selectedPrefixes.exists(p => c.testName.startsWith(p + "/"))
      })
    } else {
      allReports
    }

    if (reports.cases.isEmpty) {
      out.println("No matching test results found for the selected tests.")
      return 0
    }

    out.println()
    out.println("# Review Results:")
    out.println()

    var totalPassed = 0
    var totalFailed = 0
    var accepted = 0
    var quit = false

    reports.cases.takeWhile(_ => !quit).foreach { caseReport =>
      val testRelPath = os.RelPath(caseReport.testName)
      val outFile = outDir / testRelPath / os.up / s"${testRelPath.last}.md"
      val snapshotFile = config.snapshotDir / testRelPath / os.up / s"${testRelPath.last}.md"
      val logFile = outDir / testRelPath / os.up / s"${testRelPath.last}.log"

      // Trust the case report result — token-by-token comparison already
      // accounted for info-only diffs during the test run
      val isPassed = caseReport.result == "OK"

      if (isPassed) {
        out.println(s"  ${caseReport.testName}..${LightGreen("ok")} ${caseReport.durationMs} ms")
        if (config.verbose && os.exists(outFile)) {
          out.println()
          os.read(outFile).linesIterator.foreach(line => println(s"  $line"))
          out.println()
        }
        totalPassed += 1
      } else {
        val statusColor = if (caseReport.result == "FAIL") LightRed("FAIL") else LightYellow("DIFF")
        out.println(s"  ${caseReport.testName}..$statusColor ${caseReport.durationMs} ms")

        if (os.exists(outFile)) {
          val output = os.read(outFile)
          val snapshot = if (os.exists(snapshotFile)) Some(os.read(snapshotFile)) else None

          if (config.verbose) {
            out.println()
            output.linesIterator.foreach(line => println(s"  $line"))
            out.println()
          }

          snapshot match {
            case Some(snap) if snap != output =>
              val diff = SnapshotManager.generateDiff(snap, output)
              out.println(diff)
              out.println()
            case None =>
              out.println(s"  (new test, no snapshot yet)")
              out.println()
            case _ =>
              out.println()
          }

          // Interactive review for DIFF/FAIL tests
          if (config.interactive && !quit) {
            val isFailed = caseReport.result == "FAIL"
            val response = SnapshotManager.interact(snapshotFile, outFile, logFile, isFailed, caseReport.testName)

            response match {
              case InteractiveResponse.Accept =>
                os.makeDir.all(snapshotFile / os.up)
                os.copy.over(outFile, snapshotFile)
                out.println(s"  ${LightGreen("Changes accepted.")}")
                accepted += 1
              case InteractiveResponse.AcceptAndQuit =>
                os.makeDir.all(snapshotFile / os.up)
                os.copy.over(outFile, snapshotFile)
                out.println(s"  ${LightGreen("Changes accepted.")}")
                accepted += 1
                quit = true
              case InteractiveResponse.Quit =>
                quit = true
              case _ => // continue
            }
          }
        }
        totalFailed += 1
      }
    }

    out.println()
    val summary = if (totalFailed == 0) {
      LightGreen(s"$totalPassed/${totalPassed + totalFailed} test passed").toString
    } else {
      s"${LightRed(s"$totalFailed/${totalPassed + totalFailed} test failed")}" +
        (if (accepted > 0) s" ($accepted accepted)" else "")
    }
    out.println(summary)

    if (totalFailed > 0 && totalFailed == accepted) 0
    else if (totalFailed > 0) 1
    else 0
  }
  
  /** Sequential-mode locality: after topological sort, group consecutive
    * tests sharing a live-resource dep so a resource can stay alive across
    * its consumers. Stable for tests with no live deps. */
  private def applyLocalityGrouping(tests: List[TestCase]): List[TestCase] = {
    if (!tests.exists(tc => tc.dependencies.exists(liveResources.isRegistered))) {
      return tests
    }
    // Walk tests; whenever a test has live-resource deps, look ahead and
    // pull other consumers of the SAME resource forward to be adjacent.
    val remaining = scala.collection.mutable.ListBuffer[TestCase]()
    remaining ++= tests
    val out = scala.collection.mutable.ListBuffer[TestCase]()
    while (remaining.nonEmpty) {
      val tc = remaining.remove(0)
      out += tc
      val liveDeps = tc.dependencies.filter(liveResources.isRegistered).toSet
      if (liveDeps.nonEmpty) {
        val (similar, others) = remaining.toList.partition { other =>
          other.dependencies.exists(liveDeps.contains)
        }
        remaining.clear()
        remaining ++= similar
        remaining ++= others
      }
    }
    out.toList
  }

  /** Parallel-mode locality: rank ready tests so the scheduler submits the
    * "best" candidate first. Higher score = should run sooner. */
  private def localityScore(tc: TestCase): Int = {
    val liveDeps = tc.dependencies.filter(liveResources.isRegistered)
    if (liveDeps.isEmpty) 0
    else {
      val allAlive = liveDeps.forall(liveResources.isAlive)
      val isLastConsumer = liveDeps.exists { dep =>
        liveResources.isAlive(dep) && liveResources.pendingConsumerCount(dep) == 1
      }
      // 4 = alive + last consumer (drains a resource). Best.
      // 3 = alive (locality). Good.
      // 1 = needs to start a fresh resource. Worse than running an alive one.
      if (isLastConsumer) 4
      else if (allAlive) 3
      else 1
    }
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
            // A dep not in the test map is a live resource — materialized
            // on demand by LiveResourceManager, no ordering constraint.
            if (!liveResources.isRegistered(testName))
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
          // Find tests that are ready to run, then locality-rank them so
          // the scheduler favors tests against already-alive resources
          // (and tests that drain a resource).
          //
          // If the suite declares resourceLocks, force sequential order
          // within it: don't submit a new test while any sibling is still
          // in progress. Otherwise siblings race at the lock and the
          // observed order becomes non-deterministic.
          val suiteHasLocks =
            suite != null && suite.getResourceLocks.nonEmpty
          val ready = schedulerLock.synchronized {
            if (suiteHasLocks && inProgress.nonEmpty) Nil
            else {
              val readyUnranked = testCases.filter { tc =>
                if (completed.contains(tc.name) || inProgress.contains(tc.name)) false
                else {
                  // A test is ready when every test producer it depends
                  // on (directly or transitively through a live
                  // resource's TestDep closure) has completed. Live
                  // resources themselves don't appear in `testMap`;
                  // their producer tests do, and those are what
                  // constrain readiness. See `.ai/plan/task-graph.md`
                  // Issue 1.
                  val directTestDeps = tc.dependencies.filter(testMap.contains)
                  val resourceTestDeps =
                    liveResources.transitiveTestProducers(tc.dependencies)
                  val producers = directTestDeps.toSet ++ resourceTestDeps
                  producers.forall(completed.contains)
                }
              }
              val ranked = readyUnranked.zipWithIndex
                .sortBy { case (tc, idx) => (-localityScore(tc), idx) }
                .map(_._1)
              // When locked, only submit one at a time so order = test list order.
              if (suiteHasLocks) ranked.take(1) else ranked
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

              // Trace: scheduler decided this test is ready. Producers
              // is the producer-aware set the readiness predicate
              // computed (Issue 1 Fix A).
              val producers = (testCase.dependencies.filter(testMap.contains).toSet ++
                liveResources.transitiveTestProducers(testCase.dependencies)).toList
              val suitePathForTrace = config.getSuitePath(suiteName)
              trace.emit(TraceEvent.SchedReady(
                java.time.Instant.now(), TaskTrace.currentThread(),
                s"$suitePathForTrace/${testCase.name}", producers))

              // Send "starting" message to main thread
              reportQueue.put(TestReportMessage.Starting(testCase.name, suiteName))

              // Submit test execution to thread pool
              executor.submit {
                try {
                  val result = executeTestSilently(testCase, suiteName, suite)
                  reportQueue.put(TestReportMessage.Completed(testCase.name, result))
                } catch {
                  case NonFatal(rawErr) =>
                    val e = TestRunner.unwrapInvocationTarget(rawErr)
                    // Include the full stack trace — this path catches
                    // booktest-internal failures (e.g. dependency-resolution
                    // races); a one-line message hides the call site.
                    val errorResult = TestResult(
                      testName = s"${config.getSuitePath(suiteName)}/${testCase.name}",
                      passed = false,
                      output = "",
                      diff = Some(
                        s"Test '${testCase.name}' failed with exception: ${e.getMessage}\n" +
                        TestRunner.formatStackTrace(e)),
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
                  out.println(s"${result.testName} ${LightYellow("UPDATED")} ${result.durationMs} ms.")
                  out.println()
                } else {
                  out.println(s"  ${result.testName}..${LightYellow("UPDATED")} ${result.durationMs} ms")
                }
                result.copy(passed = true)
              } else if (config.verbose) {
                printVerboseResultOutput(result)
                if (result.passed) {
                  out.println(s"${result.testName} ${LightGreen("ok")} ${result.durationMs} ms.")
                } else {
                  out.println(s"${result.testName} ${LightYellow("DIFF")} ${result.durationMs} ms.")
                  result.diff.foreach { diff =>
                    out.println()
                    out.println(diff)
                  }
                }
                out.println()
                result
              } else if (result.passed) {
                out.println(s"  ${result.testName}..${LightGreen("ok")} ${result.durationMs} ms")
                result
              } else {
                out.println(s"  ${result.testName}..${LightYellow("DIFF")} ${result.durationMs} ms")
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
      outDir = outDir,
      resourceManager = Some(resourceManager)
    )
    testRun.setFlags(config.recaptureAll, config.updateSnapshots)
    testRun.start()
    testRun.clearTmpDir()

    val startTime = System.currentTimeMillis()
    val fullTestName = s"$suitePath/${testCase.name}"

    // Acquire resource locks (for tests that share state)
    val locks = if (suite != null) suite.getResourceLocks else List.empty
    locks.foreach(lock => resourceManager.locks.acquire(lock))

    val logCapture = new LogCapture(testRun.logFile)
    logCapture.start()

    var consumerFailed = false
    var finalResult: Option[TestResult] = None

    try {
      // Setup
      if (suite != null) {
        try { suite.getSetup(testRun) }
        catch { case e: Exception =>
          testRun.iln(s"Setup failed: ${e.getMessage}")
          testRun.iln(TestRunner.formatStackTrace(e))
        }
      }

      val returnValue = try {
        executeTestWithDependencies(testCase, testRun, suitePath, suite)
      } finally {
        if (suite != null) {
          try { suite.getTeardown(testRun) }
          catch { case e: Exception =>
            testRun.iln(s"Teardown failed: ${e.getMessage}")
            testRun.iln(TestRunner.formatStackTrace(e))
          }
        }
      }

      val endTime = System.currentTimeMillis()
      val duration = endTime - startTime

      testRun.end()
      val snapshotResult = SnapshotManager.compareTest(testRun, config.diffMode)

      val result = if (testRun.isFailed) {
        val failMsg = testRun.failMessage.map(m => s": $m").getOrElse("")
        consumerFailed = true
        snapshotResult.copy(
          testName = fullTestName,
          passed = false,
          successState = SuccessState.FAIL,
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

      logCapture.stop()
      testRun.writeReport(s"Test '${testCase.name}' completed in ${duration}ms")

      // Cache return value on OK or DIFF, delete on FAIL
      if (!testRun.isFailed) {
        this.synchronized {
          dependencyCache.put(fullTestName, returnValue)
          testRun.saveReturnValue(returnValue)
        }
      } else {
        testRun.deleteReturnValue()
      }

      // Store testRun reference for snapshot updates
      val finalised = attachTraceBlock(
        result.copy(testRun = Some(testRun)),
        testCase.dependencies)
      finalResult = Some(finalised)
      finalised

    } catch {
      case NonFatal(rawErr) =>
        val e = TestRunner.unwrapInvocationTarget(rawErr)
        consumerFailed = true
        // Match the sequential-mode catch: write the full stack trace into
        // the test's .md output so it ends up in the snapshot diff and in
        // the .txt diff report. Without this, parallel-mode failures (the
        // path most likely to surface races) lose the call site.
        testRun.iln()
        testRun.fail()
        testRun.iln(s"test raised exception ${e.getClass.getName}: ${e.getMessage}")
        testRun.iln(TestRunner.formatStackTrace(e))
        testRun.end()
        logCapture.stop()
        val endTime = System.currentTimeMillis()
        val duration = endTime - startTime
        testRun.writeReport(s"Test '${testCase.name}' FAILED: ${e.getMessage}\nDuration: ${duration}ms")

        val errResult = attachTraceBlock(
          TestResult(
            testName = fullTestName,
            passed = false,
            output = "",
            diff = Some(
              s"Test '${testCase.name}' failed with exception: ${e.getMessage}\n" +
              TestRunner.formatStackTrace(e)),
            durationMs = duration),
          testCase.dependencies)
        finalResult = Some(errResult)
        errResult
    } finally {
      // Trace: this test ended. Emit before lock-release so the event
      // shows up alongside the test's other events in the ring buffer.
      finalResult.foreach { r =>
        val resultKind =
          if (r.passed) "ok"
          else if (r.successState == SuccessState.FAIL) "fail"
          else "diff"
        trace.emit(TraceEvent.TaskEnd(
          java.time.Instant.now(), TaskTrace.currentThread(),
          fullTestName, resultKind, r.durationMs))
      }
      // Release resource locks
      locks.foreach(lock => resourceManager.locks.release(lock))
      // Release every transitively-reserved live resource for this
      // consumer (in dep order — nested last). Direct dep gets the
      // failure flag so SharedWithReset / invalidate-on-fail trigger;
      // transitive nested deps don't need that signal.
      val reach = liveResources.transitiveResourceClosure(testCase.dependencies)
      val direct = testCase.dependencies.toSet
      reach.foreach { name =>
        liveResources.release(name, testCase.name,
          failed = consumerFailed && direct.contains(name),
          invalidateOnFail = config.invalidateLiveOnFail)
      }
    }
  }

  /** Update snapshot for a test result (used in parallel execution) */
  private def updateSnapshotForResult(result: TestResult): Unit = {
    result.testRun.foreach { testRun =>
      SnapshotManager.updateSnapshot(testRun)
    }
  }

  /** When a test fails or DIFFs under parallel execution, append a
    * "Trace context" block to its `diff` field listing the trace events
    * that touched this test and its transitive resources. This makes
    * race-class failures (Issue 1, future Issue Ns) diagnosable from
    * the end-of-run diff alone, with no need to re-run with `--trace`
    * or eyeball thread IDs.
    *
    * The block is opt-out for sequential mode (no concurrency = no
    * race-class confusion that the trace would clarify) but always
    * attached at `-pN ≥ 2`.
    *
    * Events are pulled from the always-on `ringBuffer` for: the test
    * itself, every live resource it transitively reaches, and every
    * test producer in that resource closure. Sorted by timestamp so
    * the reader sees the chronological order across worker threads. */
  private def attachTraceBlock(r: TestResult, deps: Iterable[String]): TestResult = {
    if (r.passed || config.threads <= 1) return r
    val resources = liveResources.transitiveResourceClosure(deps)
    val producers = liveResources.transitiveTestProducers(deps)
    val producerKeys = producers.map { p =>
      // testProducers returns short names; qualify with same suitePath
      // as the consumer (producers must live in the same suite for
      // booktest's current dep model).
      val slash = r.testName.lastIndexOf('/')
      if (slash > 0) s"${r.testName.substring(0, slash)}/$p" else p
    }
    val keys = (Set(r.testName) ++ resources ++ producerKeys).toList
    val events = ringBuffer.snapshotMany(keys)
    if (events.isEmpty) return r
    val block = formatTraceBlock(r.testName, events)
    val newDiff = r.diff match {
      case Some(d) => Some(d + "\n\n" + block)
      case None    => Some(block)
    }
    r.copy(diff = newDiff)
  }

  private def formatTraceBlock(forTask: String, events: List[TraceEvent]): String = {
    val sb = new StringBuilder
    sb.append(s"Trace context for $forTask (last ${events.size} events):\n")
    sb.append("-" * 60).append('\n')
    events.foreach { e =>
      sb.append("  ").append(TraceEvent.render(e)).append('\n')
    }
    sb.append("-" * 60)
    sb.toString
  }

  /** Print verbose output from a test result */
  private def printVerboseResultOutput(result: TestResult): Unit = {
    result.testRun.foreach { testRun =>
      val output = testRun.getTestOutput
      if (output.nonEmpty) {
        output.linesIterator.foreach { line =>
          out.println(s"  $line")
        }
        out.println()
      }
    }
  }

  /** Load a dependency value from memory cache or bin file.
    *
    * Cache lookup precedence:
    *   1. In-memory cache (populated by every successful test in this
    *      run).
    *   2. On-disk `.bin` from a *prior* invocation — but only when the
    *      dep is **not** scheduled to run in the current invocation.
    *      If the dep is scheduled, returning the on-disk value would
    *      preempt the producer that's about to run with state from the
    *      previous invocation; the caller should wait (or auto-run)
    *      instead. This is Issue 1 Fix B from
    *      `.ai/plan/task-graph.md`.
    *
    * @param depName short dependency name (e.g., "createData")
    * @param suitePath suite path prefix (e.g., "examples/MethodRefTests")
    * @param testRun test run for bin file fallback
    */
  private def loadDependencyValue(depName: String, suitePath: String, testRun: TestCaseRun): Option[Any] = {
    val qualifiedKey = s"$suitePath/$depName"
    val consumer = s"$suitePath/${testRun.testName}"
    def emit(source: String, value: String): Unit =
      trace.emit(TraceEvent.DepResolve(
        java.time.Instant.now(), TaskTrace.currentThread(),
        consumer, qualifiedKey, source, value))

    dependencyCache.get[Any](qualifiedKey) match {
      case Some(v) =>
        emit("memory", String.valueOf(v))
        Some(v)
      case None =>
        if (scheduledInThisRun.contains(qualifiedKey)) {
          // Producer is scheduled in this run; refuse to serve a stale
          // value from disk. Caller will either find the producer
          // already completed on a retry, or auto-run it inline.
          emit("miss-pending", "")
          None
        } else {
          val depBinFile = testRun.outDir / s"$depName.bin"
          if (os.exists(depBinFile)) {
            try {
              val serialized = os.read(depBinFile)
              val result = testRun.deserializeCacheValue(serialized)
              dependencyCache.put(qualifiedKey, result)
              emit("bin", String.valueOf(result))
              Some(result)
            } catch {
              case _: Exception =>
                emit("bin-error", "")
                None
            }
          } else {
            emit("miss", "")
            None
          }
        }
    }
  }

  /** Resolve a dependency value, auto-running the dependency if its cached value is missing.
    * Like Python booktest, acts as a build system: dependencies are run on demand.
    */
  private def resolveDependencyValue(
    depName: String, suitePath: String, testRun: TestCaseRun,
    suiteName: String, suite: TestSuite
  ): Any = {
    val cached = loadDependencyValue(depName, suitePath, testRun)
    if (config.verbose) {
      out.println(s"    Dependency '$depName': ${cached.isDefined}")
    }
    cached.getOrElse {
      // Auto-run missing dependency (build-system behavior, like Python booktest)
      if (suite != null) {
        val depTestCase = suite.testCases.find(_.name == depName)
        depTestCase match {
          case Some(depTC) =>
            if (config.verbose) {
              out.println(s"    Auto-running missing dependency '$depName'...")
            }
            val depResult = runTestCase(depTC, suiteName, suite)
            if (depResult.passed || depResult.successState == SuccessState.DIFF) {
              // Dependency ran - try loading its cached value
              loadDependencyValue(depName, suitePath, testRun).getOrElse {
                throw new IllegalStateException(
                  s"Dependency '$depName' was auto-run but produced no cached value for test '${testRun.testName}'")
              }
            } else {
              throw new IllegalStateException(
                s"Dependency '$depName' failed when auto-run for test '${testRun.testName}'")
            }
          case None =>
            throw new IllegalStateException(
              s"Dependency '$depName' not found in suite for test '${testRun.testName}'")
        }
      } else {
        throw new IllegalStateException(
          s"Dependency '$depName' not found in cache for test '${testRun.testName}'")
      }
    }
  }

  /** Resolve a dep that belongs to a *live resource's* dep list (used as a
    * callback by LiveResourceManager.acquire). The consumerTestName is the
    * outermost consumer that triggered the resolution chain — needed so a
    * nested resource that's Exclusive can give that consumer its own
    * instance. */
  private def resolveLiveDep(
    dep: Dep[?], suitePath: String, testRun: TestCaseRun,
    suiteName: String, suite: TestSuite, consumerTestName: String
  ): Any = dep match {
    case TestDep(ref) =>
      resolveDependencyValue(ref.name, suitePath, testRun, suiteName, suite)
    case ResourceDep(ref) =>
      liveResources.acquire[Any](ref.name, consumerTestName,
        d => resolveLiveDep(d, suitePath, testRun, suiteName, suite, consumerTestName))
    case PoolDep(pool) =>
      pool.acquire()
    case CapacityDep(cap, amount) =>
      cap.asInstanceOf[ResourceCapacity[Any]].acquire(amount.asInstanceOf[Any])
      amount
  }

  /** Resolve a single dep name for a *consumer test*. Routes to
    * LiveResourceManager if the name matches a registered live resource,
    * otherwise falls back to the existing test-cache path. */
  private def resolveConsumerDep(
    depName: String, suitePath: String, testRun: TestCaseRun,
    suiteName: String, suite: TestSuite, consumerTestName: String
  ): Any = {
    if (liveResources.isRegistered(depName)) {
      liveResources.acquire[Any](depName, consumerTestName,
        d => resolveLiveDep(d, suitePath, testRun, suiteName, suite, consumerTestName))
    } else {
      resolveDependencyValue(depName, suitePath, testRun, suiteName, suite)
    }
  }

  private def executeTestWithDependencies(testCase: TestCase, testRun: TestCaseRun, suitePath: String = "", suite: TestSuite = null): Any = {
    val suiteName = if (suite != null) suite.fullClassName else ""

    testCase.originalFunction match {
      case Some(function) =>
        // New API: Use the stored function with proper type-safe dependency injection
        if (testCase.dependencies.isEmpty) {
          // No dependencies - function is (TestCaseRun) => T
          function.asInstanceOf[(TestCaseRun) => Any](testRun)
        } else {
          // Has dependencies - need to inject cached values
          if (config.verbose) {
            out.println(s"    Looking for dependencies: ${testCase.dependencies}")
          }
          val dependencyValues = testCase.dependencies.map { depName =>
            resolveConsumerDep(depName, suitePath, testRun, suiteName, suite, testCase.name)
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
                out.println(s"    Looking for dependencies: ${testCase.dependencies}")
              }
              val dependencyValues = testCase.dependencies.map { depName =>
                resolveConsumerDep(depName, suitePath, testRun, suiteName, suite, testCase.name)
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
        out.println(s"  $line")
      }
      out.println()
    }
  }
}