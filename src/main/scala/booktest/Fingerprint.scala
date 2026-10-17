package booktest

import os.Path

import java.security.MessageDigest
import scala.collection.mutable
import scala.util.Try

/**
 * Content-addressed test caching: an entry records that a case PASSED for a
 * given input hash, so a later run that hashes to the same value can skip it.
 * Same mechanism as Bazel and the Gradle build cache.
 *
 * ==Rule #1: an entry is a CACHE WITH A FALLBACK, never an authority==
 *
 * If the key does not match, run the test. If the entry is missing, malformed,
 * or unreadable, run the test. There is deliberately no code path anywhere in
 * this file where a missing or unparseable cache entry can mean "passed" — the only
 * thing an entry can do is let us SKIP work we can prove is unnecessary.
 *
 * (CI may additionally choose to REFUSE to run on a miss and tell the developer
 * to run it locally — see `requireCache` in TestRunnerConfig. That is a
 * policy on top of this cache, and it is strictly safer than running: it
 * cannot turn a miss into a pass either.)
 *
 * ==The whole correctness question is: IS THE HASH COMPLETE?==
 *
 * An omitted input means a stale pass on a tree that would actually fail, so
 * every choice below is biased toward hashing too much rather than too little.
 * The key has two halves:
 *
 *   shared    main code + dependency jars, locale/timezone, -p   moves => rerun all
 *   per case  that case's accepted snapshots, and the test code
 *             its SUITE reaches                                  moves => rerun that suite
 *
 * The per-case half is what makes editing one test re-run one suite instead of
 * the module. It is a REFINEMENT of a whole-classpath key: the suite's own
 * compiled classes plus everything they reference, followed transitively
 * through the class-file constant pool. That closure deliberately
 * OVER-approximates — a reference through a shared helper drags in the helper's
 * whole class. Over-approximating costs a needless re-run; under-approximating
 * is a stale pass.
 *
 * Cross-module isolation needs no extra machinery: the classpath IS the module
 * closure, so a module absent from the classpath cannot be in the key.
 *
 * Hashing COMPILED OUTPUT rather than sources is deliberate:
 *   - it is what actually executes, so it captures main code, test code,
 *     dependency version bumps and compiler-flag changes alike;
 *   - it needs no knowledge of the project's source layout;
 *   - it cannot drift from a second, hand-rolled notion of "what does this test
 *     depend on" — which is exactly how incomplete keys get shipped.
 *
 * None of this is self-checking: a cache believes its own key by construction,
 * so the only real defence is `--verify-cache` running the suite for real on a
 * schedule and auditing what the cache claimed.
 */
object Fingerprint {

  /** Name of the committed manifest, in one place: it appears in the runner,
    * in the CI failure message, and in .gitignore guidance. */
  val ManifestName = "_test-cache.manifest"

  /** Bump when the key's *meaning* changes, so old entries cannot match new
    * semantics. Cheaper and safer than trying to migrate cache entries. */
  // "2": caseDigest now also hashes a suite's declared runtime inputs
  // (TestSuite.runtimeInputs, td-20261017005405516224). Bumping this
  // invalidates every existing manifest, so the next gate in each repo runs for
  // real once — that is the intended effect of widening the key.
  val KeyVersion = "2"

  private def sha256(): MessageDigest = MessageDigest.getInstance("SHA-256")

  private def hex(bytes: Array[Byte]): String =
    bytes.map(b => f"${b & 0xff}%02x").mkString

  /**
   * Digest of everything that will EXECUTE: the classpath.
   *
   * PORTABLE BY CONSTRUCTION: the digest is derived from CONTENT ONLY — no
   * absolute path, no mtime, no ordering of the classpath. That is a hard
   * requirement, not tidiness. An entry is made on a developer's machine under
   * `/home/x/project` and has to be honoured by CI under `/home/runner/work`,
   * where every absolute path differs, every mtime is a fresh checkout, and sbt
   * hands the run a background-job jar under an incrementing `job-N/` directory.
   * Key on any of those and NO cache entry ever matches anywhere but the machine that
   * wrote it, which makes the whole mechanism a no-op that looks like it works.
   *
   * Directories are hashed as (relative path, bytes) pairs; jars as their bytes.
   * Entry digests are then SORTED, so classpath order — which sbt does not
   * promise — cannot change the key either.
   *
   * Computed ONCE per run and reused for every case (see `Digests`).
   */
  /** Class-name prefixes to exclude from the classpath digest, from the system
    * property `booktest.cache.excludePaths` (comma-separated, internal form
    * e.g. `com/knowledgegarden/episto/buildInfo/`).
    *
    * The point is BUILD METADATA. A generated BuildInfo class embeds things like
    * a build timestamp or the git revision — inputs that do NOT affect whether a
    * test passes, but DO change the jar every build (or every commit). Worse, a
    * consumer that COMMITS its manifest changes the commit, which changes the
    * git-derived BuildInfo, which would invalidate the manifest on the very
    * commit that carries it. Excluding these classes breaks that circle. It is
    * sound because a real code change still shows up in the real classes; only
    * the non-semantic metadata is dropped.
    *
    * Set it identically at generation and verification (e.g. in a committed
    * .sbtopts), or the two keys will differ. */
  // A def, not a lazy val: read the property each time so it cannot be captured
  // stale before it is set (and so tests can vary it). The cost is a trivial
  // property read per classpath entry.
  private def excludedPrefixes: Seq[String] =
    sys.props.getOrElse("booktest.cache.excludePaths", "")
      .split(",").map(_.trim).filter(_.nonEmpty).toSeq

