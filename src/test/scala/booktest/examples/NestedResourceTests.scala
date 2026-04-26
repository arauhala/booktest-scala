package booktest.examples

import booktest.*

import java.util.concurrent.atomic.AtomicInteger

/** Regression coverage for transitive-refcount bug.
  *
  * X depends on Y. A and B both consume X (via test deps). With the bug, Y's
  * refcount stays at 0 (no test directly mentions Y), so Y closes after the
  * first transient acquire and is rebuilt for the second. With the fix, Y is
  * built once and survives until X's last consumer finishes.
  */
object NestedCounters {
  val yBuilds: AtomicInteger = new AtomicInteger(0)
  val yCloses: AtomicInteger = new AtomicInteger(0)
}

class YHandle extends AutoCloseable {
  NestedCounters.yBuilds.incrementAndGet()
  override def close(): Unit = NestedCounters.yCloses.incrementAndGet()
}

class XHandle(val y: YHandle) extends AutoCloseable {
  override def close(): Unit = ()
}

class NestedResourceTests extends TestSuite {

  // Counters observe call order — force sequential execution.
  override protected def resourceLocks: List[String] = List("NestedResourceTests")

  val y: ResourceRef[YHandle] = liveResource("y") { new YHandle }

  val x: ResourceRef[XHandle] = liveResource("x", y) { (yh: YHandle) =>
    new XHandle(yh)
  }

  test("aUsesX", x) { (t: TestCaseRun, xh: XHandle) =>
    t.tln(s"y builds during a: ${NestedCounters.yBuilds.get()}")
  }

  test("bUsesX", x) { (t: TestCaseRun, xh: XHandle) =>
    // With transitive refcounting, y stays alive across both consumers of x.
    // y builds should still be 1, y closes still 0.
    t.tln(s"y builds during b: ${NestedCounters.yBuilds.get()}")
    t.tln(s"y closes during b: ${NestedCounters.yCloses.get()}")
  }
}

/** Bug #2: an Exclusive resource that depends on a shared resource. Each
  * consumer gets its own X (exclusive), but every X holds the same Y
  * (shared). Y should be built once and close after the LAST X closes —
  * not after the first.
  */
object NestedExclusiveCounters {
  val yBuilds: java.util.concurrent.atomic.AtomicInteger = new java.util.concurrent.atomic.AtomicInteger(0)
  val yCloses: java.util.concurrent.atomic.AtomicInteger = new java.util.concurrent.atomic.AtomicInteger(0)
}

class NestedY extends AutoCloseable {
  NestedExclusiveCounters.yBuilds.incrementAndGet()
  override def close(): Unit = NestedExclusiveCounters.yCloses.incrementAndGet()
}

class NestedXExclusive(val y: NestedY) extends AutoCloseable {
  override def close(): Unit = ()
}

class NestedExclusiveTests extends TestSuite {

  override protected def resourceLocks: List[String] = List("NestedExclusiveTests")

  val y: ResourceRef[NestedY] = liveResource("y") { new NestedY }

  val x: ResourceRef[NestedXExclusive] =
    exclusiveResource("x", y) { (yh: NestedY) => new NestedXExclusive(yh) }

  test("first", x) { (t: TestCaseRun, xh: NestedXExclusive) =>
    t.tln(s"y builds during first: ${NestedExclusiveCounters.yBuilds.get()}")
  }

  test("second", x) { (t: TestCaseRun, xh: NestedXExclusive) =>
    // y is shared across X instances; should still be 1 build, 0 closes.
    t.tln(s"y builds during second: ${NestedExclusiveCounters.yBuilds.get()}")
    t.tln(s"y closes during second: ${NestedExclusiveCounters.yCloses.get()}")
  }
}
