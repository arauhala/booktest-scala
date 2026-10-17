package booktest.test

import booktest._
import java.util.concurrent.atomic.AtomicInteger

/** Run counters that survive across `new TestRunner(...)` calls inside one
  * meta-test, so a case's execution can be distinguished from a cache claim.
  * A claim and a run are indistinguishable in the result line — only a counter
  * tells them apart, which is the whole point of these fixtures. */
object CacheClaimCounters {
  val producerRuns = new AtomicInteger(0)
  val consumerRuns = new AtomicInteger(0)
  val readerRuns   = new AtomicInteger(0)
  /** The external file CacheClaimReader reads AT RUNTIME. Set by the meta test. */
  @volatile var externalFile: Option[os.Path] = None
  def reset(): Unit = { producerRuns.set(0); consumerRuns.set(0); readerRuns.set(0) }
}

/** A producer whose VALUE a consumer needs, plus a leaf that depends on nothing. */
class CacheClaimHelper extends TestSuite {
  def testProducer(t: TestCaseRun): String = {
    CacheClaimCounters.producerRuns.incrementAndGet()
    t.tln("producing value")
    "produced_value"
  }

  @DependsOn(Array("testProducer"))
  def testConsumer(t: TestCaseRun, produced: String): Unit = {
    CacheClaimCounters.consumerRuns.incrementAndGet()
    t.tln(s"consumed: $produced")
  }

  def testLeaf(t: TestCaseRun): Unit = {
    t.tln("leaf, depends on nothing")
  }
}

/** A suite whose output depends on a file it reads at RUNTIME — the shape of
  * aito-core's query/LinkPathTest, which walks api-docs/content. Nothing about
  * that file is part of the case key today. */
class CacheClaimReader extends TestSuite {
  def testReadsAnExternalFile(t: TestCaseRun): Unit = {
    CacheClaimCounters.readerRuns.incrementAndGet()
    val text = CacheClaimCounters.externalFile.map(p => os.read(p).trim).getOrElse("<unset>")
    t.tln(s"external content: $text")
  }
}

/** FAIL-FIRST fixtures for the two cache-correctness defects
  * (td-20261015070626241836, td-20261017005405516224).
  *
  * These are written to FAIL on 0.4.15 and pass after the fix. Both defects
  * share one root cause: a manifest entry answers "did this case pass for this
  * key?", and two call sites treat that answer as something stronger —
  * "its output is available" (A) and "every input is accounted for" (B). */
class CacheClaimTest extends TestSuite {

  // The cases share CacheClaimCounters, so they must not overlap under -pN.
  override protected def resourceLocks: List[String] = List("cache-claim-counters")

  private val nullStream = new java.io.PrintStream(new java.io.OutputStream {
    override def write(b: Int): Unit = ()
    override def write(b: Array[Byte], off: Int, len: Int): Unit = ()
  })

  private def check(t: TestCaseRun, claim: String, cond: Boolean): Unit = {
    t.reviewln(claim, cond)
    if (!cond) t.fail(s"failed: $claim")
  }

  private def cfg(dir: os.Path,
                  useCache: Boolean = false,
                  verifyCache: Boolean = false,
                  accept: Boolean = false): RunConfig =
    RunConfig(outputDir = dir, snapshotDir = dir, verbose = false, output = nullStream,
              updateSnapshots = accept, threads = 1,
              useCache = useCache, verifyCache = verifyCache)

  private def binOf(dir: os.Path, suite: String, caseName: String): os.Path =
    dir / ".out" / os.RelPath(suite.replace('.', '/')) / s"$caseName.bin"

