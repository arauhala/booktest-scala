# Memory-typed capacity needs physical-free semantics on release

Staged from aito-core (PR https://github.com/AitoDotAI/aito-core/pull/426) on
2026-05-17, after a CI run that surfaced this issue under real heap pressure.
Skim the diagnosis — the API change is small but the rationale matters.

## Symptom (in aito-core's CI)

`run-server-tests` on a 2 GB test JVM with `-p4` parallel booktest workers
fails reproducibly during the transition between two test classes that each
own a heavy shared Aito instance via `aitoServerResource`.  Concrete
sequence:

1. `GroceryTest`'s shared server is alive (its `aitoServerResource` has
   reserved `aito-server-ram` capacity = 1500 MB; ~1.5 GB of actual heap is
   resident).
2. `GroceryTest`'s last consumer test finishes → refcount drops to 0 → the
   live resource closes → the booktest runner releases the capacity slot.
3. A worker immediately picks up `GroceryRep2Test`'s state-build (which
   internally calls `withAitoServer`, instantiating another
   `SameJvmAitoInstance`).
4. `SameJvmAitoInstance` allocates its own ~1.5 GB.  Meanwhile the freshly
   "released" prior instance's objects are still on the heap because GC
   hasn't run yet.
5. Heap pressure: ~1.5 (old, ungc'd) + ~1.5 (new) ≈ 3 GB on a 2 GB JVM →
   GC death spiral → either `exit 255` or an Akka HTTP request times out
   inside the test with a 503.

This is the dominant `run-server-tests` flake mode on aito-core's branch.
It's pre-existing on `master` too, just less likely to fire without the
extra heavy test class.

## Diagnosis

The `capacity` API today is a *logical counter*:

```
override def release(amount: Double): Unit = lock.synchronized {
  _used -= amount
  if (_used < 0) _used = 0.0
  lock.notifyAll()           // ← waiters can acquire IMMEDIATELY
}
```

For non-memory resources (file handles, port numbers, semaphore-style work
counts) this is correct — `release` makes the resource genuinely available
to the next consumer.

For **memory** capacities — which the existing `BOOKTEST_CAPACITY_*` env
override docs (and the only real-world consumer in aito-core,
`aito-server-ram = 1500.0`) make first-class — the contract subtly breaks:
the counter is back to zero, but the *physical heap* hasn't released the
bytes yet.  Releasing capacity ≠ memory available.  Waiters resume and
start allocating into a heap that's still 100% full of the previous
consumer's objects.

## Proposed fix

Add a notion of "memory-typed" capacity that forces GC on release.  Three
possible shapes; I'd lean (B) for minimum API breakage.

### (A) Per-capacity kind tag

```scala
sealed trait CapacityKind
object CapacityKind {
  case object Logical extends CapacityKind  // current behavior
  case object Memory  extends CapacityKind  // System.gc() before notify
}

def capacity(
  name: String, default: Double,
  kind: CapacityKind = CapacityKind.Logical
): ResourceCapacity[Double] = …
```

`DoubleCapacity.release` checks `kind` and runs `System.gc()` before
`notifyAll`.  Logical capacities pay nothing.

### (B) `memoryCapacity` constructor (smaller blast radius)

Keep `capacity[Double]` unchanged.  Add a new helper:

```scala
def memoryCapacity(name: String, defaultMb: Double): ResourceCapacity[Double] =
  // internally a DoubleCapacity that calls System.gc() in release
```

aito-core would migrate `capacity("aito-server-ram", 1500.0)` to
`memoryCapacity("aito-server-ram", 1500.0)`.  No surprise for existing
callers.

### (C) Caller-provided release hook

Generic but more boilerplate:

```scala
def capacity[N](name: String, default: N, onRelease: () => Unit = () => ())
```

I'd skip this — it pushes the GC concern back to every caller, which is
what booktest was supposed to abstract.

## Implementation sketch

The actual change inside `DoubleCapacity` is ~3 lines:

```scala
override def release(amount: Double): Unit = lock.synchronized {
  _used -= amount
  if (_used < 0) _used = 0.0
  if (forceGcOnRelease) System.gc()   // ← new
  lock.notifyAll()
}
```

The flag is set at construction.

## Caveats worth deciding on

- `System.gc()` is a hint, not a guarantee.  HotSpot's default GC honours
  it; G1/ZGC under `-XX:+ExplicitGCInvokesConcurrent` won't do a stop-the-
  world.  For aito-core's use case this is fine — HotSpot is what CI runs
  — but worth noting in docs.
- Forcing a full GC per resource transition adds 100-500 ms of latency to
  each release.  For memory capacities that's the right trade; for logical
  capacities it would be wasteful, hence the kind/separate-constructor
  split.
- An alternative to `System.gc()` is to spin until `Runtime.getRuntime
  .freeMemory()` reaches a target.  Less reliable in practice and depends
  on JVM behaviour; `System.gc()` is the conventional answer.

## What aito-core wants from booktest

A single API path where `release` actually means "the memory is back".  At
that point aito-core's `EpistoBookTestSuite` can switch its
`aitoServerRam` declaration to the memory-typed variant and stop worrying
about the race.

Happy to PR this into booktest myself if you sketch the preferred shape
((A), (B), or other) — saves a roundtrip.

— from aito-core via Claude Code

---

## Resolved shape — option (B)

Confirmed 2026-05-18. Go with the `memoryCapacity` helper — separate
constructor, no `kind` enum to plumb, no surprise for existing
`capacity(...)` callers. Below is the merge target.

### `DoubleCapacity` — `src/main/scala/booktest/ResourceManager.scala:120`

Add a constructor flag and use it inside `release`:

```scala
class DoubleCapacity(
    val name: String,
    val capacity: Double,
    forceGcOnRelease: Boolean = false   // ← new, defaults to current behaviour
) extends ResourceCapacity[Double] {

  // ... unchanged ...

  override def release(amount: Double): Unit = lock.synchronized {
    _used -= amount
    if (_used < 0) _used = 0.0
    if (forceGcOnRelease) System.gc()   // ← intentionally STW *under the lock*
    lock.notifyAll()
  }
}
```

**Critical:** `System.gc()` must run while `lock` is held. Releasing the
lock between decrement and gc lets a waiter `acquire` into a still-full
heap and re-introduces the race the fix is meant to close.

### `ResourceManager.memoryCapacity` — same file, alongside `capacity` at line 43

```scala
/** Get-or-create a memory-typed Double capacity.
  *
  * Identical to [[capacity]] except that `release(amount)` forces a
  * `System.gc()` *while holding the capacity lock* before notifying
  * waiters. The contract is that when `release` returns, the physical
  * heap genuinely has room — not just the logical counter.
  *
  * Use for RAM budgets on shared JVM-resident resources whose `close()`
  * drops references but where GC hasn't yet reclaimed the bytes, and
  * the next consumer is about to allocate into the same heap. The
  * concrete case this addresses: two test classes under `-pN`, each
  * owning a heavy shared instance via a `liveResource` reserving the
  * same RAM capacity, where the second class's `build` starts the
  * moment the first's refcount hits zero. Without forced gc the old
  * instance's bytes are still resident, the new one allocates on top,
  * and the JVM goes into a GC death spiral on a `-Xmx` boundary.
  *
  * Caveats:
  *  - `System.gc()` is a hint. HotSpot's default (Parallel) GC honours
  *    it as a full STW collection. Under
  *    `-XX:+ExplicitGCInvokesConcurrent` (common with G1/ZGC tuning)
  *    it does not stop-the-world and the race can come back. Document
  *    the JVM-flag requirement for callers who rely on this.
  *  - Each `release` adds ~100–500 ms of latency on HotSpot. Acceptable
  *    for memory capacities that bracket heavyweight instances; do not
  *    use for hot-path resources.
  *
  * Subsequent calls with the same `name` return the same instance, so
  * all suites share one budget. Total overridable at runtime via
  * `--capacity <name>=<value>` or `BOOKTEST_CAPACITY_<NAME>`.
  */
def memoryCapacity(name: String, default: Double): ResourceCapacity[Double] =
  synchronized {
    capacities.get(name) match {
      case Some(c) => c.asInstanceOf[ResourceCapacity[Double]]
      case None =>
        val total = capacityOverrides.getOrElse(name,
          sys.env.get(s"BOOKTEST_CAPACITY_${name.toUpperCase}")
            .flatMap(s => scala.util.Try(s.toDouble).toOption)
            .getOrElse(default))
        val cap = new DoubleCapacity(name, total, forceGcOnRelease = true)
        capacities(name) = cap.asInstanceOf[ResourceCapacity[Any]]
        cap
    }
  }
```

Note: a single global `capacities` map keyed by `name` means a name
collision between `capacity("ram", ...)` and `memoryCapacity("ram", ...)`
silently returns whichever was created first — including its
`forceGcOnRelease` flag. If you want belt-and-braces, fail in
`memoryCapacity` when an existing entry has `forceGcOnRelease = false`
(or vice versa). I'd ship without the check first and add it only if
someone trips on it.

### `TestSuite.memoryCapacity` — `src/main/scala/booktest/TestSuite.scala:312`, beside `capacity`

```scala
/** Declare a memory-typed numeric capacity (e.g. 1500 MB of JVM heap
  * reserved for a shared in-process server). Identical to [[capacity]]
  * but `release` forces GC so the heap is genuinely free when the next
  * consumer allocates. Use for RAM budgets on shared JVM-resident
  * resources where `close()` drops references but GC hasn't reclaimed
  * the bytes yet. See [[ResourceManager.memoryCapacity]] for full
  * caveats. */
protected def memoryCapacity(name: String, total: Double): ResourceCapacity[Double] =
  resources.memoryCapacity(name, total)
```

### Test coverage

The race is heap-pressure-dependent and hard to reproduce
deterministically inside a unit test. Coverage targets, in order of
value:

1. **GC-count assertion** — unit test under
   `src/test/scala/booktest/test/` that snapshots GC-count
   (`ManagementFactory.getGarbageCollectorMXBeans` →
   `getCollectionCount`) before and after a
   `memoryCapacity("x", 100).release(50)` call, asserts strictly
   greater after. Proves the `System.gc()` call fires. Pair with the
   same probe on a regular `capacity(...)` showing no increase.
2. **Snapshot test** under `src/test/scala/booktest/examples/`
   demonstrating two sequential consumers of a `memoryCapacity`-backed
   live resource, with the second's build emitting a log line that
   captures "release ran first". Documents the API surface in the
   form a reviewer expects (booktest is TDD-by-snapshot).
3. **Optional stress test** in a file ignored by default (e.g.
   suffix-filtered out by `booktest.ini`'s exclude), runnable manually
   with `-Xmx256m` to actually reproduce the original aito-core flake
   and confirm the fix in situ. Not for CI.

### Docs to update in the same PR

- `USAGE.md` — new "memoryCapacity" subsection under capacity docs.
- `llms.txt` — mirror the `USAGE.md` change (per `CLAUDE.md`'s
  "When changing public API" instruction).
- `README.md` — only if this gets promoted to a top-billed feature;
  otherwise leave alone.
- `CHANGELOG.md` — entry under the next-release header.

— resolved by booktest maintainer via Claude Code
