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

// String-based dependencies (legacy approach)
class DependencyTests extends TestSuite {
  def testCreateData(t: TestCaseRun): String = {
    t.tln("Creating data...")
    "some_data"
  }
  
  @dependsOn("testCreateData")
  def testUseData(t: TestCaseRun, cachedData: String): Unit = {
    t.tln(s"Using cached data: $cachedData")
  }
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

### Dual-Layer Caching System
- **Memory Cache**: Fast in-process storage for test execution
- **Filesystem Cache**: Persistent storage in `books/.cache/` directory
- **Cache Format**: Simple text format (e.g., "STRING:value", "INT:42")
- **Type Support**: String, Int, Double, Boolean, and general objects via toString

### Test Discovery and Execution
- **Reflection-based**: Discovers methods starting with "test" that take TestCaseRun as first parameter
- **Dependency Resolution**: Topological sort ensures correct execution order
- **Sequential Execution**: Single-threaded execution for reliability and simplicity
- **Error Handling**: Proper cleanup with detailed error reporting

### File Organization
```
books/
├── .cache/              # Cached test results (gitignored)
├── .out/               # Test execution logs (gitignored)
├── TestSuiteName/      # Final snapshots (committed to Git)
└── _snapshots/         # JSON snapshots for HTTP/Env/Function data
```

### Snapshot Comparison
- **Diff Generation**: Line-by-line comparison with colored output
- **Interactive Mode**: Accept/reject changes with y/n prompts
- **Review Mode**: Show diffs without re-running tests
- **Markdown Format**: Human-readable test output format

## Related Projects

- [booktest (Python)](https://github.com/lumoa-oss/booktest) - The original Python implementation