package booktest.test

import booktest._

/** Helper suite with tests that have overlapping names.
  * Uses hyphenated names to reproduce the real-world issue where
  * "state-10M" contains-matches "optimized-state-10M". */
class OverlappingNamesSuite extends TestSuite {
  // Use method-ref API to control test names with hyphens
  val state = test("state-M") { (t: TestCaseRun) =>
    t.tln("running state")
    "state_result"
  }

  val perf = test("perf-M", state) { (t: TestCaseRun, stateResult: String) =>
    t.tln(s"running perf with: $stateResult")
  }

  val optimizedState = test("optimized-state-M") { (t: TestCaseRun) =>
    t.tln("running optimized-state")
    "optimized_state_result"
  }

  val perfOptimized = test("perf-optimized-M", optimizedState) { (t: TestCaseRun, stateResult: String) =>
    t.tln(s"running perf-optimized with: $stateResult")
  }
}

/** Helper suite with dependencies for .bin fallback test */
class BinFallbackSuite extends TestSuite {
  val setupState = test("setup-state") { (t: TestCaseRun) =>
    t.tln("setting up state")
    "produced_state"
  }

  val useState = test("use-state", setupState) { (t: TestCaseRun, state: String) =>
    t.tln(s"using state: $state")
  }
}

/**
 * Meta test: Verifies test filtering and dependency resolution.
 *
 * Issue 1: -t filter uses contains, so "state" matches "optimized-state"
 * Issue 2: Running a dependent test alone fails if dependency wasn't selected
 */
class FilterAndDependencyTest extends TestSuite {

  private def quietConfig(tempDir: os.Path): RunConfig = {
    val logStream = new java.io.PrintStream(
      new java.io.FileOutputStream((tempDir / "runner.log").toIO))
    RunConfig(
      outputDir = tempDir,
      snapshotDir = tempDir,
      verbose = false,
      output = logStream
    )
  }

  def testExactFilterDoesNotMatchSubstring(t: TestCaseRun): Unit = {
    t.h1("Filter: Exact Match")

    val tempDir = t.tmpDir("filter-exact")
    // exactFilter = true simulates path resolution (SuiteName/testCase)
    val config = quietConfig(tempDir).copy(testFilter = Some("state-M"), exactFilter = true)

    val suite = new OverlappingNamesSuite()
    val runner = new TestRunner(config)
    val result = runner.runSuite(suite)

    val ranNames = result.results.map(_.testName.split("/").last)
    t.tln(s"ran: ${ranNames.mkString(", ")}")

    // "state-M" exact filter should match "state-M" but NOT "optimized-state-M"
    assert(ranNames.contains("state-M"),
      "should run 'state-M' test")
    assert(!ranNames.contains("optimized-state-M"),
      s"should NOT run 'optimized-state-M' — exact filter should not substring match. Ran: $ranNames")
    assert(!ranNames.contains("perf-optimized-M"),
      s"should NOT run 'perf-optimized-M'. Ran: $ranNames")

    t.tln("PASS: exact filter does not match substring")
  }

  def testDependencyAutoIncluded(t: TestCaseRun): Unit = {
    t.h1("Filter: Dependency Auto-Included")

    val tempDir = t.tmpDir("filter-deps")
    val config = quietConfig(tempDir).copy(testFilter = Some("perf-M"), exactFilter = true)

    val suite = new OverlappingNamesSuite()
    val runner = new TestRunner(config)
    val result = runner.runSuite(suite)

    val ranNames = result.results.map(_.testName.split("/").last)
    t.tln(s"ran: ${ranNames.mkString(", ")}")

    // "perf-M" should run, and its dependency "state-M" should be auto-included
    assert(ranNames.contains("perf-M"), "should run 'perf-M'")
    assert(ranNames.contains("state-M"), "should auto-include dependency 'state-M'")

    // But should NOT include "optimized-state-M" or "perf-optimized-M"
    assert(!ranNames.contains("optimized-state-M"),
      s"should NOT run 'optimized-state-M'. Ran: $ranNames")
    assert(!ranNames.contains("perf-optimized-M"),
      s"should NOT run 'perf-optimized-M'. Ran: $ranNames")

    t.tln("PASS: dependency auto-included, no substring matches")
  }

  def testDependencyLoadedFromBin(t: TestCaseRun): Unit = {
    t.h1("Dependency: Loaded from .bin")

    val tempDir = t.tmpDir("dep-bin")

    // Step 1: Run full suite to create .bin files
    val config1 = quietConfig(tempDir)
    val runner1 = new TestRunner(config1)
    runner1.runSuite(new BinFallbackSuite())

    // Step 2: Run only "use-state" with filter — its dependency "setup-state"
    // should load from .bin even though it's not in the filtered test list
    val config2 = quietConfig(tempDir).copy(testFilter = Some("use-state"), exactFilter = true)
    val runner2 = new TestRunner(config2)
    val result = runner2.runSuite(new BinFallbackSuite())

    val ranNames = result.results.map(_.testName.split("/").last)
    t.tln(s"ran: ${ranNames.mkString(", ")}")

    if (result.results.nonEmpty) {
      val useStateResult = result.results.find(_.testName.endsWith("use-state"))
      useStateResult match {
        case Some(tr) =>
          t.tln(s"use-state state: ${tr.successState}")
          assert(tr.successState != SuccessState.FAIL,
            s"use-state should not FAIL — dependency should load from .bin. Got: ${tr.successState}")
          t.tln("PASS: dependency loaded from .bin cache")
        case None =>
          t.fln("FAIL: use-state test not found in results")
      }
    } else {
      t.fln("FAIL: no results — test was not run")
    }
  }
}
