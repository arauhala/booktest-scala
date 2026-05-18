package booktest.test

import booktest.*

import java.lang.management.ManagementFactory
import scala.jdk.CollectionConverters.*

/** Verifies that `memoryCapacity.release` invokes `System.gc()` while
  * `capacity.release` does not.
  *
  * Background: shared JVM-resident live resources (e.g. an in-process
  * server reserving 1500 MB of "ram" capacity) drop references in
  * `close()` but the bytes stay on the heap until GC runs. Under `-pN`
  * the next consumer's `build` starts the instant refcount hits zero —
  * before GC has reclaimed the previous instance — and allocates into a
  * still-full heap. memoryCapacity closes that window by forcing a full
  * GC under the capacity lock on release. */
class MemoryCapacityGcTest extends TestSuite {

  private def gcCount(): Long =
    ManagementFactory.getGarbageCollectorMXBeans.asScala
      .map(_.getCollectionCount).sum

  def testForcedGcOnRelease(t: TestCaseRun): Unit = {
    t.h1("memoryCapacity.release forces GC; capacity.release does not")

    val iters = 10

    val memCap =
      new DoubleCapacity("memCapGcTest", 100.0, forceGcOnRelease = true)
    val memBefore = gcCount()
    for (_ <- 0 until iters) {
      memCap.acquire(50.0)
      memCap.release(50.0)
    }
    val memDelta = gcCount() - memBefore

    val logCap = new DoubleCapacity("logCapGcTest", 100.0)
    val logBefore = gcCount()
    for (_ <- 0 until iters) {
      logCap.acquire(50.0)
      logCap.release(50.0)
    }
    val logDelta = gcCount() - logBefore

    // Snapshot only stable predicates — actual gc counts vary by JVM,
    // GC algorithm, and background activity. The hard contract is in
    // the asserts below.
    t.tln(s"memoryCapacity forced gc on every release ($iters/$iters): " +
      s"${memDelta >= iters}")
    t.tln(s"regular capacity did not force gc ($iters releases, delta < $iters): " +
      s"${logDelta < iters}")
    t.tln(s"memoryCapacity gc count strictly exceeds regular: " +
      s"${memDelta > logDelta}")

    assert(memDelta >= iters,
      s"memoryCapacity.release should force a GC per call; " +
        s"got delta=$memDelta over $iters releases")
    assert(logDelta < iters,
      s"regular capacity.release should not force GC; " +
        s"got delta=$logDelta over $iters releases " +
        s"(background GC alone shouldn't reach $iters)")
  }
}
