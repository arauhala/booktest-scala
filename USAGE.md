# Booktest-Scala Usage Guide

This guide is for using booktest-scala as a testing framework in your Scala project.

## Installation

Add to your `build.sbt`:

```scala
libraryDependencies += "io.github.arauhala" %% "booktest-scala" % "0.4.0" % Test
```

Cross-compiled for Scala 2.12, 2.13, and 3.3.

## Writing Tests

### Basic Test

Create a test class extending `TestSuite`:

```scala
package myproject

import booktest.*

class MyTests extends TestSuite {
  def testExample(t: TestCaseRun): Unit = {
    t.h1("Example Test")
    t.tln("This line is checked against the snapshot")
    t.iln("This info line is NOT checked (useful for debugging)")
  }
}
```

### Test Output Methods

| Method | Description | Checked in Snapshot |
|--------|-------------|---------------------|
| `t.h1(text)` | Level 1 header | Yes |
| `t.h2(text)` | Level 2 header | Yes |
| `t.h3(text)` | Level 3 header | Yes |
| `t.tln(text)` | Test line with newline | Yes |
| `t.t(text)` | Test text (no newline) | Yes |
| `t.iln(text)` | Info line with newline | No |
| `t.i(text)` | Info text (no newline) | No |
| `t.key(key, value)` | Labeled key-value pair | Yes |
| `t.assertln(condition)` | Assert and output OK/FAILED | Yes |
| `t.assertln(label, cond)` | Assert with label | Yes |
| `t.fail(msg)` | Mark test as failed (no exception) | N/A |
| `t.f(text)` | Fail content — always marks test failed | Yes |
| `t.fln(text)` | Fail content with newline | Yes |
| `t.diff()` | Mark current line as a diff | N/A |
| `t.file(name)` | Get File in test assets dir | N/A |

Note: `t.tln` and `t.iln` can be called without parentheses to output an empty line.

### Metrics with Tolerance

For data science workflows where numeric values vary slightly between runs:

```scala
class MetricsTests extends TestSuite {
  def testModelMetrics(t: TestCaseRun): Unit = {
    t.h1("Model Evaluation")

    // Absolute tolerance - value kept stable if within tolerance
    t.tmetric("accuracy", 0.923, tolerance = 0.01)
    t.tmetric("f1_score", 0.891, tolerance = 0.02)

    // Percentage-based tolerance
    t.tmetricPct("auc_roc", 0.945, tolerancePct = 2.0)

    // Direction constraints - prevent regressions
    t.tmetric("accuracy", 0.923, tolerance = 0.01, direction = Some("min"))   // must not decrease
    t.tmetric("error_rate", 0.077, tolerance = 0.01, direction = Some("max")) // must not increase

    // Info-only metrics (not snapshot-checked)
    t.imetric("training_time_s", 142.5)

    // Key-value output
    t.key("model", "random_forest")
    t.key("features", "128")

    // Numeric values with units
    t.tLongLn(50000L, "items")
    t.tDoubleLn(3.14, "seconds", max = 5.0)
  }
}
```

### Tables

```scala
class TableTests extends TestSuite {
  def testTables(t: TestCaseRun): Unit = {
    // Table from headers and rows
    t.ttable(Seq("Model", "Accuracy", "F1"), Seq(
      Seq("RF", "0.92", "0.89"),
      Seq("XGB", "0.94", "0.91")
    ))

    // Table from Map (info-only)
    t.itable(Map("learning_rate" -> "0.01", "epochs" -> "100"))

    // Table from case classes
    case class Result(model: String, accuracy: Double)
    t.ttable(Seq(Result("RF", 0.92), Result("XGB", 0.94)))
  }
}
```

### Images

```scala
class ImageTests extends TestSuite {
  def testPlot(t: TestCaseRun): Unit = {
    // Create an image file in the test directory
    val plotFile = t.file("confusion_matrix.png")
    generatePlot(plotFile)

    // Include in tested snapshot output
    t.timage(plotFile, "Confusion Matrix")

    // Include as info-only (not snapshot-checked)
    t.iimage(plotFile, "Debug visualization")

    // Rename file to content hash for deterministic references
    t.renameFileToHash("confusion_matrix.png")
  }
}
```

### Snapshot Caching

Cache expensive computations that don't need to re-run every time:

```scala
class ExpensiveTests extends TestSuite {
  def testModelTraining(t: TestCaseRun): Unit = {
    // Only runs computation on first execution or when -S flag is used
    val model = t.snapshot("trained_model") {
      trainExpensiveModel()
    }

    // Cache with arguments - changing args invalidates the cache
    val predictions = t.snapshot("predictions", "test_dataset_v2") {
      model.predict(loadTestData())
    }

    t.tmetric("accuracy", evaluate(predictions), tolerance = 0.01)
  }
}
```

Use `-S` (recapture) to force recomputation of all cached snapshots.

### Temporary Directories

Files that persist between dependent tests but are cleared on re-run:

```scala
class DataPipelineTests extends TestSuite {
  val prepare = test("prepare") { (t: TestCaseRun) =>
    val dataDir = t.tmpDir("data")
    val outputFile = t.tmpFile("data/processed.csv")
    // Write data to outputFile...
    t.tln(s"Wrote data to ${outputFile.getName}")
    outputFile.getAbsolutePath
  }

  test("analyze", prepare) { (t: TestCaseRun, dataPath: String) =>
    // Access files created by the dependent test
    t.tln(s"Reading from: $dataPath")
  }
}
```

### Async Support

```scala
import scala.concurrent.Future

class AsyncTests extends TestSuite {
  def testFutures(t: TestCaseRun): Unit = {
    // Await a future
    val result = t.await(Future {
      expensiveComputation()
    }(t.ec))

    // Run an async block with implicit ExecutionContext
    val data = t.async { implicit ec =>
      val f1 = Future(fetchData("source1"))
      val f2 = Future(fetchData("source2"))
      t.await(Future.sequence(Seq(f1, f2)))
    }

    t.tln(s"Got ${data.size} results")
  }
}
```

### Performance Testing

```scala
class PerfTests extends TestSuite {
  def testPerformance(t: TestCaseRun): Unit = {
    t.h1("Performance Benchmarks")

    // Measure raw time
    val (ms, result) = t.ms {
      heavyComputation()
    }
    t.tln(s"Completed in ${ms}ms")

    // Execute block and print elapsed time as info
    t.iMsLn("Database query") {
      db.query("SELECT * FROM users")
    }

    // Measure us/operation
    t.iUsPerOpLn(10000, "List append") {
      List(1, 2, 3) :+ 4
    }
    t.tUsPerOpLn(10000, "Vector append") {
      Vector(1, 2, 3) :+ 4
    }
  }
}
```

### Tests with Dependencies

Use the method reference API for type-safe dependencies:

```scala
class DataTests extends TestSuite {
  // First test returns data that gets cached
  val setup = test("setup") { (t: TestCaseRun) =>
    t.h1("Setup")
    val data = loadTestData()
    t.tln(s"Loaded ${data.size} records")
    data  // Return value is cached
  }

  // Dependent test receives the cached data
  test("process", setup) { (t: TestCaseRun, data: List[Record]) =>
    t.h1("Process")
    val result = process(data)
    t.tln(s"Processed: $result")
  }
}
```

### Setup and Teardown

```scala
class DatabaseTests extends TestSuite {
  private var db: Database = _

  // Prevent parallel execution when sharing mutable state
  override protected def resourceLocks: List[String] = List("database")

  override def beforeAll(): Unit = { db = Database.connect() }
  override def afterAll(): Unit = { db.close() }
  override def setup(t: TestCaseRun): Unit = { db.beginTransaction() }
  override def teardown(t: TestCaseRun): Unit = { db.rollback() }

  def testQuery(t: TestCaseRun): Unit = {
    val rows = db.query("SELECT count(*) FROM users")
    t.tln(s"Users: $rows")
  }
}
```

### Test Markers

Tag tests for selective execution:

```scala
class MixedTests extends TestSuite {
  mark("trainModel", TestMarkers.Slow, TestMarkers.GPU)
  mark("unitCheck", TestMarkers.Fast, TestMarkers.Unit)

  def testTrainModel(t: TestCaseRun): Unit = { /* ... */ }
  def testUnitCheck(t: TestCaseRun): Unit = { /* ... */ }
}
```

Predefined markers: `Slow`, `Fast`, `Integration`, `Unit`, `GPU`, `Network`, `Flaky`.

### Test Discovery

