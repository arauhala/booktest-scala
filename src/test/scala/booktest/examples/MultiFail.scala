package booktest.examples

import booktest.{TestSuite, TestCaseRun}

class MultiFail extends TestSuite {
  
  def testFirstFail(t: TestCaseRun): Unit = {
    t.h1("First failing test")
    t.tln("This is the UPDATED first failing test")
    t.tln("Content has been modified")
  }
  
  def testSecondFail(t: TestCaseRun): Unit = {
    t.h1("Second failing test")  
    t.tln("This is the DIFFERENT second test")
    t.tln("Lines have been altered")
  }
  
  def testThirdFail(t: TestCaseRun): Unit = {
    t.h1("Third failing test")
    t.tln("Content for the third test has CHANGED")
    t.tln("This should definitely fail now")
  }
}