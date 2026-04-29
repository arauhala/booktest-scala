package booktest.test

import booktest._
import java.util.concurrent.{CountDownLatch, Executors, TimeUnit}

/** Meta test: stress-test [[DependencyCache]] under concurrent put/get to
  * catch the lost-write race that surfaces under -pN.
  *
  * Background: prior to 0.4.2, `DependencyCache.memoryCache` was a plain
  * `var Map[String, Any]` mutated without synchronization. Two workers
  * doing `cache.put("a", v1)` / `cache.put("b", v2)` concurrently could
  * each read the same baseline map, append their own entry, and write the
  * resulting map back — silently dropping whichever write landed first.
  * Symptoms downstream: `Dependency 'X' failed when auto-run`, missing
  * tables in producer/consumer chains, sporadic None.get on cached
  * values.
  *
  * The test pounds on the cache from many threads at once and asserts
  * every put is observable afterwards. Without synchronization this
  * fails immediately under -p4. */
class DependencyCacheConcurrencyTest extends TestSuite {

  def testPutsArePreservedUnderConcurrency(t: TestCaseRun): Unit = {
    t.h1("DependencyCache: concurrent puts")

    val cache = new DependencyCache()
    val numThreads = 16
    val keysPerThread = 200
    val totalKeys = numThreads * keysPerThread

    val pool = Executors.newFixedThreadPool(numThreads)
    val ready = new CountDownLatch(numThreads)
    val go = new CountDownLatch(1)
    val done = new CountDownLatch(numThreads)

    for (tid <- 0 until numThreads) {
      pool.submit(new Runnable {
        override def run(): Unit = {
          ready.countDown()
          go.await()
          try {
            for (i <- 0 until keysPerThread) {
              cache.put(s"t$tid/key$i", s"v$tid-$i")
            }
          } finally done.countDown()
        }
      })
    }

    ready.await()
    go.countDown()
    val finished = done.await(30, TimeUnit.SECONDS)
    pool.shutdown()
    pool.awaitTermination(5, TimeUnit.SECONDS)

    t.tln(s"workers finished: $finished")
    t.tln(s"expected entries: $totalKeys")

    // Every put must be observable. A dropped write would show up as a
    // missing key here.
    var missing = 0
    var mismatched = 0
    for (tid <- 0 until numThreads; i <- 0 until keysPerThread) {
      val key = s"t$tid/key$i"
      cache.get[String](key) match {
        case None => missing += 1
        case Some(v) if v != s"v$tid-$i" => mismatched += 1
        case _ => ()
      }
    }

    t.tln(s"missing keys: $missing")
    t.tln(s"mismatched values: $mismatched")
    assert(finished, "workers must finish within timeout")
    assert(missing == 0, s"$missing puts were lost (lost-write race)")
    assert(mismatched == 0, s"$mismatched values were corrupted")

    t.tln("PASS: every concurrent put was preserved")
  }

  def testInterleavedReadsAndWrites(t: TestCaseRun): Unit = {
    t.h1("DependencyCache: interleaved reads and writes")

    // Half the threads write, half read what's been written. Reader threads
    // must never observe an inconsistent map (e.g. ConcurrentModification or
    // a stale snapshot that loses an already-published key).
    val cache = new DependencyCache()
    val writers = 8
    val readers = 8
    val keysPerWriter = 250

    // Pre-populate so readers always have something to find.
    for (k <- 0 until 50) cache.put(s"seed$k", s"seed-v$k")

    val pool = Executors.newFixedThreadPool(writers + readers)
    val go = new CountDownLatch(1)
    val done = new CountDownLatch(writers + readers)

    @volatile var readerObservedSeed = 0L
    @volatile var readerExceptions = 0

    for (wid <- 0 until writers) {
      pool.submit(new Runnable {
        override def run(): Unit = {
          go.await()
          try {
            for (i <- 0 until keysPerWriter) {
              cache.put(s"w$wid/k$i", s"w$wid-$i")
            }
          } finally done.countDown()
        }
      })
    }
    for (rid <- 0 until readers) {
      pool.submit(new Runnable {
        override def run(): Unit = {
          go.await()
          try {
            // Spin reading well-known seed keys; if the map is being copied
            // unsafely on the write path, an iterator or .get can blow up.
            for (_ <- 0 until 1000; k <- 0 until 50) {
              try cache.get[String](s"seed$k").foreach(_ => readerObservedSeed += 1)
              catch { case _: Throwable => readerExceptions += 1 }
            }
          } finally done.countDown()
        }
      })
    }

    go.countDown()
    val finished = done.await(30, TimeUnit.SECONDS)
    pool.shutdown()
    pool.awaitTermination(5, TimeUnit.SECONDS)

    t.tln(s"workers finished: $finished")
    t.tln(s"reader exceptions: $readerExceptions")
    t.tln(s"reader observed at least one seed: ${readerObservedSeed > 0}")

    // After the storm: every writer's full set must still be visible.
    var missing = 0
    for (wid <- 0 until writers; i <- 0 until keysPerWriter) {
      if (cache.get[String](s"w$wid/k$i").isEmpty) missing += 1
    }
    t.tln(s"missing writer keys: $missing")
    assert(finished, "workers must finish within timeout")
    assert(readerExceptions == 0,
      s"reader saw $readerExceptions exceptions — concurrent read of unsync'd Map")
    assert(missing == 0, s"$missing writer puts were lost")

    t.tln("PASS: reads and writes are consistent under interleave")
  }
}
