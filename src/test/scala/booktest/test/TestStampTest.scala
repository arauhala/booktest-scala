package booktest.test

import booktest._

/** Helper: a case that always passes once its snapshot is accepted. */
class StampPasser extends TestSuite {
  def testStable(t: TestCaseRun): Unit = {
    t.tln("stable output")
  }
}

/** Helper: a case that explicitly fails. It must never leave a stamp behind. */
class StampFailer extends TestSuite {
  def testAlwaysFails(t: TestCaseRun): Unit = {
    t.tln("some output")
    t.fail("intentional failure")
  }
}

/**
 * Meta test for the content-addressed result cache (see `TestStamp`).
 *
 * Every case here asserts a way the cache must NEVER produce a false pass.
 * That is the only property worth testing hard: a cache that skips too little
 * costs runner minutes, while a cache that skips too much reports success for
 * a tree nobody ran. The asymmetry is the whole design.
 */
class TestStampTest extends TestSuite {

  private def quietConfig(tempDir: os.Path,
                          useStamps: Boolean = false,
                          requireStamps: Boolean = false,
                          accept: Boolean = false): RunConfig = {
    val logStream = new java.io.PrintStream(
      new java.io.FileOutputStream((tempDir / "runner.log").toIO))
    RunConfig(
      outputDir = tempDir,
      snapshotDir = tempDir,
      verbose = false,
      output = logStream,
      // Accept only on the priming run. Auto-accept must NOT be on when we are
      // testing failure or cache behaviour: it rewrites the snapshot and reports
      // UPDATED even for an explicitly failed case, which would mask both.
      updateSnapshots = accept,
      useStamps = useStamps,
      requireStamps = requireStamps)
  }

  private def stamps(tempDir: os.Path): Seq[os.Path] =
    if (os.exists(tempDir / "_stamps"))
      os.walk(tempDir / "_stamps").filter(_.last.endsWith(".stamp"))
    else Seq.empty

  /** The key must change when an input changes — here, the accepted snapshot.
    * If it did not, editing an expectation would keep the stale pass. */
  def testKeyCoversSnapshots(t: TestCaseRun): Unit = {
    t.h1("The key covers the accepted snapshot")

    val dir = t.tmpDir("key-snap")
    os.makeDir.all(dir)
    val d = TestStamp.digests(1)

    val before = d.keyFor("Suite", "case", dir)
    os.write.over(dir / "case.md", "expected A")
    val afterA = d.keyFor("Suite", "case", dir)
    os.write.over(dir / "case.md", "expected B")
    val afterB = d.keyFor("Suite", "case", dir)

    t.tln(s"no snapshot : ${before.take(12)}")
    t.tln(s"snapshot A  : ${afterA.take(12)}")
    t.tln(s"snapshot B  : ${afterB.take(12)}")

    assert(before != afterA, "adding a snapshot must change the key")
    assert(afterA != afterB, "changing snapshot CONTENT must change the key")
  }

  /** -p changes results in some codebases, not just speed, so it is an input. */
  def testKeyCoversParallelism(t: TestCaseRun): Unit = {
    t.h1("The key covers parallelism")
    val dir = t.tmpDir("key-par")
    os.makeDir.all(dir)
    val k1 = TestStamp.digests(1).keyFor("Suite", "case", dir)
    val k4 = TestStamp.digests(4).keyFor("Suite", "case", dir)
    t.tln(s"-p1: ${k1.take(12)}")
    t.tln(s"-p4: ${k4.take(12)}")
    assert(k1 != k4, "-p must be part of the key")
  }

