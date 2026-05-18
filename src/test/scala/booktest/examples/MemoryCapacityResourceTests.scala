package booktest.examples

import booktest.*

/** Documents `memoryCapacity` — the GC-forcing variant of `capacity`.
  *
  * Use this declaration form when the budget represents JVM heap reserved
  * by a shared live resource. On release, the runner forces a full GC so
  * the next consumer's `build` doesn't allocate on top of the previous
  * instance's still-resident bytes. See `ResourceManager.memoryCapacity`
  * for the motivating race and caveats.
  *
  * The unit test `booktest.test.MemoryCapacityGcTest` proves the
  * `System.gc()` call actually fires. This example documents the API
  * surface and the capacity reclamation behaviour.
  */

class MockJvmResidentServer(allocatedMb: Double, label: String)
    extends AutoCloseable {
  override def close(): Unit = ()
  def describe: String = s"$label reserving $allocatedMb MB of heap"
}

class MemoryCapacityResourceTests extends TestSuite {

  // Capacity state is global; force sequential so the "ram in use"
  // snapshot is order-stable.
  override protected def resourceLocks: List[String] =
    List("MemoryCapacityResourceTests")

  // 2 GB JVM heap budget shared across resources declared in this suite.
  val heap: ResourceCapacity[Double] = memoryCapacity("exampleJvmHeap", 2048.0)

  val firstServer: ResourceRef[MockJvmResidentServer] =
    liveResource("firstServer", heap.reserve(1500.0)) { (mb: Double) =>
      new MockJvmResidentServer(mb, "first")
    }

  val secondServer: ResourceRef[MockJvmResidentServer] =
    liveResource("secondServer", heap.reserve(1500.0)) { (mb: Double) =>
      new MockJvmResidentServer(mb, "second")
    }

  test("firstConsumer", firstServer) {
    (t: TestCaseRun, server: MockJvmResidentServer) =>
      t.tln(s"resource: ${server.describe}")
      t.tln(s"heap in use: ${heap.used} / ${heap.capacity}")
  }

  // The runner closes `firstServer` before building `secondServer`
  // (both reserve 1500 MB out of 2048; they cannot coexist). On close,
  // memoryCapacity's release forces a full GC, so `secondServer` builds
  // into a heap that has actually given back the first server's bytes.
  test("secondConsumer", secondServer) {
    (t: TestCaseRun, server: MockJvmResidentServer) =>
      t.tln(s"resource: ${server.describe}")
      t.tln(s"heap in use: ${heap.used} / ${heap.capacity}")
  }
}
