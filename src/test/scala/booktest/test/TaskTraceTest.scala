package booktest.test

import booktest.*

/** Helper for the trace test: a producer/consumer pair where the consumer
  * intentionally fails so we can check the trace-context block. */
class TraceFailHelper extends TestSuite {

  val state: TestRef[String] = test("state") { (t: TestCaseRun) =>
    t.tln("state ran")
    "the-state"
  }

  case class Handle(s: String) extends AutoCloseable {
    override def close(): Unit = ()
  }

  val handle: ResourceRef[Handle] =
    liveResource("handle", state) { (s: String) => Handle(s) }

  test("consumer", handle) { (t: TestCaseRun, h: Handle) =>
    t.tln(s"consumer holds: ${h.s}")
    t.fail("force a fail so the trace block is attached")
  }
}

/** A clean producer/consumer pair, no failures, used to verify the
  * happy-path event stream. */
class TraceHappyHelper extends TestSuite {

  val state: TestRef[String] = test("state") { (t: TestCaseRun) =>
    t.tln("state ran")
    "the-state"
  }

  case class Handle(s: String) extends AutoCloseable {
    override def close(): Unit = ()
  }

  val handle: ResourceRef[Handle] =
    liveResource("handle", state) { (s: String) => Handle(s) }

  test("consumer", handle) { (t: TestCaseRun, h: Handle) =>
    t.tln(s"consumer holds: ${h.s}")
  }
}

/** Meta test: verifies the [[TaskTrace]] bus captures the events the
  * runner is supposed to emit, and that a failing test under -pN ≥ 2
  * carries a "Trace context" block on its diff. */
class TaskTraceTest extends TestSuite {

  private def quietConfig(tempDir: os.Path,
                          threads: Int = 1,
                          updateSnapshots: Boolean = false): RunConfig = {
    val logStream = new java.io.PrintStream(
      new java.io.FileOutputStream((tempDir / s"runner-p$threads.log").toIO))
    RunConfig(
      outputDir = tempDir,
      snapshotDir = tempDir,
      verbose = false,
      output = logStream,
      threads = threads,
      updateSnapshots = updateSnapshots
    )
  }

  /** Happy-path: the trace records SchedReady / DepResolve / TaskRun /
    * TaskAcquire / TaskRelease / TaskClose / TaskEnd in roughly the
    * order we'd expect for a producer + live-resource + consumer chain
    * under -p2. */
  def testTraceCapturesLifecycle(t: TestCaseRun): Unit = {
    t.h1("Task trace captures lifecycle events end-to-end")
    val tempDir = t.tmpDir("happy")

    val runner = new TestRunner(quietConfig(tempDir, threads = 2, updateSnapshots = true))
    runner.runMultipleSuites(List(new TraceHappyHelper))

    val events = runner.traceBuffer.snapshotGlobal
    val kinds = events.groupBy(_.getClass.getSimpleName).map {
      case (k, vs) => k -> vs.size
    }
    val kindSummary = kinds.toSeq.sortBy(_._1).map {
      case (k, n) => s"$k=$n"
    }.mkString(", ")
    t.tln(s"event kinds: $kindSummary")

    assert(events.exists(_.isInstanceOf[TraceEvent.SchedReady]),
      s"expected SchedReady; got: $kindSummary")
    assert(events.exists(_.isInstanceOf[TraceEvent.DepResolve]),
      s"expected DepResolve; got: $kindSummary")
    assert(events.exists(_.isInstanceOf[TraceEvent.TaskRun]),
      s"expected TaskRun (resource build); got: $kindSummary")
    assert(events.exists(_.isInstanceOf[TraceEvent.TaskAcquire]),
      s"expected TaskAcquire; got: $kindSummary")
    assert(events.exists(_.isInstanceOf[TraceEvent.TaskRelease]),
      s"expected TaskRelease; got: $kindSummary")
    assert(events.exists(_.isInstanceOf[TraceEvent.TaskClose]),
      s"expected TaskClose; got: $kindSummary")
    assert(events.exists(_.isInstanceOf[TraceEvent.TaskEnd]),
      s"expected TaskEnd; got: $kindSummary")

    t.tln("PASS: every expected lifecycle event was emitted")
  }

  /** DepResolve carries a `source` field that distinguishes
    * memory/bin/miss/auto-run. After a fresh run, the consumer's
    * dep resolution should hit source=memory (not bin) because Fix A
    * holds it back until the producer has populated the in-memory
    * cache. */
  def testDepResolveAnnotatesSource(t: TestCaseRun): Unit = {
    t.h1("DepResolve events annotate the cache source")
    val tempDir = t.tmpDir("source")

    val runner = new TestRunner(quietConfig(tempDir, threads = 2, updateSnapshots = true))
    runner.runMultipleSuites(List(new TraceHappyHelper))

    val resolves = runner.traceBuffer.snapshotGlobal.collect {
      case e: TraceEvent.DepResolve => e
    }
    val sources = resolves.map(_.source).toSet
    t.tln(s"DepResolve sources observed: ${sources.toSeq.sorted.mkString(", ")}")

    assert(resolves.nonEmpty, "expected at least one DepResolve event")
    assert(sources.contains("memory"),
      s"expected at least one source=memory resolution under Fix A; got $sources")

    t.tln("PASS: source annotations are present")
  }

  /** A failing test at -p2 carries a "Trace context" block on its
    * `diff`. */
  def testFailingTestHasTraceBlock(t: TestCaseRun): Unit = {
    t.h1("Failing test result has a Trace context block")
    val tempDir = t.tmpDir("failblock")

    val runner = new TestRunner(quietConfig(tempDir, threads = 2))
    val r = runner.runMultipleSuites(List(new TraceFailHelper))

    val consumer = r.results.find(_.testName.endsWith("/consumer")).get
    t.tln(s"consumer state: ${consumer.successState}")

    val diff = consumer.diff.getOrElse("")
    val hasBlock = diff.contains("Trace context for")
    t.tln(s"diff contains trace block: $hasBlock")
    t.tln(s"diff length: ${diff.length}")

    assert(consumer.successState == SuccessState.FAIL ||
           consumer.successState == SuccessState.DIFF,
      s"consumer should be failing; got ${consumer.successState}")
    assert(hasBlock,
      "failing-test diff at -p2 should auto-include a Trace context block")

    t.tln("PASS: trace block auto-attached to failing result")
  }
}
