# Changelog

## 0.4.3 (unreleased)

### Fix: `-t`-filtered runs no longer re-execute cached transitive dependencies

A run scoped with `-t pattern` previously expanded the filter to include
every transitive dependency unconditionally, so a 4-hour state-builder
test with a fresh `.bin` cache would re-execute on every invocation of
its consumer. This diverged from Python booktest's build-system
semantic, which loads cached deps from disk and only runs them when the
cache is missing.

Filter expansion is now cache-aware (`expandSelectionWithMissingDeps`
in `TestRunner`): a transitive dep is added to the run list only if (a)
it matches the filter, (b) `-r` / `--refresh-deps` is set, or (c) its
`<output>/.out/<suite>/<dep>.bin` is missing. Otherwise the consumer
loads the producer's value from disk via `resolveDependencyValue` and
the producer is not run.

- New `-r` / `--refresh-deps` flag forces every transitive dep to
  re-execute (use before benchmarks, or when you suspect upstream state
  is stale).
- `resolveDependencyOrder` now silently skips deps that exist in the
  suite but were filtered out as cached, instead of throwing
  `Dependency '...' not found`. Genuinely unknown names still throw.
- The pre-pass in `runMultipleSuites` (`scheduledInThisRun`,
  live-resource refcount reservation) uses the same expansion, so the
  cache-fence and refcount stay in sync with what actually runs.

New meta-suite `RefreshDepsTest` covers all three branches: cached dep
is loaded from `.bin`, `-r` forces a re-run, and a missing `.bin`
auto-includes the dep without `-r`.

### Fix: path-resolved test selection uses exact match (not substring)

When a CLI argument resolved to `SuiteName/testCase` (e.g. running
`InvoicePerf/state-10M`), the test name was being substring-matched, so
`state-10M` also picked up `optimized-state-10M` and any other test
whose name contained the target as a substring.

Path-resolved selections now use exact equality. The grep-style `-t`
flag keeps substring matching for convenience. Internally, this is a
new `RunConfig.exactFilter` flag that `BooktestMain` sets when the
filter comes from path resolution (the `SuiteName.testCase` split path
in argument resolution).

New meta-suite `FilterAndDependencyTest` covers exact-vs-substring,
auto-inclusion of dependencies under filtered runs, and `.bin`
fallback for filtered-out dependencies.

## 0.4.2 (2026-04-29)

### Fix Issue 1: live-resource consumers could outrun their transitive TestRef producers under -pN

The parallel scheduler's readiness predicate (`runTestsParallel`)
only walked a test's *immediate* `tc.dependencies` strings, treating
any name that wasn't in `testMap` as "must be a live resource,
materialized on demand" — and dispatching the consumer as ready
without waiting for the producer tests that the live resource
transitively reads. Combined with the disk `.bin` fallback in
`loadDependencyValue`, this let the consumer's resource build
closure resolve the producer from a *prior* invocation's `.bin`
while the in-run producer concurrently called `clearTmpDir()` and
recreated its state. The Aito Core integration project hit this as
sporadic `NoSuchObjectException: invoices not found` at `-p2`. See
the full anatomy in `.ai/plan/task-graph.md` (Issue 1).

Two complementary fixes, both shipped:

- **Fix A — producer-aware readiness predicate.** New
  `LiveResourceManager.transitiveTestProducers(deps)` walks every
  registered live resource reachable from the consumer's
  `tc.dependencies`, recursing through nested `ResourceDep`s, and
  returns the names of every `TestDep` encountered. The parallel
  scheduler now waits for that producer set to complete before
  considering a test ready, in addition to the immediate-deps set.
- **Fix B — cache run-set awareness.** `loadDependencyValue` now
  consults a `scheduledInThisRun: Set[String]` populated by the
  pre-pass and refuses to fall back to disk `.bin` when the
  requested dep is scheduled to run in this invocation. The
  in-memory cache (which Fix A guarantees is populated by the time
  the consumer reads) is the only valid source for in-run
  producers; disk reflects a prior invocation. `--continue` and
  cross-invocation use cases keep their disk fallback.

