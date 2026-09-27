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

  /** Fatal throwables that escaped to a thread's top level, as one line each.
    *
    * Printing them is not enough, and that gap cost a real investigation: an
    * `OutOfMemoryError` inside `TestCaseRun.getTestOutput` killed a case's
    * thread, the message went to the console, and the run CARRIED ON — later
    * cases reported `ok`, the dead case's book was left half-written, and the
    * exit code stayed 0. A half-written book is worse than a missing one,
    * because the next `-C` run can accept it as evidence.
    *
    * So the fact is LATCHED here, process-wide, and the run's verdict reads it.
    * A latch rather than a counter reset per case: whatever escaped, escaped,
    * and no later success can retract it. */
  private val fatalEscapes = new java.util.concurrent.ConcurrentLinkedQueue[String]()

  private[booktest] def recordFatalEscape(description: String): Unit =
    fatalEscapes.add(description)

  /** Run `body` with the latch isolated, and report what IT latched.
    *
    * Only for booktest's own tests: a test that deliberately throws a
    * VirtualMachineError would otherwise trip the process-wide latch and fail the
    * very run that is testing it. Entries present beforehand are put back
    * afterwards, so a real escape elsewhere in the run cannot be swallowed by a
    * test that happens to run after it. */
  private[booktest] def withFatalEscapesIsolated[T](body: => T): (T, List[String]) =
    synchronized {
      val preexisting = drainFatalEscapes()
      try {
        val out = body
        // Drained INSIDE the try: if `body` throws, its entries are deliberately
        // left in the queue, because a genuine escape must not be swallowed by
        // the helper that was meant to isolate a synthetic one.
        (out, drainFatalEscapes())
      } finally preexisting.foreach(fatalEscapes.add)
    }

  private def drainFatalEscapes(): List[String] = {
    val b = List.newBuilder[String]
    var e = fatalEscapes.poll()
    while (e != null) { b += e; e = fatalEscapes.poll() }
    b.result()
  }

  /** Empty when nothing fatal escaped. Read once, at the end of a run. */
  private[booktest] def fatalEscapesSeen: List[String] = {
    val it = fatalEscapes.iterator()
    val b = List.newBuilder[String]
    while (it.hasNext) b += it.next()
    b.result()
  }

  /** Idempotent, and safe to call from several threads at once. */
  def install(): Unit = synchronized {
    if (!installed) {
      realOut = System.out
      realErr = System.err
      System.setOut(new PrintStream(new MuxOutputStream(outSink, realOut), true))
      System.setErr(new PrintStream(new MuxOutputStream(errSink, realErr), true))

      // A capture must never be able to swallow the reason a run died.
      //
      // Test bodies run inside a capture, and the runner's catch is NonFatal —
      // which by design does NOT catch OutOfMemoryError and friends. A fatal
      // error therefore escapes without the capture being torn down, and the
      // JVM's default handler writes the stack trace to System.err, which is
      // still this thread's capture buffer. The run dies with no trace, no
      // summary and no clue; we lost a CI run to exactly that.
      //
      // Route uncaught throwables to the real console, bypassing any sink.
      val previous = Thread.getDefaultUncaughtExceptionHandler
      Thread.setDefaultUncaughtExceptionHandler((t, e) => {
        try {
          recordFatalEscape(s"${e.getClass.getName} on thread '${t.getName}'")
          realErr.println(s"booktest: uncaught ${e.getClass.getName} on thread '${t.getName}'")
          e.printStackTrace(realErr)
          realErr.flush()
        } catch { case _: Throwable => () }
        if (previous != null) previous.uncaughtException(t, e)
      })

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
