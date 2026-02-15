# Booktest-Scala 0.2.1 Updates

**Date:** 2026-02-08
**Version:** 0.2.1 (locally published)

## Summary

Major updates to booktest-scala addressing the needs identified in the aito-core migration plan. The library now provides comprehensive support for snapshot testing with caching, parallel execution, async patterns, and review workflows.

## Dependency

```scala
// build.sbt
libraryDependencies += "io.github.arauhala" %% "booktest-scala" % "0.2.1"

// For local development (already published to ~/.ivy2/local)
resolvers += Resolver.mavenLocal
```

Cross-compiled for: Scala 2.12.18, 2.13.12, 3.3.1

## New Features

### 1. Computation Snapshotting with Cache Invalidation

Cache expensive computations with automatic invalidation when arguments change:

```scala
def testModelEvaluation(t: TestCaseRun): Unit = {
  // Cached on disk, invalidated when args change
  val model = t.snapshot("loadModel", modelPath, modelConfig) {
    loadExpensiveModel(modelPath, modelConfig)
  }

  t.tln(s"Accuracy: ${model.evaluate(testData)}")
}
```

- Results stored in `.cache/<testName>/<snapshotName>.snapshot`
- SHA-256 hash of arguments stored in `.hash` file
- Automatic recomputation when arguments change
- `-S` flag forces recomputation of all snapshots
- `-s` flag auto-accepts changes

### 2. Table Output Methods

```scala
// With headers and rows
t.ttable(Seq("Name", "Score", "Grade"), Seq(
  Seq("Alice", 95, "A"),
  Seq("Bob", 87, "B")
))

// With Map
t.ttable(Map("precision" -> 0.95, "recall" -> 0.92))

// With case classes
case class Result(name: String, score: Double)
t.ttable(Seq(Result("test1", 0.95), Result("test2", 0.88)))

// Info versions (not in snapshot)
t.itable(headers, rows)
```

### 3. Parallel Execution with Resource Management

```scala
# Run with 4 threads (like make -j4)
booktest -j4 booktest.examples.ExampleTests
```

Resource management for parallel tests:

```scala
def testWithPort(t: TestCaseRun): Unit = {
  // Acquire port from pool (thread-safe)
  val port = t.resources.ports.acquire()
  try {
    startServer(port)
    // ... test ...
  } finally {
    t.resources.ports.release(port)
  }
}
```

### 4. ExecutionContext Support for Async Patterns

```scala
def testAsync(t: TestCaseRun): Unit = {
  // Use test's ExecutionContext
  implicit val ec = t.ec

  val result = t.await {
    for {
      data <- fetchDataAsync()
      processed <- processAsync(data)
    } yield processed
  }

  t.tln(s"Result: $result")
}

// Or use async helper
def testAsyncHelper(t: TestCaseRun): Unit = {
  val result = t.async { implicit ec =>
    Future(computeExpensive())
  }
  t.tln(s"Result: $result")
}
```

### 5. Review Workflow

```scala
def testWithReview(t: TestCaseRun): Unit = {
  val output = generateOutput()

  t.startReview()
  t.reviewln("Output format valid", validateFormat(output))
  t.reviewln("Quality score", s"${qualityScore}/100", Some(qualityScore > 80))
  t.reviewln("Human review needed", output)  // None = needs review
  t.endReview()
}
```

### 6. Test Groups Configuration

Create `booktest.conf` in project root:

```ini
default = booktest.tests.CoreTests
unit = booktest.tests.UnitTests, booktest.tests.IntegrationTests
perf = booktest.tests.PerformanceTests
```

Run with:
```bash
booktest              # Runs 'default' group
booktest unit         # Runs 'unit' group
booktest perf         # Runs 'perf' group
```

### 7. Direction Constraints and Percentage Tolerance

Direction constraints for metrics that should only improve over time:

