package booktest.examples

import booktest._
import booktest.DependsOn

/**
 * Migrated from Python booktest: test/setup_teardown_test.py
 * Tests setup and teardown hooks
 */
class SetupTeardownTest extends TestSuite {

  // Use a resource lock to prevent parallel execution of tests that share state
  override protected def resourceLocks: List[String] = List("SetupTeardownTest")

  private var setupCount = 0
  private var teardownCount = 0
  private var sharedResource: Option[String] = None

  override protected def beforeAll(): Unit = {
    // Called once before all tests
    sharedResource = Some("initialized")
  }

  override protected def afterAll(): Unit = {
    // Called once after all tests
    sharedResource = None
  }

  override protected def setup(t: TestCaseRun): Unit = {
    // Called before each test
    setupCount += 1
    t.iln(s"Setup called (count: $setupCount)")
  }

  override protected def teardown(t: TestCaseRun): Unit = {
    // Called after each test (even if it fails)
    teardownCount += 1
    t.iln(s"Teardown called (count: $teardownCount)")
  }

  def testFirstTest(t: TestCaseRun): Unit = {
    t.h1("First Test with Setup/Teardown")
    t.tln(s"Shared resource: ${sharedResource.getOrElse("none")}")
    t.tln("First test executed")
  }

  @DependsOn(Array("testFirstTest"))
  def testSecondTest(t: TestCaseRun): Unit = {
    t.h1("Second Test with Setup/Teardown")
    t.tln(s"Setup has been called: $setupCount times")
    t.tln("Second test executed")
  }

  @DependsOn(Array("testSecondTest"))
  def testThirdTest(t: TestCaseRun): Unit = {
    t.h1("Third Test with Setup/Teardown")
    t.tln(s"Teardown has been called: $teardownCount times before this")
    t.tln("Third test executed")
  }
}
