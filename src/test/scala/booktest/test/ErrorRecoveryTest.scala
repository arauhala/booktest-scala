package booktest.test

import booktest.*

/** Helper suite for ErrorRecoveryTest: one test throws an Error subclass
  * (AssertionError extends Error, not Exception); a sibling test does
  * not. Excluded from default discovery via booktest.ini. */
class ErrorThrowingHelper extends TestSuite {
  def testThrowsAssertion(t: TestCaseRun): Unit = {
    t.tln("about to throw an Error")
    // Use Error directly so getMessage is exactly the string we pass.
    // AssertionError/NotImplementedError quirks around message storage
    // are out of scope for this regression test.
    throw new Error("not done yet")
  }

  def testRunsAfterError(t: TestCaseRun): Unit = {
    t.tln("sibling test ran")
  }
}

/** Regression test: a single test throwing an Error subclass (e.g.
  * NotImplementedError, AssertionError) must not abort the rest of the
  * batch. Pre-fix, runTestCase caught only `Exception`, so Errors escaped
  * into the suite-level loop and aborted everything queued after the
  * offending test. */
class ErrorRecoveryTest extends TestSuite {

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

  def testErrorInTestDoesNotAbortBatch(t: TestCaseRun): Unit = {
    t.h1("Error subclass in one test does not abort sibling tests")

    val tempDir = t.tmpDir("error-recovery")
    val runner = new TestRunner(quietConfig(tempDir))
    val result = runner.runMultipleSuites(List(new ErrorThrowingHelper()))

    val thrower = result.results.find(_.testName.endsWith("/throwsAssertion")).get
    val sibling = result.results.find(_.testName.endsWith("/runsAfterError"))

    t.tln(s"thrower state: ${thrower.successState}")
    t.tln(s"sibling ran:   ${sibling.isDefined}")

    assert(thrower.successState == SuccessState.FAIL,
      "test that throws an Error should be recorded as FAIL")
    assert(sibling.isDefined,
      "sibling test must still execute after a sibling throws an Error")

    val diff = thrower.diff.getOrElse("")
    val mentionsCause = diff.contains("not done yet")
    t.tln(s"failure message mentions cause: $mentionsCause")
    assert(mentionsCause, s"failure diff should mention the cause: $diff")
  }
}
