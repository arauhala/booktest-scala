package booktest.test

import booktest._

/**
 * Meta test: Verifies token-level diff/info/fail marking.
 *
 * Tests that:
 * - t() tokens that differ from snapshot produce diff markers (test failure)
 * - i() tokens that differ from snapshot produce info markers (no failure)
 * - Mixed t()/i() on the same line correctly distinguishes checked vs info
 * - Tokenizer alignment works across separate feed calls
 */
class TokenMarkerTest extends TestSuite {

  def testInfoTokensDontFail(t: TestCaseRun): Unit = {
    t.h1("Token Markers: Info Tokens Don't Fail")

    val tempDir = t.tmpDir("token-info")
    val snapshotDir = tempDir / "snapshots" / "TokenTestHelper"
    os.makeDir.all(snapshotDir)

    // Create a snapshot with timing info (test name has "test" prefix stripped)
    os.write(snapshotDir / "mixedLine.md", "label..99ms\n")

    val config = RunConfig(
      outputDir = tempDir,
      snapshotDir = tempDir / "snapshots",
      verbose = false
    )

    // Create a helper suite that outputs the same label with different timing
    val suite = new TestSuite {
      override def suiteName: String = "TokenTestHelper"
      override def fullClassName: String = "TokenTestHelper"

      def testMixedLine(tc: TestCaseRun): Unit = {
        // "label.." is checked, "42ms" is info — timing differs from snapshot "99ms"
        tc.t("label..")
        tc.iln("42ms")
      }
    }

    val runner = new TestRunner(config)
    val result = runner.runSuite(suite)
    val testResult = result.results.head

    t.tln(s"test passed: ${testResult.passed}")
    t.tln(s"test state: ${testResult.successState}")

    // The test should pass — label matches, timing is info-only
    assert(testResult.successState == SuccessState.OK,
      s"Expected OK (info-only diff), got ${testResult.successState}")

    t.tln("PASS: info-only timing diff does not cause failure")
  }

  def testCheckedTokensFail(t: TestCaseRun): Unit = {
    t.h1("Token Markers: Checked Tokens Fail")

    val tempDir = t.tmpDir("token-checked")
    val snapshotDir = tempDir / "snapshots" / "TokenTestHelper2"
    os.makeDir.all(snapshotDir)

    // Create a snapshot with specific content (test name has "test" prefix stripped)
    os.write(snapshotDir / "checkedDiff.md", "expected content\n")

    val config = RunConfig(
      outputDir = tempDir,
      snapshotDir = tempDir / "snapshots",
      verbose = false
    )

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

    // Snapshot has "step..55ms" — the ".55" should NOT be tokenized as decimal number
    os.write(snapshotDir / "alignment.md", "step..55ms\ndone\n")

    val config = RunConfig(
      outputDir = tempDir,
      snapshotDir = tempDir / "snapshots",
      verbose = false
    )

    val suite = new TestSuite {
      override def suiteName: String = "TokenTestHelper3"
      override def fullClassName: String = "TokenTestHelper3"

      def testAlignment(tc: TestCaseRun): Unit = {
        // "step.." via testFeed, "23ms\n" via infoFeed — different timing
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

    // Should be OK — "step" and ".." match, timing is info-only, "done" matches
    assert(testResult.successState == SuccessState.OK,
      s"Expected OK (timing is info-only), got ${testResult.successState}")

    t.tln("PASS: tokenizer aligns correctly across separate feed calls")
  }

  def testDiffReportMarkers(t: TestCaseRun): Unit = {
    t.h1("Token Markers: Diff Report Symbols")

    val tempDir = t.tmpDir("token-report")
    val snapshotDir = tempDir / "snapshots" / "TokenTestHelper4"
    os.makeDir.all(snapshotDir)

    // Snapshot with timing (test name has "test" prefix stripped)
    os.write(snapshotDir / "reportSymbols.md", "label..77ms\nchecked line\n")

    val config = RunConfig(
      outputDir = tempDir,
      snapshotDir = tempDir / "snapshots",
      verbose = false
    )

    val suite = new TestSuite {
      override def suiteName: String = "TokenTestHelper4"
      override def fullClassName: String = "TokenTestHelper4"

      def testReportSymbols(tc: TestCaseRun): Unit = {
        tc.t("label..")
        tc.iln("22ms")       // info diff — should show as . (cyan)
        tc.tln("changed line")  // checked diff — should show as ? (yellow)
      }
    }

    val runner = new TestRunner(config)
    val result = runner.runSuite(suite)
    val testResult = result.results.head

    t.tln(s"test state: ${testResult.successState}")

    // Should be DIFF because "changed line" != "checked line"
    assert(testResult.successState == SuccessState.DIFF,
      s"Expected DIFF, got ${testResult.successState}")

    // Check the diff report contains the right markers
    testResult.diff.foreach { diff =>
      // . for info-only line (timing), ? for checked diff line
      val hasInfoMarker = diff.contains(".")  // cyan info marker
      val hasDiffMarker = diff.contains("?")  // yellow diff marker
      t.tln(s"diff has info marker (.): $hasInfoMarker")
      t.tln(s"diff has diff marker (?): $hasDiffMarker")
    }

    t.tln("PASS: diff report uses correct markers")
  }
}
