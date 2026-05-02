package booktest.examples

import booktest.*

/** Regression for SharedWithReset hang: when a withReset consumer fails,
  * the close path runs while the consumer still holds the per-instance
  * lock. If the unlock doesn't happen, the next consumer waits forever.
  *
  * The test exercises: first consumer fails -> instance invalidated and
  * closed -> second consumer must complete (would hang without the fix).
  */
class HangHandle extends AutoCloseable {
  override def close(): Unit = ()
}

class ResetHangTests extends TestSuite {

  override protected def resourceLocks: List[String] = List("ResetHangTests")

  val handle: ResourceRef[HangHandle] =
    liveResourceWithReset("hangHandle") {
      new HangHandle
    } { _ => () }

  test("failingFirst", handle) { (t: TestCaseRun, h: HangHandle) =>
    t.tln("about to fail")
    t.fail("intentional first-consumer failure")
  }

  test("succeedingSecond", handle) { (t: TestCaseRun, h: HangHandle) =>
    // Without the fix, this hangs on the instance lock.
    // With the fix, the prior consumer's failure released the lock,
    // closed the instance, and a fresh build runs for this consumer.
    t.tln("second consumer ran")
  }
}
