package booktest

import os.Path

import java.security.MessageDigest
import scala.collection.mutable
import scala.util.Try

/**
 * Content-addressed test caching: a stamp records that a case PASSED for a
 * given input hash, so a later run that hashes to the same value can skip it.
 * Same mechanism as Bazel and the Gradle build cache.
 *
 * ==Rule #1: a stamp is a CACHE WITH A FALLBACK, never an authority==
 *
 * If the key does not match, run the test. If the stamp is missing, malformed,
 * or unreadable, run the test. There is deliberately no code path anywhere in
 * this file where a missing or unparseable stamp can mean "passed" — the only
 * thing a stamp can do is let us SKIP work we can prove is unnecessary.
 *
 * (CI may additionally choose to REFUSE to run on a miss and tell the developer
 * to run it locally — see `requireStamps` in TestRunnerConfig. That is a
 * policy on top of this cache, and it is strictly safer than running: it
 * cannot turn a miss into a pass either.)
 *
 * ==The whole correctness question is: IS THE HASH COMPLETE?==
 *
 * An omitted input means a stale pass on a tree that would actually fail. So
 * this version is deliberately CONSERVATIVE — it hashes the whole compiled
 * classpath rather than a per-test dependency closure. That over-invalidates
 * (any engine change invalidates every stamp) but it cannot under-invalidate,
 * and the failure mode of over-invalidating is a wasted run, not a false pass.
 *
 * Hashing COMPILED OUTPUT rather than sources is deliberate too:
 *   - it is what actually executes, so it captures main code, test code,
 *     dependency version bumps and compiler-flag changes alike;
 *   - it needs no knowledge of the project's source layout;
 *   - it cannot drift from a second, hand-rolled notion of "what does this test
 *     depend on" — which is exactly how incomplete keys get shipped.
 *
 * Per-test granularity (read zinc's analysis for the real closure, so a change
 * to the SQL parser stops re-running storage suites) is the next step, and it
 * is a REFINEMENT of this key: it can only ever remove inputs that provably do
 * not affect a case. It must not be attempted without the nightly verifier.
 */
object TestStamp {

  /** Bump when the key's *meaning* changes, so old stamps cannot match new
    * semantics. Cheaper and safer than trying to migrate stamp files. */
  val KeyVersion = "1"

  private def sha256(): MessageDigest = MessageDigest.getInstance("SHA-256")

  private def hex(bytes: Array[Byte]): String =
    bytes.map(b => f"${b & 0xff}%02x").mkString

  /**
   * Digest of everything that will EXECUTE: the classpath.
   *
   * PORTABLE BY CONSTRUCTION: the digest is derived from CONTENT ONLY — no
   * absolute path, no mtime, no ordering of the classpath. That is a hard
   * requirement, not tidiness. A stamp is made on a developer's machine under
   * `/home/x/project` and has to be honoured by CI under `/home/runner/work`,
   * where every absolute path differs, every mtime is a fresh checkout, and sbt
   * hands the run a background-job jar under an incrementing `job-N/` directory.
   * Key on any of those and NO stamp ever matches anywhere but the machine that
   * wrote it, which makes the whole mechanism a no-op that looks like it works.
   *
   * Directories are hashed as (relative path, bytes) pairs; jars as their bytes.
   * Entry digests are then SORTED, so classpath order — which sbt does not
   * promise — cannot change the key either.
   *
   * Computed ONCE per run and reused for every case (see `Digests`).
   */
  private def entryDigest(path: Path): Option[String] = Try {
    if (os.isDir(path)) {
      val md = sha256()
      os.walk(path).filter(f => os.isFile(f) && f.last.endsWith(".class"))
        .sortBy(_.relativeTo(path).toString)
        .foreach { f =>
          md.update(f.relativeTo(path).toString.getBytes("UTF-8"))
          md.update(os.read.bytes(f))
        }
      Some(hex(md.digest()))
    } else if (os.isFile(path)) {
      Some(hex(sha256().digest(os.read.bytes(path))))
    } else {
      // Absent entry: contributes no classes, so it contributes nothing to the
      // key. Recording its path here would only make the key machine-specific.
      None
    }
  }.getOrElse(None)

  def classpathDigest(entries: Seq[Path]): String = {
    val md = sha256()
    entries.flatMap(entryDigest).sorted.foreach { d =>
      md.update(d.getBytes("UTF-8")); md.update(0.toByte)
    }
    hex(md.digest())
  }

