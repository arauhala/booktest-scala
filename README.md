# Booktest Scala

A Scala port of the [booktest](https://github.com/lumoa-oss/booktest) framework - a review-driven testing tool designed for data science workflows.

## Overview

Booktest enables snapshot testing where tests write output to markdown files that are compared against saved snapshots. This approach is ideal for data science where results aren't strictly right/wrong but require expert review and comparison over time.

## Key Features

- **Snapshot Testing**: Test output compared against saved markdown files
- **Type-Safe Dependencies**: Tests can depend on other tests with compile-time type checking
- **Method Reference API**: Direct test references instead of string-based dependencies
- **Dual-Layer Caching**: Results cached in both memory and filesystem
- **Test Selection**: Filter tests by name patterns
- **Interactive Mode**: Accept/reject snapshot changes
- **Colored Output**: Clear success/failure indicators with diff visualization

## Quick Start

### Prerequisites

- Scala 3.3.1+
- SBT 1.x

### Build the Project

```bash
sbt compile
```

### Run Tests

```bash
# Compile test sources
sbt "Test/compile"

# Run example tests with verbose output
sbt "Test/runMain booktest.BooktestMain -v booktest.examples.ExampleTests"

# Run dependency tests to see parameter injection
sbt "Test/runMain booktest.BooktestMain -v booktest.examples.DependencyTests"

# Run method reference tests (improved approach)
sbt "Test/runMain booktest.BooktestMain -v booktest.examples.MethodRefTests"

# List test cases
sbt "Test/runMain booktest.BooktestMain -l booktest.examples.MethodRefTests"

# Show test logs
sbt "Test/runMain booktest.BooktestMain -L booktest.examples.MethodRefTests"

# Filter and show logs
sbt "Test/runMain booktest.BooktestMain -L -t Data booktest.examples.MethodRefTests"

# Show help
sbt "Test/runMain booktest.BooktestMain --help"
```

## Test Examples

### Basic Tests

```scala
import booktest.*

class ExampleTests extends TestSuite {
  def testHello(t: TestCaseRun): Unit = {
    t.h1("Hello Test")
    t.tln("Hello, World!")
    t.tln("This is a simple test")
  }
  
  def testCalculation(t: TestCaseRun): Unit = {
    t.h1("Calculation Test")
    val result = 2 + 2
    t.tln(s"2 + 2 = $result")
  }
}
```

### Dependency Tests

**New Method Reference API (recommended):**
```scala
class MethodRefTests extends TestSuite {
  // Test with no dependencies - returns String
  val data = test("createData") { (t: TestCaseRun) =>
    t.h1("Create Data Test")
    val result = "processed_data_123"
    t.tln(s"Created data: $result")
    result // Cached automatically
  }
  
  // Test with one dependency - receives cached String parameter
  val data2 = test("useData", data) { (t: TestCaseRun, cachedData: String) =>
    t.h1("Use Data Test")
    t.tln(s"Using cached data: $cachedData") // "processed_data_123"
    val result = s"enhanced_$cachedData"
    t.tln(s"Generated result: $result")
    result
  }
  
  // Test with multiple dependencies - receives both cached values
  test("finalStep", data, data2) { (t: TestCaseRun, original: String, processed: String) =>
    t.h1("Final Step Test")
    t.tln(s"Original: $original")      // "processed_data_123"
    t.tln(s"Processed: $processed")    // "enhanced_processed_data_123"
  }
}
```

**Legacy Annotation API:**
```scala
class DependencyTests extends TestSuite {
  def testCreateData(t: TestCaseRun): String = {
    // ... same as above
  }
  
  @dependsOn("testCreateData")
  def testUseData(t: TestCaseRun, cachedData: String): String = {
    // ... same as above
  }
}
```

## CLI Options

```bash
# Basic usage
sbt "Test/runMain booktest.BooktestMain [options] <test-class-names>"

# Options:
-v, --verbose       # Verbose output
-i, --interactive   # Interactive mode for snapshot updates
-l, --list          # List test cases
-L, --logs          # Show test logs
-t, --test-filter   # Filter tests by name pattern
--output-dir DIR    # Output directory (default: books)
--snapshot-dir DIR  # Snapshot directory (default: books)
-h, --help         # Show help message
```

## CLI Examples

```bash
# Run all tests in a suite
sbt "Test/runMain booktest.BooktestMain -v booktest.examples.ExampleTests"

# Filter tests by name pattern
sbt "Test/runMain booktest.BooktestMain -v -t Data booktest.examples.DependencyTests"

# Run multiple test suites
sbt "Test/runMain booktest.BooktestMain -v booktest.examples.ExampleTests booktest.examples.DependencyTests"

# Interactive mode (accept/reject snapshot changes)
sbt "Test/runMain booktest.BooktestMain -i booktest.examples.FailingTest"
```

## Test API

### Core Methods

- `t.h1(title)`, `t.h2(title)`, `t.h3(title)` - Headers
- `t.tln(text)` - Test line (checked against snapshot)
- `t.t(text)` - Test text without newline
- `t.i(text)` - Info text (not checked against snapshot)
- `t.iln(text)` - Info line with newline

### Dependencies

Tests can declare dependencies using three approaches:

**1. Annotation-based (original):**
```scala
@dependsOn("testCreateData")
def testUseData(t: TestCaseRun, cachedData: String): String = { ... }
```

**2. Method reference API (recommended):**
```scala
val data = test("createData") { (t: TestCaseRun) =>
  val result = "processed_data_123"
  t.tln(s"Created: $result")
  result
}

val data2 = test("useData", data) { (t: TestCaseRun, cachedData: String) =>
  t.tln(s"Using: $cachedData")
  s"enhanced_$cachedData"
}
```

**3. Type-safe dependencies with multiple inputs:**
```scala
test("finalStep", data, data2) { (t: TestCaseRun, original: String, processed: String) =>
  t.tln(s"Original: $original, Processed: $processed")
}
```

#### Key Benefits of Method Reference API:

1. **Type Safety**: Dependencies are type-checked at compile time
2. **No String References**: Direct method references prevent typos and support refactoring
3. **IDE Support**: Full auto-completion and navigation support
4. **Parameter Injection**: Cached values automatically injected with correct types
5. **Return Value Caching**: Results automatically cached in memory and filesystem

#### How it works:
1. **Test Declaration**: Use `test()` method with lambda functions
2. **Dependency References**: Pass `TestRef` objects directly as dependencies
3. **Type Inference**: Return types automatically inferred and cached
4. **Parameter Injection**: Cached values passed as typed parameters to dependent tests

## Output Structure

```
books/                          # Snapshot directory
├── .cache/                     # Cached test results
│   ├── createData.cache        # "STRING:processed_data_123"
│   └── useData.cache          # "STRING:enhanced_processed_data_123"
├── .out/                      # Test logs directory
│   ├── ExampleTests/
│   │   ├── hello.log          # Test execution logs
│   │   └── calculation.log
│   └── MethodRefTests/
│       ├── createData.log
│       ├── testUseData.log
│       └── testFinalStep.log
├── ExampleTests/              # Test suite snapshots
│   ├── hello.md               # Test output snapshots
│   ├── calculation.md
│   └── ...
└── DependencyTests/
    ├── createData.md
    ├── useData.md
    └── finalStep.md
```

## How It Works

1. **Test Execution**: Tests run and generate markdown output
2. **Snapshot Comparison**: Output compared against saved `.md` files
3. **Dependency Resolution**: Tests with dependencies run in correct order
4. **Parameter Injection**: Cached values injected as method parameters
5. **Result Caching**: Return values cached in memory and filesystem
6. **Diff Generation**: Failed tests show colored diffs

## Development

See `CLAUDE.md` for development guidance and architecture details.

## Migration Status

This is a Scala port of the Python booktest framework with full feature parity for core and advanced features.

**✅ Implemented:**
- Snapshot testing with markdown output
- Test discovery and execution
- Dependency injection with parameter passing (up to 3 dependencies)
- Dual-layer caching (memory + filesystem)
- Test filtering and selection
- Interactive and batch review modes
- CLI interface with tree view
- HTTP request/response snapshotting
- Environment variable snapshotting
- Function call snapshotting

**Planned:**
- Parallel execution
- Resource management (port pools, memory allocation)
- SBT plugin integration

## Advanced Features

### HTTP Snapshotting

Capture and replay HTTP requests/responses:

```scala
import booktest.*

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

Mock and snapshot environment variables:

```scala
class EnvSnapshotTests extends TestSuite {
  def testEnvVars(t: TestCaseRun): Unit = {
    EnvSnapshotting.withEnv(Map("API_KEY" -> "test_key"), t) {
      val value = EnvSnapshotting.get("API_KEY", t)
      t.tln(s"API_KEY = $value")
    }
  }
}
```

### Function Call Snapshotting

Capture expensive function calls and replay from cache:

```scala
class FunctionSnapshotTests extends TestSuite {
  def testExpensiveCalculation(t: TestCaseRun): Unit = {
    val cachedFn = FunctionSnapshotting.cached[Int, String]("expensiveOp", t) { n =>
      // This only runs on first execution, cached thereafter
      s"Result for $n"
    }
    t.tln(cachedFn(42))
  }
}
```

## Examples

The `src/test/scala/booktest/examples/` directory contains working examples:

- `ExampleTests.scala` - Basic test examples
- `DependencyTests.scala` - Dependency injection examples (string approach)
- `MethodRefTests.scala` - Dependency injection with method references (recommended)
- `FailingTest.scala` - Example of failing tests with diffs

Run any of these to see the framework in action!