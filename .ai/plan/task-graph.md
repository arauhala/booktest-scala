# Task Graph for Booktest-Scala

Status: design proposal, not yet implemented.
Audience: framework maintainers, Aito Core integration team, Claude in
future sessions.

> **Naming.** Throughout this document, **Task** is the unified unit
> of work in the graph: a test, a live resource, a pool/capacity
> allocation, a future artifact, or a future composite. The term is
> the sbt / Gradle / Mill / Airflow standard for "a value-producing
> dependency-aware unit of work" and aligns with the colloquial *task
> graph* / *build graph* used by all of those. Where this document
> says "task," read "the abstraction that subsumes today's test cases
> and live resources." Lowercase "build" / "build closure" /
> "first-time build" retain their natural English meanings (the verb
> of running a task).

## Why this document exists

Two pieces of evidence converged in late April 2026:

1. The `DependencyCache.memoryCache` lost-write race fix (0.4.2) cleared
   3 of 5 hard failures the Aito Core integration project saw at `-p2`,
   but two failures remained. Both stem from the same architectural
   shape: **booktest has two independent dependency tracks** — the
   `testCases` graph that the parallel scheduler walks, and the
   `liveResources` graph that the runner walks lazily through `acquire`.
   The scheduler doesn't know about the second graph, so it can declare
   a test "ready" while a transitive producer of the resource it
   consumes hasn't started yet.

2. When the resulting failures fire, the only visible signal is whatever
   the application throws at the end of the chain
   (`NoSuchObjectException: invoices not found`). Diagnosis took hours
   of staring at thread IDs and reasoning about `loadDependencyValue`
   line-by-line. Booktest has all the information needed to print the
   actual cause in a two-line block. It just doesn't.

The mechanical fixes for both are small. But the same shape will keep
producing this class of bug as long as the framework treats tests and
live resources as separate kingdoms with separate scheduling rules. So
this plan does two things:

- **Near term**: a precise patch description for both issues, suitable
  to ship as 0.4.3 against the current `TestRunner` / `LiveResource`
  code.
- **Long arc**: a unified **task graph** abstraction that absorbs
  tests, live resources, pools, capacities, and future artifact /
  composite tasks under one engine. Issue 1 disappears as a category
  in that model — the readiness predicate is just transitive closure
  on the producer relation, by construction. Issue 2's diagnostics
  become graph-level events that every task kind contributes to
  uniformly.

The Aito Core build system (the wider context the user gestured at)
wants tests, resources, generated artifacts, migrations, dataset
fixtures, and model files to all live in one DAG. This document is the
target shape for that.

## Concrete bugs that motivate the redesign

### Issue 1: live-resource consumers can outrun their transitive TestRef producers

Current readiness predicate, `TestRunner.scala` ~line 950–960:

```scala
val readyUnranked = testCases.filter { tc =>
  !completed.contains(tc.name) &&
  !inProgress.contains(tc.name) &&
  tc.dependencies.forall(dep =>
    completed.contains(dep) || !testMap.contains(dep))
}
```

`tc.dependencies` is a flat `List[String]`. For a consumer test that
declares `test("component", stateDirAitoR)`, that list contains
`"stateDirAitoR"` — the live-resource name. The resource's *own*
deps (typically a `TestDep[T]` to the producer test that returned the
state path) live in `LiveResourceDef.deps`, which the scheduler does
not consult.

Because live-resource names aren't in `testMap`, the predicate
`!testMap.contains(dep)` is **true** for them, and the consumer is
deemed ready immediately — even when the upstream producer test
hasn't run.

When the consumer is dispatched, `executeTestWithDependencies` calls
`liveResources.acquire`, which runs the resource's build closure. That
closure calls `resolveDependencyValue("<producerName>")`, which goes
through `loadDependencyValue` (`TestRunner.scala:1250–1270`). The
in-memory cache misses because the producer hasn't run in this
invocation. But the producer's `.bin` from a *previous* invocation is
sitting on disk in `outDir`, so the disk fallback returns the **stale
prior path**. The build closure runs against that stale path.

Concurrently, the same scheduling pass also schedules the producer
test. The producer's test runner calls `clearTmpDir()`, wipes the old
path, creates a new `tmp` directory, populates it, finishes. The live
resource handle the consumer is using is now backed by a directory that
was wiped out from under it.

In aito-core's case the consumer eventually calls
`aito.view.master.get[TableDb]("invoices")` and gets
`NoSuchObjectException` because the directory the resource was built
against doesn't have the table. The error points at the application,
not at booktest's dep layer.

