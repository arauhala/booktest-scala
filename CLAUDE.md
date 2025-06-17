# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

This is a Scala port of the booktest framework - a review-driven testing tool designed for data science workflows. The original Python implementation is available in the `python/` directory for reference.

Booktest enables snapshot testing where tests write output to markdown files that are compared against saved snapshots. This approach is ideal for data science where results aren't strictly right/wrong but require expert review.

## Architecture

The Scala implementation follows a minimal core design with these key components:

- **TestCaseRun**: Main API for writing test output (`tln()`, `h1()`, `i()`, `iln()`)
- **TestSuite**: Base class for organizing tests, discovers test methods automatically  
- **TestRunner**: Sequential test execution engine
- **SnapshotManager**: Handles reading/writing/comparing snapshot files
- **BooktestMain**: CLI entry point

Test output is written to `books/` directory as markdown files and compared against previous snapshots stored in Git.

## Project Structure

```
booktest-scala/
├── build.sbt                    # SBT build configuration
├── src/main/scala/booktest/     # Core framework implementation
├── src/test/scala/booktest/     # Framework tests and examples
├── books/                       # Snapshot storage directory
├── org/tasks/                   # Migration planning documents
└── python/                      # Original Python implementation (reference)
```

## Technology Stack

- **Scala 3.x**: Modern Scala with improved syntax and type inference
- **os-lib**: Ergonomic file system operations (preferred over java.nio)
- **fansi**: Terminal colors for output formatting
- **munit**: Testing framework for the booktest framework itself

## Development Commands

```bash
# Build the project
sbt compile

# Compile test sources
sbt "Test/compile"

# Run example tests
sbt "Test/runMain booktest.BooktestMain -v booktest.examples.ExampleTests"

# Run with help
sbt "Test/runMain booktest.BooktestMain --help"

# Run all available test classes
sbt "Test/runMain booktest.BooktestMain -v booktest.examples.ExampleTests booktest.examples.FailingTest"
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
```

## Implementation Status

This is a migration in progress. Current phase focuses on:
- Core snapshot testing functionality
- Sequential test execution  
- Basic CLI interface
- Markdown output generation

Not yet implemented:
- Parallel execution
- HTTP/environment snapshotting
- Complex dependency management
- Interactive review mode

## Migration Reference

The Python implementation in `python/` provides the reference architecture. Key files to understand:
- `python/booktest/testcaserun.py` - Core test API
- `python/booktest/testbook.py` - Test suite organization
- `python/booktest/runs.py` - Test execution
- `python/examples/` - Usage examples

Migration plan is documented in `org/tasks/migration-plan.md`.