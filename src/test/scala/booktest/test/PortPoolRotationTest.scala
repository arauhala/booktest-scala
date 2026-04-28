package booktest.test

import booktest.*

/** Verifies the PortPool rotates ports instead of always returning the
  * lowest-numbered free port, and that a just-released port is not
  * re-issued during the cooldown window. Pre-fix, two-port-at-a-time
  * acquire/release patterns under -p2 reused 10000/10001 forever, which
  * defeated the cooldown the kernel needs after a listener teardown. */
class PortPoolRotationTest extends TestSuite {

  def testRotatesAcrossReleases(t: TestCaseRun): Unit = {
    t.h1("PortPool acquires rotate, not stick to the lowest port")

    // Small range so we can reason deterministically. cooldownMs=0 isolates
    // the LRU-by-release-timestamp policy from the cooldown timer.
    val pool = new PortPool(basePort = 11050, maxPort = 11055, cooldownMs = 0L)

    val a = pool.acquire(); val b = pool.acquire(); val c = pool.acquire()
    t.tln(s"first three: $a, $b, $c")
    assert(List(a, b, c) == List(11050, 11051, 11052),
      s"first acquires should walk from base; got $a, $b, $c")

    // Release a, then b. Acquire one more — pre-fix this would always
    // return `a` (the lowest free port); post-fix the pool prefers a
    // never-used port even though `a` is numerically smaller.
    pool.release(a)
    pool.release(b)
    val d = pool.acquire()
    t.tln(s"after releasing $a and $b, next acquire: $d")
    assert(d != a && d != b,
      s"rotation should prefer never-used ports; got $d (just released $a, $b)")

    // Drain the remaining never-used ports (11053..11055 minus d).
    val drained = scala.collection.mutable.ListBuffer[Int]()
    drained ++= (11053 to 11055).filter(_ != d).map(_ => pool.acquire())
    t.tln(s"drained never-used: ${drained.toList.sorted}")

    // Now the only candidates are the released ones. cooldownMs=0 means
    // they're all eligible; lowest score (= longest released) wins, and
    // `a` was released before `b`.
    val first = pool.acquire()
    t.tln(s"first reused port: $first (should be $a)")
    assert(first == a,
      s"longest-released should come back first; expected $a got $first")

    val second = pool.acquire()
    t.tln(s"second reused port: $second (should be $b)")
    assert(second == b,
      s"second longest-released next; expected $b got $second")
  }

  def testCooldownBlocksImmediateReissue(t: TestCaseRun): Unit = {
    t.h1("PortPool cooldown blocks re-issuing a just-released port")

    // Tiny range (single port) so we MUST reuse it. 100ms cooldown is
    // long enough that an immediate reacquire has to wait observably.
    val pool = new PortPool(basePort = 11099, maxPort = 11099, cooldownMs = 100L)

    val p = pool.acquire()
    pool.release(p)
    val t0 = System.currentTimeMillis()
    val p2 = pool.acquire()
    val elapsed = System.currentTimeMillis() - t0
    t.tln(s"first port: $p, second port: $p2")
    t.tln(s"reissue waited at least ~100ms: ${elapsed >= 80}")
    assert(p2 == p, "single-port range must reuse the only port")
    assert(elapsed >= 80,
      s"reacquire of just-released port should wait for cooldown; elapsed=${elapsed}ms")
  }
}