  /**
   * Digest of the environment that can change a rendered result.
   *
   * Locale and timezone are here because they demonstrably change output:
   * `f"..."` and String.format are locale-sensitive, and this repo pins
   * 15-significant-digit doubles in snapshots (0,7126 vs 0.7126). They are
   * ALSO pinned in the build — anything we cannot guarantee identical between
   * two machines is either pinned or in the key, and belt-and-braces is right
   * for the one input whose failure mode is a wrong pass.
   *
   * Parallelism is here because -p changes RESULTS in this codebase, not just
   * speed: there is a documented process-wide-lineage-cache race under -p4 that
   * can mask assertions, and at least one suite is pinned to -p1.
   */
  def environmentDigest(parallelism: Int): String = {
    val md = sha256()
    val props = Seq("user.language", "user.country", "user.timezone", "java.specification.version")
    props.foreach { k => md.update(s"$k=${sys.props.getOrElse(k, "")}".getBytes("UTF-8")) }
    Seq("LANG", "LC_ALL", "TZ").foreach { k =>
      md.update(s"$k=${sys.env.getOrElse(k, "")}".getBytes("UTF-8"))
    }
    md.update(s"p=$parallelism".getBytes("UTF-8"))
    hex(md.digest())
  }

  /** Digest of the ACCEPTED snapshots a case asserts against. A changed
    * expectation changes the verdict, so it must change the key. */
  def snapshotDigest(snapshotDir: Path, caseName: String): String = {
    val md = sha256()
    val files =
      if (os.exists(snapshotDir))
        Try(os.list(snapshotDir).filter(os.isFile).filter(_.last.startsWith(caseName)).sortBy(_.toString))
          .getOrElse(Nil)
      else Nil
    if (files.isEmpty) md.update("<no-snapshot>".getBytes("UTF-8"))
    files.foreach { f =>
      md.update(f.last.getBytes("UTF-8"))
      Try(md.update(os.read.bytes(f))).getOrElse(())
    }
    hex(md.digest())
  }

  // ---------------------------------------------------------------------------
  // Per-suite test granularity
  //
  // Cross-MODULE isolation is already free: the key hashes the actual classpath,
  // and the classpath IS the module closure. `core` does not depend on `server`,
  // so server classes are not on a core test's classpath and cannot move its
  // digest. Changing server already leaves core's stamps valid.
  //
  // What is NOT free is per-SUITE isolation inside a module: every suite's
  // compiled test classes live in the same test-classes directory, so hashing
  // that directory wholesale means editing ONE test invalidates every stamp in
  // the module. This section fixes that by attributing test classes to the suite
  // that reaches them.
  //
  // Reachability is read from the CONSTANT POOL: a class file lists, as UTF-8
  // entries, the names of the classes it refers to. Starting from a suite's own
  // class files and following those references transitively gives the test code
  // that suite depends on — including shared helpers, and including other suites
  // it drives (a real case: a meta-test that runs another suite).
  //
  // It OVER-approximates deliberately: any name appearing anywhere in the pool
  // counts, including in unrelated string constants. Over-approximating costs a
  // needless re-run; under-approximating is a stale pass. Only one of those is
  // acceptable, so the bias is chosen, not accidental.

  /** UTF-8 constant-pool entries of a class file. Enough to see which other
    * classes it names, without a bytecode library. Any parse failure yields an
    * empty set, which makes the caller assume nothing and hash more, not less. */
  private[booktest] def constantPoolStrings(bytes: Array[Byte]): Set[String] = Try {
    val in = new java.io.DataInputStream(new java.io.ByteArrayInputStream(bytes))
    val out = Set.newBuilder[String]
    if (in.readInt() == 0xCAFEBABE) {
      in.readUnsignedShort(); in.readUnsignedShort()        // minor, major
      val count = in.readUnsignedShort()
      var i = 1
      var ok = true
      while (ok && i < count) {
        in.readUnsignedByte() match {
          case 1  => out += in.readUTF()
          case 7 | 8 | 16 | 19 | 20 => in.skipBytes(2)
          case 15 => in.skipBytes(3)
          case 3 | 4 | 9 | 10 | 11 | 12 | 17 | 18 => in.skipBytes(4)
          case 5 | 6 => in.skipBytes(8); i += 1             // long/double take two slots
          case _ => ok = false                              // unknown tag: stop, keep what we have
        }
        i += 1
      }
    }
    out.result()
  }.getOrElse(Set.empty)

  /** Internal name ("a/b/C") of a class file relative to its output directory. */
  private def internalName(root: Path, f: Path): String =
    f.relativeTo(root).toString.stripSuffix(".class")

  /** The compiled test tree: every test class, its content hash, and the other
    * test classes it names. Built ONCE per run.
    *
    * Sources may be output DIRECTORIES (`.../test-classes/`) or JARS — sbt runs
    * `runMain` through its background job service, which hands the job a
    * content-addressed `*-tests.jar` rather than the class directory. Both
    * layouts have to work or the index is empty on exactly the setup we use. */
  class TestIndex(sources: Seq[Path]) {
    private val content = mutable.Map[String, String]()
    private val refs = mutable.Map[String, Set[String]]()

