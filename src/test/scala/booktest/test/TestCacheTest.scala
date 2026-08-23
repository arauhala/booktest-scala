package booktest.test

import booktest._

/** Helper: a case that always passes once its snapshot is accepted. */
class CachePasser extends TestSuite {
  def testStable(t: TestCaseRun): Unit = {
    t.tln("stable output")
  }
}

/** Helper: a case that explicitly fails. It must never leave an entry behind. */
class CacheFailer extends TestSuite {
  def testAlwaysFails(t: TestCaseRun): Unit = {
    t.tln("some output")
    t.fail("intentional failure")
  }
}

/**
 * Meta test for the content-addressed result cache (see `Fingerprint`).
 *
 * Every case here asserts a way the cache must NEVER produce a false pass.
 * That is the only property worth testing hard: a cache that skips too little
 * costs runner minutes, while a cache that skips too much reports success for
 * a tree nobody ran. The asymmetry is the whole design.
 */
class TestCacheTest extends TestSuite {

  /** keyFor refuses suites it cannot find compiled, so use one that exists. */
  private val RealSuite = "booktest.test.CachePasser"

  /** An assertion that reads as a claim in the book AND hard-fails on
    * regression, so the snapshot documents the contract instead of just
    * recording whatever happened. */
  private def check(t: TestCaseRun, claim: String, cond: Boolean): Unit = {
    t.reviewln(claim, cond)
    if (!cond) t.fail(s"failed: $claim")
  }