  private def isExcluded(internalName: String): Boolean =
    excludedPrefixes.exists(internalName.startsWith)

  private def entryDigest(path: Path): Option[String] = Try {
    if (os.isDir(path)) {
      val md = sha256()
      os.walk(path).filter(f => os.isFile(f) && f.last.endsWith(".class"))
        .filterNot(f => isExcluded(f.relativeTo(path).toString))
        .sortBy(_.relativeTo(path).toString)
        .foreach { f =>
          md.update(f.relativeTo(path).toString.getBytes("UTF-8"))
          md.update(os.read.bytes(f))
        }
      Some(hex(md.digest()))
    } else if (os.isFile(path)) {
      // Hash what the jar CONTAINS, not how it was packed.
      //
      // Raw bytes are not reproducible across machines even when every class
      // inside is identical: zip entry order follows directory traversal, and
      // compression settings and metadata vary. sbt hands `runMain` freshly
      // PACKAGED jars of the project's own code (under target/bg-jobs/), so
      // keying on jar bytes made the key machine-specific — every class file
      // matched between a laptop and a runner, and the key still differed.
      //
      // Sorted (entry name, bytes) is stable under all of that, and is exactly
      // the content that determines behaviour.
      // Not every classpath file is a readable zip. Falling back to raw bytes
      // keeps such an entry IN the key: silently skipping it (the previous
      // behaviour, via Try) meant changes to it could never invalidate the
      // cache — a stale pass waiting to happen, and invisible.
      val isZip = Try {
        val in = new java.io.DataInputStream(new java.io.FileInputStream(path.toIO))
        try in.readInt() == 0x504b0304 finally in.close()
      }.getOrElse(false)
      if (!isZip) Some(hex(sha256().digest(os.read.bytes(path)))) else {
      val jar = new java.util.jar.JarFile(path.toIO)
      try {
        val md = sha256()
        val names = {
          val b = List.newBuilder[String]
          val it = jar.entries()
          while (it.hasMoreElements) {
            val e = it.nextElement()
            if (!e.isDirectory && !isExcluded(e.getName)) b += e.getName
          }
          b.result().sorted
        }
        names.foreach { n =>
          md.update(n.getBytes("UTF-8"))
          val in = jar.getInputStream(jar.getEntry(n))
          try {
            val buf = new Array[Byte](16384)
            var r = in.read(buf)
            while (r > 0) { md.update(buf, 0, r); r = in.read(buf) }
          } finally in.close()
        }
        Some(hex(md.digest()))
      } finally jar.close() }
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
    val _ = parallelism  // retained for source compatibility; see note below
    val md = sha256()
    val props = Seq("user.language", "user.country", "user.timezone", "java.specification.version")
    props.foreach { k => md.update(s"$k=${sys.props.getOrElse(k, "")}".getBytes("UTF-8")) }
    Seq("LANG", "LC_ALL", "TZ").foreach { k =>
      md.update(s"$k=${sys.env.getOrElse(k, "")}".getBytes("UTF-8"))
    }
    // NOTE: parallelism is deliberately NOT here. It moved to the per-CASE
    // digest (see caseDigest). A real CI run executes the -p4 sweep AND -p1
    // serial guards against ONE manifest; with -p in the shared digest the two
    // runs had different shared keys, so the second wiped the first's entries.
    // Parallelism affects whether a *case* passes (races), so it is a per-case
    // input, not a run-wide one.
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
  // digest. Changing server already leaves core's entries valid.
  //
  // What is NOT free is per-SUITE isolation inside a module: every suite's
  // compiled test classes live in the same test-classes directory, so hashing
  // that directory wholesale means editing ONE test invalidates every entry in
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
      * and a digest that does not depend on the test code is an entry that
      * survives editing the test — the precise unsoundness this whole mechanism
      * exists to avoid. Refuse rather than return a constant. */
    def digestFor(suiteName: String): String = {
      val c = closure(suiteName)
      if (c.isEmpty)
        throw new IllegalStateException(
          s"booktest cache: no compiled class found for suite '$suiteName' among " +
          s"${known.size} indexed test classes. Refusing to cache it, because a key that " +
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
  /** The build tool's own bootstrap, which is on the classpath by accident of
    * how the tests are launched — not because it affects them.
    *
    * `java.class.path` under sbt is literally `sbt-launch.jar`, and its bytes
    * depend on how sbt was INSTALLED: a CI runner downloading 1.10.5 and a
    * developer on nixpkgs' 1.12.11 have different launchers while building the
    * exact same code with the exact same compiler. Keying on it makes every
    * cache entry machine-specific for a reason that has nothing to do with the
    * code under test. Measured on aito-core: of 121 classpath entries, this was
    * the only genuine difference between a laptop and a runner.
    *
    * The sbt version that actually builds is pinned by project/build.properties
    * and its jars (compiler-interface, scala-library, …) appear as separate
    * entries, so dropping the launcher loses no real input. */
  private[booktest] def isBuildToolBootstrap(p: Path): Boolean =
    p.last == "sbt-launch.jar"

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

  case class Digests(classpath: String, environment: String, testIndex: TestIndex, parallelism: Int) {
    /** The per-case half of the key: this case's accepted snapshots AND the
      * test code its suite reaches. `classpath` (main code + jars) is the
      * shared half, recorded once in the manifest.
      *
      * The split is what makes editing one test re-run one suite instead of the
      * whole module: a test edit moves only the suites whose closure contains
      * it, while a main-code edit moves the shared digest and invalidates
      * everything — which is correct, because main code is upstream of all of
      * them. */
    /** Content of the paths a suite declared via `runtimeInputs`. A missing
      * path hashes as a marker rather than being skipped: "the input is gone"
      * must not collide with "the input never existed". */
    def runtimeInputDigest(paths: Seq[String]): String = {
      if (paths.isEmpty) return "<none>"
      val md = sha256()
      paths.sorted.foreach { raw =>
        md.update(raw.getBytes("UTF-8"))
        val p = Try(if (raw.startsWith("/")) Path(raw) else os.pwd / os.RelPath(raw)).toOption
        p match {
          case Some(path) if Try(os.isFile(path)).getOrElse(false) =>
            Try(md.update(os.read.bytes(path))).getOrElse(())
          case Some(path) if Try(os.isDir(path)).getOrElse(false) =>
            val files = Try(os.walk(path).filter(os.isFile).sortBy(_.toString)).getOrElse(Nil)
            files.foreach { f =>
              md.update(f.relativeTo(path).toString.getBytes("UTF-8"))
              Try(md.update(os.read.bytes(f))).getOrElse(())
            }
          case _ => md.update("<missing>".getBytes("UTF-8"))
        }
        md.update(0.toByte)
      }
      hex(md.digest())
    }

    def caseDigest(suiteName: String, caseName: String, snapshotDir: Path,
                   runtimeInputs: Seq[String] = Nil): String = {
      val md = sha256()
      Seq(KeyVersion, suiteName, caseName,
          snapshotDigest(snapshotDir, caseName),
          runtimeInputDigest(runtimeInputs),
          testIndex.digestFor(suiteName),
          // Parallelism belongs to the case, not the run: a case can pass at
          // -p1 and fail at -p4 (process-wide cache races — the reason serial
          // guards exist). Keying it here lets one manifest hold the -p4 sweep
          // and the -p1 guards side by side, each case tied to the -p it ran at.
          s"p=$parallelism").foreach { s =>
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
    val (testArtifacts, rest0) = entries.partition(isTestArtifact)
    val rest = rest0.filterNot(isBuildToolBootstrap)
    val index = new TestIndex(testArtifacts)
    // A classpath we could not read is a classpath we cannot key on, and a
    // constant key answers "cached" for code it has never seen. The test index
    // is the sharpest check available here: the suites about to run are on the
    // classpath, so an empty index means discovery failed.
    if (index.isEmpty)
      throw new IllegalStateException(
        s"booktest cache: found no compiled test classes among ${entries.length} classpath " +
        "entries. Refusing to compute an entry key, because a key that does not depend on " +
        "the code would cache forever.")
    Digests(
      classpath = classpathDigest(rest),
      environment = environmentDigest(parallelism),
      testIndex = index,
      parallelism = parallelism)
  }

  /** What CI prints when it refuses to run on a miss.
    *
    * This is the whole user experience of `--require-cache`, so it has to say
    * what happened, why this job will not just run the test, and the exact two
    * commands that fix it. */
  def missingEntryMessage(suiteName: String, caseName: String, runner: String): String =
    s"""No valid cache entry for $suiteName/$caseName.
       |
       |This job does not run the suite; it accepts a PR on the proof that the
       |suite passed locally, for exactly this tree and environment. No entry
       |matches this fingerprint, so there is nothing to accept.
       |
       |Run the suite locally with the cache enabled and commit the manifest
       |it writes (running more than this one case is fine — one manifest
       |holds them all):
       |
       |    $runner
       |    git add '**/$ManifestName' && git commit
       |
       |An entry is invalidated by a change to the main code or dependencies (which
       |invalidates all of them), to the test code this suite reaches (which
       |invalidates this suite), to this case's accepted snapshots, or to
       |locale/timezone/parallelism.""".stripMargin
}
