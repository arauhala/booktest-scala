package booktest.test

import booktest.*

import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}

/** Helper suite for [[LiveResourceProducerOrderingTest]].
  *
  * The producer creates a fresh subdirectory under its `tmpDir` on every
  * run (the dir name embeds a fresh UUID), writes a marker file inside,
  * and returns the dir's full path. The live resource captures that
  * path. The consumer compares its handle's `path` to the in-run
  * producer's actual path.
  *
  * Across runs, the path string differs (different UUID), so a consumer
  * that resolves the producer via a stale `.bin` from a prior run will
  * hold a path that is ≠ the in-run producer's path. That's the
  * Issue 1 signal.
  *
  * The producer sleeps for ~500ms so the bug fires deterministically
  * under -p2: the parallel scheduler dispatches the consumer
  * concurrently (today: it doesn't walk the live resource's transitive
  * `TestDep` to find the producer in `tc.dependencies`'s closure), and
  * the consumer's `liveResource.build` closure falls back to the disk
  * `.bin` from run 1 because the in-run producer hasn't completed
  * yet. */
class ProducerOrderingHelper extends TestSuite {

  val producer: TestRef[String] = test("producer") { (t: TestCaseRun) =>
    Thread.sleep(500)
    val unique = java.util.UUID.randomUUID().toString
    val dir = t.tmpDir(s"data-$unique")
    os.write.over(dir / "marker", unique)
    val path = dir.toString
    ProducerOrderingHelper.lastProducerPath.set(path)
    t.tln(s"producer wrote marker (uuid len ${unique.length})")
    path
  }

  case class StateHandle(path: String) extends AutoCloseable {
    override def close(): Unit = ()
  }

  val handle: ResourceRef[StateHandle] =
    liveResource("stateHandle", producer) { (path: String) =>
      StateHandle(path)
    }

  test("consumer", handle) { (t: TestCaseRun, h: StateHandle) =>
    // Snapshot both values atomically as far as the test thread is
    // concerned. After this point, lastProducerPath could change due to
    // a future run, but `consumerPath` is what *this* invocation saw.
    val consumerPath = h.path
    val producerPathInRun = ProducerOrderingHelper.lastProducerPath.get()
    val matches = consumerPath == producerPathInRun
    ProducerOrderingHelper.lastConsumerMatched.set(matches)
    ProducerOrderingHelper.lastConsumerPath.set(consumerPath)
    t.tln(s"consumer path matches in-run producer path: $matches")
  }
}

object ProducerOrderingHelper {
  /** Path the most recently invoked producer returned. */
  val lastProducerPath = new AtomicReference[String]("")
  /** Path the most recently invoked consumer received via the live
    * resource handle. */
  val lastConsumerPath = new AtomicReference[String]("")
  /** Did the most recent consumer's handle path equal its in-run
    * producer's path? */
  val lastConsumerMatched = new AtomicBoolean(false)

  def reset(): Unit = {
    lastProducerPath.set("")
    lastConsumerPath.set("")
    lastConsumerMatched.set(false)
  }
}

class LiveResourceProducerOrderingTest extends TestSuite {

  private def quietConfig(tempDir: os.Path,
                          threads: Int = 1,
                          updateSnapshots: Boolean = false): RunConfig = {
    val logStream = new java.io.PrintStream(
      new java.io.FileOutputStream((tempDir / s"runner-p$threads.log").toIO))
    RunConfig(
      outputDir = tempDir,
      snapshotDir = tempDir,
      verbose = false,
      output = logStream,
      threads = threads,
      updateSnapshots = updateSnapshots
    )
  }

  /** Issue 1 reproduction. */
  def testConsumerWaitsForTransitiveProducer(t: TestCaseRun): Unit = {
    t.h1("Live-resource consumer must wait for its transitive TestRef producer")
    val tempDir = t.tmpDir("issue1")

    // ----- Run 1: sequential, populates .bin so run 2 has a stale baseline.
    ProducerOrderingHelper.reset()
    val r1Helper = new ProducerOrderingHelper
    val runner1 = new TestRunner(quietConfig(tempDir, threads = 1, updateSnapshots = true))
    runner1.runMultipleSuites(List(r1Helper))
    val run1Match = ProducerOrderingHelper.lastConsumerMatched.get()
    t.tln(s"run 1 (sequential, snapshots populated): match = $run1Match")
    assert(run1Match, "run 1 (sequential) consumer must see in-run producer's path")

    // The producer's .bin now points at a path containing a UUID generated
    // in run 1. That path is the stale fixture run 2 sees on disk.

    // ----- Run 2: parallel. With Issue 1 unfixed, the consumer is judged
    // ready immediately (the scheduler doesn't walk into stateHandle's
    // TestDep closure) and races against the producer's in-run output.
    ProducerOrderingHelper.reset()
    val r2Helper = new ProducerOrderingHelper
    val runner2 = new TestRunner(quietConfig(tempDir, threads = 2))
    val r2 = runner2.runMultipleSuites(List(r2Helper))

    val run2Match = ProducerOrderingHelper.lastConsumerMatched.get()
    val consumerPath = ProducerOrderingHelper.lastConsumerPath.get()
    val producerPath = ProducerOrderingHelper.lastProducerPath.get()
    t.tln(s"run 2 (-p2): match = $run2Match")
    t.tln(s"consumer path == producer path: ${consumerPath == producerPath}")
    val consumerResult = r2.results.find(_.testName.endsWith("/consumer"))
    consumerResult.foreach { c =>
      t.tln(s"run 2 consumer state: ${c.successState}")
    }

    assert(run2Match,
      s"run 2 (-p2) consumer's handle path ($consumerPath) must equal " +
      s"in-run producer's path ($producerPath); mismatch indicates the " +
      s"consumer was dispatched before the producer completed (Issue 1)")
  }
}