  /** A1 — A CACHED PRODUCER MUST NOT STARVE ITS CONSUMER.
    *
    * shouldRun() schedules a producer when its .bin is missing, which is
    * correct. withCache then intercepts that scheduled run, finds a manifest
    * claim, and returns a synthetic pass — so nothing executes and no .bin is
    * written. The consumer then resolves its dependency, finds no value, and
    * throws "was auto-run but produced no cached value".
    *
    * A claim says the case PASSED. It does not say its VALUE is on disk. */
  def testCachedProducerDoesNotStarveItsConsumer(t: TestCaseRun): Unit = {
    t.h1("A cached producer still runs when its value is needed and absent")
    t.tln()
    t.tln("Priming: accept snapshots, then one cached run so the manifest claims")
    t.tln("every case passes. Then delete ONLY the producer's `.bin` — the value,")
    t.tln("not the claim — and run again with the cache on.")
    t.tln()

    val dir = t.tmpDir("starve")
    CacheClaimCounters.reset()
    new TestRunner(cfg(dir, accept = true)).runSuite(new CacheClaimHelper())
    new TestRunner(cfg(dir, useCache = true)).runSuite(new CacheClaimHelper())

    // THE CONDITION IS ASYMMETRIC, and getting it wrong makes this fixture
    // prove nothing. Deleting only the producer's `.bin` is NOT enough: the
    // consumer is cache-claimed too, so it never runs, never resolves its
    // dependency, and never needs the value — everything "passes" while
    // nothing executes. That is what my first version of this test measured.
    //
    // The real shape (#1679 in aito-core) is: the CONSUMER's key moved so it
    // RUNS, while the PRODUCER's key did not so it is CLAIMED. Reproduce it by
    // dropping the consumer's entry only, and removing the producer's value.
    val bin = binOf(dir, "booktest.test.CacheClaimHelper", "producer")
    val existed = os.exists(bin)
    if (existed) os.remove(bin)

    val manifest = dir / "_test-cache.manifest"
    val kept = os.read.lines(manifest).filterNot(_.contains("consumer"))
    os.write.over(manifest, kept.mkString("", "\n", "\n"))

    t.tln(s"producer .bin removed: $existed; consumer entry dropped, producer entry kept")
    t.tln()

    CacheClaimCounters.reset()
    val run = new TestRunner(cfg(dir, useCache = true)).runSuite(new CacheClaimHelper())
    val consumer = run.results.find(_.testName.endsWith("consumer"))

    t.ttable(Seq("what", "value"), Seq(
      Seq("producer executions", CacheClaimCounters.producerRuns.get()),
      Seq("consumer executions", CacheClaimCounters.consumerRuns.get()),
      Seq("consumer passed", consumer.map(_.passed).getOrElse("(no result)"))))

    // NON-VACUITY, and note what it canNOT be. The obvious check — "the
    // consumer body ran" — is unusable here: the starvation throws during
    // DEPENDENCY RESOLUTION, before the body, so the body counter stays 0 on
    // exactly the run that proves the bug. (My first version asserted that and
    // reported red for the wrong reason.) What is true either way is that the
    // consumer was SCHEDULED and produced a result.
    check(t, "the consumer was scheduled, so this fixture is not vacuous",
      consumer.isDefined)
    check(t, "the consumer does not fail for want of a value it declared",
      consumer.exists(_.passed))
    check(t, "the producer re-ran, because its value was needed and absent",
      CacheClaimCounters.producerRuns.get() > 0)

    // Show the reviewer WHY it fails today; after the fix there is no diff to show.
    consumer.flatMap(_.diff).foreach { d =>
      t.tln("")
      t.tln("consumer failure today:")
      d.linesIterator.take(4).foreach(l => t.tln(s"  $l"))
    }
  }