New meta test `LiveResourceProducerOrderingTest` reproduces Issue 1
deterministically — a producer with a 500ms sleep, a unique-per-run
path, and a stale `.bin` from the prior run — and asserts the
consumer always sees the in-run producer's path under `-p2`.

### Fix Issue 2: failure logs now surface dep-resolution and live-resource lifecycle

Race-class failures previously surfaced as a `NoSuchObjectException`
or `None.get` deep in user code, with no signal pointing at the
booktest-internal cause. Diagnosing took eyeball-on-thread-IDs
work. Booktest had every piece of information needed; it just
didn't print it.

This release adds a structured event bus and an always-on bounded
ring buffer that captures lifecycle events from the runner and the
live-resource manager. Events: `SchedReady`, `DepResolve` (with
`source` ∈ memory / bin / miss / miss-pending / bin-error), `TaskRun`
(producer or live-resource build), `TaskAcquire`, `TaskRelease`,
`TaskReset`, `TaskClose`, `TaskEnd`. Every live-resource event
carries an identity hash (hex of `System.identityHashCode`) so the
trace disambiguates "same handle as the previous event" from
"different handle."

When a test fails or DIFFs at `-pN ≥ 2`, the runner automatically
appends a "Trace context for ..." block to the result's `diff`,
populated from the ring buffer for that test plus its transitive
resources and producers. CI artifact viewers and end-of-run diff
sections see the full chronological event stream without re-running
with `--trace`.

Opt-in extras:

- `--trace` / `BOOKTEST_TRACE=1` writes the same events to
  `<output-dir>/.booktest.log` as a thread-tagged human-readable
  log, ready for grep.

New meta test `TaskTraceTest` verifies the event stream and the
auto-attached failure block.

### Public API additions

- `class TraceEvent` ADT (`SchedReady`, `DepResolve`, `TaskRun`,
  `TaskAcquire`, `TaskRelease`, `TaskReset`, `TaskClose`, `TaskEnd`).
- `trait TaskTrace` with `def emit(e: TraceEvent): Unit`.
- `class RingBufferSink`, `class LogfileSink`, `class BroadcastTrace`.
- `LiveResourceManager.transitiveTestProducers(deps)`.
- `TestRunner.traceBuffer: RingBufferSink` (for meta-tests).
- `RunConfig.trace: Boolean` (CLI: `--trace`; env:
  `BOOKTEST_TRACE`).

This is Phase 1 of the unified Task Graph plan
(`.ai/plan/task-graph.md`). Phase 2 will collapse the
test/live-resource execution paths into a single `TaskEngine`; this
release intentionally keeps the public API and code shape minimal.

### Fix lost-write race in DependencyCache and harden adjacent maps

`DependencyCache.memoryCache` was an unsynchronized `var Map[String, Any]`.
Under `-pN`, two workers calling `put` concurrently each read the same
baseline map, appended their own entry, and wrote the result back —
silently dropping whichever write landed first. Symptoms were
non-deterministic: `Dependency 'X' failed when auto-run`, missing
producer values in consumer chains, sporadic `None.get` on cached
values, and downstream "table not found" / "items not found" failures
in resource-heavy suites.

Fixed: every `DependencyCache` read and write now goes through a
`synchronized` block, with double-checked publication on `getOrLoad`
so a slow `.bin` read doesn't stall the cache.

Same defensive shape applied to the per-test snapshot managers that
held unsynchronized `var` maps — `EnvSnapshotManager`,
`EnvMockManager`, `FunctionSnapshotManager`, `FunctionMocker`,
`HttpSnapshotManager` — and to `ResourceManager.register` / `pool` /
`releaseAll` and `LockPool.release`. None of these is normally hit
by multiple threads in routine use, but they shared the same
"copy-on-write a `var Map` without a lock" pattern that was the bug
above.

New meta test `DependencyCacheConcurrencyTest` stress-tests the cache
from 16 writers + 8 readers and asserts no put is lost or corrupted.

### Full stack traces for booktest-internal failures

