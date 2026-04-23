package booktest.test

import booktest._

/**
 * Meta test: Verifies token-level diff/info/fail marking.
 */
class TokenMarkerTest extends TestSuite {

  /** Create a RunConfig with output redirected to a temp file */
  private def quietConfig(tempDir: os.Path, snapshotDir: os.Path): RunConfig = {
    val logStream = new java.io.PrintStream(
      new java.io.FileOutputStream((tempDir / "runner.log").toIO))
    RunConfig(
      outputDir = tempDir,
      snapshotDir = snapshotDir,
      verbose = false,
      output = logStream
    )
  }

  def testInfoTokensDontFail(t: TestCaseRun): Unit = {
    t.h1("Token Markers: Info Tokens Don't Fail")

    val tempDir = t.tmpDir("token-info")
    val snapshotDir = tempDir / "snapshots" / "TokenTestHelper"
    os.makeDir.all(snapshotDir)
    os.write(snapshotDir / "mixedLine.md", "label..99ms\n")

    val config = quietConfig(tempDir, tempDir / "snapshots")

    val suite = new TestSuite {
      override def suiteName: String = "TokenTestHelper"
      override def fullClassName: String = "TokenTestHelper"

      def testMixedLine(tc: TestCaseRun): Unit = {
        tc.t("label..")
        tc.iln("42ms")
      }
    }

    val runner = new TestRunner(config)
    val result = runner.runSuite(suite)
    val testResult = result.results.head

    t.tln(s"test passed: ${testResult.passed}")
    t.tln(s"test state: ${testResult.successState}")

    assert(testResult.successState == SuccessState.OK,
      s"Expected OK (info-only diff), got ${testResult.successState}")

    t.tln("PASS: info-only timing diff does not cause failure")
  }

  def testCheckedTokensFail(t: TestCaseRun): Unit = {
    t.h1("Token Markers: Checked Tokens Fail")

    val tempDir = t.tmpDir("token-checked")
    val snapshotDir = tempDir / "snapshots" / "TokenTestHelper2"
    os.makeDir.all(snapshotDir)
    os.write(snapshotDir / "checkedDiff.md", "expected content\n")

    val config = quietConfig(tempDir, tempDir / "snapshots")

    val suite = new TestSuite {
      override def suiteName: String = "TokenTestHelper2"
      override def fullClassName: String = "TokenTestHelper2"

      def testCheckedDiff(tc: TestCaseRun): Unit = {
        tc.tln("different content")
      }
    }

    val runner = new TestRunner(config)
    val result = runner.runSuite(suite)
    val testResult = result.results.head

    t.tln(s"test passed: ${testResult.passed}")
    t.tln(s"test state: ${testResult.successState}")

    assert(testResult.successState == SuccessState.DIFF,
      s"Expected DIFF, got ${testResult.successState}")

    t.tln("PASS: checked content diff correctly causes DIFF")
  }

  def testTokenizerAlignment(t: TestCaseRun): Unit = {
    t.h1("Token Markers: Tokenizer Alignment")

    val tempDir = t.tmpDir("token-alignment")
    val snapshotDir = tempDir / "snapshots" / "TokenTestHelper3"
    os.makeDir.all(snapshotDir)
    os.write(snapshotDir / "alignment.md", "step..55ms\ndone\n")

    val config = quietConfig(tempDir, tempDir / "snapshots")

    val suite = new TestSuite {
      override def suiteName: String = "TokenTestHelper3"
      override def fullClassName: String = "TokenTestHelper3"

      def testAlignment(tc: TestCaseRun): Unit = {
        tc.t("step..")
        tc.iln("23ms")
        tc.tln("done")
      }
    }

    val runner = new TestRunner(config)
    val result = runner.runSuite(suite)
    val testResult = result.results.head

    t.tln(s"test passed: ${testResult.passed}")
    t.tln(s"test state: ${testResult.successState}")

    assert(testResult.successState == SuccessState.OK,
      s"Expected OK (timing is info-only), got ${testResult.successState}")

    t.tln("PASS: tokenizer aligns correctly across separate feed calls")
  }

  def testDiffReportMarkers(t: TestCaseRun): Unit = {
    t.h1("Token Markers: Diff Report Symbols")

    val tempDir = t.tmpDir("token-report")
    val snapshotDir = tempDir / "snapshots" / "TokenTestHelper4"
    os.makeDir.all(snapshotDir)
    os.write(snapshotDir / "reportSymbols.md", "label..77ms\nchecked line\n")

    val config = quietConfig(tempDir, tempDir / "snapshots")

    val suite = new TestSuite {
      override def suiteName: String = "TokenTestHelper4"
      override def fullClassName: String = "TokenTestHelper4"

      def testReportSymbols(tc: TestCaseRun): Unit = {
        tc.t("label..")
        tc.iln("22ms")
        tc.tln("changed line")
      }
    }

    val runner = new TestRunner(config)
    val result = runner.runSuite(suite)
    val testResult = result.results.head

    t.tln(s"test state: ${testResult.successState}")

    assert(testResult.successState == SuccessState.DIFF,
      s"Expected DIFF, got ${testResult.successState}")

    testResult.diff.foreach { diff =>
      val hasInfoMarker = diff.contains(".")
      val hasDiffMarker = diff.contains("?")
      t.tln(s"diff has info marker (.): $hasInfoMarker")
      t.tln(s"diff has diff marker (?): $hasDiffMarker")
    }

    t.tln("PASS: diff report uses correct markers")
  }
}
