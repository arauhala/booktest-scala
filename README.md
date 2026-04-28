# Booktest Scala

A Scala port of the [booktest](https://github.com/lumoa-oss/booktest) framework - a review-driven testing tool designed for data science workflows.

## Overview

Booktest enables snapshot testing where tests write output to markdown files that are compared against saved snapshots. This approach is ideal for data science where results aren't strictly right/wrong but require expert review and comparison over time.

## Key Features

- **Snapshot Testing**: Test output compared against saved markdown files
- **Live Resources**: Share an expensive `AutoCloseable` (database, HTTP server, process) across many tests with `liveResource(...)`. Three sharing modes (default shared, `withReset`, `exclusive`) and refcount-based teardown.
- **Resource Capacity**: Numeric budgets (RAM, CPU shares) with `capacity("ram", 4096.0)` and per-resource `cap.reserve(amount)`.
- **Parallel Execution**: Run tests concurrently with `-pN`, with resource locks for shared state
- **Metrics with Tolerance**: Track numeric metrics with absolute/percentage tolerance and direction constraints
- **Type-Safe Dependencies**: Tests can depend on other tests with compile-time type checking
- **Snapshot Caching**: Cache expensive computations with automatic invalidation
- **Continue Mode**: Re-run only failed tests with `-c`
- **Images and Tables**: Rich output with embedded images and markdown tables
- **Async Support**: Built-in Future handling with `t.await()` and `t.async {}`
- **Setup/Teardown**: Per-test and per-suite lifecycle hooks
- **Test Markers**: Tag tests as Slow, Fast, GPU, etc. for selective execution
- **Configuration File**: `booktest.ini` for test groups, excludes, and project settings
- **Safe Test Discovery**: Only methods with `@DependsOn` are discovered when they have extra parameters — no more phantom tests from helper methods
- **Interactive Mode**: Accept/reject snapshot changes with colored diffs
- **Log Capture**: Stdout/stderr captured to `.log` files

## Quick Start

### Installation

Add to your `build.sbt`:

```scala
libraryDependencies += "io.github.arauhala" %% "booktest-scala" % "0.4.1" % Test
```

Cross-compiled for Scala 2.12, 2.13, and 3.3.

### Write a Test

```scala
import booktest.*

class MyTests extends TestSuite {
  def testExample(t: TestCaseRun): Unit = {
    t.h1("Example Test")
    t.tln("This line is checked against the snapshot")
    t.iln("This info line is NOT checked")
  }

  def testMetrics(t: TestCaseRun): Unit = {
    t.tmetric("accuracy", 0.923, tolerance = 0.01)
    t.tmetric("error_rate", 0.077, tolerance = 0.01, direction = Some("max"))
  }
}
```

### Run Tests

```bash
# Run with verbose output
sbt "Test/runMain booktest.BooktestMain -v myproject.MyTests"

# Run in parallel with 4 threads
sbt "Test/runMain booktest.BooktestMain -p4 myproject.MyTests"

# Continue mode - only re-run failures
sbt "Test/runMain booktest.BooktestMain -c myproject.MyTests"

# Interactive mode (accept/reject snapshot changes)
sbt "Test/runMain booktest.BooktestMain -i myproject.MyTests"
```

Or use the `do` script with `booktest.ini` configuration:

```bash
./do test                        # Run default group
./do test examples               # Run named group
```

## Test Examples

### Dependencies (Method Reference API)

```scala
class DataTests extends TestSuite {
  val data = test("createData") { (t: TestCaseRun) =>
    t.tln("Creating data...")
    "processed_data_123"
  }

  test("useData", data) { (t: TestCaseRun, cachedData: String) =>
    t.tln(s"Using cached data: $cachedData")
  }
}
```

### Metrics with Direction Constraints

```scala
class ModelTests extends TestSuite {
  def testEvaluate(t: TestCaseRun): Unit = {
    t.tmetric("accuracy", 0.923, tolerance = 0.01, direction = Some("min"))
    t.tmetricPct("auc_roc", 0.945, tolerancePct = 2.0)
    t.imetric("training_time_s", 142.5)
  }
}
```

### Setup/Teardown with Resource Locks

```scala
class SharedStateTests extends TestSuite {
  override protected def resourceLocks: List[String] = List("shared-db")

  override def setup(t: TestCaseRun): Unit = { initDb() }
  override def teardown(t: TestCaseRun): Unit = { cleanupDb() }

  def testFirst(t: TestCaseRun): Unit = { /* ... */ }
  def testSecond(t: TestCaseRun): Unit = { /* ... */ }
}
```

### Snapshot Caching

```scala
class ExpensiveTests extends TestSuite {
  def testModel(t: TestCaseRun): Unit = {
    val model = t.snapshot("trained_model") {
      trainExpensiveModel()  // Only runs once, cached thereafter
    }
    t.tmetric("accuracy", evaluate(model), tolerance = 0.01)
  }
}
```

### Live Resources

Share a stateful service across many test consumers — built once, closed
once. Pool/capacity allocations are held for the resource's lifetime.

```scala
class StringServer(port: Int, payload: String) extends AutoCloseable {
  // ... starts/stops an HttpServer on `port` ...
}

class ServerTests extends TestSuite {
  val state: TestRef[String] = test("createState") { (t: TestCaseRun) =>
    t.tln("State: hello world"); "hello world"
  }

  // Built once on first consumer; closed when the last consumer finishes.
  // The port is held until close() returns it to the pool.
  val server: ResourceRef[StringServer] =
    liveResource("server", state, resources.ports) {
      (s: String, port: Int) => new StringServer(port, s)
    }

  test("clientGet", server) { (t, http) =>
    t.tln(s"GET /echo -> ${http.get("/echo")}")
  }

  test("clientLength", server) { (t, http) =>
    t.tln(s"length: ${http.get("/echo").length}")
  }
}
```

Three sharing modes:

| Declaration | When to use |
|---|---|
| `liveResource(...)` | Default. Shared instance, multiple readers. |
| `liveResourceWithReset(...) { build } { reset }` | Stateful resource; runner serializes consumers and calls `reset` between. |
| `exclusiveResource(...)` | Each consumer gets its own instance. |

Numeric capacity budgets (RAM, CPU shares):

```scala
val ram = capacity("ram", 4096.0)  // 4 GB total

val server: ResourceRef[Server] =
  liveResource("server", ram.reserve(1024.0)) { (mb: Double) => ... }
```

Override capacity at runtime via `BOOKTEST_CAPACITY_<NAME>` or
`--capacity name=value`.

See the **[Live Resources section in CLAUDE.md](CLAUDE.md#live-resources)**
for the full reference, or `.ai/plan/live-resources*.md` for the
design and a worked example.

## CLI Options

| Option | Description |
|--------|-------------|
| `-v, --verbose` | Verbose output |
| `-pN` | Parallel execution with N threads |
| `-c, --continue` | Skip tests that passed in previous run |
| `-i, --interactive` | Interactive mode for snapshot updates |
| `-l, --list` | List test cases |
| `-L, --logs` | Show test logs |
| `-t PATTERN` | Filter tests by name pattern |
| `-w, --review` | Review mode (show diffs without running) |
| `-S, --recapture` | Force regenerate all snapshots |
| `-s, --update` | Auto-accept all snapshot changes (DIFF and FAIL) |
| `-a, --accept` | Auto-accept DIFF tests only (not FAIL) |
| `--batch-review` | Sequential interactive review of failures at end |
| `--tree` | Hierarchical tree display (with `-l`) |
| `--inline` | Show diffs inline during execution |
| `--diff-style STYLE` | `unified` / `side-by-side` / `inline` / `minimal` |
| `--output-dir DIR` / `--snapshot-dir DIR` | Override `books/` location |
| `--root PREFIX` | Override `root` from `booktest.ini` |
| `--garbage` | List orphan files in books/ |
| `--clean` | Remove orphan files and tmp directories |
| `--invalidate-live-on-fail` | Force-close shared live resources after a consumer fails |
| `--capacity NAME=VALUE` | Override a `capacity(NAME, _)` total at runtime |

### Environment variables

| Variable | Effect |
|---|---|
| `BOOKTEST_PORT_BASE` | Port pool starting port (default 10000) |
| `BOOKTEST_PORT_MAX` | Port pool maximum (default 60000) |
| `BOOKTEST_CAPACITY_<NAME>` | Override a `capacity(NAME, _)` total |

## Output Structure

```
books/
├── .out/                        # Test execution output (gitignored)
│   └── SuiteName/
│       ├── testName/            # Tmp directory for test
│       ├── testName.bin         # Return value cache
│       ├── testName.md          # Test output
│       ├── testName.log         # Captured stdout/stderr
│       └── testName.snapshots.json
├── SuiteName/                   # Committed snapshots
│   ├── testName.md
│   └── testName/                # Asset directory (images, etc.)
└── cases.ndjson                 # Test results for continue mode
```

## Documentation

- **[USAGE.md](USAGE.md)** — Detailed usage guide with all API methods and examples
- **[CHANGELOG.md](CHANGELOG.md)** — Release history and changes
- **[CLAUDE.md](CLAUDE.md)** — Development guidance and architecture details
- **[llms.txt](llms.txt)** — AI-friendly API summary (for coding agents)
- **`.ai/plan/`** — Design documents (live resources, etc.)

## Examples

The `src/test/scala/booktest/examples/` directory contains working examples:

- `ExampleTests.scala` - Basic snapshot testing
- `DependencyTests.scala` - Annotation-based dependencies
- `MethodRefTests.scala` - Method reference API (recommended)
- `MetricsTest.scala` - Metrics with tolerance and direction constraints
- `ImageTest.scala` - Image output in snapshots
- `InfoMethodsTest.scala` - Tables and structured output
- `SnapshotCacheTest.scala` - Caching expensive computations
- `AsyncTest.scala` - Async/Future support
- `TmpDirTest.scala` - Temporary file management
- `SetupTeardownTest.scala` - Lifecycle hooks
- `MarkersTest.scala` - Test tagging and filtering
- `DirectionConstraintsTest.scala` - Metric regression prevention
- `LiveHttpServerTests.scala` - Shared live HTTP server across consumers
- `ExclusiveResourceTests.scala` - Per-consumer live resource instances
- `ResetResourceTests.scala` - Shared resource with reset between consumers
- `CapacityResourceTests.scala` - Numeric capacity reservation
- `NestedResourceTests.scala` - Resources depending on resources

## Links

- GitHub: https://github.com/arauhala/booktest-scala
- Maven Central: [io.github.arauhala:booktest-scala](https://central.sonatype.com/artifact/io.github.arauhala/booktest-scala_3)
- Original Python booktest: https://github.com/lumoa-oss/booktest
