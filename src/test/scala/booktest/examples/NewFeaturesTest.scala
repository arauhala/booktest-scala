package booktest.examples

import booktest.*

/** Tests demonstrating new API features: fail(), file(), iMsLn() */
class NewFeaturesTest extends TestSuite {

  def testTimingWithIMsLn(t: TestCaseRun): Unit = {
    t.h1("Timing Test")
    t.tln("Testing iMsLn timing utility")

    // Timing with label
    val result1 = t.iMsLn("Quick operation") {
      Thread.sleep(10)
      "computed value"
    }
    t.tln(s"Result: $result1")

    // Another timing with label
    val result2 = t.iMsLn("Heavy computation") {
      Thread.sleep(15)
      42
    }
    t.tln(s"Answer: $result2")
  }

  def testFileCreation(t: TestCaseRun): Unit = {
    t.h1("File Test")
    t.tln("Testing file() utility")

    val testFile = t.file("test-output.txt")
    t.tln(s"File path: ${testFile.getName}")

    // Write to the file
    val writer = new java.io.PrintWriter(testFile)
    writer.println("Hello from test!")
    writer.close()

    // Read it back
    val content = scala.io.Source.fromFile(testFile).mkString
    t.tln(s"File content: ${content.trim}")
  }

  def testFailMethod(t: TestCaseRun): Unit = {
    t.h1("Fail Test")
    t.tln("This test demonstrates t.fail()")

    val value = 5
    t.tln(s"Value is: $value")

    if (value < 10) {
      t.fail("Value should be >= 10")
    }

    t.tln("This line still executes after fail()")
  }

  def testPassingTest(t: TestCaseRun): Unit = {
    t.h1("Passing Test")
    t.tln("This test should pass normally")

    val result = 2 + 2
    t.tln(s"2 + 2 = $result")

    // No fail() called, so this passes
  }
}
