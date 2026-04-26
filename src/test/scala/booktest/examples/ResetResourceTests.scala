package booktest.examples

import booktest.*

import java.util.concurrent.atomic.AtomicInteger

/** Phase 3: liveResourceWithReset(...) — shared instance, but the runner
  * serializes consumer access and calls the reset closure between consumers.
  * Build and close happen exactly once.
  */
object ResetCounter {
  val builds: AtomicInteger = new AtomicInteger(0)
  val closes: AtomicInteger = new AtomicInteger(0)
  val resets: AtomicInteger = new AtomicInteger(0)
}

class ResetCounter extends AutoCloseable {
  ResetCounter.builds.incrementAndGet()
  private var n = 0
  def increment(): Unit = n += 1
  def value: Int = n
  def reset(): Unit = {
    n = 0
    ResetCounter.resets.incrementAndGet()
  }
  override def close(): Unit = {
    ResetCounter.closes.incrementAndGet()
  }
}

class ResetResourceTests extends TestSuite {

  // Counters observe call order — force sequential execution.
  override protected def resourceLocks: List[String] = List("ResetResourceTests")

  val counter: ResourceRef[ResetCounter] =
    liveResourceWithReset("counter") {
      new ResetCounter
    } { c => c.reset() }

  test("first", counter) { (t: TestCaseRun, c: ResetCounter) =>
    c.increment()
    c.increment()
    t.tln(s"value: ${c.value}")
    t.tln(s"builds so far: ${ResetCounter.builds.get()}")
    t.tln(s"resets so far: ${ResetCounter.resets.get()}")
  }

  test("second", counter) { (t: TestCaseRun, c: ResetCounter) =>
    // Reset was called between consumers; counter is back to 0.
    c.increment()
    t.tln(s"value: ${c.value}")
    t.tln(s"builds so far: ${ResetCounter.builds.get()}")
    t.tln(s"resets so far: ${ResetCounter.resets.get()}")
  }

  test("third", counter) { (t: TestCaseRun, c: ResetCounter) =>
    c.increment()
    c.increment()
    c.increment()
    t.tln(s"value: ${c.value}")
    t.tln(s"builds so far: ${ResetCounter.builds.get()}")
    t.tln(s"resets so far: ${ResetCounter.resets.get()}")
    t.tln(s"closes so far: ${ResetCounter.closes.get()}")
  }
}