#### Why sequential mode happens to work today

`runSuiteWithFilter` does `applyLocalityGrouping(resolveDependencyOrder(testsToRun))`.
`resolveDependencyOrder` is a topological sort that walks `tc.dependencies`
and skips names that aren't in `testMap` "because they're live resources,
materialized on demand." Sequential execution then runs producers before
consumers because they appear earlier in the list (the user typically
declares them earlier). That's accidental ordering, not an enforced
invariant. Parallel mode exposes the missing invariant.

#### Minimal patch space

Two complementary fixes; either alone closes the race, but combining
them is belt-and-braces and is what we should ship.

**A. Readiness follows live-resource transitive TestDeps.**

Add a helper to `LiveResourceManager` (a thin wrapper over
`transitiveResourceClosure`):

```scala
/** All test producers that must complete before `consumerTest` can
  * acquire its declared live-resource deps. Recurses through nested
  * ResourceDeps. Used by the parallel scheduler's readiness check. */
def transitiveTestProducers(consumerTestDeps: Iterable[String]): Set[String] = {
  val seen = mutable.Set[String]()
  val tests = mutable.Set[String]()
  def walk(name: String): Unit = {
    if (!isRegistered(name) || seen.contains(name)) return
    seen += name
    lookup(name).foreach(_.deps.foreach {
      case ResourceDep(ref) => walk(ref.name)
      case TestDep(ref)     => tests += ref.name
      case _                => ()
    })
  }
  consumerTestDeps.foreach(walk)
  tests.toSet
}
```

In `runTestsParallel`, replace the readiness predicate with:

```scala
val liveTestDeps = liveResources.transitiveTestProducers(tc.dependencies)
val producers = tc.dependencies.filter(testMap.contains) ++ liveTestDeps
producers.forall(completed.contains)
```

**B. The .bin disk fallback skips tests that are scheduled in this run.**

Plumb the run's scheduled-test name set into `loadDependencyValue` (or
into a wrapper that calls it). For dep names in that set, return
`None` instead of falling back to the on-disk `.bin` from a previous
invocation. The resolver then either finds the test already completed
(memory cache hit) or, in the auto-run path, runs the producer
synchronously. The disk cache stays useful for `--continue` mode and
for cross-invocation reuse, where the producer is *not* scheduled in
this run.

```scala
private def loadDependencyValue(
  depName: String, suitePath: String, testRun: TestCaseRun,
  scheduledInThisRun: String => Boolean
): Option[Any] = {
  val qualifiedKey = s"$suitePath/$depName"
  dependencyCache.get[Any](qualifiedKey).orElse {
    if (scheduledInThisRun(depName)) None  // wait for in-run producer
    else loadFromBinFileIfExists(...)
  }
}
```

Both fixes are mechanical and live entirely inside `TestRunner.scala`
+ `LiveResourceManager.scala`. They don't require the bigger redesign.

### Issue 2: failure logs don't surface dep-resolution or live-resource lifecycle

When Issue 1 (or any future cross-track failure) fires, the visible
output is whatever the application throws — a `NoSuchObjectException`
deep inside aito-core. The user has to re-run with `-v`, eyeball
thread IDs, and reason about `loadDependencyValue` and
`liveResources.acquire` semantics across threads to figure out that
the consumer used a stale `.bin` path.

Booktest has every piece of information needed to print the answer at
the moment of failure. It just doesn't. Three concrete asks from the
aito-core feedback, all in scope here:

1. **A booktest-internal trace log** at `<output-dir>/.booktest.log`,
   thread-tagged, structured-text, opt-in via `--trace` /
   `BOOKTEST_TRACE=1`:

   ```
   T7  12:34:01.115 sched-ready    <suite>/<test>      deps=[…] liveDeps=[stateDirAitoR]
   T7  12:34:01.118 dep-resolve    <suite>/state       source=bin path=/tmp/inference-OLD
   T9  12:34:01.119 liveres-build  stateDirAitoR       consumer=<test> resolvedDeps=[…]
   T9  12:34:01.180 liveres-built  stateDirAitoR       instance=#abcd1234 build-ms=61
   T11 12:34:01.181 liveres-acq    stateDirAitoR       consumer=<test> instance=#abcd1234 refcount=1
   T11 12:34:01.222 test-end       <suite>/<test>      result=ok|diff|fail
   ```

   With those events, Issue 1 is one grep away: spot a `dep-resolve …
   source=bin` for a test that is also scheduled in this run and has
   no `test-end` event yet from its own row.