  private def quietConfig(tempDir: os.Path,
                          useCache: Boolean = false,
                          requireCache: Boolean = false,
                          verifyCache: Boolean = false,
                          accept: Boolean = false,
                          threads: Int = 1): RunConfig = {
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
      threads = threads,
      useCache = useCache,
      requireCache = requireCache,
      verifyCache = verifyCache)
  }

  /** Per-case entries recorded in the manifest (comment and shared-digest
    * lines excluded). */
  private def cacheEntries(tempDir: os.Path): Seq[String] = {
    val f = tempDir / "_test-cache.manifest"
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
    val d = Fingerprint.digests(1)

    val before = d.keyFor(RealSuite, "case", dir)
    os.write.over(dir / "case.md", "expected A")
    val afterA = d.keyFor(RealSuite, "case", dir)
    os.write.over(dir / "case.md", "expected B")
    val afterB = d.keyFor(RealSuite, "case", dir)

    // Relations, not digests: a key folds in the test tree, so printing one
    // would make this snapshot diff on every edit to this very file.
    check(t, "adding a snapshot changes the key", before != afterA)
    check(t, "changing snapshot CONTENT changes the key", afterA != afterB)
  }

  /** -p changes results in some codebases, not just speed, so it is an input. */
  def testKeyCoversParallelism(t: TestCaseRun): Unit = {
    t.h1("The key covers parallelism")
    val dir = t.tmpDir("key-par")
    os.makeDir.all(dir)
    val k1 = Fingerprint.digests(1).keyFor(RealSuite, "case", dir)
    val k4 = Fingerprint.digests(4).keyFor(RealSuite, "case", dir)
    check(t, "-p1 and -p4 give different keys", k1 != k4)
  }

  /** Absent, malformed and mismatched cache entries must all read as "run it".
    * This is rule #1: there is no path where a bad cache entry means "passed". */
  def testCorruptEntryNeverMatches(t: TestCaseRun): Unit = {
    t.h1("A corrupt or absent cache entry never matches")

    val dir = t.tmpDir("corrupt")
    os.makeDir.all(dir)
    val f = dir / "m.manifest"
    val d = Fingerprint.digests(1)
    val name = "Suite/x"
    val snap = "snapdigest"

    def store = new CacheManifest(f, d)

    assert(!store.matches(name, snap), "absent manifest must not match")

    os.write.over(f, "this is not a manifest at all")
    assert(!store.matches(name, snap), "malformed must not match")

    os.write.over(f, s"keyVersion=${Fingerprint.KeyVersion}\nclasspath=WRONG\nenv=${d.environment}\n$name $snap\n")
    assert(!store.matches(name, snap), "a STALE CLASSPATH must invalidate every entry")

    os.write.over(f, s"keyVersion=${Fingerprint.KeyVersion}\nclasspath=${d.classpath}\nenv=${d.environment}\n$name otherdigest\n")
    assert(!store.matches(name, snap), "a different snapshot digest must not match")

    os.write.over(f, "")
    assert(!store.matches(name, snap), "empty must not match")

    val good = store; good.record(name, snap); good.flush()
    assert(new CacheManifest(f, d).matches(name, snap), "a recorded pass must match on reload")
    t.tln("absent / malformed / stale-classpath / wrong-snapshot / empty all read as MISS")
    t.tln("only a manifest whose SHARED digests and per-case digest both match is a hit")
  }

  /** A failing case must leave no entry — otherwise the next run skips a case
    * that has never passed, which is precisely the false pass we must not have. */
  def testFailingTestIsNotCached(t: TestCaseRun): Unit = {
    t.h1("A failing case leaves no entry")

    val dir = t.tmpDir("fail-no-cache entry")
    // No auto-accept: we want the genuine FAIL verdict from t.fail().
    val runner = new TestRunner(quietConfig(dir, useCache = true))
    val result = runner.runSuite(new CacheFailer())

    t.tln(s"passed: ${result.results.head.passed}")
    t.tln(s"cache entries written: ${cacheEntries(dir).length}")
    assert(!result.results.head.passed, "the helper must fail")
    assert(cacheEntries(dir).isEmpty, "a failing case must not write an entry")
  }

  /** The happy path: pass once, then the same inputs skip. */
  def testSecondRunIsCached(t: TestCaseRun): Unit = {
    t.h1("A second run with identical inputs is served from the cache")

    val dir = t.tmpDir("cached")

    // Prime the snapshot with cache entries OFF, so the next run is a genuine compare.
    new TestRunner(quietConfig(dir, accept = true)).runSuite(new CachePasser())

    val first = new TestRunner(quietConfig(dir, useCache = true)).runSuite(new CachePasser())
    t.tln(s"first cache entryed run passed: ${first.results.head.passed} (${first.results.head.successState}), cache entries: ${cacheEntries(dir).length}")
    assert(first.results.head.passed, "a run against an accepted snapshot should pass")
    assert(cacheEntries(dir).nonEmpty, "a passing case must write an entry")

    val second = new TestRunner(quietConfig(dir, useCache = true)).runSuite(new CachePasser())
    t.tln(s"second run passed: ${second.results.head.passed}, duration: ${second.results.head.durationMs} ms")
    assert(second.results.head.passed, "cached run reports pass")

    // Now corrupt the entry: the cache must fall back to RUNNING, not to passing.
    os.write.over(dir / "_test-cache.manifest", "classpath=tampered\n")
    val third = new TestRunner(quietConfig(dir, useCache = true)).runSuite(new CachePasser())
    t.tln(s"after tampering, still passed by RE-RUNNING: ${third.results.head.passed}")
    assert(third.results.head.passed, "a tampered cache entry must cause a re-run, and the test still passes")
  }

  /** --require-cache must FAIL on a miss, never pass. It is CI's policy for
    * "I will not run the suite; show me the proof" — strictly safer than
    * running, because it cannot turn a miss into a pass either. */
  def testRequireCacheFailsOnMiss(t: TestCaseRun): Unit = {
    t.h1("--require-cache fails on a miss, and says how to fix it")

    val dir = t.tmpDir("require")
    val runner = new TestRunner(quietConfig(dir, requireCache = true))
    val result = runner.runSuite(new CachePasser())
    val r = result.results.head

    t.tln(s"passed: ${r.passed}")
    t.tln(s"state : ${r.successState}")
    check(t, "a miss under --require-cache does NOT pass", !r.passed)
    // Assert what the developer needs out of the message, not its wording: the
    // flag that actually writes a manifest (without it they would re-run and
    // hit the same failure) and the file to commit.
    check(t, "the failure names the flag that writes the manifest",
      r.diff.exists(_.contains("-C")))
    check(t, "the failure names the file to commit",
      r.diff.exists(_.contains(Fingerprint.ManifestName)))
    check(t, "--require-cache writes no entries itself", cacheEntries(dir).isEmpty)
    t.tln("")
    t.tln("failure message:")
    r.diff.foreach(d => d.linesIterator.foreach(l => t.tln(s"  $l")))
  }

  /** THE ORACLE. Simulate an incomplete key: forge an entry claiming a case
    * passes at its current key, when the case actually fails. A from-scratch
    * verifying run must NOTICE — that is the only defence against a key that
    * omits an input, and the reason the verifier must exist before anyone
    * leans on the cache. */
  def testVerifierCatchesALyingEntry(t: TestCaseRun): Unit = {
    t.h1("The verifier catches an entry that claims a pass the code does not deliver")

    val dir = t.tmpDir("verify")
    os.makeDir.all(dir)

    // Forge exactly the entry an incomplete key would have left behind: valid
    // for the CURRENT inputs, for a case that does not pass.
    val suiteName = "booktest.test.CacheFailer"
    val caseName = "alwaysFails"
    // Derive the suite path exactly as the runner does, rather than hand-writing
    // it — getSuitePath applies test-root stripping, so a guess here would forge
    // an entry at a path the runner never looks at, and the test would "pass" by
    // testing nothing.
    val cfg = quietConfig(dir, useCache = true)
    val suitePath = os.RelPath(cfg.getSuitePath(suiteName))
    val manifest = cfg.snapshotDir / "_test-cache.manifest"
    val fullName = s"$suiteName/$caseName"
    val digests = Fingerprint.digests(cfg.threads)
    // Again: compute the per-case digest through the same call the runner uses,
    // so the forgery is the real thing and not a value only this test believes.
    val caseDigest = digests.caseDigest(suiteName, caseName, cfg.snapshotDir / suitePath)
    val forged = new CacheManifest(manifest, digests)
    forged.record(fullName, caseDigest)
    forged.flush()
    t.tln(s"forged an entry claiming $caseName passes: ${cacheEntries(dir).contains(s"$fullName $caseDigest")}")

    // With the cache TRUSTED, that lie would be believed and the case skipped.
    val trusting = new TestRunner(cfg).runSuite(new CacheFailer())
    t.tln(s"with --cache, the forged claim is believed: passed=${trusting.results.head.passed}")
    assert(trusting.results.head.passed, "precondition: a valid cache entry does cause a skip")

    // The verifier runs it for real and refuses the claim.
    val runner = new TestRunner(quietConfig(dir, verifyCache = true))
    val verified = runner.runSuite(new CacheFailer())
    t.tln(s"with --verify-cache, the claim is refused: passed=${verified.results.head.passed}")
    t.tln(s"stale entries reported: ${runner.staleCacheEntries.mkString(", ")}")
    t.tln(s"the lying cache entry was deleted: ${cacheEntries(dir).isEmpty}")

    assert(!verified.results.head.passed, "the verifier must report the real verdict")
    assert(runner.staleCacheEntries.nonEmpty, "the verifier must NAME the case whose cache entry lied")
    assert(verified.results.head.diff.exists(_.contains("THE CACHE KEY IS INCOMPLETE")),
      "the message must point at the key, not just the test")
    assert(cacheEntries(dir).isEmpty, "an entry that lied once must be deleted")
  }

  /** Per-suite granularity: editing ONE suite's test code must invalidate that
    * suite, not every suite in the module. Cross-MODULE isolation is already
    * free (the key hashes the real classpath, and the classpath is the module
    * closure), so this is the part that had to be built. */
  def testEditingOneSuiteDoesNotInvalidateAnother(t: TestCaseRun): Unit = {
    t.h1("Editing one suite's test code invalidates that suite only")

    t.tln("A per-case key folds in the test code that case's suite REACHES, so an")
    t.tln("edit to one test file moves only the suites whose closure contains it.")
    t.tln("What follows checks the three properties that have to hold for it to be useful.")
    t.tln("")
    t.tln("Note this book prints RELATIONS, never digests: a digest of the test tree")
    t.tln("changes whenever this very file is edited, so printing one would make the")
    t.tln("snapshot diff on every unrelated change and teach the reviewer to ignore it.")

    // runtimeClasspathEntries, not java.class.path: under unforked sbt the
    // system property holds only the launcher jar (this test is what caught that).
    // Test code may arrive as a directory OR as sbt's background-job
    // `*-tests.jar`; ask Fingerprint which entries carry it rather than assuming.
    val dirs = Fingerprint.runtimeClasspathEntries().filter(Fingerprint.isTestArtifact)
    check(t, "the classpath carries compiled test code", dirs.nonEmpty)

    val index = new Fingerprint.TestIndex(dirs)
    val passer = index.digestFor("booktest.test.CachePasser")
    val failer = index.digestFor("booktest.test.CacheFailer")

    t.h2("1. Distinct suites get distinct digests")
    t.tln("If two suites hashed alike, editing one would invalidate the other and the")
    t.tln("granularity would be fictional.")
    check(t, "CachePasser and CacheFailer hash differently", passer != failer)

    t.h2("2. A suite that drives another DEPENDS on it")
    t.tln("This test class instantiates both helper suites, so both must appear in its")
    t.tln("closure. Miss that edge and editing a helper leaves a stale pass for its driver —")
    t.tln("the under-approximation that makes a cache lie.")
    val selfClosure = index.closure("booktest.test.TestCacheTest")
    check(t, "TestCacheTest's closure contains CachePasser",
      selfClosure.exists(_.contains("CachePasser")))
    check(t, "TestCacheTest's closure contains CacheFailer",
      selfClosure.exists(_.contains("CacheFailer")))

    t.h2("3. ...but the dependency is not symmetric")
    t.tln("The helper must NOT reach back into its driver. Without this asymmetry every")
    t.tln("suite would transitively reach every other and the closure would be the module.")
    val helperClosure = index.closure("booktest.test.CachePasser")
    check(t, "CachePasser's closure excludes TestCacheTest",
      !helperClosure.exists(_.contains("TestCacheTest")))
    check(t, "the driver's closure is strictly larger than the helper's",
      selfClosure.size > helperClosure.size)
  }

  /** The fail-loud contract on the other side: a suite whose compiled class the
    * index cannot see must be REFUSED, not cache entryed with a constant. That case is
    * how a per-suite key silently degrades back into "cache forever". */
  def testUnknownSuiteIsRefused(t: TestCaseRun): Unit = {
    t.h1("A suite with no compiled class is refused, not cache entryed")
    val d = Fingerprint.digests(1)
    val err = try {
      d.digestFor_forTest("no.such.Suite"); "no exception"
    } catch { case e: IllegalStateException => e.getMessage }
    // Redact the class COUNT: it is genuinely useful in a real error message,
    // but printing it here makes this snapshot diff every time a test suite is
    // added anywhere in the repo — a book that cries wolf.
    t.tln(err.replaceAll("among \\d+ indexed", "among <N> indexed"))
    assert(err.contains("no compiled class found"), "must name the actual problem")
    assert(err.contains("no.such.Suite"), "must name the suite")
  }

  /** The property CI depends on: an entry is written on a laptop under one path
    * and must be honoured on a runner under a different path, from a fresh
    * checkout with fresh mtimes. So the classpath digest must depend on CONTENT
    * and nothing else. If this test ever fails, cache entries silently stop matching
    * anywhere except the machine that wrote them. */
  def testClasspathDigestIsPortable(t: TestCaseRun): Unit = {
    t.h1("The classpath digest survives a different path and fresh mtimes")

    val root = t.tmpDir("portable")
    val a = root / "home-laptop" / "target" / "classes"
    val b = root / "runner-work" / "job-7" / "target" / "classes"
    os.makeDir.all(a / "pkg"); os.makeDir.all(b / "pkg")
    val bytes = Array[Byte](1, 2, 3, 4, 5)
    os.write.over(a / "pkg" / "X.class", bytes)
    os.write.over(b / "pkg" / "X.class", bytes)
    // A fresh checkout does not preserve mtimes; make them differ on purpose.
    java.nio.file.Files.setLastModifiedTime(
      (b / "pkg" / "X.class").toNIO, java.nio.file.attribute.FileTime.fromMillis(0L))

    val da = Fingerprint.classpathDigest(Seq(a))
    val db = Fingerprint.classpathDigest(Seq(b))
    t.ttable(Seq("checkout", "digest"),
      Seq(Seq("laptop path, current mtime", da.take(16)),
          Seq("runner path, epoch mtime", db.take(16))))
    assert(da == db, "same bytes at a different path/mtime must give the same digest")

    // Classpath ORDER is not promised by the build tool either.
    val c = root / "second"; os.makeDir.all(c)
    os.write.over(c / "Y.class", Array[Byte](9))
    assert(Fingerprint.classpathDigest(Seq(a, c)) == Fingerprint.classpathDigest(Seq(c, a)),
      "classpath order must not change the key")

    // And it must still notice an actual change.
    os.write.over(b / "pkg" / "X.class", Array[Byte](1, 2, 3, 4, 6))
    val changed = Fingerprint.classpathDigest(Seq(b))
    t.tln(s"after editing one class byte: ${changed.take(16)}")
    assert(changed != db, "a content change MUST change the digest")
  }

  /** Parallel runs must use the cache exactly like sequential ones.
    *
    * They did not: `-pN` takes a SEPARATE execution path, and while the cache
    * logic lived only in the sequential one, parallel runs consulted nothing
    * and wrote nothing. Found in a real consumer whose CI runs `-p4` — the very
    * configuration the feature exists for — where `--require-cache` silently
    * degraded into "run everything", a gate that does not gate. */
  def testParallelRunsUseTheCache(t: TestCaseRun): Unit = {
    t.h1("A parallel run uses the cache, like a sequential one")

    t.tln("Each check below is built so it FAILS if the parallel path ignores the")
    t.tln("cache. That is not automatic: the obvious assertions (\"the run passed\",")
    t.tln("\"the gated run failed\") hold either way, because a bypassed run simply")
    t.tln("executes the tests and reaches the same verdict by doing the work.")

    val dir = t.tmpDir("parallel")
    os.makeDir.all(dir)

    t.h2("It WRITES entries")
    // Prime snapshots so the cases can reach OK (only a genuine pass is cached).
    new TestRunner(quietConfig(dir, accept = true, threads = 4)).runSuite(new CachePasser())
    val first = new TestRunner(quietConfig(dir, useCache = true, threads = 4))
      .runSuite(new CachePasser())
    check(t, "the parallel run passes", first.results.forall(_.passed))
    check(t, "a parallel run writes entries", cacheEntries(dir).nonEmpty)

    t.h2("It CONSULTS entries")
    t.tln("Proven with a case that CANNOT pass: forge an entry for it, then run it")
    t.tln("under -p4. A consulted cache skips it and reports a pass; a bypassed one")
    t.tln("runs it and it fails. The verdict itself is the evidence.")
    val forgeDir = t.tmpDir("parallel-consult")
    os.makeDir.all(forgeDir)
    val cfg = quietConfig(forgeDir, useCache = true, threads = 4)
    val suiteName = "booktest.test.CacheFailer"
    val caseName = "alwaysFails"
    val suitePath = os.RelPath(cfg.getSuitePath(suiteName))
    val digests = Fingerprint.digests(cfg.threads)
    val store = new CacheManifest(cfg.snapshotDir / Fingerprint.ManifestName, digests)
    store.record(s"$suiteName/$caseName",
      digests.caseDigest(suiteName, caseName, cfg.snapshotDir / suitePath))
    store.flush()
    val consulted = new TestRunner(cfg).runSuite(new CacheFailer())
    check(t, "a parallel run skips a case that has an entry",
      consulted.results.head.passed)

    t.h2("The --require-cache gate still bites")
    t.tln("With snapshots primed but NO manifest, these cases would pass if they ran.")
    t.tln("So a failure here can only come from the gate refusing — not from the")
    t.tln("tests themselves failing.")
    val gateDir = t.tmpDir("parallel-gate")
    os.makeDir.all(gateDir)
    new TestRunner(quietConfig(gateDir, accept = true, threads = 4)).runSuite(new CachePasser())
    val wouldPass = new TestRunner(quietConfig(gateDir, threads = 4)).runSuite(new CachePasser())
    check(t, "precondition: these cases pass when actually run",
      wouldPass.results.forall(_.passed))
    val gated = new TestRunner(quietConfig(gateDir, requireCache = true, threads = 4))
      .runSuite(new CachePasser())
    check(t, "with no manifest, --require-cache refuses under -p4",
      gated.results.forall(r => !r.passed))
    check(t, "and it refuses for the right reason",
      gated.results.forall(_.diff.exists(_.contains("No valid cache entry"))))
  }

  /** A jar's BYTES are not reproducible across machines; its CONTENTS are.
    *
    * Zip entry order follows directory traversal and compression settings vary,
    * so two jars built from byte-identical class files still differ as files.
    * sbt hands `runMain` freshly packaged jars of the project's own code, so
    * keying on jar bytes made the key machine-specific — measured on aito-core,
    * every class file matched between a laptop and a CI runner and the key
    * still differed. That is the whole reason `--require-cache` could not be
    * switched on. */
  def testJarDigestIgnoresPackaging(t: TestCaseRun): Unit = {
    t.h1("Two jars with the same contents hash the same, however they were packed")

    val dir = t.tmpDir("jars")
    os.makeDir.all(dir)

    def buildJar(path: os.Path, entries: Seq[(String, Array[Byte])], level: Int): Unit = {
      val fos = new java.io.FileOutputStream(path.toIO)
      val zos = new java.util.zip.ZipOutputStream(fos)
      zos.setLevel(level)
      entries.foreach { case (name, bytes) =>
        val e = new java.util.zip.ZipEntry(name)
        // Deliberately different timestamps too — another thing that varies.
        e.setTime(if (level == 0) 0L else 1000000000L)
        zos.putNextEntry(e); zos.write(bytes); zos.closeEntry()
      }
      zos.close()
    }

    val content = Seq(
      "a/A.class" -> Array[Byte](1, 2, 3),
      "b/B.class" -> Array[Byte](4, 5, 6),
      "c/C.class" -> Array[Byte](7, 8, 9))

    // Same content; different entry ORDER, compression LEVEL and timestamps.
    buildJar(dir / "one.jar", content, 9)
    buildJar(dir / "two.jar", content.reverse, 0)

    val sameBytes = os.read.bytes(dir / "one.jar").sameElements(os.read.bytes(dir / "two.jar"))
    t.tln(s"the two jars are byte-identical as files: $sameBytes")
    check(t, "precondition: the jars genuinely differ as files", !sameBytes)

    val d1 = Fingerprint.classpathDigest(Seq(dir / "one.jar"))
    val d2 = Fingerprint.classpathDigest(Seq(dir / "two.jar"))
    check(t, "but they produce the same digest", d1 == d2)

    // And a real content change must still move it.
    buildJar(dir / "three.jar", content.map {
      case ("b/B.class", _) => "b/B.class" -> Array[Byte](4, 5, 7)
      case other => other
    }, 9)
    check(t, "changing one byte inside an entry changes the digest",
      Fingerprint.classpathDigest(Seq(dir / "three.jar")) != d1)
  }

  /** The build tool's own launcher must not be part of the key.
    *
    * `java.class.path` under sbt is literally `sbt-launch.jar`, whose bytes
    * depend on how sbt was installed — a CI runner on 1.10.5 versus a developer
    * on nixpkgs' 1.12.11 — while both build identical code with the identical
    * pinned compiler. Measured on a real consumer: of 121 classpath entries,
    * this was the ONLY genuine difference between laptop and runner. */
  def testBuildToolBootstrapIsNotPartOfTheKey(t: TestCaseRun): Unit = {
    t.h1("sbt's own launcher is excluded from the key")

    val dir = t.tmpDir("bootstrap")
    os.makeDir.all(dir / "a"); os.makeDir.all(dir / "b")

    // Two "installations" of the build tool: same name, different bytes.
    os.write.over(dir / "a" / "sbt-launch.jar", "launcher version A")
    os.write.over(dir / "b" / "sbt-launch.jar", "launcher version B")
    // Plus one real dependency, identical in both.
    os.write.over(dir / "a" / "dep.jar", "real dependency")
    os.write.over(dir / "b" / "dep.jar", "real dependency")

    check(t, "the two launchers genuinely differ",
      os.read(dir / "a" / "sbt-launch.jar") != os.read(dir / "b" / "sbt-launch.jar"))

    val da = Fingerprint.digests(1)
    // classpathDigest is fed the already-filtered list by `digests`, so assert
    // the filter itself as well as the effect.
    check(t, "the launcher is recognised as build-tool bootstrap",
      Fingerprint.isBuildToolBootstrap(dir / "a" / "sbt-launch.jar"))
    check(t, "a real jar is not",
      !Fingerprint.isBuildToolBootstrap(dir / "a" / "dep.jar"))

    val withA = Fingerprint.classpathDigest(
      Seq(dir / "a" / "dep.jar", dir / "a" / "sbt-launch.jar").filterNot(Fingerprint.isBuildToolBootstrap))
    val withB = Fingerprint.classpathDigest(
      Seq(dir / "b" / "dep.jar", dir / "b" / "sbt-launch.jar").filterNot(Fingerprint.isBuildToolBootstrap))
    check(t, "two different launchers give the same key", withA == withB)

    // And a real dependency change must still move it.
    //
    // This also pins a silent-failure fix: `dep.jar` here is NOT a valid zip,
    // and jar reading used to be wrapped in a Try that returned None on
    // failure — so an unreadable entry contributed NOTHING to the key and
    // changes to it could never invalidate the cache. It now falls back to
    // hashing the raw bytes. This check failed for exactly that reason when
    // first written.
    os.write.over(dir / "b" / "dep.jar", "real dependency, changed")
    val changed = Fingerprint.classpathDigest(Seq(dir / "b" / "dep.jar"))
    check(t, "changing a real dependency still changes the key", changed != withA)
  }
}
