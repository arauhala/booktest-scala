# Booktest-Scala Usage Guide

This guide is for using booktest-scala as a testing framework in your Scala project.

## Installation

Add to your `build.sbt`:

```scala
libraryDependencies += "io.github.arauhala" %% "booktest-scala" % "0.2.0" % Test
```

For Scala 2.12/2.13 (coming soon), cross-compiled versions will be available.

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
| `t.fail(msg)` | Mark test as failed | N/A |
| `t.file(name)` | Get File in output dir | N/A |
| `t.iMsLn(label) { }` | Execute block, print timing | No |

### Utility Methods

```scala
class UtilityTests extends TestSuite {
  def testUtilities(t: TestCaseRun): Unit = {
    // Mark test as failed without exception
    if (result < expected) {
      t.fail(s"Expected $expected but got $result")
    }

    // Get a file in the test output directory
    val outputFile = t.file("results.json")
    writeJson(outputFile, data)

    // Time a block and print duration as info
    val result = t.iMsLn("Database query") {
      db.query("SELECT * FROM users")
    }
    t.tln(s"Found ${result.size} users")
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

## Running Tests

### Command Line

```bash
# Run all tests in a suite
sbt "Test/runMain booktest.BooktestMain -v myproject.MyTests"

# Run specific test by pattern
sbt "Test/runMain booktest.BooktestMain -v -t setup myproject.DataTests"

# List tests without running
sbt "Test/runMain booktest.BooktestMain -l myproject.MyTests"

# Interactive mode (accept/reject snapshot changes)
sbt "Test/runMain booktest.BooktestMain -i myproject.MyTests"

# Show test logs
sbt "Test/runMain booktest.BooktestMain -L myproject.MyTests"
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
| `--help` | Show help |

## Snapshot Directory Structure

```
books/
├── MyTests/
│   ├── example.md          # Snapshot for testExample
│   └── other.md            # Snapshot for testOther
├── DataTests/
│   ├── setup.md
│   └── process.md
├── .cache/                  # Cached test results (gitignore)
└── .out/                    # Test logs (gitignore)
```

## Workflow

1. **Write test** - Create test methods that output to `t.tln()`, `t.h1()`, etc.
2. **Run test** - First run creates snapshot in `books/TestSuite/testName.md`
3. **Review snapshot** - Check the generated markdown file
4. **Commit snapshot** - Add `books/` to git (except `.cache/` and `.out/`)
5. **Subsequent runs** - Test output compared against saved snapshot
6. **Update snapshot** - Use `-i` flag to accept changes interactively

## Git Configuration

Add to `.gitignore`:

```
books/.cache/
books/.out/
```

Commit the `books/` directory with your snapshots - they serve as the expected test output.

## Tips for Claude Code

When writing booktest tests:

1. **Use descriptive headers** - `t.h1("What this test verifies")` makes snapshots readable
2. **Structure output logically** - Group related assertions under headers
3. **Use info lines for debugging** - `t.iln()` for values that might change but aren't part of the test contract
4. **Name tests descriptively** - Test method names become snapshot filenames
5. **Review generated snapshots** - Ensure they capture the intended behavior

## Links

- GitHub: https://github.com/arauhala/booktest-scala
- Original Python booktest: https://github.com/lumoa-oss/booktest
