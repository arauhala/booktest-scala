package booktest

import java.io.{ByteArrayOutputStream, PrintStream, OutputStream}

/**
 * Process-wide stdout/stderr multiplexer.
 *
 * `System.out` is global, but capture is per-test, and under `-pN` several
 * tests capture at once. The obvious implementation — each capture saves the
 * current `System.out` and restores it when done — is a race, and it silently
 * destroys output:
 *
 *   thread A start(): original = realStdout; setOut(teeA)
 *   thread B start(): original = teeA          <- captures A's tee as "original"
 *   thread A stop() : setOut(realStdout)
 *   thread B stop() : setOut(teeA)             <- stdout is now a dead buffer
 *
 * From then on everything printed to `System.out` lands in a
 * ByteArrayOutputStream nobody reads. That is how the end-of-run
 * "N/N test succeeded" summary vanished on every multi-suite `-pN` run: the
 * per-test lines go through the runner's own stream (captured before any
 * redirection) and survived, so the loss looked like a missing feature rather
 * than lost output.
 *
 * So: install ONE stream, once, and never restore. It dispatches by thread —
 * a thread that has registered a sink writes there, every other thread writes
 * to the real console. There is no save/restore step left to race on.
 */
private[booktest] object OutputMux {
  // Inheritable: a test that spawns threads (servers, executors) should have
  // their output captured with it rather than leaking to the console.
  private val outSink = new InheritableThreadLocal[OutputStream]()
  private val errSink = new InheritableThreadLocal[OutputStream]()

  @volatile private var installed = false
  private var realOut: PrintStream = System.out
  private var realErr: PrintStream = System.err

  /** Idempotent, and safe to call from several threads at once. */
  def install(): Unit = synchronized {
    if (!installed) {
      realOut = System.out
      realErr = System.err
      System.setOut(new PrintStream(new MuxOutputStream(outSink, realOut), true))
      System.setErr(new PrintStream(new MuxOutputStream(errSink, realErr), true))
      installed = true
    }
  }

  /** The console, whatever System.out currently is. Used for output that must
    * never be swallowed by a capture. */
  def console: PrintStream = realOut

  def setSinks(o: OutputStream, e: OutputStream): (Option[OutputStream], Option[OutputStream]) = {
    val prev = (Option(outSink.get()), Option(errSink.get()))
    outSink.set(o); errSink.set(e)
    prev
  }

  /** Restore whatever this thread had before — an Option, because nesting is
    * legal and the outer capture must survive the inner one ending. */
  def restoreSinks(prev: (Option[OutputStream], Option[OutputStream])): Unit = {
    prev._1 match { case Some(o) => outSink.set(o); case None => outSink.remove() }
    prev._2 match { case Some(e) => errSink.set(e); case None => errSink.remove() }
  }
}

/** Writes to this thread's registered sink, or to the console if it has none. */
private class MuxOutputStream(sink: InheritableThreadLocal[OutputStream], console: OutputStream)
    extends OutputStream {
  private def target: OutputStream = {
    val s = sink.get()
    if (s != null) s else console
  }
  override def write(b: Int): Unit = target.write(b)
  override def write(b: Array[Byte]): Unit = target.write(b)
  override def write(b: Array[Byte], off: Int, len: Int): Unit = target.write(b, off, len)
  override def flush(): Unit = target.flush()
}

/**
 * Captures stdout and stderr during test execution.
 * Python booktest-style logging where output goes to log files by default.
 *
 * Capture is per-thread (see OutputMux), so concurrent tests cannot steal each
 * other's output or corrupt the console.
 */
class LogCapture(logFile: os.Path) {
  private var captureOut: ByteArrayOutputStream = _
  private var captureErr: ByteArrayOutputStream = _
  private var previous: (Option[OutputStream], Option[OutputStream]) = (None, None)
  private var active = false

  /** Start capturing stdout and stderr for THIS thread */
  def start(): Unit = {
    OutputMux.install()
    captureOut = new ByteArrayOutputStream()
    captureErr = new ByteArrayOutputStream()
    previous = OutputMux.setSinks(captureOut, captureErr)
    active = true
  }

  /** Stop capturing and return the captured content */
  def stop(): String = {
    if (!active) return ""
    System.out.flush()
    System.err.flush()
    OutputMux.restoreSinks(previous)
    active = false

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
      if (active) {
        OutputMux.restoreSinks(previous)
        active = false
      }
    } catch {
      case _: Exception => // ignore
    }
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
