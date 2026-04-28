package booktest.examples

import booktest.*

import java.util.concurrent.atomic.AtomicInteger

/** liveResourceSerialized(...) — shared instance, consumers run one at a
  * time, and no reset is called between them. Use when the resource is
  * logically read-only at the consumer level but produces snapshot output
  * that isn't safe to interleave (e.g. shared logger writes, multiline
  * report builders).
  *
  * Differs from `liveResourceWithReset` in two ways: there's no reset
  * closure to write, and the instance is NOT auto-invalidated on consumer
  * failure (same policy as the default `liveResource`).
  */
object SerializedCounter {
  val builds: AtomicInteger = new AtomicInteger(0)
  val closes: AtomicInteger = new AtomicInteger(0)
}

class SerializedCounter extends AutoCloseable {
  SerializedCounter.builds.incrementAndGet()
  private var n = 0
  def increment(): Unit = n += 1
  def value: Int = n
  override def close(): Unit = {
    SerializedCounter.closes.incrementAndGet()
  }
}

class SerializedResourceTests extends TestSuite {

  // Counters observe call order across the whole suite; force sequential.
  override protected def resourceLocks: List[String] = List("SerializedResourceTests")

  val counter: ResourceRef[SerializedCounter] =
    liveResourceSerialized("counter") {
      new SerializedCounter
    }

  test("first", counter) { (t: TestCaseRun, c: SerializedCounter) =>
    c.increment()
    c.increment()
    t.tln(s"value: ${c.value}")
    t.tln(s"builds so far: ${SerializedCounter.builds.get()}")
  }

  test("second", counter) { (t: TestCaseRun, c: SerializedCounter) =>
    // No reset between consumers — value carries over from `first`.
    c.increment()
    t.tln(s"value: ${c.value}")
    t.tln(s"builds so far: ${SerializedCounter.builds.get()}")
  }

  test("third", counter) { (t: TestCaseRun, c: SerializedCounter) =>
    c.increment()
    t.tln(s"value: ${c.value}")
    t.tln(s"builds so far: ${SerializedCounter.builds.get()}")
    t.tln(s"closes so far: ${SerializedCounter.closes.get()}")
  }
}
