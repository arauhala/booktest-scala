package booktest.test

import booktest.{LogCapture, TestCaseRun, TestSuite}

/**
 * `LogCapture` redirects PROCESS-WIDE stdout, but capture is per-test and `-pN`
 * runs several tests at once. The save-and-restore implementation raced: a
 * capture starting while another was active saved the *other capture's* stream
 * as "the original" and restored that on the way out, leaving `System.out`
 * pointing at a buffer nobody reads.
 *
 * The visible symptom was a missing feature rather than a bug: every
 * multi-suite `-pN` run printed no "N/N test succeeded" summary, because the
 * summary goes through `System.out` while per-test lines go through the
 * runner's own stream (captured before any redirection) and kept working.
 */
class LogCaptureConcurrencyTest extends TestSuite {

  // These cases deliberately manipulate PROCESS-WIDE stdout, so they must not
  // run beside anything else doing the same — including each other.
  override protected def resourceLocks: List[String] = List("system-out")

  private def check(t: TestCaseRun, claim: String, cond: Boolean): Unit = {
    t.reviewln(claim, cond)
    if (!cond) t.fail(s"failed: $claim")
  }

  def testConcurrentCapturesDoNotStealTheConsole(t: TestCaseRun): Unit = {
    t.h1("Concurrent captures leave the console intact")

    t.tln("Four threads capture with deliberately overlapping lifetimes — the")
    t.tln("interleaving that broke save-and-restore. Afterwards the console must")
    t.tln("still be the console, and each thread's output must be in its own log.")

    val dir = t.tmpDir("logcap")
    os.makeDir.all(dir)

    // Whatever stdout is right now IS the correct answer afterwards. Comparing
    // against a remembered reference rather than a hardcoded one keeps this
    // honest when the suite itself runs under a capture.
    val before = System.out

    val threads: Seq[Thread] = (0 until 4).map { i =>
      new Thread(new Runnable {
        def run(): Unit = {
          val cap = new LogCapture(dir / s"t$i.log")
          cap.start()
          // Stagger so the captures are genuinely concurrent, not nested.
          System.out.println(s"hello from $i")
          Thread.sleep(20L * (i + 1))
          System.out.println(s"goodbye from $i")
          cap.stop()
        }
      })
    }.toSeq
    threads.foreach(_.start())
    threads.foreach(_.join())

    val after = System.out
    check(t, "System.out is the same stream after concurrent captures", before eq after)

    // Each thread's output landed in its OWN log, not another thread's.
    val logs = (0 until 4).map(i => os.read(dir / s"t$i.log"))
    check(t, "every capture wrote its own log", logs.forall(_.nonEmpty))
    check(t, "no capture stole another thread's output",
      logs.zipWithIndex.forall { case (content, i) =>
        content.contains(s"hello from $i") && content.contains(s"goodbye from $i") &&
          (0 until 4).filter(_ != i).forall(j => !content.contains(s"hello from $j"))
      })
  }

  def testOutputAfterCaptureReachesTheConsole(t: TestCaseRun): Unit = {
    t.h1("Printing after a capture goes on, not into a dead buffer")

    t.tln("The corruption only bites AFTER a capture ends: the next thing printed")
    t.tln("disappears. That is what swallowed the end-of-run summary.")
    t.tln("")
    t.tln("Observed with an OUTER capture rather than by swapping System.out — the")
    t.tln("multiplexer IS System.out, so replacing it would defeat the mechanism")
    t.tln("under test and the assertions would hold for the wrong reason. Nesting")
    t.tln("also exercises that an inner capture ending restores the outer one.")

    val dir = t.tmpDir("after")
    os.makeDir.all(dir)

    val outer = new LogCapture(dir / "outer.log")
    val inner = new LogCapture(dir / "inner.log")
    outer.start()
    inner.start()
    // NOTE: System.out.println, not println — scala's Console caches the stream
    // at first use, so a plain println would bypass this entirely.
    System.out.println("inner-only")
    inner.stop()
    System.out.println("after-inner")
    val outerContent = outer.stop()

    val innerContent = os.read(dir / "inner.log")
    check(t, "output during the inner capture went to the inner log",
      innerContent.contains("inner-only"))
    check(t, "the inner capture did not leak into the outer one",
      !outerContent.contains("inner-only"))
    check(t, "output AFTER the inner capture still goes somewhere — the outer one",
      outerContent.contains("after-inner"))
  }
}
