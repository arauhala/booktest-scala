package booktest.examples

import booktest.*

/** Phase 3 + Capacity: ResourceCapacity allocates numeric amounts (e.g. RAM
  * in MB) up to a configured cap. Capacity reservations appear in
  * `liveResource(...)` dep lists via `cap.reserve(amount)`. The amount is
  * held until the live resource closes.
  */

class MockMemoryUser(allocatedMb: Double, label: String) extends AutoCloseable {
  override def close(): Unit = ()
  def describe: String = s"$label using $allocatedMb MB"
}

class CapacityResourceTests extends TestSuite {

  // The "ram in use" snapshot reads global capacity state, which is
  // order-sensitive — force sequential.
  override protected def resourceLocks: List[String] = List("CapacityResourceTests")

  // 4 GB RAM capacity (units arbitrary — using MB-as-Double here).
  val ram: ResourceCapacity[Double] = capacity("ram", 4096.0)

  // Reserve 1024 MB for the duration of this resource's life.
  val small: ResourceRef[MockMemoryUser] =
    liveResource("small", ram.reserve(1024.0)) { (mb: Double) =>
      new MockMemoryUser(mb, "small")
    }

  // Reserve 2048 MB.
  val large: ResourceRef[MockMemoryUser] =
    liveResource("large", ram.reserve(2048.0)) { (mb: Double) =>
      new MockMemoryUser(mb, "large")
    }

  test("smallConsumer", small) { (t: TestCaseRun, m: MockMemoryUser) =>
    t.tln(s"resource: ${m.describe}")
    t.tln(s"ram in use: ${ram.used} / ${ram.capacity}")
  }

  test("largeConsumer", large) { (t: TestCaseRun, m: MockMemoryUser) =>
    t.tln(s"resource: ${m.describe}")
    t.tln(s"ram in use: ${ram.used} / ${ram.capacity}")
  }

  test("bothConsumer", small, large) {
    (t: TestCaseRun, s: MockMemoryUser, l: MockMemoryUser) =>
      t.tln(s"small: ${s.describe}")
      t.tln(s"large: ${l.describe}")
      t.tln(s"ram in use: ${ram.used} / ${ram.capacity}")
  }
}
