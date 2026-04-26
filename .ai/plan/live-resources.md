# Live Resources for Booktest-Scala

Status: design proposal, not yet implemented.
Audience: framework maintainers (and Claude in future sessions).

## Problem

Some tests need a stateful external service (e.g. a database server, a Kafka
broker, a process under test). Today every such test must spin the service up
and tear it down on its own. If setup costs 2 s, teardown 0.5 s, and the test
itself runs 1 s, then 80% of wall-clock time is service plumbing. With 100 ms
tests, the ratio is even worse.

Booktest already supports:

- **Cached return values** in `<test>.bin` (serialized via Java serialization
  for `java.io.Serializable`, otherwise `toString`).
- **Dependency injection** (`test("name", dep1)` or `@DependsOn`), with the
  dependency's cached return value passed in.
- **Auto-running missing dependencies** like a build system
  (`TestRunner.resolveDependencyValue`).
- A **`ResourceManager`** with `PortPool` and `LockPool`, plus per-suite
  `resourceLocks: List[String]`.

What's missing is a first-class notion of a **live, non-serializable
resource** that:

1. Is built once and shared across many tests, then torn down.
2. Holds onto allocated pool resources (ports, RAM, GPU IDs, …) for its
   lifetime.
3. Plays nicely with the existing serialized dependency machinery — tests
   that produce plain state still work as today.

## Goals

1. A test can declare a dependency on a **live service** and receive the
   live object directly, not a serialized blob.
2. The runner sets up the service once, runs every test that needs it, then
   closes it — minimizing setup/teardown time.
3. Resource accounting (ports, RAM, custom pools) prevents conflicts in
   parallel runs. Pools are themselves dependencies, listed explicitly.
4. The same dependency machinery works whether the producer is a test (state)
   or a `liveResource` declaration (live object).
5. Backwards compatible: existing tests don't change.

## Non-goals

- Cross-process / cross-machine resource sharing. Single JVM only.
- Distributed scheduling. Scheduler is in-process and greedy.
- Replacing the snapshot model. Live resources sit on top of the existing
  dependency machinery; test output is still snapshotted.
- Serializing live state across runs. The live object is always materialized
  fresh inside the current process.
- Caching live resources to `.bin`. Only test return values get cached;
  resources are always live in-process.

## Design overview

The model has **three** kinds of dependency-providing things, all
type-distinguished. Pool resources reuse the existing `ResourcePool[T]`
trait from `ResourceManager.scala` — no new pool hierarchy.

| Kind          | Producer                          | Dep type passed in       | Cached to disk? | Snapshotted? |
|---------------|-----------------------------------|--------------------------|-----------------|--------------|
| Test result   | `test("n") { ... }`               | `TestRef[T]`             | yes (`.bin`)    | yes (`.md`)  |
| Live resource | `liveResource("n", deps) { ... }` | `ResourceRef[T]` (where `T <: AutoCloseable`) | no | no |
| Pool resource | existing `ResourceManager` pools  | `ResourcePool[T]` (existing trait) | no | no |

A `liveResource` declaration takes a heterogeneous dependency list — any mix
of the three. The runner resolves each:

- `TestRef[T]` deps → load cached test return value (state, e.g. a `String`).
- `ResourceRef[T]` deps → resolve transitively, share the live instance.
- `ResourcePool[T]` deps → call `pool.acquire()`. The allocation is **held by
  the live resource for its lifetime** and `pool.release(value)` is called
  inside `close()`.

The build closure receives resolved values in the declared order and returns
an `AutoCloseable`. Sharing identity = the declaration ref + its resolved
dependency values; two consumers of the same ref share the same live object.

```
test("createState")            ─►  String "hello world"     (cached in .bin)
                                       │
                                       ▼
liveResource("stringServer",          (state, port)         (no cache, no snapshot)
             state, resources.ports)   │
  build ─► new StringServer(port, s)   │
                                       ▼
test("clientGet", server)         StringServer (live)       (snapshot test output)
test("clientLength", server)      ↑ same instance
test("clientUpper", server)       ↑ same instance
                                  └── close() when last consumer finishes
                                      └── pool.release(port) on existing PortPool
```

### Reuse of existing infrastructure

This design adds **one** genuinely new abstraction (`liveResource` and the
`ResourceRef[T]` type). Everything else is layered on existing types in
`src/main/scala/booktest/ResourceManager.scala`:

- `ResourcePool[T]` — used as-is. A pool dep in a `liveResource` declaration
  is just a `ResourcePool[T]` value. The runner calls `acquire()` /
  `release(t)` on it.
- `PortPool extends ResourcePool[Int]` — used as-is for port deps.
- `LockPool` — reused for the per-instance `withReset` serialization (one
  named lock per resource instance, e.g. `lr/<suite>/<name>/<instanceId>`).