    private def add(name: String, bytes: Array[Byte]): Unit = {
      content(name) = hex(sha256().digest(bytes))
      refs(name) = constantPoolStrings(bytes)
    }

    sources.foreach { src =>
      Try {
        if (os.isDir(src)) {
          os.walk(src).filter(f => os.isFile(f) && f.last.endsWith(".class")).foreach { f =>
            add(internalName(src, f), os.read.bytes(f))
          }
        } else if (os.isFile(src)) {
          val jar = new java.util.jar.JarFile(src.toIO)
          try {
            val it = jar.entries()
            while (it.hasMoreElements) {
              val e = it.nextElement()
              if (!e.isDirectory && e.getName.endsWith(".class")) {
                val in = jar.getInputStream(e)
                try add(e.getName.stripSuffix(".class"), in.readAllBytes()) finally in.close()
              }
            }
          } finally jar.close()
        }
      }.getOrElse(())
    }

    private val known: Set[String] = content.keySet.toSet

    /** Every class file grouped by the top-level class it belongs to, so a
      * reference resolves in one lookup instead of scanning all known names —
      * the closure walks thousands of constant-pool strings per class and the
      * scan version is quadratic on a real suite. */
    private val byOwner: Map[String, Set[String]] =
      known.groupBy(n => n.indexOf('$') match { case -1 => n; case i => n.substring(0, i) })

    def isEmpty: Boolean = known.isEmpty

    /** Transitive closure of test classes reachable from a suite. */
    def closure(suiteName: String): Set[String] = {
      val start = byOwner.getOrElse(suiteName.replace('.', '/'), Set.empty)
      val seen = mutable.Set[String]() ++ start
      val queue = mutable.Queue[String]() ++ start
      while (queue.nonEmpty) {
        val n = queue.dequeue()
        refs.getOrElse(n, Set.empty).foreach { r =>
          // A pool string names a class either as "a/b/C" or "a.b.C"; and a
          // reference to a class pulls in its companions and nested classes too.
          val cand = r.replace('.', '/').stripSuffix(".class")
          byOwner.getOrElse(cand, Set.empty).foreach { k =>
            if (seen.add(k)) queue.enqueue(k)
          }
        }
      }
      seen.toSet
    }

    /** Digest of the test code a suite reaches.
      *
      * An empty closure means we could not find the suite's own compiled class,
      * and a digest that does not depend on the test code is a stamp that
      * survives editing the test — the precise unsoundness this whole mechanism
      * exists to avoid. Refuse rather than return a constant. */
    def digestFor(suiteName: String): String = {
      val c = closure(suiteName)
      if (c.isEmpty)
        throw new IllegalStateException(
          s"booktest stamps: no compiled class found for suite '$suiteName' among " +
          s"${known.size} indexed test classes. Refusing to stamp it, because a key that " +
          "ignores the test code would keep passing after the test changed.")
      val md = sha256()
      c.toSeq.sorted.foreach { n =>
        md.update(n.getBytes("UTF-8")); md.update(content.getOrElse(n, "").getBytes("UTF-8"))
      }
      hex(md.digest())
    }
  }

  /** Classpath split: what carries TEST code, and everything else.
    *
    * Covers both layouts seen in practice: a `test-classes` output directory,
    * and the `*-tests.jar` sbt's background job service builds. */
  private[booktest] def isTestArtifact(p: Path): Boolean = {
    val s = p.toString
    s.contains("test-classes") || s.endsWith("-tests.jar")
  }

  def classpathEntries(classpath: String): Seq[Path] =
    classpath.split(java.io.File.pathSeparator).toIndexedSeq.filter(_.nonEmpty).sorted
      .flatMap(e => Try(os.Path(e, os.pwd)).toOption)

  /** What will actually execute.
    *
    * NOT simply `java.class.path`: under `sbt` without `fork := true` — which is
    * how this suite and aito-core both run — the tests are loaded by a LAYERED
    * classloader and `java.class.path` contains only sbt's launcher jar. Keying
    * on that would produce one constant digest for every possible version of the
    * code, i.e. a cache that never invalidates. So walk the loader chain, which
    * is the real answer in both forked and unforked mode, and union it with the
    * system property for the forked case. */
  def runtimeClasspathEntries(): Seq[Path] = {
    val out = mutable.LinkedHashSet[Path]()
    var cl: ClassLoader = getClass.getClassLoader
    while (cl != null) {
      cl match {
        case u: java.net.URLClassLoader =>
          u.getURLs.foreach { url =>
            if (url.getProtocol == "file")
              Try(os.Path(java.nio.file.Paths.get(url.toURI))).foreach(out += _)
          }
        case _ => ()
      }
      cl = cl.getParent
    }
    out ++= classpathEntries(sys.props.getOrElse("java.class.path", ""))
    out.toSeq.sortBy(_.toString)
  }

