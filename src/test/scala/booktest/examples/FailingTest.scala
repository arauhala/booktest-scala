package booktest.examples

import booktest.{TestSuite, TestCaseRun}

class FailingTest extends TestSuite {
  
  def testWillFail(t: TestCaseRun): Unit = {
    t.h1("This will fail")
    t.tln("This output has been changed!")
    t.tln("Extra line added")
  }
}