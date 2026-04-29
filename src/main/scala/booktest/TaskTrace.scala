package booktest

import java.time.Instant
import scala.collection.mutable

/** Structured lifecycle events emitted by the runner and the live-resource
  * manager. Intended for diagnostics: a thread-tagged record of what
  * happened in what order, queryable on failure to surface "this consumer
  * resolved its producer from the .bin disk cache while the producer was
  * running on another thread" in two lines instead of two hours of
  * staring at thread IDs.
  *
  * See `.ai/plan/task-graph.md` Issue 2 for the broader design. The
  * task-name field is the same qualified name used by the dependency
  * cache (`<suitePath>/<testName>`) so a downstream tool can grep for
  * the producer/consumer pair.
  */
sealed trait TraceEvent {
  def at: Instant
  def thread: String
  /** The task this event is *about*. For consumer events that mention a
    * dep, the dep name lives in event-specific fields. */
  def task: String
}

object TraceEvent {
  /** Scheduler decided this task is ready to run. `producers` is the
    * full set of upstream tests it had to wait for (Fix A's expanded
    * readiness set). */
  final case class SchedReady(
    at: Instant, thread: String, task: String,
    producers: List[String]
  ) extends TraceEvent

  /** A dep was resolved for this task. `source` is one of `memory`,
    * `bin`, `auto-run`, `live-acquire`. `summary` is a short string
    * representation of the resolved value (truncated). */
  final case class DepResolve(
    at: Instant, thread: String, task: String,
    dep: String, source: String, summary: String
  ) extends TraceEvent

  /** A task's `run` (or live-resource build closure) executed. */
  final case class TaskRun(
    at: Instant, thread: String, task: String,
    instance: String, durationMs: Long
  ) extends TraceEvent

  /** A consumer acquired a shared-task handle. */
  final case class TaskAcquire(
    at: Instant, thread: String, task: String,
    consumer: String, instance: String, refcount: Int
  ) extends TraceEvent

  /** A consumer released a shared-task handle. */
  final case class TaskRelease(
    at: Instant, thread: String, task: String,
    consumer: String, failed: Boolean, refcount: Int
  ) extends TraceEvent

  /** Reset closure ran between consumers (SharedWithReset only). */
  final case class TaskReset(
    at: Instant, thread: String, task: String,
    instance: String, durationMs: Long, ok: Boolean
  ) extends TraceEvent

  /** A live task closed (last consumer released, or end-of-run). */
  final case class TaskClose(
    at: Instant, thread: String, task: String,
    instance: String, durationMs: Long
  ) extends TraceEvent

  /** A test completed (OK / DIFF / FAIL). */
  final case class TaskEnd(
    at: Instant, thread: String, task: String,
    result: String, durationMs: Long
  ) extends TraceEvent

  /** Format an event as a single thread-tagged line for human-readable
    * grep. Compact; structured fields go after a `|` separator. */
  def render(e: TraceEvent): String = {
    val ts = e.at.toString
    val head = f"${e.thread}%-8s $ts%-30s"
    e match {
      case SchedReady(_, _, t, ps) =>
        s"$head sched-ready    $t  producers=[${ps.mkString(",")}]"
      case DepResolve(_, _, t, d, src, s) =>
        s"$head dep-resolve    $t  dep=$d source=$src value=${truncate(s)}"
      case TaskRun(_, _, t, inst, ms) =>
        s"$head task-run       $t  instance=$inst ms=$ms"
      case TaskAcquire(_, _, t, c, inst, rc) =>
        s"$head task-acquire   $t  consumer=$c instance=$inst refcount=$rc"
      case TaskRelease(_, _, t, c, f, rc) =>
        s"$head task-release   $t  consumer=$c failed=$f refcount=$rc"
      case TaskReset(_, _, t, inst, ms, ok) =>
        s"$head task-reset     $t  instance=$inst ms=$ms ok=$ok"
      case TaskClose(_, _, t, inst, ms) =>
        s"$head task-close     $t  instance=$inst ms=$ms"
      case TaskEnd(_, _, t, r, ms) =>
        s"$head task-end       $t  result=$r ms=$ms"
    }
  }

  private def truncate(s: String, max: Int = 80): String =
    if (s == null) "null"
    else if (s.length <= max) s
    else s.take(max - 1) + "…"
}

/** A sink for trace events. Implementations are thread-safe; events
  * arrive concurrently from worker threads. */
