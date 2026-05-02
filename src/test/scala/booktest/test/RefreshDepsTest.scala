package booktest.test

import booktest._
import java.util.concurrent.atomic.AtomicInteger

/** Counters that survive across `new TestRunner(...)` invocations within a
  * single meta-test method, so we can tell whether a producer test was
  * actually executed or merely loaded from `.bin`. */
object RefreshDepsCounters {
  val producerRuns = new AtomicInteger(0)
  val consumerRuns = new AtomicInteger(0)
  def reset(): Unit = { producerRuns.set(0); consumerRuns.set(0) }
}

/** Helper suite: a producer test plus a consumer that depends on it.
  * Each invocation of `testProducer` bumps a global counter so the meta
  * test can assert whether it was re-executed. */
class RefreshDepsHelper extends TestSuite {
  def testProducer(t: TestCaseRun): String = {
    RefreshDepsCounters.producerRuns.incrementAndGet()
    t.tln("producing value")
    "produced_value"
  }

  // Use Java @DependsOn so the multi-arg method is picked up by reflection
  // (the Scala @dependsOn annotation does not have runtime retention for
  // the multi-param discovery filter in TestSuite.discoverTests).
  @DependsOn(Array("testProducer"))
  def testConsumer(t: TestCaseRun, produced: String): Unit = {
    RefreshDepsCounters.consumerRuns.incrementAndGet()
    t.tln(s"consumed: $produced")
  }
}

/** Meta test: verifies that filtering to a subset of tests does NOT
  * unconditionally re-execute their transitive dependencies when those
  * dependencies have a fresh `.bin` cache file (Python booktest parity).
  *
  * Also verifies that `--refresh-deps` (-r) opts in to re-running them. */
class RefreshDepsTest extends TestSuite {

  // The three cases share the global RefreshDepsCounters object, so under
  // -pN they must run one at a time. The lock has no effect at -p1.
  override protected def resourceLocks: List[String] = List("refresh-deps-counters")

  private def quietConfig(tempDir: os.Path, filter: Option[String], refresh: Boolean): RunConfig = {
    val logStream = new java.io.PrintStream(
      new java.io.FileOutputStream((tempDir / s"runner-${System.nanoTime()}.log").toIO))
    RunConfig(
      outputDir = tempDir,
      snapshotDir = tempDir,
      verbose = false,
      output = logStream,
      testFilter = filter,
      refreshDeps = refresh
    )
  }

  def testFilteredRunUsesCachedDep(t: TestCaseRun): Unit = {
    t.h1("Cached deps are loaded from .bin instead of re-executed")

    val tempDir = t.tmpDir("refresh-deps-cached")
    RefreshDepsCounters.reset()

    // Phase 1: warm the cache by running the full suite.
    val warmCfg = quietConfig(tempDir, filter = None, refresh = false)
    new TestRunner(warmCfg).runSuite(new RefreshDepsHelper())

    val producerAfterWarm = RefreshDepsCounters.producerRuns.get
    val consumerAfterWarm = RefreshDepsCounters.consumerRuns.get
    t.tln(s"after warm-up: producer=$producerAfterWarm consumer=$consumerAfterWarm")
    assert(producerAfterWarm == 1, "producer should have run exactly once during warm-up")
    assert(consumerAfterWarm == 1, "consumer should have run exactly once during warm-up")

    // Phase 2: filter to the consumer only. The producer's .bin exists,
    // so it must NOT be re-executed; the consumer should load the value
    // from .bin via resolveDependencyValue. The filter pattern matches
    // the cleaned test name ("consumer", not "testConsumer").
    val filteredCfg = quietConfig(tempDir, filter = Some("consumer"), refresh = false)
    val filteredResult = new TestRunner(filteredCfg).runSuite(new RefreshDepsHelper())

    val producerAfterFiltered = RefreshDepsCounters.producerRuns.get
    val consumerAfterFiltered = RefreshDepsCounters.consumerRuns.get
    t.tln(s"after filter -t consumer: producer=$producerAfterFiltered consumer=$consumerAfterFiltered")
    t.tln(s"  results in run: ${filteredResult.results.map(_.testName).mkString(", ")}")

    assert(producerAfterFiltered == 1,
      s"producer must NOT be re-run when its .bin is cached (got runs=$producerAfterFiltered)")
    assert(consumerAfterFiltered == 2,
      s"consumer should have run again (got runs=$consumerAfterFiltered)")
    assert(filteredResult.results.length == 1,
      s"only the matched consumer should be in the run list (got ${filteredResult.results.length})")

    t.tln("PASS: cached transitive dep was loaded from .bin, not re-executed")
  }

  def testRefreshDepsForcesRerun(t: TestCaseRun): Unit = {
    t.h1("--refresh-deps forces transitive deps to re-run")

    val tempDir = t.tmpDir("refresh-deps-forced")
    RefreshDepsCounters.reset()

    val warmCfg = quietConfig(tempDir, filter = None, refresh = false)
    new TestRunner(warmCfg).runSuite(new RefreshDepsHelper())

    val producerAfterWarm = RefreshDepsCounters.producerRuns.get
    t.tln(s"after warm-up: producer=$producerAfterWarm")
    assert(producerAfterWarm == 1, "producer should have run once during warm-up")

    // Filter to consumer with -r set: producer must re-execute even
    // though its .bin is cached.
    val refreshCfg = quietConfig(tempDir, filter = Some("consumer"), refresh = true)
    val refreshResult = new TestRunner(refreshCfg).runSuite(new RefreshDepsHelper())

    val producerAfterRefresh = RefreshDepsCounters.producerRuns.get
    t.tln(s"after filter -t consumer -r: producer=$producerAfterRefresh")
    t.tln(s"  results in run: ${refreshResult.results.map(_.testName).mkString(", ")}")

    assert(producerAfterRefresh == 2,
      s"producer must re-run with --refresh-deps (got runs=$producerAfterRefresh)")
    assert(refreshResult.results.length == 2,
      s"both producer and consumer should be in the run list (got ${refreshResult.results.length})")

    t.tln("PASS: --refresh-deps re-executed the cached dep")
  }

  def testMissingBinTriggersRerun(t: TestCaseRun): Unit = {
    t.h1("Missing .bin causes the dep to run even without -r")

    val tempDir = t.tmpDir("refresh-deps-missing")
    RefreshDepsCounters.reset()

    // Warm to create snapshots for the consumer's md file (so the
    // filtered run isn't churning on snapshots), then delete the
    // producer's .bin to simulate a cache miss.
    new TestRunner(quietConfig(tempDir, None, refresh = false)).runSuite(new RefreshDepsHelper())
    val producerBin = os.walk(tempDir / ".out").find(_.last == "producer.bin")
    producerBin.foreach(os.remove)
    t.tln(s".bin removed: ${producerBin.isDefined}")

    val cfg = quietConfig(tempDir, filter = Some("consumer"), refresh = false)
    val result = new TestRunner(cfg).runSuite(new RefreshDepsHelper())

    val producerRuns = RefreshDepsCounters.producerRuns.get
    t.tln(s"after filter -t consumer (with .bin missing): producer=$producerRuns")
    t.tln(s"  results in run: ${result.results.map(_.testName).mkString(", ")}")

    assert(producerRuns == 2,
      s"producer must run when its .bin is missing (got runs=$producerRuns)")
    assert(result.results.length == 2,
      s"producer should be auto-included when its .bin is missing (got ${result.results.length})")

    t.tln("PASS: missing .bin promoted the dep into the run list")
  }
}