Race conditions and other framework-internal errors previously
surfaced as one-line `${e.getMessage}` summaries from the parallel
worker, the setup/teardown hooks, and `beforeAll` / `afterAll`. The
call site was lost. Every catch path in `TestRunner` now prints the
full stack trace (via `formatStackTrace`) into the test's `.md`
output and `.txt` diff report, so failures only seen under `-pN` are
diagnosable from CI artifacts.

### CI runs meta tests under -p4

The workflow already ran examples both sequentially and under `-p4`,
but meta tests only ran sequentially. Added a parallel meta-test step
so race regressions in the framework are caught at PR time, not in a
downstream consumer.

### `<test>.txt` matches Python booktest

`<outDir>/.out/<suite>/<test>.txt` previously contained only a one-line
`Test 'X' completed in Xms` summary, so CI artifact viewers landing on
it after a flagged DIFF showed nothing useful. It now mirrors Python
booktest: the full per-line diff report with `?` / `.` / `!` markers
and side-by-side expected vs actual, followed by the status/duration
line. The data was already built up in memory (`diffReportBuffer`) — it
just wasn't being persisted.

### Live resource docs: lifecycle and consumer-borrow contract

Added explicit "Lifecycle (runner-managed)" and "Consumer contract: do
not close an injected handle" sections to USAGE.md, llms.txt, and
CLAUDE.md. Lifecycle walks build → first acquire → per-consumer
borrow → last release → close → shutdownAll safety net. The contract
section makes the implicit `T <: AutoCloseable` rule explicit: the
bound exists for the runner; consumers borrow, never own.

No runtime close-guard for injected handles yet (requires per-handle
opt-in wrapping; deferred until requested).

## 0.4.1 (2026-04-28)

### Hardening from real-world parallel use

Driven by the aito-core migration to 0.4.0 — issues surfaced under
`-p2`/`-p4`/`-p8` after a clean sequential migration.

- **`liveResourceSerialized(name, deps...)(build)`** — new sharing mode
  for "shared, but consumers run one at a time, no reset closure
  required". Sits between `liveResource` (concurrent readers) and
  `liveResourceWithReset` (serialized + reset + always-invalidate-on-fail).
  Useful for resources that are read-only at the consumer level but
  produce snapshot output that's not safe to interleave.
- **PortPool rotation + cooldown**. `resources.ports` previously always
  returned the lowest-numbered free port and used a non-`SO_REUSEADDR`
  probe, which under `-pN` could re-issue a just-released port before
  the kernel had finished tearing down the previous listener — the next
  Akka/Netty bind on that port would then fail with `BindException:
  Address already in use`. The pool now:
  - prefers ports never used (then longest-released) over the lowest
    free port, so port reuse rotates organically;
  - applies a cooldown between release and re-issue (default 250ms,
    `BOOKTEST_PORT_COOLDOWN_MS=N` to override; `0` disables);
  - probes with `SO_REUSEADDR=true` and an explicit bind so the probe
    matches what most server libs do at bind time.
- **Errors no longer abort the batch**. `runTestCase` previously caught
  only `Exception`, so a test throwing an `Error` subclass (e.g.
  `NotImplementedError`, `AssertionError`) escaped into the suite-level
  loop and prevented every test queued after it from running. The catch
  is now `NonFatal`, and the diff message unwraps reflection's
  `InvocationTargetException` so reports show the underlying cause
  instead of `failed with exception: null`.

## 0.4.0 (2026-04-26)

### Live resources

A new `liveResource(name, deps...) { build }` declaration on `TestSuite`
shares an expensive-to-build `AutoCloseable` (database, HTTP server,
process) across many test consumers. Three sharing modes:

- `liveResource(...)` — default. One instance, many concurrent readers.
- `liveResource.withReset` (`liveResourceWithReset(...) { build } { reset }`)
  — shared instance, runner serializes consumer access and calls `reset`
  between consumers. Build/close happen once.
- `exclusiveResource(...)` — each consumer gets its own instance.

Consumers receive the live object directly:

