# Booktest Scala

A Scala port of the [booktest](https://github.com/lumoa-oss/booktest) framework - a review-driven testing tool designed for data science workflows.

## Overview

Booktest enables snapshot testing where tests write output to markdown files that are compared against saved snapshots. This approach is ideal for data science where results aren't strictly right/wrong but require expert review and comparison over time.

## Key Features

- **Snapshot Testing**: Test output compared against saved markdown files
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
- **Interactive Mode**: Accept/reject snapshot changes with colored diffs
- **Log Capture**: Stdout/stderr captured to `.log` files

## Quick Start

### Installation

Add to your `build.sbt`:

```scala
libraryDependencies += "io.github.arauhala" %% "booktest-scala" % "0.3.0" % Test
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
| `-s, --update` | Auto-accept all snapshot changes |
| `--tree` | Hierarchical tree display (with `-l`) |
| `--inline` | Show diffs inline during execution |
| `--garbage` | List orphan files in books/ |
| `--clean` | Remove orphan files and tmp directories |

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

- **[USAGE.md](USAGE.md)** - Detailed usage guide with all API methods and examples
- **[CHANGELOG.md](CHANGELOG.md)** - Release history and changes
- **[CLAUDE.md](CLAUDE.md)** - Development guidance and architecture details

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

## Links

- GitHub: https://github.com/arauhala/booktest-scala
- Maven Central: [io.github.arauhala:booktest-scala](https://central.sonatype.com/artifact/io.github.arauhala/booktest-scala_3)
- Original Python booktest: https://github.com/lumoa-oss/booktest
