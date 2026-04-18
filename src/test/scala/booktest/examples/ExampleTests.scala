package booktest.examples

import booktest.{TestSuite, TestCaseRun}

class ExampleTests extends TestSuite {
  
  def testHello(t: TestCaseRun): Unit = {
    t.h1("Hello Test")
    t.tln("Hello, World!")
    t.tln("This is a simple test")
  }
  
  def testCalculation(t: TestCaseRun): Unit = {
    t.h1("Calculation Test")
    val result = 2 + 2
    t.tln(s"2 + 2 = $result")
    
    val factorial = (1 to 5).product
    t.tln(s"5! = $factorial")
  }
  
  def testMultipleHeaders(t: TestCaseRun): Unit = {
    t.h1("Main Header")
    t.tln("Content under main header")
    
    t.h2("Sub Header")
    t.tln("Content under sub header")
    
    t.h3("Sub-sub Header")
    t.tln("Content under sub-sub header")
  }
  
  def testInfoAndData(t: TestCaseRun): Unit = {
    t.h1("Info vs Data Test")
    t.tln("This line will be checked against snapshot")
    t.i("This line is info only and won't be checked")
    t.iln("This info line ends with newline")
    t.tln("This line will be checked again")
  }
}