```scala
val server: ResourceRef[StringServer] =
  liveResource("server", state, resources.ports) {
    (s: String, port: Int) => new StringServer(port, s)
  }

test("clientGet", server) { (t: TestCaseRun, http: StringServer) =>
  t.tln(s"GET /echo -> ${http.get("/echo")}")
}
```

Dependencies in a `liveResource` declaration mix freely:
- `TestRef[T]` — a previous test's return value (loaded from `.bin`).
- `ResourceRef[T]` — another live resource (refcount-shared).
- `ResourcePool[T]` (e.g. `resources.ports`) — held for the resource's
  lifetime, released into the pool on close.
- `ResourceCapacity[N].reserve(amount)` — see Capacity.

### Capacity (numeric budgets)

`ResourceCapacity` allocates fractions of a numeric total (e.g. RAM in MB,
CPU shares). Declared per suite, but global per name:

```scala
val ram = capacity("ram", 4096.0)  // 4 GB total

val server: ResourceRef[Server] =
  liveResource("server", ram.reserve(1024.0)) { (mb: Double) => ... }
```

Override at runtime:
- `BOOKTEST_CAPACITY_<NAME>=8192` env var.
- `--capacity ram=8192` CLI flag.

### Lifecycle, scheduling, and failure handling

- **Refcount-based teardown**. The runner pre-reserves the transitive
  closure of each test's live-resource deps so nested resources stay
  alive across all consumers, then releases each (leaf-last) when the
  test ends.
- **Locality-aware scheduler**. Sequential mode groups consumers of the
  same resource adjacent. Parallel mode ranks ready tests so already-alive
  resources are drained first. Suites with `resourceLocks` are serialized
  inside the parallel scheduler for deterministic order.
- **Capacity validation pre-pass**: any single resource's reservation
  exceeding its capacity total fails fast with a clear message.
- **Lifecycle telemetry**: `-v` prints `[build name] ms`, `[close name] ms`,
  `[reset name] ms`, plus an end-of-run "live resources" summary table
  (builds/closes/resets/alive ms per resource).
- **Failure semantics**:
  - `build` throws → consumer fails clearly with the cause; sibling tests
    still run; partially-acquired pool/capacity allocations are released.
  - `close` throws → swallowed; allocations released anyway.
  - `reset` throws → instance invalidated; next consumer triggers a fresh
    build.
  - Consumer fails on `withReset` → instance always invalidated.
  - Consumer fails on `SharedReadOnly` → instance kept by default. Pass
    `--invalidate-live-on-fail` to force close + rebuild after any
    failure.
- **Concurrency safety**: per-entry build lock prevents concurrent
  first-time consumers in parallel mode from each materializing their
  own instance.

### Documentation

- New `## Live Resources` section in CLAUDE.md with API reference, sharing
  mode table, dependency types, capacity, failure semantics, and verbose
  output sample.
- `## Test-Driven Development` section codifies the snapshot-test
  workflow used to develop the framework.
- Design docs in `.ai/plan/live-resources.md` (full design) and
  `.ai/plan/live-resources-example.md` (worked example).

## 0.3.7 (2026-04-23)

### Unified interactive review

- **Single `interact()` function** for both `-i` and `-w` modes, matching
  Python booktest. Previously `-i` mode only had `a/c/q`, now both modes
  support all options: `(a)ccept`, `(c)ontinue`, `(q)uit`, `(v)iew`,
  `(l)ogs`, `(d)iff`, `(aq)` accept & quit.
- **`(a)ccept` hidden on FAIL** tests (matches Python — can't accept a
  test that threw an exception).
- **`runTool()`** for launching external tools, matching Python's
  `run_tool()`. Resolves from `BOOKTEST_{TOOL}` env vars with defaults
  (`diff` for diff_tool, `less` for viewers).

### Bug fixes

- **Always cache return values on OK/DIFF**: Previously .bin files were only
  written when the test passed (snapshot matched) or in auto-accept mode.
  Tests with DIFF status (new or changed snapshots) didn't persist their
  return values, breaking dependent tests. Now matches Python booktest:
  .bin is written whenever the test ran successfully (OK or DIFF), and
  deleted on FAIL (exception/t.fail()).