  /** A2 — THE GUARD AGAINST OVER-CORRECTING. A leaf case, whose value nothing
    * needs, must still be served from the cache. "Never cache a producer" would
    * satisfy A1 and quietly destroy the feature. */
  def testALeafCaseIsStillCached(t: TestCaseRun): Unit = {
    t.h1("A case nothing depends on is still served from the cache")

    val dir = t.tmpDir("leaf")
    CacheClaimCounters.reset()
    new TestRunner(cfg(dir, accept = true)).runSuite(new CacheClaimHelper())
    new TestRunner(cfg(dir, useCache = true)).runSuite(new CacheClaimHelper())

    CacheClaimCounters.reset()
    val again = new TestRunner(cfg(dir, useCache = true)).runSuite(new CacheClaimHelper())
    val leaf = again.results.find(_.testName.endsWith("leaf"))

    t.tln(s"leaf passed: ${leaf.map(_.passed).getOrElse("(none)")}")
    check(t, "the leaf case passes", leaf.exists(_.passed))
    check(t, "nothing re-ran that did not need to: the consumer stayed cached",
      CacheClaimCounters.consumerRuns.get() == 0)
  }

  /** B1 — AN INPUT READ AT RUNTIME MUST BE IN THE KEY.
    *
    * caseDigest is KeyVersion + suite + case + snapshotDigest + the suite's
    * test-code digest + p=N. A file the case opens at runtime appears in none
    * of them, so editing it changes what the case OUTPUTS without changing what
    * it is KEYED on, and the stale entry is served. */
  def testRuntimeReadInputIsPartOfTheKey(t: TestCaseRun): Unit = {
    t.h1("Editing a file the case reads at runtime invalidates its cache entry")

    val dir = t.tmpDir("runtime-read")
    val ext = dir / "external.txt"
    os.write.over(ext, "first\n")
    CacheClaimCounters.externalFile = Some(ext)

    CacheClaimCounters.reset()
    new TestRunner(cfg(dir, accept = true)).runSuite(new CacheClaimReader())
    new TestRunner(cfg(dir, useCache = true)).runSuite(new CacheClaimReader())
    t.tln(s"runs during priming: ${CacheClaimCounters.readerRuns.get()}")

    // Change ONLY the external file. Snapshots and test code are untouched.
    os.write.over(ext, "SECOND\n")
    CacheClaimCounters.reset()
    val after = new TestRunner(cfg(dir, useCache = true)).runSuite(new CacheClaimReader())

    t.ttable(Seq("what", "value"), Seq(
      Seq("executions after editing the external file", CacheClaimCounters.readerRuns.get()),
      Seq("reported passed", after.results.headOption.map(_.passed).getOrElse("(none)"))))

    check(t, "the case re-ran, because an input it reads changed",
      CacheClaimCounters.readerRuns.get() > 0)
  }

  /** B2 — DOES THE ORACLE SHARE THE BLIND SPOT?
    *
    * --verify-cache exists to catch an incomplete key: it runs for real and
    * holds the result against the claim. On the B1 tree the claim is stale and
    * a real run produces a different book, so the verifier MUST report it. If
    * it does not, the auditor has the same gap as the thing it audits, and that
    * is a third defect rather than a variant of this one. */
  def testVerifyCacheCatchesTheRuntimeReadStaleness(t: TestCaseRun): Unit = {
    t.h1("--verify-cache notices a stale entry caused by a runtime-read input")

    val dir = t.tmpDir("verify-runtime-read")
    val ext = dir / "external.txt"
    os.write.over(ext, "first\n")
    CacheClaimCounters.externalFile = Some(ext)

    new TestRunner(cfg(dir, accept = true)).runSuite(new CacheClaimReader())
    new TestRunner(cfg(dir, useCache = true)).runSuite(new CacheClaimReader())

    os.write.over(ext, "SECOND\n")
    CacheClaimCounters.reset()
    val verified = new TestRunner(cfg(dir, verifyCache = true)).runSuite(new CacheClaimReader())
    val r = verified.results.head

    t.tln(s"executions under --verify-cache: ${CacheClaimCounters.readerRuns.get()}")
    t.tln(s"passed: ${r.passed}")
    r.diff.foreach { d => t.tln(""); d.linesIterator.take(6).foreach(l => t.tln(s"  $l")) }

    check(t, "the verifier RUNS the case rather than trusting the claim",
      CacheClaimCounters.readerRuns.get() > 0)
    check(t, "the verifier does not report a pass for a tree whose output changed",
      !r.passed)
  }
}