trait TaskTrace {
  def emit(e: TraceEvent): Unit
}

object TaskTrace {
  /** Drops events on the floor. Use when no diagnostics are wanted. */
  val Noop: TaskTrace = new TaskTrace { def emit(e: TraceEvent): Unit = () }

  /** Capture the current thread name for an event. Java's
    * `Thread.currentThread().getName` is sometimes long
    * ("ForkJoinPool.commonPool-worker-3"); keep it short for log
    * readability. */
  def currentThread(): String = {
    val n = Thread.currentThread().getName
    val short = n.lastIndexOf('-') match {
      case -1 => n
      case i  => "T" + n.substring(i + 1)
    }
    short.take(10)
  }

  /** Hex of identityHashCode — the per-instance fingerprint used in
    * trace events and failure blocks. Stable for the lifetime of a JVM
    * instance; comparable across events to confirm "same handle." */
  def identityHash(o: AnyRef): String =
    if (o == null) "null"
    else f"#${java.lang.System.identityHashCode(o)}%08x"
}

/** Bounded ring buffers — one global, one per task — that always run
  * (the cost is a few synchronized list ops per emit). On failure, the
  * runner pulls per-task events for a "Trace context" block in the
  * failure report. */
class RingBufferSink(
  perTaskCapacity: Int = 100,
  globalCapacity: Int = 5000
) extends TaskTrace {

  private val lock = new Object
  // java.util.ArrayDeque is cross-version-portable (Scala 2.12 doesn't
  // ship `scala.collection.mutable.ArrayDeque`).
  private val all = new java.util.ArrayDeque[TraceEvent]()
  private val byTask = mutable.Map[String, java.util.ArrayDeque[TraceEvent]]()

  override def emit(e: TraceEvent): Unit = lock.synchronized {
    all.addLast(e)
    while (all.size > globalCapacity) all.pollFirst()
    val q = byTask.getOrElseUpdate(e.task, new java.util.ArrayDeque[TraceEvent]())
    q.addLast(e)
    while (q.size > perTaskCapacity) q.pollFirst()
  }

  private def toList(q: java.util.ArrayDeque[TraceEvent]): List[TraceEvent] = {
    val it = q.iterator()
    val b = List.newBuilder[TraceEvent]
    while (it.hasNext) b += it.next()
    b.result()
  }

  /** All events touching `task`, in emission order. */
  def snapshot(task: String): List[TraceEvent] = lock.synchronized {
    byTask.get(task).map(toList).getOrElse(Nil)
  }

  /** All events for the given tasks, merged and re-sorted by timestamp. */
  def snapshotMany(tasks: Iterable[String]): List[TraceEvent] = lock.synchronized {
    val merged: List[TraceEvent] =
      tasks.flatMap(t => byTask.get(t).toList.flatMap(toList)).toList
    merged.sortBy(_.at.toEpochMilli)
  }

  /** Newest-first global snapshot; bounded by `globalCapacity`. */
  def snapshotGlobal: List[TraceEvent] = lock.synchronized { toList(all) }
}

/** Multiplex one event to many sinks. Keep enabled even if one sink
  * throws — diagnostics shouldn't take down a run. */
class BroadcastTrace(sinks: TaskTrace*) extends TaskTrace {
  override def emit(e: TraceEvent): Unit =
    sinks.foreach(s => try s.emit(e) catch { case _: Throwable => () })
}

/** Append every event as a single thread-tagged line to a logfile.
  * Lazy file open — no file is created until the first emit. */
class LogfileSink(path: os.Path) extends TaskTrace {
  private val lock = new Object
  @volatile private var writer: Option[java.io.PrintWriter] = None

  private def openIfNeeded(): java.io.PrintWriter = {
    writer match {
      case Some(w) => w
      case None =>
        os.makeDir.all(path / os.up)
        val fw = new java.io.FileWriter(path.toIO, /*append=*/ true)
        val pw = new java.io.PrintWriter(fw, /*autoFlush=*/ true)
        writer = Some(pw)
        pw
    }
  }

  override def emit(e: TraceEvent): Unit = lock.synchronized {
    try openIfNeeded().println(TraceEvent.render(e))
    catch { case _: Throwable => () }
  }

  def close(): Unit = lock.synchronized {
    writer.foreach { w => try w.close() catch { case _: Throwable => () } }
    writer = None
  }
}
