/init# Booktest Scala Migration Plan

## Overview

This document outlines the plan for creating a minimal Scala implementation of the booktest framework. The goal is to build a basic single-threaded version focusing on core snapshot testing functionality, without advanced features like HTTP/env snapshotting, parallel execution, or complex dependency management.

## Core Concepts

Booktest is a review-driven testing framework designed for data science workflows. Its key features:
- Tests write output to markdown files
- Output is compared against saved snapshots
- Changes require human review
- Results are stored in Git for version control

## Phase 1: Minimal Core Implementation

### 1.1 Project Structure
```
booktest-scala/
├── build.sbt
├── src/
│   ├── main/scala/booktest/
│   │   ├── TestCaseRun.scala      # Core test execution API
│   │   ├── TestCase.scala         # Test case definition
│   │   ├── TestSuite.scala        # Test grouping
│   │   ├── TestRunner.scala       # Test execution engine
│   │   ├── SnapshotManager.scala  # Snapshot comparison
│   │   └── BooktestMain.scala     # CLI entry point
│   └── test/scala/booktest/
│       └── examples/              # Example tests
├── books/                         # Snapshot storage
└── org/tasks/                     # Migration documentation
```

### 1.2 Core Classes

#### TestCaseRun
- Main API for writing test output
- Methods: `tln(text)`, `h1(header)`, `i(info)`, `iln(info)`
- Manages output buffer and file writing
- Compares output with snapshots

#### TestCase
- Represents a single test
- Contains test name, function, and metadata
- Handles test execution lifecycle

#### TestSuite
- Groups related tests
- Provides test discovery mechanism
- Manages test organization

#### TestRunner
- Executes tests sequentially
- Handles success/failure reporting
- Manages snapshot comparison workflow

#### SnapshotManager
- Reads/writes snapshot files
- Compares current output with snapshots
- Reports differences

### 1.3 Dependencies

Minimal Scala dependencies:
- **scala 3.x** - Latest Scala version
- **os-lib** - File system operations
- **fansi** - Terminal colors for output
- **mainargs** - Command-line argument parsing
- **munit** - For testing the framework itself

### 1.4 Test Definition API

```scala
import booktest.*

class ExampleTests extends TestSuite {
  def testHello(t: TestCaseRun): Unit = {
    t.h1("Hello Test")
    t.tln("Hello, World!")
  }
  
  def testCalculation(t: TestCaseRun): Unit = {
    val result = 2 + 2
    t.tln(s"2 + 2 = $result")
  }
}
```

## Phase 2: Basic Features (Future)

### 2.1 Test Dependencies
- Simple dependency management
- Cached return values
- `@dependsOn` annotation

### 2.2 Interactive Mode
- Show diffs for failed tests
- Accept/reject changes
- Update snapshots

### 2.3 Test Selection
- Run specific tests by name
- Test filtering patterns
- Test organization by package

## Implementation Steps

1. **Setup Project** (Week 1)
   - Create build.sbt with dependencies
   - Setup basic project structure
   - Configure Scala 3.x

2. **Core API** (Week 1-2)
   - Implement TestCaseRun with basic methods
   - Create TestCase wrapper
   - Build simple TestSuite base class

3. **Snapshot System** (Week 2)
   - Implement SnapshotManager
   - File reading/writing with os-lib
   - Basic diff detection

4. **Test Runner** (Week 3)
   - Sequential test execution
   - Result collection and reporting
   - Basic CLI with mainargs

5. **Examples & Testing** (Week 3-4)
   - Create example test suites
   - Test the framework itself
   - Documentation

## Success Criteria

A minimal working implementation that can:
1. Discover and run test methods
2. Write output to markdown files
3. Compare output against snapshots
4. Report test success/failure
5. Run from command line

## Non-Goals for Phase 1

- Parallel execution
- HTTP request snapshotting
- Environment variable snapshotting
- Function call mocking
- Complex dependency graphs
- Resource management
- Pytest integration
- Configuration files

## Technical Decisions

1. **Use Scala 3** - Modern syntax, better type inference
2. **os-lib over java.nio** - More ergonomic file operations
3. **No reflection** - Use explicit test registration
4. **Simple CLI** - Basic command-line interface with mainargs
5. **Markdown only** - Focus on markdown output format

## Next Steps

1. Create build.sbt file
2. Implement TestCaseRun class
3. Build minimal test runner
4. Create first working example