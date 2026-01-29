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
  private var _failed = false
  private var _failMessage: Option[String] = None

  // Snapshot reader state - tokenized view of existing snapshot
  private lazy val snapshotTokens: Array[String] = {
    if (os.exists(snapshotFile)) {
      val content = os.read(snapshotFile)
      // Tokenize: split on whitespace and common delimiters, keeping numbers intact
      content.split("\\s+|(?<=[^0-9.eE+-])|(?=[^0-9.eE+-])").filter(_.nonEmpty)
    } else {
      Array.empty
    }
  }
  private var snapshotTokenIndex = 0
  
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

  /** Mark the test as failed without throwing an exception */
  def fail(message: String = ""): TestCaseRun = {
    _failed = true
    _failMessage = if (message.nonEmpty) Some(message) else None
    this
  }

  /** Check if test has been marked as failed */
  def isFailed: Boolean = _failed

  /** Get the failure message if test was marked as failed */
  def failMessage: Option[String] = _failMessage

  /** Get a File in the test output directory */
  def file(name: String): java.io.File = {
    val filePath = outDir / name
    os.makeDir.all(filePath / os.up)
    filePath.toIO
  }

  /** Execute block, print elapsed time as info line, return block result */
  def iMsLn[T](block: => T): T = {
    iMsLn("")(block)
  }

  /** Execute block with label, print elapsed time as info line, return block result */
  def iMsLn[T](label: String)(block: => T): T = {
    val start = System.currentTimeMillis()
    val result = block
    val elapsed = System.currentTimeMillis() - start
    if (label.nonEmpty) {
      iln(s"$label: ${elapsed}ms")
    } else {
      iln(s"${elapsed}ms")
    }
    result
  }

  /** Execute block, return (elapsed_ms, result) tuple */
  def ms[T](block: => T): (Long, T) = {
    val start = System.currentTimeMillis()
    val result = block
    val elapsed = System.currentTimeMillis() - start
    (elapsed, result)
  }

  /** Execute block n times, print us/operation as info line, return last result */
  def iUsPerOpLn[T](n: Int, label: String = "")(block: => T): T = {
    val start = System.nanoTime()
    var result: T = block
    var i = 1
    while (i < n) {
      result = block
      i += 1
    }
    val elapsed = System.nanoTime() - start
    val usPerOp = elapsed / 1000.0 / n
    if (label.nonEmpty) {
      iln(f"$label: $usPerOp%.2f us/op")
    } else {
      iln(f"$usPerOp%.2f us/op")
    }
    result
  }

  /** Execute block n times, print us/operation as test line, return last result */
  def tUsPerOpLn[T](n: Int, label: String = "")(block: => T): T = {
    val start = System.nanoTime()
    var result: T = block
    var i = 1
    while (i < n) {
      result = block
      i += 1
    }
    val elapsed = System.nanoTime() - start
    val usPerOp = elapsed / 1000.0 / n
    if (label.nonEmpty) {
      tln(f"$label: $usPerOp%.2f us/op")
    } else {
      tln(f"$usPerOp%.2f us/op")
    }
    result
  }

  /** Output a long value with unit as test line */
  def tLongLn(value: Long, unit: String = "", max: Long = Long.MaxValue): TestCaseRun = {
    val suffix = if (unit.nonEmpty) s" $unit" else ""
    val warning = if (value > max) " [EXCEEDED]" else ""
    tln(s"$value$suffix$warning")
  }

  /** Output a double value with unit as test line */
  def tDoubleLn(value: Double, unit: String = "", max: Double = Double.MaxValue): TestCaseRun = {
    val suffix = if (unit.nonEmpty) s" $unit" else ""
    val warning = if (value > max) " [EXCEEDED]" else ""
    tln(f"$value%.2f$suffix$warning")
  }

  /** Assert condition, output result as test line */
  def assertln(condition: Boolean): TestCaseRun = {
    if (condition) {
      tln("OK")
    } else {
      tln("FAILED")
      fail("Assertion failed")
    }
  }

  /** Assert condition with label, output result as test line */
  def assertln(label: String, condition: Boolean): TestCaseRun = {
    if (condition) {
      tln(s"$label: OK")
    } else {
      tln(s"$label: FAILED")
      fail(s"Assertion failed: $label")
    }
  }

  /** Peek at next token in snapshot, try to parse as Double */
  def peekDouble: Option[Double] = {
    findNextNumber.flatMap { token =>
      try {
        Some(token.toDouble)
      } catch {
        case _: NumberFormatException => None
      }
    }
  }

  /** Peek at next token in snapshot, try to parse as Long */
  def peekLong: Option[Long] = {
    findNextNumber.flatMap { token =>
      try {
        Some(token.toLong)
      } catch {
        case _: NumberFormatException => None
      }
    }
  }

  /** Peek at next token in snapshot as String */
  def peekToken: Option[String] = {
    if (snapshotTokenIndex < snapshotTokens.length) {
      Some(snapshotTokens(snapshotTokenIndex))
    } else {
      None
    }
  }

  /** Skip to next token in snapshot */
  def skipToken(): Unit = {
    if (snapshotTokenIndex < snapshotTokens.length) {
      snapshotTokenIndex += 1
    }
  }

  /** Find next numeric token in snapshot (advances index) */
  private def findNextNumber: Option[String] = {
    while (snapshotTokenIndex < snapshotTokens.length) {
      val token = snapshotTokens(snapshotTokenIndex)
      snapshotTokenIndex += 1
      // Check if it looks like a number
      if (token.nonEmpty && (token.head.isDigit || token.head == '-' || token.head == '.')) {
        try {
          token.toDouble // validate it's a number
          return Some(token)
        } catch {
          case _: NumberFormatException => // continue searching
        }
      }
    }
    None
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