Test methods are discovered by reflection: any method starting with `test` whose first
parameter is `TestCaseRun`. Methods with additional parameters are only discovered if
they have a `@DependsOn` annotation — this prevents helper methods like
`testAllColumns(t: TestCaseRun, columns: List[String])` from being picked up as tests.

### HTTP Snapshotting

Capture HTTP responses for reproducible tests:

```scala
class ApiTests extends TestSuite {
  def testApi(t: TestCaseRun): Unit = {
    val client = HttpSnapshotting.createClient(t)
    val response = client.get("https://api.example.com/data")
    t.tln(s"Status: ${response.code}")
    t.tln(s"Body: ${response.body}")
  }
}
```

### Environment Variable Snapshotting

Mock environment variables in tests:

```scala
class ConfigTests extends TestSuite {
  def testConfig(t: TestCaseRun): Unit = {
    EnvSnapshotting.withEnv(Map("API_KEY" -> "test_key"), t) {
      val config = loadConfig()
      t.tln(s"API Key: ${config.apiKey}")
    }
  }
}
```

### Function Snapshotting

Cache expensive function calls:

```scala
class ExpensiveTests extends TestSuite {
  def testExpensive(t: TestCaseRun): Unit = {
    val cached = FunctionSnapshotting.cached[String, Result]("fetchData", t) { url =>
      // Only runs on first test execution, cached thereafter
      fetchFromNetwork(url)
    }
    val result = cached("https://example.com/data")
    t.tln(s"Result: $result")
  }
}
```

### Review Workflow (AI-Assisted Evaluation)

A structured way to collect items that need human (or AI) judgment, batch
them at the end of a test, and decide pass/fail. Mirrors Python booktest's
`start_review()` / `reviewln()` / `end_review()` flow.

```scala
class ModelReviewTests extends TestSuite {
  def testGptOutput(t: TestCaseRun): Unit = {
    val response = generateResponse("What is the capital of France?")

    t.h1("GPT Response")
    t.iln(response)

    t.startReview()
    // (label, value, passed)
    //   passed = Some(true)  -> [OK]
    //   passed = Some(false) -> [FAIL] (and instance is invalidated for retests)
    //   passed = None        -> [REVIEW] (needs human/AI decision)
    t.reviewln("Response is accurate", "Yes", Some(true))
    t.reviewln("Response is concise", s"${response.length} chars", None)
    // shorthand: condition -> [OK]/[FAIL]
    t.reviewln("contains 'Paris'", response.contains("Paris"))

    val allPassed = t.endReview()
    if (!allPassed) t.iln(s"${t.getReviewItems.size} item(s) need attention")
  }
}
```

`startReview()` writes a `## Review Section` header. Each `reviewln`
writes `[OK]`, `[FAIL]`, or `[REVIEW]` followed by the label and value.
`endReview()` returns true when nothing needs review.

### Snapshot Navigation (Advanced)

Booktest's snapshot reader maintains a CURSOR over the expected output.
By default each `t.tln` advances it linearly. For tests where order isn't
fixed (e.g., dictionary iteration), seek the cursor before writing:

```scala
def testNonLinearOutput(t: TestCaseRun): Unit = {
  // Headers automatically seek to a matching line in the snapshot.
  // Use anchor/seek explicitly when content arrives in non-fixed order.

  t.anchor("Total: ")           // seek to a line starting with "Total: ", then write the prefix
  t.tln(s"$total")              // continues writing on that anchored line

  t.anchorln("# Summary")       // seek to exact line, write it as tested

  // Manual cursor control:
  t.seekLine("Section A")       // exact match
  t.seekPrefix("Item ")         // line-prefix match
  t.seek(_.contains("FOOBAR"))  // arbitrary predicate
  t.jump(42)                    // absolute line number
}
```

For metric-style tolerance against the previous snapshot value, peek the
next expected token without consuming it:

```scala
def testTolerantOutput(t: TestCaseRun): Unit = {
  t.t("score: ")
  val previous = t.peekDouble.getOrElse(0.0)  // previous value from snapshot
  val current  = computeScore()
  // emit previous if within 5%, else current — keeps snapshot stable
  val toEmit = if (math.abs(current - previous) / previous < 0.05) previous else current
  t.tln(toEmit.toString)
}

// Peek API:
//   t.peekDouble: Option[Double]
//   t.peekLong:   Option[Long]
//   t.peekToken:  Option[String]
//   t.skipToken(): Unit
```

