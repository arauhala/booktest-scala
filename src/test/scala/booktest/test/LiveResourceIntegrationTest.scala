package booktest.test

import booktest.*

import java.util.concurrent.atomic.AtomicInteger

// ---- Helper classes for the tests below. Excluded from default discovery
// via booktest.ini; instantiated directly in meta-tests. ----

object IntegrationCounters {
  /** Per-class counters keyed by tag so each helper has its own state. */
  private val maps = scala.collection.concurrent.TrieMap[String, AtomicInteger]()
  def get(tag: String): AtomicInteger =
    maps.getOrElseUpdate(tag, new AtomicInteger(0))
  def reset(tag: String): Unit = get(tag).set(0)
}

class TaggedHandle(tag: String) extends AutoCloseable {
  IntegrationCounters.get(s"$tag.builds").incrementAndGet()
  override def close(): Unit =
    IntegrationCounters.get(s"$tag.closes").incrementAndGet()
}

/** Helper for review-mode test: a tiny suite with one live resource and
  * one consumer. */
class ReviewHelper extends TestSuite {
  val handle: ResourceRef[TaggedHandle] =
    liveResource("reviewHandle") { new TaggedHandle("review") }

  test("usesHandle", handle) { (t: TestCaseRun, h: TaggedHandle) =>
    t.tln(s"tag: review")
  }
}

/** Helper for continue-mode test. */
class ContinueModeHelper extends TestSuite {
  val handle: ResourceRef[TaggedHandle] =
    liveResource("contHandle") { new TaggedHandle("continue") }

  test("first", handle) { (t: TestCaseRun, h: TaggedHandle) =>
    t.tln("first ran")
  }

  test("second", handle) { (t: TestCaseRun, h: TaggedHandle) =>
    t.tln("second ran")
  }
}

/** Helper for auto-run test: live resource depends on a TestRef whose
  * producer test isn't run directly. The build path must trigger
  * auto-run of the producer to satisfy the dep. */
class AutoRunHelper extends TestSuite {
  val producer: TestRef[String] = test("producer") { (t: TestCaseRun) =>
    IntegrationCounters.get("autorun.producerRuns").incrementAndGet()
    t.tln("producer ran")
    "produced-value"
  }

  val handle: ResourceRef[TaggedHandle] =
    liveResource("autorunHandle", producer) { (s: String) =>
      IntegrationCounters.get("autorun.buildSawValue").set(s.length)
      new TaggedHandle("autorun")
    }

  // Only the consumer is selected via -t; the producer must auto-run
  // when the build path resolves the producer TestRef.
  test("consumer", handle) { (t: TestCaseRun, h: TaggedHandle) =>
    t.tln("consumer ran")
  }
}

/** Helper for failing-TestRef-dep test: producer always fails, so the
  * live resource's build cannot resolve its dep. */
class FailingTestRefHelper extends TestSuite {

  val producer: TestRef[String] = test("brokenProducer") { (t: TestCaseRun) =>
    t.tln("about to throw")
    throw new RuntimeException("producer is broken")
  }

  val handle: ResourceRef[TaggedHandle] =
    liveResource("dependsOnBroken", producer, resources.ports) {
      (_: String, _: Int) => new TaggedHandle("failingref")
    }

  test("usesHandle", handle) { (t: TestCaseRun, h: TaggedHandle) =>
    t.tln("unreachable")
  }
}

class LiveResourceIntegrationTest extends TestSuite {

  private def quietConfig(tempDir: os.Path,
                          continueMode: Boolean = false,
                          testFilter: Option[String] = None,
                          updateSnapshots: Boolean = false): RunConfig = {
    val logStream = new java.io.PrintStream(
      new java.io.FileOutputStream((tempDir / "runner.log").toIO))
    RunConfig(
      outputDir = tempDir,
      snapshotDir = tempDir,
      verbose = false,
      output = logStream,
      continueMode = continueMode,
      testFilter = testFilter,
      updateSnapshots = updateSnapshots
    )
  }

  // ----- Test #2: review mode ---------------------------------------------

  def testReviewModeDoesNotBuildResources(t: TestCaseRun): Unit = {
    t.h1("Review mode does not materialize live resources")
    val tempDir = t.tmpDir("review-mode")

    // First: run normally (updateSnapshots so OK status is recorded).
    IntegrationCounters.reset("review.builds")
    IntegrationCounters.reset("review.closes")
    val firstRun = new TestRunner(quietConfig(tempDir, updateSnapshots = true))
    firstRun.runMultipleSuites(List(new ReviewHelper))

    val buildsAfterRun = IntegrationCounters.get("review.builds").get()
    val closesAfterRun = IntegrationCounters.get("review.closes").get()
    t.tln(s"after run -- builds: $buildsAfterRun closes: $closesAfterRun")
    assert(buildsAfterRun == 1, "first run should have built once")

    // Then: review the same set of suites. No tests should execute, no
    // resources should materialize.
    IntegrationCounters.reset("review.builds")
    val reviewRunner = new TestRunner(quietConfig(tempDir))
    val rc = reviewRunner.reviewResults(List(new ReviewHelper))
    val buildsAfterReview = IntegrationCounters.get("review.builds").get()
    t.tln(s"after review -- builds: $buildsAfterReview rc: $rc")

    assert(buildsAfterReview == 0,
      s"review mode should not build live resources; got $buildsAfterReview")
  }

