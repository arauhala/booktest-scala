# Changelog

## 0.3.0 (2026-02-14)

Major release adding parallel execution, Python-style file structure, rich test output,
and data science workflow features.

### Parallel Execution

- **`-pN` flag**: Run tests in parallel with N threads (e.g., `-p4`)
- **Resource locks**: Tests sharing mutable state can declare `resourceLocks` to
  serialize execution even under `-pN`
- Dependency-aware scheduling ensures prerequisites complete before dependents run

### Continue Mode

- **`-c` / `--continue` flag**: Skip tests that passed in the previous run,
  re-running only failures
- Test results persisted in `cases.ndjson` (Python-compatible format)

### Python-Style File Structure

Snapshot files reorganized to match the Python booktest layout:

```
books/
├── .out/                        # Test execution output (gitignored)
│   └── SuiteName/
│       ├── testName/            # Tmp directory
│       ├── testName.md          # Test output
│       ├── testName.log         # Captured stdout/stderr
│       └── testName.bin         # Return value cache
├── SuiteName/                   # Committed snapshots
│   └── testName.md
```

### Configuration File (`booktest.ini`)

- Define test root package, default groups, and exclude patterns
- Named groups for running sets of tests (e.g., `./do test examples`)
- Exclude patterns to skip specific tests by default

### Metrics with Tolerance

- **`t.tmetric(name, value, tolerance)`**: Track numeric metrics with absolute tolerance
- **`t.tmetricPct(name, value, tolerancePct)`**: Percentage-based tolerance
- **Direction constraints**: `direction = Some("min")` prevents regressions (value must
  not decrease), `direction = Some("max")` prevents increases
- When value is within tolerance of the snapshot, the old value is kept for stable diffs

### Image Support

- **`t.timage(file, caption)`**: Include images in tested snapshot output
- **`t.iimage(file, caption)`**: Include images as info-only output
- **`t.renameFileToHash(filename)`**: Rename files to content hash for deterministic names
- Images stored in per-test asset directories

### Table Output

- **`t.ttable(headers, rows)`**: Markdown table in tested output
- **`t.itable(map)`**: Info-only table from key-value Map
- **`t.ttable(caseClasses)`**: Auto-generate table from case class sequence using reflection

### Snapshot Caching

- **`t.snapshot(name) { expensive() }`**: Cache expensive computations
- Automatic invalidation when arguments change (hash-based)
- **`-S` flag** forces recomputation of all cached snapshots
- Methods: `hasSnapshot()`, `invalidateSnapshot()`, `invalidateAllSnapshots()`

### Temporary Directories

- **`t.tmpDir(subpath)`**, **`t.tmpFile(subpath)`**, **`t.tmpPath(subpath)`**:
  Create temp files/directories that persist between dependent tests
- Automatically cleared on test re-run

### Async Support

- **`t.await(future)`**: Wait for a Future with configurable timeout
- **`t.async { implicit ec => ... }`**: Run async blocks with automatic ExecutionContext
- **`t.ec`**: Access the test's implicit ExecutionContext

### Test Markers

- **`mark(testName, markers...)`**: Tag tests with categories
- Predefined: `Slow`, `Fast`, `Integration`, `Unit`, `GPU`, `Network`, `Flaky`
- Query with `getMarkers()`, `hasMarker()`

### Setup/Teardown Hooks

- **`setup(t)`** / **`teardown(t)`**: Run before/after each test
- **`beforeAll()`** / **`afterAll()`**: Run once per suite
- `teardown` always executes, even on test failure

### Log Capture

- Stdout/stderr captured to `.log` files during test execution
- Captured output available in review mode

### Additional CLI Options

- **`--garbage`**: List orphan files in `books/` not corresponding to active tests
- **`--clean`**: Remove orphan files and temp directories
- **`--tree`**: Hierarchical tree display with `-l`
- **`--inline`**: Show diffs inline during execution
- **`-S` / `--recapture`**: Force regenerate all snapshots
- **`-s` / `--update`**: Auto-accept all snapshot changes

### Other Improvements

- **`t.assertln(condition, message)`**: Assert with automatic OK/FAILED output
- **`t.key(key, value)`**: Output labeled key-value pairs
- **`t.fail(message)`**: Explicitly mark test as failed
- **`t.iMsLn { block }`**: Execute block and print elapsed time
- **`t.ms { block }`**: Returns `(elapsed_ms, result)` tuple
- Python-style colored terminal output
- SBT plugin scaffolding (`plugin/` directory)

## 0.2.1 (2026-02-07)

- Fix publishing to include all Scala versions (2.12, 2.13, 3.3)
- `./do publish` now uses `+publishSigned` for cross-compilation

## 0.2.0 (2026-02-06)

- `t.fail()`, `t.file()`, `t.iMsLn()` methods
- `t.ms{}`, `t.iUsPerOpLn()`, `t.tUsPerOpLn()` for performance testing
- `t.tLongLn()`, `t.tDoubleLn()` with tolerance parameter
- `t.assertln()` for assertions
- `t.peekDouble`, `t.peekLong`, `t.peekToken` for snapshot comparison
- Cross-compilation: Scala 2.12.18, 2.13.12, 3.3.1
- Info lines correctly excluded from snapshot comparison