# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

This is a Scala port of the [booktest](https://github.com/lumoa-oss/booktest) framework - a review-driven testing tool designed for data science workflows.

Booktest enables snapshot testing where tests write output to markdown files that are compared against saved snapshots. This approach is ideal for data science where results aren't strictly right/wrong but require expert review.

## Architecture

The Scala implementation follows a minimal core design with these key components:

- **TestCaseRun**: Main API for writing test output (`tln()`, `h1()`, `i()`, `iln()`)
- **TestSuite**: Base class for organizing tests, discovers test methods automatically
- **TestRunner**: Sequential test execution engine
- **SnapshotManager**: Handles reading/writing/comparing snapshot files
- **BooktestMain**: CLI entry point
- **HttpSnapshotting**: HTTP request/response capture and replay
- **EnvSnapshotting**: Environment variable mocking and snapshotting
- **FunctionSnapshotting**: Function call caching and snapshotting

Test output is written to `books/` directory as markdown files and compared against previous snapshots stored in Git.

## Project Structure

```
booktest-scala/
├── build.sbt                    # SBT build configuration
├── booktest.ini                 # Test configuration (root, groups, exclude patterns)
├── src/main/scala/booktest/     # Core framework implementation
├── src/test/scala/booktest/     # Framework tests and examples
└── books/                       # Snapshot storage directory
```

## Technology Stack

- **Scala 3.3.1**: Modern Scala with improved syntax and type inference
- **os-lib**: Ergonomic file system operations (preferred over java.nio)
- **fansi**: Terminal colors for output formatting
- **upickle**: JSON serialization for snapshots
- **sttp**: HTTP client library for HTTP snapshotting
- **munit**: Testing framework for the booktest framework itself

## Development Commands

```bash
# Build the project
sbt compile

# Compile test sources
sbt "Test/compile"

# Run example tests
sbt "Test/runMain booktest.BooktestMain -v booktest.examples.ExampleTests"

# Run dependency tests (demonstrates string-based dependencies)
sbt "Test/runMain booktest.BooktestMain -v booktest.examples.DependencyTests"

# Run method reference tests (recommended approach)
sbt "Test/runMain booktest.BooktestMain -v booktest.examples.MethodRefTests"

# List test cases
sbt "Test/runMain booktest.BooktestMain -l booktest.examples.MethodRefTests"

# Show test logs
sbt "Test/runMain booktest.BooktestMain -L booktest.examples.MethodRefTests"

# Test filtering by name pattern
sbt "Test/runMain booktest.BooktestMain -v -t Data booktest.examples.DependencyTests"

# Interactive mode (for snapshot updates)
sbt "Test/runMain booktest.BooktestMain -i booktest.examples.FailingTest"

# Run with help
sbt "Test/runMain booktest.BooktestMain --help"

# Run advanced feature tests
sbt "Test/runMain booktest.BooktestMain -v booktest.examples.HttpSnapshotTests"
sbt "Test/runMain booktest.BooktestMain -v booktest.examples.EnvSnapshotTests"
sbt "Test/runMain booktest.BooktestMain -v booktest.examples.FunctionSnapshotTests"

# Review mode (show diffs without re-running tests)
sbt "Test/runMain booktest.BooktestMain -w booktest.examples.ExampleTests"

# Parallel execution (4 threads)
sbt "Test/runMain booktest.BooktestMain -p4 examples"

# Garbage collection (list orphan files)
sbt "Test/runMain booktest.BooktestMain --garbage"

# Clean orphan files and tmp directories
sbt "Test/runMain booktest.BooktestMain --clean"
```

## Test API Design

Tests extend `TestSuite` and define methods starting with `test`:

```scala
import booktest.*

class ExampleTests extends TestSuite {
  def testExample(t: TestCaseRun): Unit = {
    t.h1("Test Header")
    t.tln("Output line checked against snapshot")
    t.i("Info line not checked against snapshot")
  }
}

// Annotation-based dependencies (for execution order and value injection)
class DependencyTests extends TestSuite {
  def testCreateData(t: TestCaseRun): String = {
    t.tln("Creating data...")
    "some_data"
  }

  @DependsOn(Array("testCreateData"))
  def testUseData(t: TestCaseRun, cachedData: String): Unit = {
    t.tln(s"Using cached data: $cachedData")
  }
}

// Resource locks for tests sharing mutable state
class SharedStateTests extends TestSuite {
  // All tests in this suite will run sequentially (even with -pN)
  override protected def resourceLocks: List[String] = List("shared-state")

  private var counter = 0
  def testFirst(t: TestCaseRun): Unit = { counter += 1 }
  def testSecond(t: TestCaseRun): Unit = { counter += 1 }
}

// Method reference API (recommended approach)
class MethodRefTests extends TestSuite {
  val data = test("createData") { (t: TestCaseRun) =>
    t.tln("Creating data...")
    "processed_data_123"
  }
  
  val enhanced = test("useData", data) { (t: TestCaseRun, cachedData: String) =>
    t.tln(s"Using cached data: $cachedData")
    s"enhanced_$cachedData"
  }
  
  test("finalStep", data, enhanced) { (t: TestCaseRun, original: String, processed: String) =>
    t.tln(s"Original: $original, Processed: $processed")
  }
}
```

## Implementation Status

**Phase 1 Complete**: Core snapshot testing framework
- ✅ TestCaseRun API with tln(), h1(), i(), iln() methods
- ✅ Test discovery and execution
- ✅ Snapshot comparison with diffs
- ✅ CLI interface

**Phase 2 Complete**: Advanced features
- ✅ @dependsOn annotation for test dependencies
- ✅ Dependency resolution and execution ordering
- ✅ Cached return values between dependent tests
- ✅ Test filtering by name patterns (-t option)
- ✅ Interactive mode for snapshot updates (-i option)
- ✅ HTTP request/response snapshotting
- ✅ Environment variable snapshotting
- ✅ Function call snapshotting
- ✅ Review mode for showing diffs without re-running tests

**Planned for future releases:**
- Parallel execution
- Resource management (port pools, memory allocation)
- Dependency injection beyond 3 parameters
- SBT plugin integration

## Advanced Features

### HTTP Snapshotting
```scala
// Capture HTTP requests/responses as JSON snapshots
class HttpSnapshotTests extends TestSuite {
  def testHttpGet(t: TestCaseRun): Unit = {
    val client = HttpSnapshotting.createClient(t)
    val response = client.get("https://api.example.com/data")
    t.tln(s"Status: ${response.code}")
    t.tln(s"Body: ${response.body}")
  }
}
```

### Environment Variable Snapshotting
```scala
// Mock and snapshot environment variables
class EnvSnapshotTests extends TestSuite {
  def testEnvVars(t: TestCaseRun): Unit = {
    EnvSnapshotting.withEnv(Map("TEST_VAR" -> "test_value"), t) {
      val value = sys.env.get("TEST_VAR")
      t.tln(s"Environment variable: $value")
    }
  }
}
```

### Function Call Snapshotting
```scala
// Capture function calls and return values
class FunctionSnapshotTests extends TestSuite {
  def testFunctionMocking(t: TestCaseRun): Unit = {
    val mockFn = FunctionSnapshotting.mock[String, Int]("parseNumber", t)
    val result = mockFn("123")
    t.tln(s"Parsed number: $result")
  }
}
```

## Architecture Patterns

### Caching System
- **Memory Cache**: Fast in-process storage for test execution
- **Per-test Cache**: Persistent storage in `testName.bin` alongside test output
- **Cache Format**: Simple text format (e.g., "STRING:value", "INT:42", "LONG:123")
- **Type Support**: String, Int, Long, Double, Boolean, and general objects via toString

### Test Discovery and Execution
- **Reflection-based**: Discovers methods starting with "test" that take TestCaseRun as first parameter
- **Dependency Resolution**: Topological sort ensures correct execution order
- **Parallel Execution**: Use `-pN` for N parallel threads (e.g., `-p4`)
- **Error Handling**: Proper cleanup with detailed error reporting

### File Organization (Python-style)
```
books/
├── .out/                        # Test execution output (gitignored)
│   └── SuiteName/
│       ├── testName/            # Tmp directory for test
│       ├── testName.bin         # Return value cache (for dependencies)
│       ├── testName.md          # Test output
│       ├── testName.log         # Captured stdout/stderr
│       ├── testName.txt         # Test report
│       └── testName.snapshots.json  # HTTP/function snapshots
├── SuiteName/                   # Final snapshots (committed to Git)
│   └── testName.md
└── index.md                     # Optional index file
```

### Snapshot Comparison
- **Diff Generation**: Line-by-line comparison with colored output
- **Interactive Mode**: Accept/reject changes with y/n prompts
- **Review Mode**: Show diffs without re-running tests
- **Markdown Format**: Human-readable test output format

## Live Resources

Some tests need a stateful external service (database, HTTP server, process)
that is expensive to start. Booktest's **live resources** let many tests
share one running instance.

A live resource is declared with `liveResource(...)` next to `test(...)`. It
is **not** a test: no snapshot, no `.bin` cache, no `TestCaseRun`. It just
returns an `AutoCloseable` and the runner manages its lifecycle.

```scala
class StringServer(port: Int, payload: String) extends AutoCloseable {
  // ... starts an HttpServer on `port` in init, stops it in close() ...
}

class MyTests extends TestSuite {

  // A normal test: serializable state, snapshotted as today.
  val state: TestRef[String] = test("createState") { (t: TestCaseRun) =>
    t.tln("State: hello world")
    "hello world"
  }

  // A live resource: depends on `state` (a TestRef) and a port from the
  // existing PortPool. Built once on first consumer, closed when the last
  // consumer finishes. The port is held for the resource's lifetime and
  // released back to the pool when close() is called.
  val server: ResourceRef[StringServer] =
    liveResource("server", state, resources.ports) {
      (s: String, port: Int) => new StringServer(port, s)
    }

  // Consumers depend on the ResourceRef and receive the live object.
  test("clientGet", server) { (t: TestCaseRun, http: StringServer) =>
    t.tln(s"GET /echo -> ${http.get("/echo")}")
  }

  test("clientLength", server) { (t: TestCaseRun, http: StringServer) =>
    t.tln(s"length: ${http.get("/echo").length}")
  }
}
```

### Choosing a sharing mode

| Declaration | When to use |
|---|---|
| `liveResource(name, deps...) { build }` | Default. Multiple consumers use the **same** instance concurrently. Tests promise not to mutate observable state. |
| `liveResource.withReset` (`liveResourceWithReset(name, deps...) { build } { reset }`) | Tests mutate state but the reset closure brings it back to a known baseline. The runner serializes consumer access on this instance and calls `reset(handle)` between consumers. Build/close still happen exactly once. |
| `exclusiveResource(name, deps...) { build }` | Each consumer gets its **own** fresh instance. Use when sharing isn't safe at all. Equivalent to today's per-test setup/teardown. |

### Dependency types

A `liveResource(...)` dep list mixes three kinds, all type-distinguished:

- `TestRef[T]` — a previous test's return value, loaded from `.bin` (or
  auto-run if missing).
