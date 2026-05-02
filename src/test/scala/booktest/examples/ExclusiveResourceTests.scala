package booktest.examples

import booktest.*

import java.util.concurrent.atomic.AtomicInteger

/** Phase 3: exclusiveResource(...) — each consumer gets its own instance.
  * Build and close happen per-consumer; nothing is shared.
  */
object ExclusiveCounter {
  val builds: AtomicInteger = new AtomicInteger(0)
  val closes: AtomicInteger = new AtomicInteger(0)
}

class ExclusiveCounter extends AutoCloseable {
  ExclusiveCounter.builds.incrementAndGet()
  private var n = 0
  def increment(): Unit = n += 1
  def value: Int = n
  override def close(): Unit = {
    ExclusiveCounter.closes.incrementAndGet()
  }
}

class ExclusiveResourceTests extends TestSuite {

  // Counters observe call order — force sequential execution.
  override protected def resourceLocks: List[String] = List("ExclusiveResourceTests")

  val counter: ResourceRef[ExclusiveCounter] =
    exclusiveResource("counter") {
      new ExclusiveCounter
    }

  test("incrementOnce", counter) { (t: TestCaseRun, c: ExclusiveCounter) =>
    c.increment()
    t.tln(s"value: ${c.value}")
    t.tln(s"builds so far: ${ExclusiveCounter.builds.get()}")
  }

  test("incrementTwice", counter) { (t: TestCaseRun, c: ExclusiveCounter) =>
    c.increment()
    c.increment()
    // Second consumer gets a FRESH instance since this is exclusive.
    // Counter is independent — value is 2, not 4.
    t.tln(s"value: ${c.value}")
    t.tln(s"builds so far: ${ExclusiveCounter.builds.get()}")
    // Closes from prior consumer are visible by now.
    t.tln(s"closes so far: ${ExclusiveCounter.closes.get()}")
  }
}
