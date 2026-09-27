package booktest.test

import booktest.*

/** Helper suite for FatalErrorTest: one case throws a VirtualMachineError, a
  * sibling does not. Excluded from default discovery via booktest.ini.
  *
  * The error is a SYNTHETIC instance, not a real exhaustion. Actually filling the
  * heap would make this test slow, machine-dependent, and capable of killing the
  * run that is measuring it — while telling us nothing extra: every code path
  * under test dispatches on the TYPE. */
class FatalThrowingHelper extends TestSuite {
  def testThrowsFatal(t: TestCaseRun): Unit = {
    t.tln("about to throw a VirtualMachineError")
    throw new OutOfMemoryError("synthetic — not a real heap exhaustion")
  }

  def testRunsAfterFatal(t: TestCaseRun): Unit = {
    t.tln("sibling test ran")
  }
}

/**
 * A fatal throwable must fail its case AND the run.
 *
 * WHAT WENT WRONG (measured 2026-09-27 on aito-core's iodf suite at -Xmx4g): the
 * runner caught `NonFatal`, which by design excludes `OutOfMemoryError`. Three
 * OOMs escaped to their threads' top level in one run. Each was printed — and
 * then the run CARRIED ON: later cases reported `ok`, the killed cases' books
 * were left half-written, and the exit code stayed 0. A half-written book is
 * worse than a missing one, because the result cache can accept it as evidence
 * for a case that never finished.
 *
 * WHAT THE FIX ACTUALLY ADDS, as located by running this test against unfixed
 * code rather than assumed: a case whose BODY throws already failed, so the gap is
 * a fatal error that escapes anywhere ELSE. In the iodf run it came from
 * `TestCaseRun.getTestOutput` — the output-finalisation path, outside any case's
 * try — so no case-level catch could ever have seen it. The load-bearing part is
 * therefore the process-wide latch plus the check on the run's verdict; widening
 * the case catch only adds the case NAME to the report.
 *
 * The same run then hung for three hours holding a machine-wide build lock, which
 * is tracked separately — this test covers the verdict, not the liveness.
 */
class FatalErrorTest extends TestSuite {

  private def quietConfig(tempDir: os.Path): RunConfig = {
    val logStream = new java.io.PrintStream(
      new java.io.FileOutputStream((tempDir / "runner.log").toIO))
    RunConfig(
      outputDir = tempDir,
      snapshotDir = tempDir,
      verbose = false,
      output = logStream
    )
  }

  private def check(t: TestCaseRun, label: String, cond: Boolean): Unit = {
    t.reviewln(label, cond)
    if (!cond) t.fail(label)
  }

  def testFatalErrorFailsCaseAndIsLatched(t: TestCaseRun): Unit = {
    t.h1("An OutOfMemoryError fails its case and is latched for the run's verdict")
    t.tln()
    t.tln("Pre-fix this case's error escaped `NonFatal`, so the case got no failing")
    t.tln("verdict and the run stayed green. Both halves are asserted: the CASE must")
    t.tln("fail, and the fact must be latched process-wide so a run whose fatal error")
    t.tln("happened OUTSIDE any case body still cannot report success.")
    t.tln()

    val tempDir = t.tmpDir("fatal-error")
    val runner = new TestRunner(quietConfig(tempDir))

    // Isolated: the latch is process-wide and deliberately never self-resets, so a
    // synthetic trip here would otherwise fail booktest's own run.
    val (result, latched) = OutputMux.withFatalEscapesIsolated {
      runner.runMultipleSuites(List(new FatalThrowingHelper()))
    }

    val thrower = result.results.find(_.testName.endsWith("/throwsFatal"))
    val sibling = result.results.find(_.testName.endsWith("/runsAfterFatal"))

    t.ttable(Seq("case", "reported"), Seq(
      Seq("throwsFatal", thrower.map(_.successState.toString).getOrElse("MISSING")),
      Seq("runsAfterFatal", sibling.map(_.successState.toString).getOrElse("MISSING"))))
    t.tln()
    t.tln("Latched fatal escapes:")
    if (latched.isEmpty) t.tln("  (none)") else latched.foreach(l => t.tln(s"  $l"))
    t.tln()

    check(t, "the throwing case produced a result at all", thrower.isDefined)
    // NOT "this was broken before": master already fails a case whose body throws
    // a VirtualMachineError, which the fail-first run proved by passing this line
    // on unfixed code. Asserted anyway, as the property the fix must not regress.
    check(t, "the throwing case FAILED", thrower.exists(!_.passed))
    check(t, "the whole run reports failure", !result.success)
    check(t, "the fatal error was latched for the run's verdict", latched.nonEmpty)
    check(t, "the latch names the case, so a reader knows which book to distrust",
      latched.exists(_.contains("throwsFatal")))
    check(t, "the latch names the throwable type",
      latched.exists(_.contains("OutOfMemoryError")))

    // The sibling still runs: a fatal error must not silently truncate the batch
    // into looking like a shorter, passing run.
    //
    // Only that it RAN is asserted. `passed` is false for any brand-new book,
    // because with no committed snapshot every case reports DIFF — so asserting it
    // would fail on the first run of this very test and say nothing about fatals.
    check(t, "the sibling case still ran", sibling.isDefined)
    check(t, "the sibling case was not aborted by the fatal error",
      sibling.exists(_.successState.toString != "MISSING"))

    // The latch must not leak into the outer run, or booktest could not test this.
    check(t, "the latch was restored outside the isolation",
      OutputMux.fatalEscapesSeen.isEmpty)
  }