```scala
// Accuracy should not decrease (direction="min" means value must not go below baseline)
t.tmetric("accuracy", 0.95, tolerance = 0.02, direction = Some("min"))

// Latency should not increase (direction="max" means value must not go above baseline)
t.tmetric("latency_ms", 45.0, tolerance = 5.0, direction = Some("max"))

// Percentage-based tolerance (±5%)
t.tmetricPct("throughput", 1250.0, tolerancePct = 5.0)

// Combined direction with percentage tolerance
t.tmetricPct("p99_latency", 120.0, tolerancePct = 10.0, direction = Some("max"))
```

If a direction constraint is violated, the test fails with a regression message.

### 8. Image Output

Include images in test output for data science workflows:

```scala
def testVisualization(t: TestCaseRun): Unit = {
  // Generate image
  val image = createChart(data)
  val imageFile = t.file("chart.png")
  ImageIO.write(image, "png", imageFile)

  // Include in snapshot (file is copied with content hash for stability)
  t.timage(imageFile, "Performance chart")

  // Info-only image (not in snapshot comparison)
  t.iimage(imageFile, "Debug visualization")

  // Rename file to content hash for determinism
  val hashedName = t.renameFileToHash("output.png")
}
```

### 9. Setup/Teardown Lifecycle Hooks

```scala
class MyTest extends TestSuite {
  override def beforeAll(): Unit = {
    // Called once before all tests in suite
    initializeSharedResources()
  }

  override def afterAll(): Unit = {
    // Called once after all tests in suite
    cleanupSharedResources()
  }

  override def setup(t: TestCaseRun): Unit = {
    // Called before each test
    t.iln("Setting up test...")
  }

  override def teardown(t: TestCaseRun): Unit = {
    // Called after each test (even on failure)
    t.iln("Tearing down...")
  }

  def testSomething(t: TestCaseRun): Unit = {
    // Test code here
  }
}
```

### 10. Test Markers/Tags

Mark tests for filtering and categorization:

```scala
class MyTest extends TestSuite {
  // Apply markers to tests
  mark("slowTest", TestMarkers.Slow, TestMarkers.Integration)
  mark("gpuTest", TestMarkers.GPU)
  mark("fastTest", TestMarkers.Fast, TestMarkers.Unit)

  // Check markers programmatically
  def testMarkerExample(t: TestCaseRun): Unit = {
    t.tln(s"Is slow: ${hasMarker("slowTest", TestMarkers.Slow)}")
    t.tln(s"Markers: ${getMarkers("slowTest").mkString(", ")}")
  }
}
```

Built-in markers: `Fast`, `Slow`, `Unit`, `Integration`, `GPU`, `Network`, `Flaky`

### 11. Python-Style Reporting

Diffs are now shown at the end by default (like Python booktest):

```
# test results:

  CoreTests/test1..ok 5 ms
  CoreTests/test2..ok 3 ms
  CoreTests/test3..DIFF 4 ms

============================================================
# 1 test(s) with differences:
============================================================

## CoreTests/test3
----------------------------------------
- expected line
+ actual line

============================================================
```

Use `--inline` for old behavior (diffs shown immediately).

## CLI Reference

```
Options:
  -v, --verbose       Verbose output
  -i, --interactive   Interactive mode (accept/reject changes)
  -t, --test-filter   Filter tests by name pattern
  -jN, -j N           Run tests in parallel using N threads (e.g., -j4)
  -s, --update        Auto-accept snapshot changes
  -S, --recapture     Force regenerate all snapshots
  --inline            Show diffs inline (default: show at end)
  -l, --list          List test classes
  -L, --logs          Show logs
  -w, --review        Review previous test results
```

## Full TestCaseRun API