  /** Run-wide digests, computed once and shared by every case. */

  case class Digests(classpath: String, environment: String, testIndex: TestIndex) {
    /** The per-case half of the key: this case's accepted snapshots AND the
      * test code its suite reaches. `classpath` (main code + jars) is the
      * shared half, recorded once in the manifest.
      *
      * The split is what makes editing one test re-run one suite instead of the
      * whole module: a test edit moves only the suites whose closure contains
      * it, while a main-code edit moves the shared digest and invalidates
      * everything — which is correct, because main code is upstream of all of
      * them. */
    def caseDigest(suiteName: String, caseName: String, snapshotDir: Path): String = {
      val md = sha256()
      Seq(KeyVersion, suiteName, caseName,
          snapshotDigest(snapshotDir, caseName),
          testIndex.digestFor(suiteName)).foreach { s =>
        md.update(s.getBytes("UTF-8")); md.update(0.toByte)
      }
      hex(md.digest())
    }

    /** Exposed so tests can assert the refusal contract directly. */
    private[booktest] def digestFor_forTest(suiteName: String): String =
      testIndex.digestFor(suiteName)

    /** Retained for tests that want the whole key as one value. */
    def keyFor(suiteName: String, caseName: String, snapshotDir: Path): String = {
      val md = sha256()
      Seq(classpath, environment, caseDigest(suiteName, caseName, snapshotDir)).foreach { s =>
        md.update(s.getBytes("UTF-8")); md.update(0.toByte)
      }
      hex(md.digest())
    }
  }

  def digests(parallelism: Int): Digests = {
    val entries = runtimeClasspathEntries().filter(p => Try(os.exists(p)).getOrElse(false))
    // A classpath we could not read is a classpath we cannot key on, and a
    // constant key is a cache that answers "cached" for code it has never seen.
    // Refuse rather than hand back a digest that means nothing.

    // Test-class directories are indexed per suite; everything else — main
    // output and jars — is the shared digest.
    val (testArtifacts, rest) = entries.partition(isTestArtifact)
    val index = new TestIndex(testArtifacts)
    // A classpath we could not read is a classpath we cannot key on, and a
    // constant key answers "cached" for code it has never seen. The test index
    // is the sharpest check available here: the suites about to run are on the
    // classpath, so an empty index means discovery failed.
    if (index.isEmpty)
      throw new IllegalStateException(
        s"booktest stamps: found no compiled test classes among ${entries.length} classpath " +
        "entries. Refusing to compute a stamp key, because a key that does not depend on " +
        "the code would cache forever.")
    Digests(
      classpath = classpathDigest(rest),
      environment = environmentDigest(parallelism),
      testIndex = index)
  }

  /** Where a case's stamp lives. Committed alongside the books, so a PR
    * carries its own proof and a reviewer can see which stamps moved. */
  def stampFile(stampRoot: Path, suitePath: os.RelPath, caseName: String): Path =
    stampRoot / suitePath / s"$caseName.stamp"

  /** True only if a stamp exists, parses, and records exactly this key.
    * Every other outcome — absent, unreadable, malformed, different key — is
    * false, i.e. "run the test". */
  def matches(file: Path, key: String): Boolean =
    Try {
      os.exists(file) && os.read.lines(file).exists(_.trim == s"key=$key")
    }.getOrElse(false)

  def write(file: Path, key: String, suiteName: String, caseName: String): Unit =
    Try {
      os.makeDir.all(file / os.up)
      os.write.over(
        file,
        Seq(
          "# booktest result stamp — this case PASSED for the input hash below.",
          "# A cache, not an authority: if the key does not match, the test runs.",
          s"suite=$suiteName",
          s"case=$caseName",
          s"keyVersion=$KeyVersion",
          s"key=$key",
          ""
        ).mkString("\n"))
    }.getOrElse(())

  /** Delete a stamp that has been proven wrong. Best-effort: failing to remove
    * it must not fail the run, and the verifier has already recorded the
    * violation, which is the part that matters. */
  def invalidate(file: Path): Unit = Try(os.remove(file)).getOrElse(())

  /** What CI prints when it refuses to run on a miss. */
  def missingStampMessage(suiteName: String, caseName: String, runner: String): String =
    s"""No valid result stamp for $suiteName/$caseName.
       |
       |This job does not run the suite; it accepts a PR on the proof that the
       |suite passed locally, for exactly this tree and environment. There is no
       |stamp matching this input hash, so there is nothing to accept.
       |
       |Run it locally and commit the stamps it writes:
       |
       |    $runner
       |    git add '**/*.stamp' && git commit
       |
       |A stamp is invalidated by any change to the compiled classpath (so any
       |code change invalidates all of them, for now), to this case's accepted
       |snapshots, or to locale/timezone/parallelism.""".stripMargin
}