- `ResourceRef[T]` — another live resource. The runner resolves
  transitively and shares its instance via refcount.
- `ResourcePool[T]` — the existing pool API (e.g. `resources.ports`,
  custom pools). Allocation is **held by the live resource** for its
  lifetime and released inside `close()`.
- `ResourceCapacity[N].reserve(amount)` — see Capacity below.

### Capacity (numeric budgets)

For RAM, CPU shares, GPU memory, etc., declare a `capacity` and reserve a
fraction of it per resource:

```scala
val ram = capacity("ram", 4096.0)  // 4 GB total, default

val server: ResourceRef[Server] =
  liveResource("server", ram.reserve(1024.0)) { (mb: Double) =>
    new Server(allocatedMb = mb)
  }
```

Capacities are **process-global** — multiple suites declaring `capacity("ram", N)`
share one budget. Override the total at runtime:

- `BOOKTEST_CAPACITY_RAM=8192` env var.
- `--capacity ram=8192` CLI flag.

The runner pre-validates that the **max concurrent demand** (sum across
reachable live resources) doesn't exceed the capacity total — if it would,
the run fails fast with the offending reservations listed.

### Failure handling

- **build throws** → consumer fails clearly with the cause; pool/capacity
  allocations released; sibling tests still run.
- **close throws** → swallowed; allocations released anyway.
- **reset throws** → instance invalidated; next consumer triggers a fresh
  build.