`tmetric`, `tLongLn`, `tDoubleLn` use this internally — most tests don't
need to touch peek directly.

### Live Resources

Share an expensive `AutoCloseable` (database, HTTP server, process) across
many test consumers. The runner builds it on first use and closes it
when the last consumer finishes. Pool/capacity allocations are held for
the resource's lifetime.

```scala
class StringServer(port: Int, payload: String) extends AutoCloseable {
  // ... starts/stops an HttpServer on `port` ...
}

class ServerTests extends TestSuite {
  // Plain test producing serializable state — same as today.
  val state: TestRef[String] = test("createState") { (t: TestCaseRun) =>
    t.tln("State: hello world"); "hello world"
  }

  // Live resource: depends on `state` (a TestRef) and a port from the
  // existing PortPool. Built once on first consumer; closed when the
  // last consumer finishes; port returned to the pool inside close().
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

#### Sharing modes

| Declaration | When to use |
|---|---|
| `liveResource(name, deps...) { build }` | Default. One instance, many concurrent readers. Tests promise not to mutate observable state. |
| `liveResourceWithReset(name, deps...) { build } { reset }` | Stateful resource. Runner serializes consumer access (per-instance lock) and calls `reset(handle)` between consumers. Build/close still happen exactly once. |
| `exclusiveResource(name, deps...) { build }` | Each consumer gets its own fresh instance. Equivalent to today's per-test setup/teardown. |

#### Dependency types

Mix freely in the dep list:

- `TestRef[T]` — a prior test's return value (loaded from `.bin`, or
  auto-run if missing).
- `ResourceRef[T]` — another live resource. Refcount-shared; the runner
  resolves transitively.
- `ResourcePool[T]` — the existing pool API (e.g. `resources.ports`).
  The acquired value is **held by the live resource for its lifetime**
  and returned to the pool inside `close()`.
- `ResourceCapacity[N].reserve(amount)` — see Capacity below.

#### Capacity (numeric budgets)

For RAM, CPU shares, GPU memory, etc., declare a `capacity` and reserve
fractions per resource:

```scala
val ram = capacity("ram", 4096.0)  // 4 GB total

val server: ResourceRef[Server] =
  liveResource("server", ram.reserve(1024.0)) { (mb: Double) =>
    new Server(allocatedMb = mb)
  }
```

Capacities are **process-global** — multiple suites declaring `capacity("ram", N)`
share one budget. Override the total at runtime:

- `BOOKTEST_CAPACITY_RAM=8192` env var.
- `--capacity ram=8192` CLI flag.

The runner pre-validates that no single resource exceeds its capacity
total (which would deadlock on acquire). Cross-resource sums are not
validated statically — the runtime acquire path blocks safely if real
demand exceeds capacity.

#### Failure handling

| Event | Action |
|---|---|
| `build` throws | Consumer FAILs with the cause; partial pool/capacity allocations released; sibling tests still run. |
| `close` throws | Swallowed; allocations released anyway. |
| `reset` throws | Instance invalidated; next consumer triggers a fresh build. |
| Consumer fails on `withReset` | Instance always invalidated (state is unknown). |
| Consumer fails on `SharedReadOnly` | Instance kept by default. Pass `--invalidate-live-on-fail` to force close + rebuild. |

#### Verbose output

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

## Configuration (`booktest.ini`)

Create a `booktest.ini` in your project root:

```ini
# Package prefix stripped from test paths
root = myproject

# Default test group when no arguments given
default = all

# Named groups
all = unit, integration
unit = tests.unit
integration = tests.integration

# Exclude patterns (comma-separated)
exclude = SlowTest, FlakyTest
```

## Running Tests

### With the `do` script

```bash
./do test                        # Run default group from booktest.ini
./do test examples               # Run named group
./do test ExampleTests           # Run specific suite
```

### With SBT directly

```bash
# Run all tests in a suite
sbt "Test/runMain booktest.BooktestMain -v myproject.MyTests"

# Parallel execution with 4 threads
sbt "Test/runMain booktest.BooktestMain -p4 myproject.MyTests"

# Continue mode - only re-run failures
sbt "Test/runMain booktest.BooktestMain -c myproject.MyTests"

