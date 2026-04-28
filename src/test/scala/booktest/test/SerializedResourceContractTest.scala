package booktest.test

import booktest.*

import java.util.concurrent.atomic.AtomicInteger

/** Process-wide overlap tracker: each consumer increments on entry and
  * decrements on exit; we record the max observed concurrency. */
object SerializedOverlapTracker {
  val current = new AtomicInteger(0)
  val maxSeen = new AtomicInteger(0)
  def reset(): Unit = { current.set(0); maxSeen.set(0) }
  def enter(): Unit = {
    val now = current.incrementAndGet()
    var snap = maxSeen.get()
    while (now > snap && !maxSeen.compareAndSet(snap, now)) snap = maxSeen.get()
  }
  def exit(): Unit = current.decrementAndGet()
}

/** Helper suite for SerializedResourceContractTest. Excluded from default
  * discovery via booktest.ini. */
class SerializedContractHelper extends TestSuite {
  class Watcher extends AutoCloseable {
    override def close(): Unit = ()
  }

  val res: ResourceRef[Watcher] =
    liveResourceSerialized("watcher") { new Watcher }

  private def hold(): Unit = {
    SerializedOverlapTracker.enter()
    try Thread.sleep(40) finally SerializedOverlapTracker.exit()
  }

  test("c1", res) { (t: TestCaseRun, _: Watcher) => hold(); t.tln("c1 ok") }
  test("c2", res) { (t: TestCaseRun, _: Watcher) => hold(); t.tln("c2 ok") }
  test("c3", res) { (t: TestCaseRun, _: Watcher) => hold(); t.tln("c3 ok") }
  test("c4", res) { (t: TestCaseRun, _: Watcher) => hold(); t.tln("c4 ok") }
}

/** Verifies that liveResourceSerialized actually serializes consumer access
  * even under -p4. Pre-fix this share mode didn't exist; users had to pick
  * between SharedReadOnly (concurrent) or SharedWithReset (with a noop
  * reset closure). */
class SerializedResourceContractTest extends TestSuite {

  override protected def resourceLocks: List[String] = List("SerializedResourceContractTest")

  private def quietConfig(tempDir: os.Path, threads: Int): RunConfig = {
    val logStream = new java.io.PrintStream(
      new java.io.FileOutputStream((tempDir / "runner.log").toIO))
    RunConfig(
      outputDir = tempDir,
      snapshotDir = tempDir,
      verbose = false,
      output = logStream,
      threads = threads
    )
  }

  def testSerializedConsumersDoNotOverlap(t: TestCaseRun): Unit = {
    t.h1("Serialized share mode: consumers run one at a time even under -p4")

    SerializedOverlapTracker.reset()
    val tempDir = t.tmpDir("serialized-contract")
    val runner = new TestRunner(quietConfig(tempDir, threads = 4))
    val r = runner.runMultipleSuites(List(new SerializedContractHelper))

    val maxConcurrent = SerializedOverlapTracker.maxSeen.get()
    val failed = r.results.count(_.successState == SuccessState.FAIL)
    t.tln(s"tests run: ${r.totalTests}")
    t.tln(s"max concurrent consumers: $maxConcurrent")
    t.tln(s"failed: $failed")

    assert(maxConcurrent == 1,
      s"serialized share mode must run consumers one at a time; saw $maxConcurrent overlap")
    assert(failed == 0,
      "no consumer should fail under serialized access")
  }
}
