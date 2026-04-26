package booktest.test

import booktest.*

import java.util.concurrent.atomic.AtomicInteger

/** Helper: counts pool acquire/release calls so the leak test can verify
  * that a partially-acquired resource doesn't leave ports allocated. */
class CountingPool extends ResourcePool[Int] {
  val acquired: AtomicInteger = new AtomicInteger(0)
  val released: AtomicInteger = new AtomicInteger(0)
  private val counter = new AtomicInteger(0)
  def acquire(): Int = { acquired.incrementAndGet(); counter.incrementAndGet() }
  def release(resource: Int): Unit = { released.incrementAndGet() }
  def releaseAll(): Unit = ()
  def outstanding: Int = acquired.get() - released.get()
}

/** Helper suite for partial-build leak: declares a resource that takes a
  * port AND a capacity reservation, then throws in the build closure.
  * The pool/capacity allocations must be released even though `build`
  * never returned. */
class LeakingBuildHelper(pool: CountingPool, cap: ResourceCapacity[Double])
    extends TestSuite {
  val handle: ResourceRef[AutoCloseable] =
    liveResource("leakBuild", pool, cap.reserve(100.0)) { (_: Int, _: Double) =>
      throw new RuntimeException("partial build failure")
    }
  test("triggersBuild", handle) { (t: TestCaseRun, h: AutoCloseable) =>
    t.tln(s"unreachable: $h")
  }
}

/** Helper suite for over-committed capacity (single resource exceeds total). */
class OverCommittedCapacityHelper extends TestSuite {
  val ram: ResourceCapacity[Double] = capacity("hardeningRam", 4.0)
  val handle: ResourceRef[AutoCloseable] =
    liveResource("toobig", ram.reserve(8.0)) { _ =>
      new AutoCloseable { def close(): Unit = () }
    }
  test("would-deadlock", handle) { (t: TestCaseRun, h: AutoCloseable) =>
    t.tln("never runs")
  }
}

/** Helper suite for duplicate liveResource names within one suite. */
class DuplicateNameHelper extends TestSuite {
  val a: ResourceRef[AutoCloseable] =
    liveResource("dupName") { new AutoCloseable { def close(): Unit = () } }
  // This second registration should throw at suite construction time.
  val b: ResourceRef[AutoCloseable] =
    liveResource("dupName") { new AutoCloseable { def close(): Unit = () } }
}

class LiveResourceHardeningTest extends TestSuite {

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

  def testBuildFailureReleasesPartialAllocations(t: TestCaseRun): Unit = {
    t.h1("Build failure releases partial allocations")
    val tempDir = t.tmpDir("partial-leak")
    val config = quietConfig(tempDir)
    val runner = new TestRunner(config)

    val pool = new CountingPool
    // Use a fresh capacity (not the global "ram") to avoid cross-test pollution.
    val cap = new DoubleCapacity("leakCap", 1000.0)

    val suite = new LeakingBuildHelper(pool, cap)
    val result = runner.runMultipleSuites(List(suite))

    t.tln(s"port acquires:  ${pool.acquired.get()}")
    t.tln(s"port releases:  ${pool.released.get()}")
    t.tln(s"port outstanding: ${pool.outstanding}")
    t.tln(s"capacity used:    ${cap.used}")
    t.tln(s"test failed:      ${result.results.head.successState == SuccessState.FAIL}")

    assert(pool.outstanding == 0,
      s"port should be released even when build throws; got ${pool.outstanding} outstanding")
    assert(cap.used == 0.0,
      s"capacity should be released even when build throws; got ${cap.used} used")
  }

  def testOverCommittedCapacityFailsFast(t: TestCaseRun): Unit = {
    t.h1("Capacity over-commit fails fast")
    val tempDir = t.tmpDir("over-commit")
    val config = quietConfig(tempDir)
    val runner = new TestRunner(config)

    val caught = try {
      runner.runMultipleSuites(List(new OverCommittedCapacityHelper))
      None
    } catch {
      case e: IllegalStateException => Some(e.getMessage)
    }

    t.tln(s"validation rejected: ${caught.isDefined}")
    caught.foreach(m => t.tln(s"contains 'over-committed': ${m.contains("over-committed")}"))

    assert(caught.isDefined, "expected IllegalStateException for single-resource over-commit")
    assert(caught.get.contains("over-committed"),
      s"error message should mention over-commit: ${caught.get}")
  }

  def testDuplicateNameRejected(t: TestCaseRun): Unit = {
    t.h1("Duplicate liveResource name rejected at registration")
    val caught = try {
      new DuplicateNameHelper
      None
    } catch {
      case e: IllegalArgumentException => Some(e.getMessage)
    }

    t.tln(s"duplicate rejected: ${caught.isDefined}")
    caught.foreach(m => t.tln(s"mentions 'Duplicate': ${m.contains("Duplicate")}"))

    assert(caught.isDefined, "expected IllegalArgumentException for duplicate name")
    assert(caught.get.contains("Duplicate"),
      s"error message should call out the duplicate: ${caught.get}")
  }
}