- `ResourceManager.register[T](name, pool)` / `pool[T](name)` — the existing
  custom-pool registration is how RAM, CPU, and custom-ID pools are added.
- `ResourceManager.fromEnv()` — env-driven configuration extended with
  additional vars; no new construction path.
- The `TestRunner` already holds a `resourceManager` and threads it into
  `TestCaseRun`. The new `LiveResourceManager` is a sibling that *uses*
  the same `ResourceManager`, not a replacement for it.

## API

### `liveResource` declaration

Lives on `TestSuite` next to `test(...)`. Signature follows the same shape:

```scala
def liveResource[T <: AutoCloseable]
    (name: String)
    (build: => T): ResourceRef[T]

def liveResource[T <: AutoCloseable, D1]
    (name: String, dep1: Dep[D1])
    (build: D1 => T): ResourceRef[T]

def liveResource[T <: AutoCloseable, D1, D2]
    (name: String, dep1: Dep[D1], dep2: Dep[D2])
    (build: (D1, D2) => T): ResourceRef[T]

// ... up to D3 / D4 like test(...) does today
```

Where `Dep[T]` is a thin sum that includes the existing `ResourcePool[T]`:

```scala
sealed trait Dep[T]
final case class TestRef[T](name: String, ...)            extends Dep[T]
final case class ResourceRef[T](name: String, ...)        extends Dep[T]
final case class PoolDep[T](pool: ResourcePool[T])        extends Dep[T]

// Implicit conversions so call sites stay clean:
given [T]: Conversion[ResourcePool[T], Dep[T]] = PoolDep(_)
given [T]: Conversion[TestRef[T], Dep[T]]     = identity
given [T]: Conversion[ResourceRef[T], Dep[T]] = identity
```

`PoolDep` is just an internal wrapper so the dep list types align;
authors write `resources.ports` and the implicit conversion lifts it.
There is no new pool API — `ResourcePool[T]` is the trait that already
exists in `ResourceManager.scala`.