### Output Methods
| Method | Description |
|--------|-------------|
| `t`, `tln` | Test output (checked in snapshot) |
| `i`, `iln` | Info output (not checked) |
| `h1`, `h2`, `h3` | Markdown headings |
| `key(label, value)` | Labeled key-value output |
| `tmetric(label, value, tolerance, direction)` | Metric with tolerance and direction |
| `tmetricPct(label, value, tolerancePct, direction)` | Metric with percentage tolerance |
| `tLongLn`, `tDoubleLn` | Numeric with tolerance |
| `ttable`, `itable` | Table output |
| `timage`, `iimage` | Image output |
| `assertln(condition)` | Assert with output |

### Assertions
| Method | Description |
|--------|-------------|
| `assertTrue(cond, msg)` | Assert condition is true |
| `assertFalse(cond, msg)` | Assert condition is false |
| `assertEquals(a, b, msg)` | Assert values are equal |
| `assertNotNull(v, msg)` | Assert value is not null |
| `assertThrows[E] { }` | Assert exception is thrown |
| `fail(message)` | Explicit test failure |

### Test Control
| Method | Description |
|--------|-------------|
| `file(name)` | Get file in test output dir |
| `testDir` | Get test output directory |
| `renameFileToHash(name)` | Rename file to content hash |

### Snapshotting
| Method | Description |
|--------|-------------|
| `snapshot(name, args*) { }` | Cached computation |
| `hasSnapshot(name, args*)` | Check if cache valid |
| `invalidateSnapshot(name)` | Clear specific cache |
| `peekDouble`, `peekLong` | Read from previous snapshot |

### Timing
| Method | Description |
|--------|-------------|
| `iMsLn { }` | Time block, output as info |
| `ms { }` | Time block, return (ms, result) |
| `iUsPerOpLn`, `tUsPerOpLn` | Microseconds per operation |

### Async
| Method | Description |
|--------|-------------|
| `await(future, timeout)` | Wait for Future |
| `async { ec => future }` | Run with ExecutionContext |
| `ec` | Test's ExecutionContext |

### Review
| Method | Description |
|--------|-------------|
| `startReview()` | Begin review section |
| `reviewln(label, value, passed?)` | Add review item |
| `endReview()` | End review, return success |

## Migration Notes for aito-core

1. **DContext replacement**: Use `booktest.conf` for test organization instead of `ImmutableDContext`

2. **executionContextHolder pattern**: Use `t.ec` or `t.async { }` instead

3. **testDir() equivalent**: `t.testDir` returns `java.io.File`, `t.testOutDir` returns `os.Path`

4. **Tolerance metrics**: Use `t.tDoubleLn(value, unit, max, tolerance)` or `t.tmetric()`

5. **Expensive computations**: Use `t.snapshot("name", args) { }` instead of manual caching

## Known Limitations

- JSON serialization for complex objects in `snapshot()` falls back to `toString`
- Parallel execution respects dependencies but may have slight output ordering variations
- Review workflow is basic - full AI-assisted review planned for future

## SBT Plugin

An SBT plugin is also available for native integration:

```scala
// project/plugins.sbt
addSbtPlugin("io.github.arauhala" % "sbt-booktest" % "0.2.1")
```

### Plugin Commands

```bash
sbt booktest              # Run default test group
sbt booktest unit         # Run 'unit' group
sbt booktestOnly com.example.MyTest  # Run specific class
sbt booktestUpdate        # Run with -s (auto-accept changes)
sbt booktestRecapture     # Run with -S (force regenerate)
sbt booktestReview        # Review previous results
sbt booktestList          # List available tests
sbt ~booktest             # Watch mode (SBT built-in)
```

### Plugin Settings

```scala
// build.sbt
booktestVersion := "0.2.1"              // Library version
booktestDefaultGroup := "default"        // Default group to run
booktestParallel := 4                    // Parallel threads
booktestOutputDir := file("books")       // Output directory
booktestSnapshotDir := file("books")     // Snapshot directory
```

## Next Steps

- Consider bumping to 0.3.0 for Maven Central release with these features
- Add more comprehensive serialization support if needed
- Implement full AI-assisted review integration