- **`assertln` fixed to match Python**: No longer throws on failure. Uses
  `iln("ok")` on success (info-only) and `fln("FAILED")` on failure (marks
  failed without throwing). Signature changed from `(label, condition)` to
  `(condition, message)` to match Python.

### Meta tests

- **BinCacheTest**: .bin written on DIFF, deleted on FAIL, dependency
  injection via .bin across runners
- **TokenMarkerTest**: info tokens don't fail, checked tokens cause DIFF,
  tokenizer alignment across feed calls, diff report markers

## 0.3.6 (2026-04-21)

### Tokenizer fix for `t.t("label..").iMsLn { block }` pattern

- **Token alignment fix**: `.` no longer starts a number token in the
  tokenizer. Previously `.40` was tokenized as one number, but when output
  comes from separate `testFeed("..") + infoFeed("40ms")` calls, the `.`
  and `40` are separate tokens. This misalignment caused info-only timing
  diffs to appear as test-failing content diffs.
- **`iMsLn` overload fix**: Removed ambiguous curried `iMsLn(label)(block)`
  overload. When the block returned `String`, Scala silently resolved
  `iMsLn { expr }` to the label overload, discarding timing output entirely.
  Use `t.t("label: ").iMsLn { block }` or `t.i("label: ").iMsLn { block }`
  instead.
- **`TimingInfoTest`**: New test suite verifying that timing differences from
  `iMsLn` are treated as info-only (`.` cyan) and don't cause test failure.

## 0.3.5 (2026-04-18)

### Interactive mode improvements

- **Hard quit (`q`)**: Pressing `q` during interactive mode now stops test
  execution immediately instead of continuing to the next test
- **Accept and quit (`aq`)**: New command to accept the current diff and stop
- **`(d)iff` option**: Launch external diff tool in review mode, configured
  via `BOOKTEST_DIFF_TOOL` env var (defaults to `diff`)
- **Uncolored prompts**: Interactive prompts now render in terminal default
  color, matching Python booktest

### Python-style diff formatting

- **Diff symbols**: `?` (yellow) for content diffs, `!` (red) for fails,
  `.` (cyan) for info-only diffs — matching Python booktest convention
- **Inline diff report**: Generated during test execution with proper
  token-level markers instead of post-hoc line comparison
- **Expected text**: Shown in gray on the right side (`actual | expected`)
- **DIFF status**: Changed from orange to yellow
- **Summary format**: Now separates "differed" and "failed" counts
  (e.g., "2 differed and 1 failed")
- **Final report**: Lists individual failed tests with colored status

### Bug fixes

- **Dependency cache collision**: Fixed parallel execution (`-p4`) crash when
  multiple suites have tests with the same name (e.g., `createData`). Cache
  keys now include suite path.
- **EOF token marking**: Info content beyond snapshot end now correctly marked
  as info (was silently ignored)

### CI

- Added GitHub Actions CI workflow with Scala 3.3.1 tests and 2.12/2.13
  cross-compilation checks

## 0.3.3 (2026-04-07)

- **Orange DIFF status**: DIFF results now shown in orange instead of red,
  distinguishing them from FAIL (red). Matches Python booktest convention.
- **Review mode (`-w`) rewritten**:
  - Shows all results from previous run (names, status, durations)
  - Test selection narrows review (e.g., `examples/TmpDirTest -w`)
  - Trusts case report results instead of re-comparing files
  - Verbose mode (`-v`) shows output content for all tests
  - Interactive mode (`-i`) adds `(v)iew` and `(l)ogs` options
- **SuiteName/testCase args**: Positional arg like `GroceryTest/prefill`
  resolves as suite + test filter
- **Dependency preservation in `-t` filter**: Transitive dependencies of
  matched tests are included so setup tests still run

## 0.3.2 (2026-04-06)

### Breaking changes

- **Snapshot files now include info-line content.** All existing snapshots need
  regeneration (`-S` flag). Previously `iln()` / `i()` output was excluded from
  snapshot files entirely, making them unreadable for data science workflows
  where diagnostic output is essential for review.