2. **Identity-hash and source annotation in failure messages.** Every
   live-resource instance gets a `#xxxxxxxx` identity (e.g.
   `Integer.toHexString(System.identityHashCode(instance))`). Every
   resolved test dep records its source (`memory`, `bin`, `auto-run`).
   The per-test failure block becomes:

   ```
   Test 'component' failed: NoSuchObjectException: invoices not found
     Live resource 'stateDirAitoR' instance #abcd1234
       built from depResolved 'state' = '/tmp/inference-OLD' (source=bin)
     Producer 'state' completed at 12:34:01.300
       returned '/tmp/inference-NEW' (source=auto-run)
   ```

   Populated from a per-test ring-buffer that captures the last N
   trace events touching this test or any of its transitive resources.

3. **Auto-include the trace block for any test that fails or DIFFs at
   `-pN ≥ 2`**, even without `--trace`. The volume is small and bounded
   to failing tests; the cost is negligible.

The redesign target is a `TaskTrace` interface that every task
driver writes to, with a default `LogfileSink` and a per-failure
`RingBufferSink`. The current `LiveResourceListener` is the seed —
it already covers `onTask` / `onClose` / `onReset` / `onAcquire` /
`onRelease`. We extend it with the dep-resolution events and the
test-lifecycle events, and we make it not just an observer but the
canonical record of what happened.

## Goal of the unified Task Graph

> Tests, live resources, pools, capacities, and (future) artifacts and
> composites are all **`Task`s** in a single typed DAG. The framework
> manages dependency resolution, scheduling, lifecycle, sharing,
> caching, and tracing for **all** kinds through one engine. Adding a
> new kind is a matter of declaring its policy bundle; the engine and
> the graph walker are unchanged.

### One protocol, one walker

A test and a live resource look identical at the dependency layer.
Both are functions with typed inputs and a typed output:

```scala
// test
def state(t: TestCaseRun): String = ...
def consumer(t: TestCaseRun, in1: String, in2: Server): Unit = ...

// live resource
def server(state: String, port: Int): Server = ...
```

What differs between them is *policy*, not *shape*:

| Aspect                        | Test               | Live resource                |
|-------------------------------|--------------------|------------------------------|
| Production trigger            | OnDemand           | OnFirstAcquire               |
| Result persistence            | `.bin` across runs | memory only                  |
| Sharing across consumers      | OneShot (run once) | SharedReadOnly / Reset / etc |
| Side-channel context          | `TestCaseRun`      | none                         |
| Output captured for snapshot  | yes                | no                           |
| Has a `close()` lifecycle     | no                 | yes                          |

Every row in that table is a **policy attached to a `Task`**, not a
graph property. The producer relation is the same in both columns:
"this `Task` consumes that `Task`'s output." So we don't need a
test-specific walker and a resource-specific walker — we need **one
walker** over a sealed `Task` trait, parameterized only on which dep
kinds count as producers (`TaskDep` yes; `PoolDep` / `CapacityDep`
no).

This is the core simplification. The Issue 1 bug is the artifact of
*not* having unified the walker: today's `transitiveResourceClosure`
walks resources, today's `tc.dependencies` walks tests, and nothing
walks across the boundary. With one `Task` interface, the boundary
doesn't exist — `transitiveProducers(b)` recursively follows
`TaskDep` regardless of whether the target is a test, a resource, or
a future artifact.

What this buys us:

- Issue 1 disappears by construction. Readiness is "all my upstream
  *producers* in the closure are done." The engine doesn't need to
  know what kind a producer task is.
- Issue 2's diagnostics become graph-level events. Every task kind
  contributes the same shape of events; the trace is uniform.
- New artifact kinds (compiled jars, generated config, dataset
  fixtures, model files) plug in without reshaping the scheduler.
- Aito Core's wider build system can use the same engine for its
  cross-cutting builds without inventing a parallel scheduler.

What this is NOT:

- A content-addressed cache (Bazel-style). Caching policy is a
  first-class field on each task, but persistent content-addressing is
  an extension, not a foundation.
- A distributed executor. The engine assumes one JVM, one process. A
  worker-pool driver could lift it to multi-process later.
- A replacement for sbt/mill/Bazel as the outer build. This is the
  *test-and-fixture* graph that runs inside whatever outer build you
  use. We just want it to be a real graph.

## Task model

