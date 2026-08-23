package booktest

import os.Path

import java.security.MessageDigest
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
   * Directories (the project's own compiled output) are hashed by content —
   * that is where a code change shows up. Jars are hashed by identity
   * (path, size, mtime) rather than content: they are immutable build
   * artefacts, and reading hundreds of megabytes to learn what a filename
   * already tells us would make the cache slower than the tests.
   *
   * Computed ONCE per run and reused for every case (see `Digests`).
   */
  def classpathDigest(classpath: String): String = {
    val md = sha256()
    val entries = classpath.split(java.io.File.pathSeparator).filter(_.nonEmpty).sorted
    entries.foreach { e =>
      val p = Try(os.Path(e, os.pwd)).toOption
      md.update(e.getBytes("UTF-8"))
      p match {
        case Some(path) if os.exists(path) && os.isDir(path) =>
          // content-hash the compiled classes, in a stable order
          val files =
            Try(os.walk(path).filter(f => os.isFile(f) && f.last.endsWith(".class")).sortBy(_.toString))
              .getOrElse(Nil)
          files.foreach { f =>
            md.update(f.relativeTo(path).toString.getBytes("UTF-8"))
            Try(md.update(os.read.bytes(f))).getOrElse(())
          }
        case Some(path) if os.exists(path) =>
          // jar/zip: identity is enough (immutable artefact)
          val st = Try(os.stat(path)).toOption
          md.update(st.map(_.size).getOrElse(-1L).toString.getBytes("UTF-8"))
          md.update(st.map(_.mtime.toMillis).getOrElse(-1L).toString.getBytes("UTF-8"))
        case _ =>
          // A classpath entry that does not exist is itself an input worth
          // recording; it must not silently hash the same as one that does.
          md.update("<absent>".getBytes("UTF-8"))
      }
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

  /** Run-wide digests, computed once and shared by every case. */
  case class Digests(classpath: String, environment: String) {
    def keyFor(suiteName: String, caseName: String, snapshotDir: Path): String = {
      val md = sha256()
      Seq(KeyVersion, suiteName, caseName, classpath, environment,
          snapshotDigest(snapshotDir, caseName)).foreach { s =>
        md.update(s.getBytes("UTF-8")); md.update(0.toByte)
      }
      hex(md.digest())
    }
  }

  def digests(parallelism: Int): Digests =
    Digests(
      classpath = classpathDigest(sys.props.getOrElse("java.class.path", "")),
      environment = environmentDigest(parallelism))

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