- **consumer fails on `withReset`** → instance always invalidated (state is
  unknown).
- **consumer fails on `SharedReadOnly`** → instance kept by default. Pass
  `--invalidate-live-on-fail` to force close + rebuild after any failure.

### Verbose output

`-v` prints lifecycle events and an end-of-run summary:

```
[build  myTests/server] 110 ms
clientGet ok 142 ms
clientLength ok 1 ms
clientUpper ok 1 ms
[close  myTests/server] 101 ms

# live resources:
  myTests/server: builds=1 closes=1 resets=0 alive=140 ms (build 110 ms, close 101 ms)
```

See `.ai/plan/live-resources.md` and `.ai/plan/live-resources-example.md`
for the full design and a worked example.

## Test-Driven Development

Booktest is itself a test framework, so framework changes are best driven by
writing booktest snapshot tests first. The workflow:

1. **Write the test you wish you could write.** Add an example to
   `src/test/scala/booktest/examples/` that uses the desired API. For
   framework-internal behavior (cache shape, scheduling, error paths), add a
   focused test under `src/test/scala/booktest/test/`.
2. **Run it and watch it fail.** A new test fails because either:
   - Compilation fails (the API doesn't exist yet) — implement the minimum
     surface to compile.
   - The snapshot is missing — run with `-v` to see the actual output.
   - The snapshot exists and shows the wrong content (DIFF) — read the
     diff, decide whether to fix the code or accept the new snapshot.
3. **Implement the smallest change** that makes the test pass.
4. **Re-run.** Use `-v` to see the full output during development. When
   the diff looks right, accept the snapshot — pick the flag that matches
   intent:
   - `-i` (interactive): step through diffs, accept/reject each.
   - `-a` (auto-accept DIFFs but not FAILs): bulk-accept after a careful
     read.
   - `-s` (update all snapshots): only when you've already reviewed the
     content; commits whatever the test produces.
5. **Add edge cases** as additional tests, repeat. Each new behavior gets its
   own test file or its own test method, not a branch in an existing test.
6. **Commit the test and snapshot together.** The `.md` snapshot is the
   spec; reviewers look at it in the PR diff.

Useful loops while iterating:

```bash
# Fast inner loop on one test
sbt "Test/runMain booktest.BooktestMain -v -t myNewTest booktest.examples.MyTests"

# Accept a snapshot interactively after inspecting the diff
sbt "Test/runMain booktest.BooktestMain -i booktest.examples.MyTests"

# Re-show last run's diffs without re-executing tests (cheap)
sbt "Test/runMain booktest.BooktestMain -w booktest.examples.MyTests"
```

Conventions:

- **Tests describe behavior, not implementation.** Snapshot the externally
  visible output, not internal counters.
- **One concept per test.** Multi-step scenarios are fine, but each test
  should answer one question.
- **No assertions hidden in helpers.** Use `t.tln(...)` directly so the
  snapshot tells the whole story.
- **Don't edit snapshots by hand.** Re-run with `-i` or `-a`. A snapshot
  that diverged from what the code produces is a lie.
- **For dependency / cache / scheduler work**, prefer tests where the
  observable trace (setup count, execution order, cached value) is part of
  the snapshot. That's how we catch regressions in the runner itself.

## Documentation Layout

For coding agents and contributors choosing where to look:

- **`README.md`** — overview, install, quick start, examples for the most-used features.
- **`USAGE.md`** — long-form reference: every public method, CLI flag, env var, with examples.
- **`llms.txt`** — same surface as USAGE.md but compressed for AI skimming. Keep in sync when API changes.
- **`CLAUDE.md`** (this file) — codebase architecture, TDD workflow, Live Resources section. For people *working on* booktest.
- **`CHANGELOG.md`** — release history.
- **`.ai/plan/`** — design documents (current: live-resources).
- **`booktest.ini`** — project test config (root, default group, exclude patterns).

When changing public API: update `USAGE.md`, `llms.txt`, `README.md` (if it's a top-billed feature), and `CHANGELOG.md` in the same PR.

## Related Projects

- [booktest (Python)](https://github.com/lumoa-oss/booktest) - The original Python implementation. Our API and snapshot format follow it closely; see `CHANGELOG.md` for any divergences.