  /** Absent, malformed and mismatched stamps must all read as "run it".
    * This is rule #1: there is no path where a bad stamp means "passed". */
  def testCorruptStampNeverMatches(t: TestCaseRun): Unit = {
    t.h1("A corrupt or absent stamp never matches")

    val dir = t.tmpDir("corrupt")
    os.makeDir.all(dir)
    val f = dir / "x.stamp"
    val key = "abc123"

    t.reviewln("absent stamp does not match", TestStamp.matches(f, key) == false)
    assert(!TestStamp.matches(f, key), "absent must not match")

    os.write.over(f, "this is not a stamp at all")
    assert(!TestStamp.matches(f, key), "malformed must not match")

    os.write.over(f, "key=someotherkey\n")
    assert(!TestStamp.matches(f, key), "different key must not match")

    os.write.over(f, "")
    assert(!TestStamp.matches(f, key), "empty must not match")

    TestStamp.write(f, key, "Suite", "x")
    assert(TestStamp.matches(f, key), "a stamp written for this key must match")
    t.tln("absent / malformed / mismatched / empty all read as MISS; only an exact key matches")
  }

  /** A failing case must leave no stamp — otherwise the next run skips a case
    * that has never passed, which is precisely the false pass we must not have. */
  def testFailingTestWritesNoStamp(t: TestCaseRun): Unit = {
    t.h1("A failing case leaves no stamp")

    val dir = t.tmpDir("fail-no-stamp")
    // No auto-accept: we want the genuine FAIL verdict from t.fail().
    val runner = new TestRunner(quietConfig(dir, useStamps = true))
    val result = runner.runSuite(new StampFailer())

    t.tln(s"passed: ${result.results.head.passed}")
    t.tln(s"stamps written: ${stamps(dir).length}")
    assert(!result.results.head.passed, "the helper must fail")
    assert(stamps(dir).isEmpty, "a failing case must not write a stamp")
  }

  /** The happy path: pass once, then the same inputs skip. */
  def testSecondRunIsCached(t: TestCaseRun): Unit = {
    t.h1("A second run with identical inputs is served from the cache")

    val dir = t.tmpDir("cached")

    // Prime the snapshot with stamps OFF, so the next run is a genuine compare.
    new TestRunner(quietConfig(dir, accept = true)).runSuite(new StampPasser())

    val first = new TestRunner(quietConfig(dir, useStamps = true)).runSuite(new StampPasser())
    t.tln(s"first stamped run passed: ${first.results.head.passed} (${first.results.head.successState}), stamps: ${stamps(dir).length}")
    assert(first.results.head.passed, "a run against an accepted snapshot should pass")
    assert(stamps(dir).nonEmpty, "a passing case must write a stamp")

    val second = new TestRunner(quietConfig(dir, useStamps = true)).runSuite(new StampPasser())
    t.tln(s"second run passed: ${second.results.head.passed}, duration: ${second.results.head.durationMs} ms")
    assert(second.results.head.passed, "cached run reports pass")

    // Now corrupt the stamp: the cache must fall back to RUNNING, not to passing.
    stamps(dir).foreach(f => os.write.over(f, "key=tampered\n"))
    val third = new TestRunner(quietConfig(dir, useStamps = true)).runSuite(new StampPasser())
    t.tln(s"after tampering, still passed by RE-RUNNING: ${third.results.head.passed}")
    assert(third.results.head.passed, "a tampered stamp must cause a re-run, and the test still passes")
  }

  /** --require-stamps must FAIL on a miss, never pass. It is CI's policy for
    * "I will not run the suite; show me the proof" — strictly safer than
    * running, because it cannot turn a miss into a pass either. */
  def testRequireStampsFailsOnMiss(t: TestCaseRun): Unit = {
    t.h1("--require-stamps fails on a miss, and says how to fix it")

    val dir = t.tmpDir("require")
    val runner = new TestRunner(quietConfig(dir, requireStamps = true))
    val result = runner.runSuite(new StampPasser())
    val r = result.results.head

    t.tln(s"passed: ${r.passed}")
    t.tln(s"state : ${r.successState}")
    assert(!r.passed, "a miss under --require-stamps must NOT pass")
    assert(r.diff.exists(_.contains("Run it locally")),
      "the failure must tell the developer how to produce the stamp")
    assert(stamps(dir).isEmpty, "--require-stamps must not write stamps itself")
    t.tln("")
    t.tln("failure message:")
    r.diff.foreach(d => d.linesIterator.foreach(l => t.tln(s"  $l")))
  }
}