- `TestResult` has a new `successState` field (`SuccessState.OK` / `DIFF` / `FAIL`).

### Token-by-token snapshot comparison

Replaces the line-by-line post-hoc comparison with Python booktest's
token-by-token comparison that runs during test execution. Each token is
compared as it is written, enabling precise diff/info/fail markers within a
single output line. Info-only differences (`i()` / `iln()`) no longer cause
test failure, matching Python booktest behavior.

### Anchor/seek for non-linear snapshot matching

Headers (`h1` .. `h5`) now seek to the matching line in the snapshot before
writing, so inserting new sections between existing ones does not cascade false
diffs across the rest of the file. Public API:

- **`t.anchor(prefix)`** / **`t.anchorln(line)`**: Seek then write
- **`t.seekLine(line)`** / **`t.seekPrefix(prefix)`**: Seek only
- **`t.seek(predicate)`**: General-purpose snapshot cursor navigation
- **`t.jump(lineNumber)`**: Absolute positioning

### New methods

- **`t.f(text)`** / **`t.fln(text)`**: Write fail content (always marks test
  as failed, included in snapshot)
- **`t.diff()`**: Mark current line as having a diff
- **`t.fail()`**: Mark current line as failed (no-arg variant)
- **`t.h4(title)`** / **`t.h5(title)`**: Level 4 and 5 headers

### Other changes

- `TestTokenizer`: Tokenizer matching Python booktest rules (whitespace,
  numbers with sign/decimal/scientific notation, alphanumeric words, special
  characters)
- `SuccessState` enum separates snapshot-mismatch from test-logic failure,
  matching Python's two-dimensional result model
- `TestCaseRun` lifecycle: `start()` / `end()` methods for output file and
  snapshot reader management
- `findNextNumber` (used by `peekDouble` / `peekLong`) no longer consumes
  the snapshot token stream

## 0.3.1 (2026-03-13)

- **`--root` CLI flag**: Override package prefix stripping from the command line
  (alternative to setting `root` in `booktest.ini`)
- **Port management API**: `t.acquirePort()`, `t.releasePort()`, `t.withPort { port => }` for
  tests that need network ports
- **Env-configurable port range**: `BOOKTEST_PORT_BASE` and `BOOKTEST_PORT_MAX` environment
  variables for CI environments with restricted ports
- **Suite-level parallel execution**: With `-pN`, suites now run in parallel (tests within
  each suite remain sequential)
- **Fix**: `RelPath` handling for `--output-dir` and `--snapshot-dir` with nested paths

## 0.3.0 (2026-02-14)

Major release adding parallel execution, Python-style file structure, rich test output,
and data science workflow features.

### Parallel Execution

- **`-pN` flag**: Run tests in parallel with N threads (e.g., `-p4`)
- **Resource locks**: Tests sharing mutable state can declare `resourceLocks` to
  serialize execution even under `-pN`
- Dependency-aware scheduling ensures prerequisites complete before dependents run

### Continue Mode

- **`-c` / `--continue` flag**: Skip tests that passed in the previous run,
  re-running only failures
- Test results persisted in `cases.ndjson` (Python-compatible format)

### Python-Style File Structure

Snapshot files reorganized to match the Python booktest layout:

```
books/
├── .out/                        # Test execution output (gitignored)
│   └── SuiteName/
│       ├── testName/            # Tmp directory
│       ├── testName.md          # Test output
│       ├── testName.log         # Captured stdout/stderr
│       └── testName.bin         # Return value cache
├── SuiteName/                   # Committed snapshots
│   └── testName.md
```

### Configuration File (`booktest.ini`)

- Define test root package, default groups, and exclude patterns
- Named groups for running sets of tests (e.g., `./do test examples`)
- Exclude patterns to skip specific tests by default

### Metrics with Tolerance

- **`t.tmetric(name, value, tolerance)`**: Track numeric metrics with absolute tolerance
- **`t.tmetricPct(name, value, tolerancePct)`**: Percentage-based tolerance
- **Direction constraints**: `direction = Some("min")` prevents regressions (value must
  not decrease), `direction = Some("max")` prevents increases