```scala
/** Stable identity of a Task within a single run. */
opaque type TaskId = String

/** A typed reference to a Task's output. Used by other Tasks to
  * declare deps. Replaces TestRef[T] / ResourceRef[T] — there is one
  * ref kind because there is one Task kind from the graph's
  * perspective. The Task's role (test, resource, artifact) is a
  * policy attached to the Task, not a property of the ref. */
final case class TaskRef[+T](id: TaskId)

/** A typed dependency edge. */
sealed trait Dep[+T]
final case class TaskDep[+T](ref: TaskRef[T])         extends Dep[T]
final case class PoolDep[+T](pool: ResourcePool[T])     extends Dep[T]
final case class CapacityDep[N](cap: ResourceCapacity[N], amount: N) extends Dep[N]

/** A Task is a typed function from resolved deps to an output, plus
  * the policy bundle that says how the engine should treat it. */
trait Task[Ctx, Out] {
  def id: TaskId
  def deps: List[Dep[?]]

  /** Run the work. `inputs` are the resolved deps in declaration
    * order (TaskDep → upstream output, PoolDep / CapacityDep →
    * allocation). `ctx` is the per-run context — TestCaseRun for a
    * test, TaskCtx for a resource, etc. */
  def run(inputs: List[Any], ctx: Ctx): Out

  /** Optional close. Only meaningful for sharing modes with a
    * lifecycle (live resources). Default is a no-op. */
  def close(out: Out): Unit = ()

  /** Optional reset between consumers (SharedWithReset only). */
  def reset(out: Out): Unit = ()

  // Policy bundle — three orthogonal axes plus failure handling.
  def sharing:    SharingMode      = SharingMode.OneShot
  def caching:    CachingPolicy    = CachingPolicy.MemoryOnly
  def production: ProductionPolicy = ProductionPolicy.OnDemand
  def onFailure:  FailureMode      = FailureMode.HardFail
}
```

That's the entire user-facing protocol. `Test` and `LiveResource`
are not subtypes; they are *factories* that produce a `Task` with a
specific policy bundle:

```scala
object Test {
  /** A test: OneShot, PersistedAcrossRuns (.bin), OnDemand. Ctx is
    * TestCaseRun. */
  def apply[Out](
    id: TaskId, deps: List[Dep[?]]
  )(body: (TestCaseRun, List[Any]) => Out): Task[TestCaseRun, Out] =
    new Task[TestCaseRun, Out] {
      def id = id; def deps = deps
      def run(inputs: List[Any], ctx: TestCaseRun): Out = body(ctx, inputs)
      override def sharing    = SharingMode.OneShot
      override def caching    = CachingPolicy.PersistedAcrossRuns
      override def production = ProductionPolicy.OnDemand
    }
}

object LiveResource {
  /** A live resource: SharedReadOnly by default, MemoryOnly,
    * OnFirstAcquire. Ctx is empty. */
  def apply[Out <: AutoCloseable](
    id: TaskId, deps: List[Dep[?]]
  )(body: List[Any] => Out): Task[Unit, Out] =
    new Task[Unit, Out] {
      def id = id; def deps = deps
      def run(inputs: List[Any], ctx: Unit): Out = body(inputs)
      override def close(out: Out): Unit = out.close()
      override def sharing    = SharingMode.SharedReadOnly
      override def caching    = CachingPolicy.MemoryOnly
      override def production = ProductionPolicy.OnFirstAcquire
    }
}
```

The existing public API surface (`test(...)`, `liveResource(...)`,
`exclusiveResource(...)`, `liveResourceWithReset(...)`,
`@dependsOn`) becomes a thin layer that produces `Task` values with
the right policy bundle. Users don't see `Task` directly unless they
want to plug in a new kind — and adding `Artifact`, `Composite`, or a
custom kind is just another factory with a different policy bundle.

`TaskRef` is the unified replacement for `TestRef[T]` and
`ResourceRef[T]`. Both became refs to "a thing that produces a typed
output" — that's just `Task`, so one ref kind suffices. `Dep[?]`
collapses `TestDep` and `ResourceDep` into a single `TaskDep` for
the same reason.

### Initial policy bundles

The framework ships four factories that wrap `Task` with sensible
defaults:

| Factory          | Sharing              | Caching               | Production       | Ctx           |
|------------------|----------------------|-----------------------|------------------|---------------|
| `Test`           | OneShot              | PersistedAcrossRuns   | OnDemand         | TestCaseRun   |
| `LiveResource`   | SharedReadOnly       | MemoryOnly            | OnFirstAcquire   | Unit          |
| `ExclusiveTask` | Exclusive            | MemoryOnly            | OnAcquire        | Unit          |
| `Pool` / `Cap`   | Reservation          | None                  | OnAcquire        | Unit          |

Two near-future factories the design leaves room for:

| Factory          | Sharing              | Caching               | Production       | Ctx           |
|------------------|----------------------|-----------------------|------------------|---------------|
| `Artifact`       | SharedReadOnly       | ContentAddressed      | OnHashMiss       | TaskCtx      |
| `Composite`      | (no output)          | None                  | (no work)        | Unit          |

`Artifact` is what we want for "compile this protobuf, generate this
config, build this dataset fixture." The engine hashes inputs, checks
a cache, runs `Task.run` only on miss. `Composite` is "the
integration suite" — pure aggregation, drives ordering across a
collection of other Tasks without producing anything itself. **No
new code path in the engine** for either; both are just `Task`
values with different policy bundles.

### Engine-level lifecycle

Because `Task` already knows how to `run`, `close`, and `reset`, the
engine doesn't need a per-kind `Driver`. It needs one routine per
*sharing mode* that translates between the policy and the work:

```scala
trait TaskEngine {
  /** Resolve an output of `b` for `consumer`. Engine ensures all
    * producers of `b` have completed; calls `b.run` exactly once or
    * many times depending on `b.sharing`; records the resulting
    * `Acquired[Out]` for refcounting and tracing. */
  def acquire[Ctx, Out](
    b: Task[Ctx, Out],
    consumer: TaskId,
    ctxFor: Task[Ctx, Out] => Ctx
  ): Acquired[Out]

  /** Release a consumer's hold; engine calls `b.close` / `b.reset` per
    * sharing mode and refcount. */
  def release[Out](handle: Acquired[Out], failed: Boolean, opts: ReleaseOpts): Unit
}

final case class Acquired[+Out](
  value: Out,
  /** Identity for trace/diagnostics. Hex of identityHashCode for
    * mutable handles; deterministic content hash for content-addressed
    * Tasks. */
  identity: String,
  /** Pool / capacity allocations to release on close. */
  allocations: List[Allocation],
  /** Free-form annotations for the trace — e.g. tests record
    * "source" -> "bin"|"memory"|"auto-run". */
  annotations: Map[String, String] = Map.empty
)
```

The five sharing modes (OneShot, SharedReadOnly, SharedSerialized,
SharedWithReset, Exclusive) each have a fixed acquire/release
implementation in the engine, identical to the current
`LiveResourceManager` cases. The engine doesn't care whether the
`Task` it's running is a test or a resource — only what its sharing
mode says to do. `Reservation` (Pool / Capacity) is the sixth mode
and is handled inline in the dep resolver, not via `acquire` /
`run` (there's no `Task.run` to call — the value is the allocation
itself).

The current `LiveResourceManager`'s six-mode logic, the existing
`TestCase` execution path, and the `PoolDep` / `CapacityDep`
allocation paths collapse into one engine with one big `match` on
`b.sharing`. Issue 1 disappears in this collapse: the readiness
predicate is `transitiveProducers(b).forall(completed)`, where
`transitiveProducers` is one walker over `Task.deps`.

### Sharing modes

Generalized from `LiveResource.ShareMode` so they apply uniformly:

```scala
sealed trait SharingMode
object SharingMode {
  /** One consumer, runs to completion, value cached per the caching
    * policy. Default for tests. */
  case object OneShot extends SharingMode

  /** All consumers see the same instance concurrently. Read-only by
    * convention; engine doesn't enforce it. */
  case object SharedReadOnly extends SharingMode

  /** Same instance shared, but consumers serialized through a per-task
    * lock. No reset between consumers. */
  case object SharedSerialized extends SharingMode

  /** Same instance shared, consumers serialized, reset(handle) called
    * between consumers. */
  final case class SharedWithReset[T](reset: T => Unit) extends SharingMode

  /** Each consumer gets a fresh build. Equivalent to today's
    * exclusiveResource. */
  case object Exclusive extends SharingMode

  /** A pool / capacity allocation. Engine treats release as
    * "return-to-pool" rather than "close handle." */
  case object Reservation extends SharingMode
}
```

### Caching modes