  def testFatalOutsideACaseBodyStillFailsTheRun(t: TestCaseRun): Unit = {
    t.h1("A fatal error with no case to blame still fails the run")
    t.tln()
    t.tln("This is the shape that actually happened. In the iodf run the")
    t.tln("`OutOfMemoryError` came from `TestCaseRun.getTestOutput` — output")
    t.tln("finalisation, outside any case's try — so no case-level catch could see")
    t.tln("it, every case that finished reported `ok`, and the exit code stayed 0.")
    t.tln()
    t.tln("Modelled here by a spawned thread, which is the same situation from the")
    t.tln("handler's point of view: a throwable reaching a thread's top level with no")
    t.tln("enclosing case catch. The run's verdict must still turn red.")
    t.tln()

    val (_, latched) = OutputMux.withFatalEscapesIsolated {
      // An explicit Runnable, not a lambda: `() => throw ...` infers as
      // `() => Nothing`, and the SAM conversion fails at the invokedynamic
      // bootstrap ("bootstrap method initialization exception") rather than at the
      // throw we want to observe.
      val body = new Runnable {
        def run(): Unit = throw new OutOfMemoryError("synthetic, off-case")
      }
      val th = new Thread(body, "synthetic-fatal-thread")
      th.start()
      th.join(5000)
    }

    t.tln("Latched from a thread with no case around it:")
    if (latched.isEmpty) t.tln("  (none)") else latched.foreach(l => t.tln(s"  $l"))
    t.tln()

    check(t, "the escape was latched even with no case to attribute it to",
      latched.nonEmpty)
    check(t, "the latch names the throwable type",
      latched.exists(_.contains("OutOfMemoryError")))
    check(t, "the latch names the thread, which is the only handle a reader has",
      latched.exists(_.contains("synthetic-fatal-thread")))
    t.tln()
    t.tln("`BooktestMain` reads exactly this list after `printSummary` and throws")
    t.tln("`BooktestFailure` when it is non-empty, so a non-zero exit follows from")
    t.tln("the latch being set — which is what makes the ok-reporting cases in that")
    t.tln("iodf run unable to carry the run to green.")
  }

  def testControlThrowableStillEscapes(t: TestCaseRun): Unit = {
    t.h1("`return` inside a closure is still not caught")
    t.tln()
    t.tln("The fix widens the case catch to `NonFatal || VirtualMachineError` rather")
    t.tln("than to `Throwable`, and that distinction is load-bearing: Scala compiles")
    t.tln("a `return` inside a closure into a ControlThrowable, which is neither. If")
    t.tln("the catch swallowed it, `return` in a test body would silently stop")
    t.tln("meaning what it says.")
    t.tln()

    // A return from inside a closure — the shape that compiles to
    // NonLocalReturnControl. If the widened catch caught ControlThrowable, this
    // would not return 42.
    def viaClosure(): Int = {
      Seq(1, 2, 3).foreach { i => if (i == 2) return 42 }
      0
    }
    val got = viaClosure()
    t.tln(s"return-from-closure produced: $got")
    check(t, "a non-local return still returns its value (42)", got == 42)
  }
}
