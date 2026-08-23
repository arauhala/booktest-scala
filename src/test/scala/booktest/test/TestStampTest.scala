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
                          verifyStamps: Boolean = false,
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
      requireStamps = requireStamps,
      verifyStamps = verifyStamps)
  }

  /** Per-case entries recorded in the manifest (comment and shared-digest
    * lines excluded). */
  private def stamps(tempDir: os.Path): Seq[String] = {
    val f = tempDir / "_stamps.manifest"
    if (!os.exists(f)) Seq.empty
    else os.read.lines(f).map(_.trim).filter(l =>
      l.nonEmpty && !l.startsWith("#") && !l.contains("="))
  }

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
    val f = dir / "m.manifest"
    val d = TestStamp.digests(1)
    val name = "Suite/x"
    val snap = "snapdigest"

    def store = new StampStore(f, d)

    assert(!store.matches(name, snap), "absent manifest must not match")

    os.write.over(f, "this is not a manifest at all")
    assert(!store.matches(name, snap), "malformed must not match")

    os.write.over(f, s"keyVersion=${TestStamp.KeyVersion}\nclasspath=WRONG\nenv=${d.environment}\n$name $snap\n")
    assert(!store.matches(name, snap), "a STALE CLASSPATH must invalidate every entry")

    os.write.over(f, s"keyVersion=${TestStamp.KeyVersion}\nclasspath=${d.classpath}\nenv=${d.environment}\n$name otherdigest\n")
    assert(!store.matches(name, snap), "a different snapshot digest must not match")

    os.write.over(f, "")
    assert(!store.matches(name, snap), "empty must not match")

    val good = store; good.record(name, snap); good.flush()
    assert(new StampStore(f, d).matches(name, snap), "a recorded pass must match on reload")
    t.tln("absent / malformed / stale-classpath / wrong-snapshot / empty all read as MISS")
    t.tln("only a manifest whose SHARED digests and per-case digest both match is a hit")
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
    os.write.over(dir / "_stamps.manifest", "classpath=tampered\n")
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

  /** THE ORACLE. Simulate an incomplete key: forge a stamp claiming a case
    * passes at its current key, when the case actually fails. A from-scratch
    * verifying run must NOTICE — that is the only defence against a key that
    * omits an input, and the reason the verifier must exist before anyone
    * leans on the cache. */
  def testVerifierCatchesALyingStamp(t: TestCaseRun): Unit = {
    t.h1("The verifier catches a stamp that claims a pass the code does not deliver")

    val dir = t.tmpDir("verify")
    os.makeDir.all(dir)

    // Forge exactly the stamp an incomplete key would have left behind: valid
    // for the CURRENT inputs, for a case that does not pass.
    val suiteName = "booktest.test.StampFailer"
    val caseName = "alwaysFails"
    // Derive the suite path exactly as the runner does, rather than hand-writing
    // it — getSuitePath applies test-root stripping, so a guess here would forge
    // a stamp at a path the runner never looks at, and the test would "pass" by
    // testing nothing.
    val cfg = quietConfig(dir, useStamps = true)
    val suitePath = os.RelPath(cfg.getSuitePath(suiteName))
    val manifest = cfg.snapshotDir / "_stamps.manifest"
    val fullName = s"$suiteName/$caseName"
    val forged = new StampStore(manifest, TestStamp.digests(cfg.threads))
    forged.record(fullName, TestStamp.snapshotDigest(cfg.snapshotDir / suitePath, caseName))
    forged.flush()
    t.tln(s"forged a stamp claiming $caseName passes: ${stamps(dir).contains(s"$fullName ${TestStamp.snapshotDigest(cfg.snapshotDir / suitePath, caseName)}")}")

    // With the cache TRUSTED, that lie would be believed and the case skipped.
    val trusting = new TestRunner(cfg).runSuite(new StampFailer())
    t.tln(s"with --stamps, the forged claim is believed: passed=${trusting.results.head.passed}")
    assert(trusting.results.head.passed, "precondition: a valid stamp does cause a skip")

    // The verifier runs it for real and refuses the claim.
    val runner = new TestRunner(quietConfig(dir, verifyStamps = true))
    val verified = runner.runSuite(new StampFailer())
    t.tln(s"with --verify-stamps, the claim is refused: passed=${verified.results.head.passed}")
    t.tln(s"stale stamps reported: ${runner.staleStamps.mkString(", ")}")
    t.tln(s"the lying stamp was deleted: ${stamps(dir).isEmpty}")

    assert(!verified.results.head.passed, "the verifier must report the real verdict")
    assert(runner.staleStamps.nonEmpty, "the verifier must NAME the case whose stamp lied")
    assert(verified.results.head.diff.exists(_.contains("THE CACHE KEY IS INCOMPLETE")),
      "the message must point at the key, not just the test")
    assert(stamps(dir).isEmpty, "a stamp that lied once must be deleted")
  }
}