```scala
sealed trait CachingPolicy
object CachingPolicy {
  /** No persistence; recomputed every run. Default for live
    * resources, pools, capacities. */
  case object MemoryOnly extends CachingPolicy

  /** Result is persisted to a per-task file on disk and reused across
    * runs unless the task was scheduled in *this* run (Issue 1's Fix
    * B). Default for tests; backs today's .bin behavior. */
  case object PersistedAcrossRuns extends CachingPolicy

  /** Persistent and content-addressed. Reuse keyed on a hash of the
    * task's deps' identities. Future. */
  case object ContentAddressed extends CachingPolicy

  /** No state; each acquire computes fresh. */
  case object None extends CachingPolicy
}
```

### Production policies

```scala
sealed trait ProductionPolicy
object ProductionPolicy {
  /** Run if asked to (test selected directly, or auto-run because a
    * downstream consumer needs its value and no cache hit). */
  case object OnDemand extends ProductionPolicy

  /** Run on first acquire from any consumer. */
  case object OnFirstAcquire extends ProductionPolicy

  /** Allocate on every acquire (pools, capacities). */
  case object OnAcquire extends ProductionPolicy

  /** Run if and only if the content-addressed cache misses. Future,
    * pairs with CachingPolicy.ContentAddressed. */
  case object OnHashMiss extends ProductionPolicy
}
```

## Engine

```scala
class TaskEngine(
  graph: TaskGraph,
  trace: TaskTrace,
  threads: Int
) {
  def run(selected: Set[TaskId]): RunResult = ...
}
```

There is no `drivers: Map[Kind, Driver]` parameter. The engine
dispatches on `task.sharing` to pick one of the lifecycle routines
described in *Engine-level lifecycle*; everything else is on the
`Task` itself (`run`, `close`, `reset`, the policy bundle).

### Topology

`TaskGraph` is built once per run from registered suites + resource
definitions + (future) artifact definitions. It exposes:

