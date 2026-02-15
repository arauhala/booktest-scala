package booktest.test

import booktest._

/**
 * Meta test: Tests the continue mode (-c) functionality.
 * This is a "booktest testing booktest" scenario.
 */
class ContinueModeTest extends TestSuite {

  def testContinueModeSkipsSuccessfulTests(t: TestCaseRun): Unit = {
    t.h1("Continue Mode Test")

    // Create a temporary test environment
    val tempDir = t.tmpDir("continue-test")
    val outDir = tempDir / ".out"
    os.makeDir.all(outDir)

    // Create a cases.ndjson with some "passed" tests
    val casesFile = outDir / "cases.ndjson"
    os.write(casesFile,
      """{"name":"test/suite1/testA","result":"OK","duration_ms":10}
        |{"name":"test/suite1/testB","result":"OK","duration_ms":20}
        |{"name":"test/suite1/testC","result":"DIFF","duration_ms":30}
        |""".stripMargin)

    // Load the reports
    val reports = CaseReports.fromDir(outDir)
    t.tln(s"Loaded ${reports.cases.length} case reports")

    // Test cases_to_done_and_todo with continue mode
    val selectedCases = List(
      "test/suite1/testA",
      "test/suite1/testB",
      "test/suite1/testC"
    )

    val (done, todo) = reports.casesToDoneAndTodo(selectedCases, continueMode = true)

    t.tln(s"Done (skipped): ${done.length} tests")
    t.tln(s"Todo (to run): ${todo.length} tests")

    // Verify: testA and testB should be skipped (OK), testC should run (DIFF)
    assert(done.length == 2, s"Expected 2 done, got ${done.length}")
    assert(todo.length == 1, s"Expected 1 todo, got ${todo.length}")
    assert(todo.contains("test/suite1/testC"), "testC should be in todo list")

    t.tln("Continue mode correctly identifies tests to skip and run")
  }

  def testContinueModeDisabledRunsAll(t: TestCaseRun): Unit = {
    t.h1("Continue Mode Disabled Test")

    // Create a temporary test environment
    val tempDir = t.tmpDir("continue-disabled-test")
    val outDir = tempDir / ".out"
    os.makeDir.all(outDir)

    // Create a cases.ndjson with some "passed" tests
    val casesFile = outDir / "cases.ndjson"
    os.write(casesFile,
      """{"name":"test/suite1/testA","result":"OK","duration_ms":10}
        |{"name":"test/suite1/testB","result":"OK","duration_ms":20}
        |""".stripMargin)

    // Load the reports
    val reports = CaseReports.fromDir(outDir)

    // Test cases_to_done_and_todo with continue mode OFF
    val selectedCases = List(
      "test/suite1/testA",
      "test/suite1/testB"
    )

    val (done, todo) = reports.casesToDoneAndTodo(selectedCases, continueMode = false)

    t.tln(s"Done (skipped): ${done.length} tests")
    t.tln(s"Todo (to run): ${todo.length} tests")

    // Verify: all tests should run when continue mode is disabled
    assert(done.isEmpty, s"Expected 0 done, got ${done.length}")
    assert(todo.length == 2, s"Expected 2 todo, got ${todo.length}")

    t.tln("Continue mode disabled correctly runs all tests")
  }

  def testNdjsonParsing(t: TestCaseRun): Unit = {
    t.h1("NDJSON Parsing Test")

    // Create a temporary test environment
    val tempDir = t.tmpDir("ndjson-test")
    val outDir = tempDir / ".out"
    os.makeDir.all(outDir)

    // Create a cases.ndjson file
    val casesFile = outDir / "cases.ndjson"
    os.write(casesFile,
      """{"name":"test/a","result":"OK","duration_ms":100}
        |{"name":"test/b","result":"DIFF","duration_ms":200}
        |{"name":"test/c","result":"FAIL","duration_ms":300}
        |""".stripMargin)

    // Load and verify
    val reports = CaseReports.fromDir(outDir)

    t.tln(s"Loaded ${reports.cases.length} cases")
    t.tln(s"Passed: ${reports.passed().mkString(", ")}")
    t.tln(s"Failed: ${reports.failed().mkString(", ")}")

    assert(reports.cases.length == 3, "Should have 3 cases")
    assert(reports.passed() == List("test/a"), "Only test/a should pass")
    assert(reports.failed().toSet == Set("test/b", "test/c"), "test/b and test/c should fail")

    t.tln("NDJSON parsing works correctly")
  }
}