# Filter by name pattern
sbt "Test/runMain booktest.BooktestMain -v -t model myproject.MyTests"

# Interactive mode (accept/reject snapshot changes)
sbt "Test/runMain booktest.BooktestMain -i myproject.MyTests"

# List tests (tree view)
sbt "Test/runMain booktest.BooktestMain -l --tree myproject.MyTests"

# Show test logs
sbt "Test/runMain booktest.BooktestMain -L myproject.MyTests"

# Review diffs without re-running
sbt "Test/runMain booktest.BooktestMain -w myproject.MyTests"

# Force regenerate all snapshots
sbt "Test/runMain booktest.BooktestMain -S myproject.MyTests"

# Auto-accept all snapshot changes
sbt "Test/runMain booktest.BooktestMain -s myproject.MyTests"

# List orphan snapshot files
sbt "Test/runMain booktest.BooktestMain --garbage"

# Clean orphan files and tmp directories
sbt "Test/runMain booktest.BooktestMain --clean"
```

### CLI Options

| Option | Description |
|--------|-------------|
| `-v, --verbose` | Verbose output |
| `-i, --interactive` | Interactive mode for snapshot updates |
| `-l, --list` | List test cases |
| `-L, --logs` | Show test logs |
| `-t, --test-filter PATTERN` | Filter tests by name pattern |
| `-w, --review` | Review mode (show diffs without running) |
| `-pN` | Parallel execution with N threads |
| `-c, --continue` | Skip tests that passed in previous run |
| `-S, --recapture` | Force regenerate all snapshots |
| `-s, --update` | Auto-accept all snapshot changes (DIFF and FAIL) |
| `-a, --accept` | Auto-accept DIFF tests only (not FAIL) |
| `--batch-review` | Sequential interactive review of all failures at end |
| `--tree` | Hierarchical tree display (with `-l`) |
| `--inline` | Show diffs inline during execution (default: at end) |
| `--diff-style STYLE` | `unified` (default) / `side-by-side` / `inline` / `minimal` |
| `--output-dir DIR` | Override output directory (default: `books`) |
| `--snapshot-dir DIR` | Override snapshot directory (default: `books`) |
| `--root PREFIX` | Override `root` from `booktest.ini` |
| `--garbage` | List orphan files in books/ |
| `--clean` | Remove orphan files and tmp directories |
| `--invalidate-live-on-fail` | Force-close shared live resources after a consumer failure (default: keep alive) |
| `--capacity NAME=VALUE` | Override a `capacity(NAME, _)` total at runtime |
| `--help` | Show help |

### Environment Variables

| Variable | Effect |
|---|---|
| `BOOKTEST_PORT_BASE` | Port pool starting port (default: 10000) |
| `BOOKTEST_PORT_MAX` | Port pool maximum port (default: 60000) |
| `BOOKTEST_CAPACITY_<NAME>` | Override `capacity(name, _)` total (uppercase suffix) |

## Snapshot Directory Structure

```
books/
├── .out/                        # Test execution output (gitignored)
│   └── SuiteName/
│       ├── testName/            # Tmp directory for test
│       ├── testName.bin         # Return value cache (for dependencies)
│       ├── testName.md          # Test output
│       ├── testName.log         # Captured stdout/stderr
│       └── testName.snapshots.json  # HTTP/function snapshots
├── SuiteName/                   # Committed snapshots
│   ├── testName.md              # Snapshot file
│   └── testName/                # Asset directory (images, etc.)
└── cases.ndjson                 # Test results for continue mode
```

## Workflow

1. **Write test** - Create test methods that output to `t.tln()`, `t.h1()`, etc.
2. **Run test** - First run creates snapshot in `books/SuiteName/testName.md`
3. **Review snapshot** - Check the generated markdown file
4. **Commit snapshot** - Add `books/` to git (except `.out/`)
5. **Subsequent runs** - Test output compared against saved snapshot
6. **Update snapshot** - Use `-i` for interactive, `-s` to auto-accept

## Git Configuration

Add to `.gitignore`:

```
books/.out/
```

Commit the `books/` directory with your snapshots - they serve as the expected test output.

## Links

- GitHub: https://github.com/arauhala/booktest-scala
- Original Python booktest: https://github.com/lumoa-oss/booktest