- `transitiveProducers(id): Set[TaskId]` — every task that must
  complete before `id` can be acquired. Walks `TaskDep` edges
  recursively, ignores `PoolDep` / `CapacityDep` (which don't have a
  "completion" in the producer sense — they're just allocations).
- `transitiveAllocations(id): List[Dep[?]]` — pool/capacity edges in
  the closure, used for pre-flight reservation.
- `consumers(id): Set[TaskId]` — for refcount initialization.
- `task(id): Task[?, ?]` — the registered Task, for sharing/caching
  lookups.

### Pre-flight

Replaces the current `runMultipleSuites` pre-pass:

1. Validate the graph is a DAG.
2. For each task selected to run, walk `transitiveProducers` to expand
   the actual run set (auto-include unselected upstreams).
3. For each task in the expanded run set, walk
   `transitiveAllocations` and reserve pool / capacity holds. Fail
   fast on over-commit (today's capacity pre-validator generalizes
   to all reservation kinds).
4. Initialize refcount on every shared task = number of consumers in
   the run set.

### Scheduler

Single loop, single readiness predicate:

```
for each not-completed task t in selected ∪ transitiveProducers:
  if t is in-progress: skip
  if any p in transitiveProducers(t) is not completed: skip
  rank by localityScore(t); dispatch the best to a worker
```

`localityScore` generalizes today's heuristic: prefer tasks whose
shared resources are alive, prefer tasks that drain a resource
(refcount → 0 after this consumer). Same shape as today, applied
uniformly across all task kinds.

Issue 1 is dead in this model: a consumer test depends on
`TaskDep(TaskRef("stateDirAitoR"))`; the graph reports
`transitiveProducers` of that consumer as containing `state`. The
scheduler waits.

### Cache lookup respects the scheduled set

The engine knows which tasks are in this run. When `acquire` resolves
a `TaskDep`, the engine asks the cache backend "is `state` scheduled
in this run?" before falling back to a persisted cache. If yes, the
backend returns None — the engine either dispatches the producer
(and re-attempts acquire on completion) or auto-runs it inline
(current behavior, but now safely *after* the producer completes).
If no, the backend uses the persisted cache.

This is Issue 1's Fix B as a property of the cache backend. The
backend is selected per-task by `task.caching`; the engine just
supplies the "scheduled in this run" predicate.

### Trace

```scala
sealed trait TraceEvent {
  def at: java.time.Instant
  def thread: String
  def task: TaskId
}
object TraceEvent {
  final case class SchedReady(at: Instant, thread: String, task: TaskId,
    deps: List[TaskId]) extends TraceEvent
  final case class DepResolve(at: Instant, thread: String, task: TaskId,
    dep: TaskId, source: String, value: Option[String]) extends TraceEvent
  final case class TaskRun(at: Instant, thread: String, task: TaskId,
    instance: String, durationMs: Long) extends TraceEvent
  final case class TaskAcquire(at: Instant, thread: String, task: TaskId,
    consumer: TaskId, instance: String, refcount: Int) extends TraceEvent
  final case class TaskRelease(at: Instant, thread: String, task: TaskId,
    consumer: TaskId, failed: Boolean, refcount: Int) extends TraceEvent
  final case class TaskClose(at: Instant, thread: String, task: TaskId,
    instance: String, durationMs: Long) extends TraceEvent
  final case class TaskEnd(at: Instant, thread: String, task: TaskId,
    result: String, durationMs: Long) extends TraceEvent
}

trait TaskTrace {
  def emit(e: TraceEvent): Unit
}
```

Three sinks always wired up:

- `LogfileSink(path)` — writes to `<output-dir>/.booktest.log` if
  `--trace` / `BOOKTEST_TRACE=1`. Otherwise inert.
- `RingBufferSink(perTask = 100, global = 5000)` — always on, cheap.
  When a task fails or DIFFs, the engine pulls the relevant entries
  (events for this task + events for its transitive producers in the
  last few seconds) and prints them as a "Trace context" block in the
  failure report.
- `ListenerSink` — backwards compat with existing
  `LiveResourceListener`. Bridges the new event model to the old
  callback shape so today's `[build]/[close]/[reset]` lines keep
  working.

The current `LiveResourceListener` interface is preserved as a thin
adapter over the trace bus, so existing CLI flags (`-v` printing
build/close lines) keep working with no caller-visible change.

### Failure semantics

Each task declares a `FailureMode`:

```scala
sealed trait FailureMode
object FailureMode {
  /** Failure marks the task failed; downstream consumers are
    * marked blocked-by-upstream and reported separately. Default. */
  case object HardFail extends FailureMode

  /** N retries before HardFail. */
  final case class Retry(n: Int) extends FailureMode

  /** Failure is reported but downstream consumers run anyway with
    * "no-input" semantics. For composite / observability tasks. */
  case object SoftSkip extends FailureMode
}
```

Today every test and resource is implicitly `HardFail`. Making it
explicit is what enables retry and soft-skip without bolting them on
later.

## Today → Tomorrow: migration plan

The redesign is large. We don't ship it all at once. Concrete phases,
each independently shippable:

### Phase 0 — what's already done (0.4.2)

- DependencyCache is thread-safe.
- Snapshot managers are hardened.
- ResourceManager / LockPool are sync'd.
- Stack traces are printed for all internal failures.
- CI runs meta-tests under `-p4`.

### Phase 1 — Issue 1 + Issue 2, current code shape (target: 0.4.3)

Direct patches to `TestRunner.scala` / `LiveResourceManager.scala` /
new `TaskTrace.scala`. No graph abstraction yet.

1. `LiveResourceManager.transitiveTestProducers(deps)` — described
   above.
2. Parallel scheduler readiness predicate uses producer-set, not
   immediate-deps.
3. `loadDependencyValue` takes a "scheduled in this run" predicate
   and skips the disk fallback when it's true.
4. `TaskTrace` interface + `LogfileSink` + `RingBufferSink`. Wire
   it into `TestRunner.executeTestSilently` for sched/dep-resolve
   events and `LiveResourceManager` for build/acquire/release/close.
5. Failure-time trace block printed automatically at `-pN ≥ 2` for
   any FAIL or DIFF; full trace logged behind `--trace` /
   `BOOKTEST_TRACE=1`.
6. Per-instance identity hash on every live-resource acquire — used in
   trace events and failure blocks.

Acceptance: a meta-test that forces Issue 1 (producer with leftover
`.bin`, consumer via live resource, `-p2`) should now either pass or
fail with a trace block that names the stale source. Two new test
files:

- `LiveResourceProducerOrderingTest` — reproduces and asserts on the
  ordering invariant.
- `TaskTraceTest` — verifies the trace contains the right events and
  the failure block is auto-attached.

### Phase 2 — internal `TaskEngine` skeleton (target: 0.5.0)

Introduce `TaskGraph`, `Task`, `TaskEngine` as internal types.
Existing public API (`TestSuite`, `liveResource(...)`, CLI flags) is
unchanged. `TestRunner` becomes a thin caller of `TaskEngine`, and
the existing `LiveResourceManager` logic moves into the engine's
sharing-mode dispatch.

Risks:

- Sequence ordering parity (`applyLocalityGrouping`) must reproduce
  exactly under the new scheduler so existing snapshots don't churn.
  Add a "compare scheduling order" cross-check during Phase 2.
- The string-typed `tc.dependencies` API stays as the public surface;
  the engine translates strings to `TaskRef`s internally, with a
  helpful error if a dep name is ambiguous between a test and a
  resource (today: silently treated as a resource because it's not in
  `testMap`).

### Phase 3 — collapse the two managers (target: 0.5.x)

`LiveResourceManager` deletes itself; its logic is the engine's
SharedReadOnly / SharedSerialized / SharedWithReset / Exclusive
acquire/release routines. `DependencyCache` becomes the
`PersistedAcrossRuns` cache backend, selected automatically for
`Task`s with that caching policy. The dual-track codepaths in
`runTestCase` / `executeTestSilently` collapse into one
`TaskEngine.runTask` path.

### Phase 4 — new task kinds (target: 0.6+, on demand)

- `Artifact` factory for content-addressed artifacts (compile outputs,
  generated configs, dataset fixtures, model files). `--clean`
  generalizes to "drop artifact cache."
- `Composite` factory for "the integration suite" / phase aggregation.
- Out-of-tree examples (e.g. a Docker / Database task) to validate
  that adding a new kind is purely a policy-bundle exercise — no
  changes to the engine or the graph walker.

## Aito Core integration shape

The user explicitly asked for "seamless integration into the aito.ai
build system." Concretely:

- Aito Core declares its tests, fixtures, generated artifacts, and
  migration steps as `Task`s in a single graph.
- Booktest provides the engine and the test/live-resource factories.
- Aito declares custom `Task` factories (its own policy bundles) for
  migrations and dataset fixtures. No new engine surface required.
- One run command (`booktest` CLI) drives the whole graph; task
  selection (today: name pattern + suite globs) extends to artifact /
  migration tasks.

The thing that makes this work and not collapse into "yet another
build tool" is that booktest stays opinionated about review-driven
snapshot testing as the leaf task kind. Other task kinds exist to
*feed* the leaves. We don't compete with sbt/Bazel; we own the
test-and-fixture half of the dependency surface.

## Out of scope (for now)

- **Distributed execution.** Engine assumes one JVM. A remote-worker
  variant could be slotted in via a future `Sharing.Remote` mode; the
  protocol allows it; we don't design for it.
- **Bazel-grade content addressing.** `CachingPolicy.ContentAddressed`
  is the placeholder. The hashing strategy, cache eviction, and
  cross-machine sharing are deferred until a concrete artifact use
  case forces the design.
- **Persistent build daemon.** Each `booktest` invocation builds the
  graph fresh and tears it down at end-of-run. A daemon variant could
  reuse the graph and the live-resource instances across invocations
  later.
- **Arbitrary cycles.** The graph is a DAG. Mutual recursion in deps
  is a hard error and will be reported pre-flight.
- **Cross-language task kinds.** `Task.run` is JVM code. A
  subprocess-backed `Task` factory can call out to Python / Bash /
  etc., but that's a wrapper, not a graph feature.

## Open questions

1. **Dep-name ambiguity** — today, a dep name in `tc.dependencies`
   could refer to a test or a resource; the runner picks based on
   `liveResources.isRegistered(name)`. With `TaskRef`-typed deps in
   the engine, the public API can either keep the string-name surface
   (engine resolves) or expose typed refs all the way out
   (`test("c", stateDirRef)` instead of `test("c", "stateDirAitoR")`).
   The latter is type-safer; the former matches Python booktest. Open.
2. **Composite UX** — what's the right user-facing way to declare
   "this task is just an aggregator"? A `phase("integration", t1,
   t2, t3)` helper? Open until a real use case arrives.
3. **Backwards compat for `@dependsOn`** — annotation-based deps
   should keep working. Engine maps method names to `TaskRef`s during
   suite registration.
4. **Trace format stability** — the structured trace format will be
   parsed by tools (Aito Core's CI dashboard wants to read it). Should
   we commit to a versioned format from day one (NDJSON with a
   `format` field), or stabilize after a release of empirical use? Lean
   toward the former; it's cheap.
5. **Graph visualization** — `--print-graph` to dump the DAG as
   Graphviz / Mermaid for debugging. Probably free-of-charge once the
   graph is a real data structure.
