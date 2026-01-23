package booktest

import os.Path

class TestCaseRun(
  val testName: String,
  val testPath: Path,
  val outputDir: Path,
  val snapshotDir: Path,
  val outDir: Path // .out directory for test results before review
) {
  private val outputBuffer = new StringBuilder
  private val infoBuffer = new StringBuilder
  private var lineNumber = 0
  
  val outputFile: Path = outputDir / s"$testName.md"
  val snapshotFile: Path = snapshotDir / s"$testName.md"
  val outFile: Path = outDir / s"$testName.md"  // Results written here first
  val reportFile: Path = outDir / s"$testName.txt"  // Test reports
  val logFile: Path = outDir / s"$testName.log"  // Test logs
  
  def h1(title: String): TestCaseRun = {
    header(s"# $title")
  }
  
  def h2(title: String): TestCaseRun = {
    header(s"## $title")
  }
  
  def h3(title: String): TestCaseRun = {
    header(s"### $title")
  }
  
  def tln(text: String = ""): TestCaseRun = {
    testFeed(text)
    testFeed("\n")
    this
  }
  
  def t(text: String): TestCaseRun = {
    testFeed(text)
    this
  }
  
  def i(text: String): TestCaseRun = {
    infoFeed(text)
    this
  }
  
  def iln(text: String = ""): TestCaseRun = {
    infoFeed(text)
    infoFeed("\n")
    this
  }
  
  private def header(headerText: String): TestCaseRun = {
    testFeed("\n")
    testFeed(headerText)
    testFeed("\n\n")
    this
  }
  
  private def testFeed(text: String): Unit = {
    outputBuffer.append(text)
    if (text == "\n") {
      lineNumber += 1
    }
  }
  
  private def infoFeed(text: String): Unit = {
    infoBuffer.append(text)
    outputBuffer.append(text)
    if (text == "\n") {
      lineNumber += 1
    }
  }
  
  def getOutput: String = outputBuffer.toString
  
  def writeOutput(): Unit = {
    // Write to .out directory first (before review/acceptance)
    os.makeDir.all(outFile / os.up)
    os.write.over(outFile, getOutput)
  }
  
  def writeReport(message: String): Unit = {
    os.makeDir.all(reportFile / os.up)
    os.write.over(reportFile, message)
  }
  
  def acceptSnapshot(): Unit = {
    // Move from .out to final snapshot location after review acceptance
    if (os.exists(outFile)) {
      os.makeDir.all(snapshotFile / os.up)
      os.copy.over(outFile, snapshotFile)
    }
  }
  
  def hasSnapshot: Boolean = os.exists(snapshotFile)
  
  def getSnapshot: Option[String] = {
    if (hasSnapshot) {
      Some(os.read(snapshotFile))
    } else {
      None
    }
  }
  
  def compareWithSnapshot(): Boolean = {
    getSnapshot match {
      case Some(snapshot) => snapshot.trim == getOutput.trim
      case None => false
    }
  }
}