- When value is within tolerance of the snapshot, the old value is kept for stable diffs

### Image Support

- **`t.timage(file, caption)`**: Include images in tested snapshot output
- **`t.iimage(file, caption)`**: Include images as info-only output
- **`t.renameFileToHash(filename)`**: Rename files to content hash for deterministic names
- Images stored in per-test asset directories

### Table Output

- **`t.ttable(headers, rows)`**: Markdown table in tested output
- **`t.itable(map)`**: Info-only table from key-value Map
- **`t.ttable(caseClasses)`**: Auto-generate table from case class sequence using reflection

### Snapshot Caching

- **`t.snapshot(name) { expensive() }`**: Cache expensive computations
- Automatic invalidation when arguments change (hash-based)
- **`-S` flag** forces recomputation of all cached snapshots
- Methods: `hasSnapshot()`, `invalidateSnapshot()`, `invalidateAllSnapshots()`

### Temporary Directories

- **`t.tmpDir(subpath)`**, **`t.tmpFile(subpath)`**, **`t.tmpPath(subpath)`**:
  Create temp files/directories that persist between dependent tests
- Automatically cleared on test re-run

### Async Support

- **`t.await(future)`**: Wait for a Future with configurable timeout
- **`t.async { implicit ec => ... }`**: Run async blocks with automatic ExecutionContext
- **`t.ec`**: Access the test's implicit ExecutionContext

### Test Markers

- **`mark(testName, markers...)`**: Tag tests with categories
- Predefined: `Slow`, `Fast`, `Integration`, `Unit`, `GPU`, `Network`, `Flaky`
- Query with `getMarkers()`, `hasMarker()`

### Setup/Teardown Hooks

- **`setup(t)`** / **`teardown(t)`**: Run before/after each test
- **`beforeAll()`** / **`afterAll()`**: Run once per suite
- `teardown` always executes, even on test failure

### Log Capture

- Stdout/stderr captured to `.log` files during test execution
- Captured output available in review mode

### Additional CLI Options

- **`--garbage`**: List orphan files in `books/` not corresponding to active tests
- **`--clean`**: Remove orphan files and temp directories
- **`--tree`**: Hierarchical tree display with `-l`
- **`--inline`**: Show diffs inline during execution
- **`-S` / `--recapture`**: Force regenerate all snapshots
- **`-s` / `--update`**: Auto-accept all snapshot changes

### Other Improvements

- **`t.assertln(condition, message)`**: Assert with automatic OK/FAILED output
- **`t.key(key, value)`**: Output labeled key-value pairs
- **`t.fail(message)`**: Explicitly mark test as failed
- **`t.iMsLn("label") { block }`**: Execute block and print elapsed time as info
- **`t.ms { block }`**: Returns `(elapsed_ms, result)` tuple
- **`t.tln` / `t.iln` without parentheses**: Output empty line without needing `()`
- **Safer test discovery**: Helper methods starting with `test` that take extra
  parameters (beyond `TestCaseRun`) are no longer picked up as phantom tests
  unless they have a `@DependsOn` annotation
- Python-style colored terminal output
- SBT plugin scaffolding (`plugin/` directory)

## 0.2.1 (2026-02-07)

- Fix publishing to include all Scala versions (2.12, 2.13, 3.3)
- `./do publish` now uses `+publishSigned` for cross-compilation

## 0.2.0 (2026-02-06)

- `t.fail()`, `t.file()`, `t.iMsLn()` methods
- `t.ms{}`, `t.iUsPerOpLn()`, `t.tUsPerOpLn()` for performance testing
- `t.tLongLn()`, `t.tDoubleLn()` with tolerance parameter
- `t.assertln()` for assertions
- `t.peekDouble`, `t.peekLong`, `t.peekToken` for snapshot comparison
- Cross-compilation: Scala 2.12.18, 2.13.12, 3.3.1
- Info lines correctly excluded from snapshot comparison