The build closure does NOT receive a `TestCaseRun` — `liveResource` is not a
test. There is no snapshot, no test output, no `.md` file. If construction
needs to log, do it through normal logging (the runner can capture and
attach to the consumer's report).

### Sharing variants

Default: `SharedReadOnly`. Variants:

```scala
// Default above is SharedReadOnly: one instance, many concurrent users.
liveResource("stringServer", state, resources.ports) {
  (s, port) => new StringServer(port, s)
}

// Each consumer gets its own instance (built and closed per consumer).
// Pool deps are reallocated per consumer too.
liveResource.exclusive("stringServer", state, resources.ports) {
  (s, port) => new StringServer(port, s)
}

// Shared, but reset between consumers (serializes consumer access).
liveResource.withReset("stringServer", state, resources.ports) {
  (s, port) => new StringServer(port, s)
} { server => server.get("/admin/reset") }
```

The reset variant takes two closures: build and reset. The runner serializes
consumer execution on this resource via the existing `LockPool` (one
named lock per instance), and calls the reset closure between consumers.

### Accessing pools from a suite

The pool values come from the existing `ResourceManager`. `TestSuite`
exposes them via `resources` (a small accessor proxy on the runner's
`ResourceManager`):

```scala
trait TestSuite {
  // ... existing members ...

  /** Access to the runner's ResourceManager. Forwards to the same instance
    * that TestRunner holds. */
  protected def resources: ResourceManager = ResourceManager.default
}
```

So the existing `PortPool` is just `resources.ports` (already exposed by
`ResourceManager.ports: PortPool`). Custom pools registered into the
manager become `resources.pool[T]("name").get`.

Each occurrence of a pool dep in a `liveResource`'s dependency list
allocates **once** at materialization and is **held until `close()`**. So
`liveResource("twoPort", resources.ports, resources.ports) { (p1, p2) => ... }`
gets two distinct ports, both held for the resource's lifetime.

### Consumer side

Tests consume `ResourceRef[T]` exactly like they consume `TestRef[T]`:

```scala
test("clientGet", server) { (t: TestCaseRun, http: StringServer) =>
  t.tln(s"GET /echo -> ${http.get("/echo")}")
}
```

The runner sees the dep is a `ResourceRef[StringServer]`, materializes (or
reuses) the live instance, and passes it as `http`. The consumer doesn't
know whether the dep is a test value or a live resource — same call shape.

## Lifecycle and refcounting

`LiveResourceManager` is a *companion* to the existing `ResourceManager`, not
a replacement. It uses `ResourceManager`'s pools for allocation and
`LockPool` for `withReset` serialization.

Responsibilities:

1. **Materialize on demand.** First consumer triggers: resolve all deps,
   call `pool.acquire()` for each `ResourcePool` dep, then `build(...)`.
   Allocations are recorded as `(pool, value)` pairs against the instance
   so they can be released later.
2. **Refcount.** Pre-pass tallies how many not-yet-run consumers each
   `ResourceRef` has. Each `acquire` decrements; when count hits 0, call
   `close()` on the instance, then call `pool.release(value)` for every
   recorded allocation.
3. **Reset routing.** For `withReset` resources: acquire a per-instance
   named lock from `ResourceManager.locks` (the existing `LockPool`)
   around each consumer call, call the reset closure between consumers.
4. **Failure handling.**
   - Build throws → mark resource failed; every dependent consumer fails
     with `LiveResourceBuildFailed`; release any pool allocations already
     made; `close` not called (instance was never built).
   - Consumer fails on `SharedReadOnly` → keep instance by default
     (`--invalidate-live-on-fail` flips this).
   - Consumer fails on `withReset` → always invalidate: next consumer
     triggers fresh build.
   - Consumer fails on `exclusive` → no effect on others (each has its own).
   - `close` throws → log to test report, swallow. Pool allocations released
     regardless.
5. **Pool allocation order.** Canonical (to avoid deadlock under parallel
   materialization): allocate in the same order pools are listed in the
   dep list. Within a single `liveResource`, the order is fixed at
   declaration time.

## Resource pools

The existing `ResourceManager` already provides `PortPool` and `LockPool`
and supports `register[T](name, pool)` for custom pools. New pool *types*
added when needed (each is just a `ResourcePool[T]` implementation
registered into the existing manager — no parallel hierarchy):

- `RamPool(maxMb: Long) extends ResourcePool[Long]` — reservation-based,
  blocking acquire.
- `CountedPool(name: String, capacity: Int) extends ResourcePool[Int]` —
  generic counted resource (e.g. CPU cores, license slots).
- `IdPool(name: String, ids: Seq[Int]) extends ResourcePool[Int]` —
  opaque IDs (e.g. GPU device IDs).

These plug into the existing manager via its existing `register` method.
`ResourceManager.fromEnv()` is extended to read additional env vars at
construction:

- `BOOKTEST_RAM_MB` (default: ~75% of `Runtime.totalMemory`)
- `BOOKTEST_CPU_CORES` (default: `Runtime.availableProcessors`)
- `BOOKTEST_POOL_<NAME>=<count>` for custom counted pools
- `BOOKTEST_IDS_<NAME>=0,1,2` for ID pools
- CLI: `--ram-mb 2048 --pool gpu=2 --ids gpu=0,1`

## Scheduling

Inputs: post-filter test list with dep edges, transitive set of
`ResourceRef` and `PoolRef` per test, pool capacities.

Pre-pass (synchronous, before any worker):

1. Compute reachability from selected tests; ignore unreached `liveResource`
   declarations.
2. Refcount each `ResourceRef` by counting reachable consumers.
3. Validate worst-case concurrent pool demand ≤ pool capacity. Fail fast
   with names of the offending tests.

Worker loop:

1. Pick next ready test (all data deps available; resource deps acquirable
   right now under current pool allocations).
2. Rank candidates by:
   1. Live resources already alive for this test (locality).
   2. Tests that are last consumer of some resource (releases pool sooner).
   3. Stable original order.
3. Acquire each `ResourceRef` (may block for `withReset` lock); inject;
   execute; release on completion.

Sequential mode: same algorithm, one worker. Locality preference becomes
deterministic — once a resource is alive, all its consumers are scheduled
before unrelated tests (when dep order allows). This is the case where the
3× speedup the user described comes from.

### Standalone consumer (filtered run)

If the user runs `-t clientLength`, the runner walks the dep chain. Test
deps load from `.bin` as today. `ResourceRef` deps materialize fresh: the
runner resolves the resource's own deps (which may load from `.bin`), calls
`build`, runs the consumer, calls `close`. The resource is never persisted.

## Identity and sharing

A `ResourceRef` identifies the **declaration**. Two consumers of the same
ref always share. Two refs with the same name in the same suite are an
error (caught at suite construction).

Two suites declaring `liveResource("foo", ...)` independently are
**separate** by default — refs aren't compared by name across suites.
Cross-suite sharing can be added later via an explicit `globalLiveResource`
keyed by content; out of scope for v1.

## Implementation phases

Each phase is a single PR with tests, mergeable independently.

**Phase 1 — `liveResource` declaration + manual lifecycle.**
- Add `Dep`, `TestRef` (already exists), `ResourceRef`, `PoolRef`,
  `liveResource(...)` declarations on `TestSuite`.
- Resolve deps and inject built instance into consumers.
- No sharing yet — every consumer gets its own build/close (effectively
  `exclusive` mode hard-wired). Proves the wiring end-to-end.
- TDD test: `CounterServiceResource` example with two consumers; assert
  `build` called twice.

**Phase 2 — Sharing under `SharedReadOnly` (the default).**
- Refcount-based reuse across consumers.
- Pre-pass to count consumers and budget pools.
- TDD test: 3 consumers of the same ref; assert `build` called once,
  `close` called once.

**Phase 3 — `withReset` and `exclusive` variants.**
- Per-instance `ReentrantLock`; reset closure invocation between consumers.
- Failure → invalidate flow.
- TDD test: stateful resource; verify reset brings state back to known
  baseline for the next consumer.

**Phase 4 — Pool extensions (RAM/CPU/custom).**
- `RamPool`, `CountedPool`, `IdPool` as new `ResourcePool[T]`
  implementations registered into the existing `ResourceManager`.
- Extend `ResourceManager.fromEnv()` to wire them up from env vars.
- Pool capacity validation pre-pass (uses each pool's reported capacity).
- Reservation in materialization (blocking when budget tight) — the
  existing `acquire()` shape already blocks; add capacity inspection.
- CLI flags + env vars.
- TDD test: two resources each needing > half RAM; verify they don't
  materialize concurrently in parallel mode.

**Phase 5 — Locality-aware scheduler.**
- Replace current parallel scheduler's "find ready tests" with the ranked
  variant.
- TDD test: 1 resource with 5 consumers + 1 unrelated test; assert all 5
  consumers run before unrelated, build/close called once each.

**Phase 6 — Polish.**
- `--invalidate-live-on-fail` CLI flag.
- Test report includes a "live resources" section: refs used, build/close
  timings, peak RAM, pool occupancy.
- Documentation in CLAUDE.md and worked example in
  `src/test/scala/booktest/examples/LiveHttpServerTests.scala`.

## Testing strategy

This is the hardest part to get right because behavior is process-bound and
timing-sensitive. Tactics:

1. **Use booktest itself.** Each phase ships an example test under
   `src/test/scala/booktest/examples/` whose snapshot encodes the expected
   trace (build/close counts, allocation order). Snapshot diffs catch
   regressions.
2. **Avoid real services in framework tests.** Use a `CounterResource`
   whose live thing is just an `AtomicInteger` — fast, deterministic,
   exercises every code path.
3. **Concurrency tests.** `CountDownLatch`-coordinated mock resources to
   force interleavings (e.g., two workers race to acquire a `withReset`
   instance).
4. **Failure injection.** A `FlakyResource` whose `build` throws on Nth
   call, used to verify error paths and refcount cleanup.
5. **Resource budget tests.** Tiny RAM pool + a resource that needs all of
   it; assert serialization in parallel mode.

## Open questions

- **How is type information for `ResourceRef` deps preserved at runtime?**
  Today `loadDependencyValue` returns `Option[Any]`. We can pattern-match
  on `ResourceRef` vs `TestRef` at the dep edge, then route through the
  appropriate manager. Reflection-free.
- **Logging from the build closure?** No `TestCaseRun` available. Option:
  `liveResource` builds get a small `BuildContext` with `iln(...)` that
  the runner attaches to the *first consumer's* report. Out of scope for
  v1; just use `println` / a real logger.
- **Resource depending on another resource that uses `withReset`?** The
  outer resource's lifetime spans many consumers, so it never goes through
  the inner reset boundary itself. Reset only applies between **consumer
  tests**, not between materializations of dependent resources. Document
  this — composing `withReset` resources can be subtle.
- **`tmpDir` for live resources?** Add `Pools.tmpDir` returning a fresh
  `os.Path` under `books/.out/.live/<resource-name>-<id>/`, cleaned on
  `close`. Resource declares it in deps if needed.
- **Interaction with `--continue` mode?** Reachability pre-pass already
  excludes consumers that ran successfully last time. If a resource has
  zero remaining consumers, it never materializes. No special handling
  needed.

## Risks

- **Hidden mutation under `SharedReadOnly`.** Tests that *say* they don't
  mutate but actually do will become flaky. Mitigation: documentation,
  prefer `withReset` in stateful examples.
- **Pool budgeting too coarse.** If RAM estimates are wrong, OOM.
  Mitigation: start permissive, add monitoring later.
- **Scheduler complexity creep.** Greedy locality + refcount is the
  *minimum* useful policy. Resist bin-packing or look-ahead until a
  concrete workload demands it.
- **Confusion between `test`, `liveResource`, and pool deps.** Three
  similar-shaped things in the dep list. Mitigation: clear naming in docs
  and the `TestRef` / `ResourceRef` / `ResourcePool` type distinction so
  misuses are caught by the compiler.
