package booktest.test

import booktest.*

/** Helper suite for LiveResourceFailureTest: build always throws.
  * Excluded from default discovery via booktest.ini. Used programmatically
  * by the meta test below. */
class BuildThrowingHelper extends TestSuite {
  val handle: ResourceRef[AutoCloseable] =
    liveResource("brokenBuild") {
      throw new RuntimeException("boom on build")
    }

  test("triesToUse", handle) { (t: TestCaseRun, h: AutoCloseable) =>
    // Unreachable: the runner aborts before this body runs.
    t.tln(s"unreachable: $h")
  }

  test("unrelatedSibling") { (t: TestCaseRun) =>
    t.tln("independent test still runs after the build failure")
  }
}

/** Meta-test for the build-throws path: stack-trace content is unstable
  * across edits, so we run the helper suite programmatically and assert on
  * the result objects + side-effect counters. */
class LiveResourceFailureTest extends TestSuite {

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

  def testBuildFailureSurfacesAsConsumerFail(t: TestCaseRun): Unit = {
    t.h1("Build failure surfaces as consumer FAIL")

    val tempDir = t.tmpDir("build-fail")
    val config = quietConfig(tempDir)
    val runner = new TestRunner(config)

    val result = runner.runMultipleSuites(List(new BuildThrowingHelper()))

    val triesToUse = result.results.find(_.testName.endsWith("/triesToUse")).get
    val unrelated = result.results.find(_.testName.endsWith("/unrelatedSibling")).get

    t.tln(s"triesToUse state:  ${triesToUse.successState}")
    // Unrelated runs in a temp dir with no baseline snapshot, so its
    // state is DIFF (content didn't match a missing snapshot). What we
    // actually care about is that it DID run — i.e. didn't FAIL because
    // of the sibling's build failure.
    t.tln(s"unrelated ran (state != FAIL): ${unrelated.successState != SuccessState.FAIL}")

    assert(triesToUse.successState == SuccessState.FAIL,
      "build-throws should produce FAIL not DIFF")
    assert(unrelated.successState != SuccessState.FAIL,
      "an independent test in the same suite should still run after a build failure")

    val diff = triesToUse.diff.getOrElse("")
    val mentionsCause = diff.contains("boom on build")
    t.tln(s"failure message mentions cause: $mentionsCause")
    assert(mentionsCause, s"failure diff should mention the cause: $diff")
  }
}