  // ----- Test #3: continue mode -------------------------------------------

  def testContinueModeSkipsAndDoesNotBuild(t: TestCaseRun): Unit = {
    t.h1("Continue mode skips passed tests AND skips their resource build")
    val tempDir = t.tmpDir("continue-mode")

    // First run with updateSnapshots so tests come out OK (continue mode
    // only skips OK tests, not DIFF).
    IntegrationCounters.reset("continue.builds")
    val firstRun = new TestRunner(quietConfig(tempDir, updateSnapshots = true))
    val r1 = firstRun.runMultipleSuites(List(new ContinueModeHelper))
    val buildsFirst = IntegrationCounters.get("continue.builds").get()
    t.tln(s"first run -- builds: $buildsFirst passed: ${r1.passedTests}/${r1.totalTests}")
    assert(buildsFirst == 1, "first run should build once")

    // Second run with continueMode=true: cases.ndjson lists everything as
    // OK, so both tests are skipped. The resource has no consumers, so no
    // build should happen.
    IntegrationCounters.reset("continue.builds")
    IntegrationCounters.reset("continue.closes")
    val secondRun = new TestRunner(quietConfig(tempDir, continueMode = true))
    val r2 = secondRun.runMultipleSuites(List(new ContinueModeHelper))
    val buildsSecond = IntegrationCounters.get("continue.builds").get()
    t.tln(s"continue run -- builds: $buildsSecond passed: ${r2.passedTests}/${r2.totalTests}")

    assert(buildsSecond == 0,
      s"continue mode should not rebuild for skipped consumers; got $buildsSecond")
  }

  // ----- Test #4: auto-run from inside build ------------------------------

  def testBuildAutoRunsTestRefDep(t: TestCaseRun): Unit = {
    t.h1("Live resource build auto-runs a missing TestRef dep")
    val tempDir = t.tmpDir("autorun-build")

    IntegrationCounters.reset("autorun.builds")
    IntegrationCounters.reset("autorun.producerRuns")
    IntegrationCounters.get("autorun.buildSawValue").set(-1)

    // Filter to ONLY the consumer. The producer is also discovered (as a
    // dep), but the goal is to verify the build path auto-runs it.
    val runner = new TestRunner(quietConfig(tempDir, testFilter = Some("consumer")))
    val r = runner.runMultipleSuites(List(new AutoRunHelper))

    val producerRuns = IntegrationCounters.get("autorun.producerRuns").get()
    val builds = IntegrationCounters.get("autorun.builds").get()
    val sawValue = IntegrationCounters.get("autorun.buildSawValue").get()
    t.tln(s"producer runs: $producerRuns")
    t.tln(s"resource builds: $builds")
    t.tln(s"build saw value of length: $sawValue")  // "produced-value".length == 14
    t.tln(s"results: ${r.results.map(rr => s"${rr.testName.split('/').last}=${rr.successState}").mkString(", ")}")

    assert(producerRuns >= 1, "producer should have been auto-run by the build path")
    assert(builds == 1, "resource should be built once")
    assert(sawValue == "produced-value".length,
      s"build should receive producer's return value; got len=$sawValue")
  }

  // ----- Test #6: live resource depending on a failing TestRef ------------

  def testFailingTestRefSurfacesAsConsumerFailure(t: TestCaseRun): Unit = {
    t.h1("Failing producer cascades cleanly to the consumer")
    val tempDir = t.tmpDir("failing-ref")

    IntegrationCounters.reset("failingref.builds")
    val pool = new CountingPool

    // Wire a custom helper that uses our counting pool so we can verify
    // no port leaks even though the build couldn't even start.
    val helper = new TestSuite {
      val producer: TestRef[String] = test("brokenProducer") { (tt: TestCaseRun) =>
        tt.tln("about to throw")
        throw new RuntimeException("producer is broken")
      }

      val handle: ResourceRef[TaggedHandle] =
        liveResource("dependsOnBroken", producer, pool) {
          (_: String, _: Int) => new TaggedHandle("failingref-pool")
        }

      test("usesHandle", handle) { (tt: TestCaseRun, h: TaggedHandle) =>
        tt.tln("unreachable")
      }
    }

    val runner = new TestRunner(quietConfig(tempDir))
    val r = runner.runMultipleSuites(List(helper))

    val builds = IntegrationCounters.get("failingref.builds").get()
    val consumer = r.results.find(_.testName.endsWith("/usesHandle")).get
    t.tln(s"resource builds: $builds")
    t.tln(s"consumer state: ${consumer.successState}")
    t.tln(s"port acquires: ${pool.acquired.get()}")
    t.tln(s"port outstanding: ${pool.outstanding}")

    assert(builds == 0,
      s"resource build should never reach the closure when its TestRef fails; got $builds")
    assert(consumer.successState == SuccessState.FAIL,
      s"consumer should FAIL when its resource's TestRef fails; got ${consumer.successState}")
    assert(pool.outstanding == 0,
      s"no ports should leak when the build aborted before the build closure ran; got ${pool.outstanding}")
  }
}
