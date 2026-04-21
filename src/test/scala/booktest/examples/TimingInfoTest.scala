package booktest.examples

import booktest.{TestSuite, TestCaseRun}

/** Tests that iMsLn timing differences don't cause test failure.
  *
  * Pattern: t.t("label..").iMsLn { work() }
  * The "label.." part is checked content, timing is info-only.
  * Timing varies between runs but should show as . (cyan info) not ? (yellow diff).
  */
class TimingInfoTest extends TestSuite {

  private def randomWork(): String = {
    // Sleep random 10-50ms to ensure timing varies between runs
    val ms = 10 + (Math.random() * 40).toInt
    Thread.sleep(ms)
    "done"
  }

  def testTimingOnSameLine(t: TestCaseRun): Unit = {
    t.h1("Timing Info Test")

    // Pattern: checked label followed by info-only timing on same line
    t.t("running a job..").iMsLn {
      randomWork()
    }
    t.tln("job completed")
  }

  def testMultipleTimings(t: TestCaseRun): Unit = {
    t.h1("Multiple Timings")

    val r1: String = t.t("step 1..").iMsLn { randomWork() }
    val r2: String = t.t("step 2..").iMsLn { randomWork() }
    val r3: String = t.t("step 3..").iMsLn { randomWork() }

    t.tln("all steps completed")
  }

  def testTimingWithLabel(t: TestCaseRun): Unit = {
    t.h1("Labeled Timing")

    // Use t() for the label, iMsLn for the timing
    t.t("processing..elapsed: ").iMsLn {
      randomWork()
    }

    t.tln("done")
  }
}
