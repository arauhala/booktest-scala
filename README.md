# Booktest Scala

A Scala port of the [booktest](https://github.com/lumoa-oss/booktest) framework - a review-driven testing tool designed for data science workflows.

## Overview

Booktest enables snapshot testing where tests write output to markdown files that are compared against saved snapshots. This approach is ideal for data science where results aren't strictly right/wrong but require expert review and comparison over time.

## Key Features

- **Snapshot Testing**: Test output compared against saved markdown files
- **Dependency Injection**: Tests can depend on other tests with cached parameter injection
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

```scala
class DependencyTests extends TestSuite {
  def testCreateData(t: TestCaseRun): String = {
    t.h1("Create Data Test")
    val data = "processed_data_123"
    t.tln(s"Created data: $data")
    data // This return value is cached
  }
  
  @dependsOn("testCreateData")
  def testUseData(t: TestCaseRun, cachedData: String): String = {
    t.h1("Use Data Test")
    t.tln(s"Using cached data: $cachedData") // Receives "processed_data_123"
    val result = s"enhanced_$cachedData"
    t.tln(s"Generated result: $result")
    result
  }
  
  @dependsOn("testCreateData", "testUseData")
  def testFinalStep(t: TestCaseRun, originalData: String, processedData: String): Unit = {
    t.h1("Final Step Test")
    t.tln(s"Original: $originalData")      // "processed_data_123"
    t.tln(s"Processed: $processedData")    // "enhanced_processed_data_123"
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

Tests can declare dependencies using `@dependsOn("testMethodName")`:

1. **Return Values**: Test methods can return values that get cached
2. **Parameter Injection**: Dependent tests receive cached values as parameters
3. **Execution Order**: Dependencies run before dependent tests
4. **Caching**: Results cached in both memory and filesystem (`.cache` files)

## Output Structure

```
books/                          # Snapshot directory
├── .cache/                     # Cached test results
│   ├── createData.cache        # "STRING:processed_data_123"
│   └── useData.cache          # "STRING:enhanced_processed_data_123"
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

This is a Scala port focusing on core functionality:

**✅ Implemented:**
- Snapshot testing with markdown output
- Test discovery and execution
- Dependency injection with parameter passing
- Dual-layer caching (memory + filesystem)
- Test filtering and selection
- Interactive mode
- CLI interface

**Not Yet Implemented:**
- Parallel execution
- HTTP request snapshotting
- Environment variable snapshotting
- Resource management
- Complex multi-parameter dependency injection

## Examples

The `src/test/scala/booktest/examples/` directory contains working examples:

- `ExampleTests.scala` - Basic test examples
- `DependencyTests.scala` - Dependency injection examples
- `FailingTest.scala` - Example of failing tests with diffs

Run any of these to see the framework in action!