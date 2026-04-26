package booktest.examples

import booktest.*

import java.util.concurrent.atomic.AtomicInteger

/** Phase 6 failure-mode coverage. Each test exercises one path:
  *
  * - build throws  -> consumer fails clearly, no leaked allocations
  * - close throws  -> swallowed, allocations released, run continues
  * - reset throws  -> instance invalidated, next consumer triggers rebuild
  *
  * The snapshots prove the observable behavior; the listener trace is in
  * `-v` output (not snapshotted, since timings vary).
  */

// Per-class counter objects so suites don't pollute each other's state.

object CloseThrowsCounters {
  val builds: AtomicInteger = new AtomicInteger(0)
  val closes: AtomicInteger = new AtomicInteger(0)
}

class CloseThrowsHandle extends AutoCloseable {
  CloseThrowsCounters.builds.incrementAndGet()
  override def close(): Unit = {
    CloseThrowsCounters.closes.incrementAndGet()
    throw new RuntimeException("boom on close")
  }
}

class CloseThrowingResource extends TestSuite {

  override protected def resourceLocks: List[String] = List("CloseThrowingResource")

  val handle: ResourceRef[CloseThrowsHandle] =
    liveResource("closingHandle") {
      new CloseThrowsHandle
    }

  test("useHandleA", handle) { (t: TestCaseRun, h: CloseThrowsHandle) =>
    t.tln(s"tag: close-throws")
  }

  test("useHandleB", handle) { (t: TestCaseRun, h: CloseThrowsHandle) =>
    // Same instance as A; close happens after this test.
    t.tln(s"tag: close-throws")
    t.tln(s"closes-before-this-test: ${CloseThrowsCounters.closes.get()}")
  }
}

object ResetThrowsCounters {
  val builds: AtomicInteger = new AtomicInteger(0)
  val resets: AtomicInteger = new AtomicInteger(0)
}

class ResetThrowsHandle extends AutoCloseable {
  ResetThrowsCounters.builds.incrementAndGet()
  override def close(): Unit = ()
}

class ResetThrowingResource extends TestSuite {

  override protected def resourceLocks: List[String] = List("ResetThrowingResource")

  val handle: ResourceRef[ResetThrowsHandle] =
    liveResourceWithReset("resettingHandle") {
      new ResetThrowsHandle
    } { _ =>
      // First call to reset (between consumers) throws.
      // The runner should treat the instance as invalidated and rebuild.
      val n = ResetThrowsCounters.resets.incrementAndGet()
      if (n == 1) throw new RuntimeException("boom on reset")
    }

  test("first", handle) { (t: TestCaseRun, h: ResetThrowsHandle) =>
    t.tln("tag: reset-throws")
    t.tln(s"builds: ${ResetThrowsCounters.builds.get()}")
  }

  test("second", handle) { (t: TestCaseRun, h: ResetThrowsHandle) =>
    // First reset threw -> instance invalidated -> fresh build for this test.
    t.tln("tag: reset-throws")
    t.tln(s"builds: ${ResetThrowsCounters.builds.get()}")
    t.tln(s"resets: ${ResetThrowsCounters.resets.get()}")
  }

  test("third", handle) { (t: TestCaseRun, h: ResetThrowsHandle) =>
    // Reset between second and third should succeed (only first reset throws).
    t.tln("tag: reset-throws")
    t.tln(s"builds: ${ResetThrowsCounters.builds.get()}")
    t.tln(s"resets: ${ResetThrowsCounters.resets.get()}")
  }
}
