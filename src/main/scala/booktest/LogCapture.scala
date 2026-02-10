package booktest

import java.io.{ByteArrayOutputStream, PrintStream, OutputStream}

/**
 * Captures stdout and stderr during test execution.
 * Python booktest-style logging where output goes to log files by default.
 */
class LogCapture(logFile: os.Path) {
  private var originalOut: PrintStream = _
  private var originalErr: PrintStream = _
  private var captureOut: ByteArrayOutputStream = _
  private var captureErr: ByteArrayOutputStream = _
  private var teeOut: PrintStream = _
  private var teeErr: PrintStream = _

  /** Start capturing stdout and stderr */
  def start(): Unit = {
    originalOut = System.out
    originalErr = System.err
    captureOut = new ByteArrayOutputStream()
    captureErr = new ByteArrayOutputStream()

    // Create tee streams that write to both capture and file
    teeOut = new PrintStream(new TeeOutputStream(captureOut, originalOut), true)
    teeErr = new PrintStream(new TeeOutputStream(captureErr, originalErr), true)

    System.setOut(teeOut)
    System.setErr(teeErr)
  }

  /** Stop capturing and return the captured content */
  def stop(): String = {
    System.out.flush()
    System.err.flush()

    System.setOut(originalOut)
    System.setErr(originalErr)

    val stdout = captureOut.toString("UTF-8")
    val stderr = captureErr.toString("UTF-8")

    val combined = new StringBuilder
    if (stdout.nonEmpty) {
      combined.append("=== STDOUT ===\n")
      combined.append(stdout)
      if (!stdout.endsWith("\n")) combined.append("\n")
    }
    if (stderr.nonEmpty) {
      combined.append("=== STDERR ===\n")
      combined.append(stderr)
      if (!stderr.endsWith("\n")) combined.append("\n")
    }

    // Write to log file
    os.makeDir.all(logFile / os.up)
    os.write.over(logFile, combined.toString)

    combined.toString
  }

  /** Stop capturing without writing (for cleanup on error) */
  def abort(): Unit = {
    try {
      System.setOut(originalOut)
      System.setErr(originalErr)
    } catch {
      case _: Exception => // ignore
    }
  }
}

/** OutputStream that writes to multiple targets */
private class TeeOutputStream(capture: OutputStream, pass: OutputStream) extends OutputStream {
  override def write(b: Int): Unit = {
    capture.write(b)
    // Don't pass through to console in quiet mode
    // pass.write(b)
  }

  override def write(b: Array[Byte]): Unit = {
    capture.write(b)
  }

  override def write(b: Array[Byte], off: Int, len: Int): Unit = {
    capture.write(b, off, len)
  }

  override def flush(): Unit = {
    capture.flush()
    pass.flush()
  }
}

object LogCapture {
  /** Execute a block with stdout/stderr captured to a log file */
  def withCapture[T](logFile: os.Path)(block: => T): T = {
    val capture = new LogCapture(logFile)
    capture.start()
    try {
      block
    } finally {
      capture.stop()
    }
  }
}
