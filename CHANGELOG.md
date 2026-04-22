# Changelog

## 0.3.7 (2026-04-22)

### Bug fix: .bin return value caching

- **Always cache return values on OK/DIFF**: Previously .bin files were only
  written when the test passed (snapshot matched) or in auto-accept mode.
  Tests with DIFF status (new or changed snapshots) didn't persist their
  return values, breaking dependent tests. Now matches Python booktest:
  .bin is written whenever the test ran successfully (OK or DIFF), and
  deleted on FAIL (exception/t.fail()).

## 0.3.6 (2026-04-21)

### Tokenizer fix for `t.t("label..").iMsLn { block }` pattern

- **Token alignment fix**: `.` no longer starts a number token in the
  tokenizer. Previously `.40` was tokenized as one number, but when output
  comes from separate `testFeed("..") + infoFeed("40ms")` calls, the `.`
  and `40` are separate tokens. This misalignment caused info-only timing
  diffs to appear as test-failing content diffs.
- **`iMsLn` overload fix**: Removed ambiguous curried `iMsLn(label)(block)`
  overload. When the block returned `String`, Scala silently resolved
  `iMsLn { expr }` to the label overload, discarding timing output entirely.
  Use `t.t("label: ").iMsLn { block }` or `t.i("label: ").iMsLn { block }`
  instead.
- **`TimingInfoTest`**: New test suite verifying that timing differences from
  `iMsLn` are treated as info-only (`.` cyan) and don't cause test failure.

## 0.3.5 (2026-04-18)

### Interactive mode improvements

- **Hard quit (`q`)**: Pressing `q` during interactive mode now stops test
  execution immediately instead of continuing to the next test
- **Accept and quit (`aq`)**: New command to accept the current diff and stop
- **`(d)iff` option**: Launch external diff tool in review mode, configured
  via `BOOKTEST_DIFF_TOOL` env var (defaults to `diff`)
- **Uncolored prompts**: Interactive prompts now render in terminal default
  color, matching Python booktest

### Python-style diff formatting

- **Diff symbols**: `?` (yellow) for content diffs, `!` (red) for fails,
  `.` (cyan) for info-only diffs — matching Python booktest convention
- **Inline diff report**: Generated during test execution with proper
  token-level markers instead of post-hoc line comparison
- **Expected text**: Shown in gray on the right side (`actual | expected`)
- **DIFF status**: Changed from orange to yellow
- **Summary format**: Now separates "differed" and "failed" counts
  (e.g., "2 differed and 1 failed")
- **Final report**: Lists individual failed tests with colored status

### Bug fixes

- **Dependency cache collision**: Fixed parallel execution (`-p4`) crash when
  multiple suites have tests with the same name (e.g., `createData`). Cache
  keys now include suite path.
- **EOF token marking**: Info content beyond snapshot end now correctly marked
  as info (was silently ignored)

### CI

- Added GitHub Actions CI workflow with Scala 3.3.1 tests and 2.12/2.13
  cross-compilation checks

## 0.3.3 (2026-04-07)

- **Orange DIFF status**: DIFF results now shown in orange instead of red,
  distinguishing them from FAIL (red). Matches Python booktest convention.
- **Review mode (`-w`) rewritten**:
  - Shows all results from previous run (names, status, durations)
  - Test selection narrows review (e.g., `examples/TmpDirTest -w`)
  - Trusts case report results instead of re-comparing files
  - Verbose mode (`-v`) shows output content for all tests
  - Interactive mode (`-i`) adds `(v)iew` and `(l)ogs` options
- **SuiteName/testCase args**: Positional arg like `GroceryTest/prefill`
  resolves as suite + test filter
- **Dependency preservation in `-t` filter**: Transitive dependencies of
  matched tests are included so setup tests still run

## 0.3.2 (2026-04-06)

### Breaking changes

- **Snapshot files now include info-line content.** All existing snapshots need
  regeneration (`-S` flag). Previously `iln()` / `i()` output was excluded from
  snapshot files entirely, making them unreadable for data science workflows
  where diagnostic output is essential for review.
- `TestResult` has a new `successState` field (`SuccessState.OK` / `DIFF` / `FAIL`).

### Token-by-token snapshot comparison

Replaces the line-by-line post-hoc comparison with Python booktest's
token-by-token comparison that runs during test execution. Each token is
compared as it is written, enabling precise diff/info/fail markers within a
single output line. Info-only differences (`i()` / `iln()`) no longer cause
test failure, matching Python booktest behavior.

### Anchor/seek for non-linear snapshot matching

Headers (`h1` .. `h5`) now seek to the matching line in the snapshot before
writing, so inserting new sections between existing ones does not cascade false
diffs across the rest of the file. Public API:

- **`t.anchor(prefix)`** / **`t.anchorln(line)`**: Seek then write
- **`t.seekLine(line)`** / **`t.seekPrefix(prefix)`**: Seek only
- **`t.seek(predicate)`**: General-purpose snapshot cursor navigation
- **`t.jump(lineNumber)`**: Absolute positioning

### New methods

- **`t.f(text)`** / **`t.fln(text)`**: Write fail content (always marks test
  as failed, included in snapshot)
- **`t.diff()`**: Mark current line as having a diff
- **`t.fail()`**: Mark current line as failed (no-arg variant)
- **`t.h4(title)`** / **`t.h5(title)`**: Level 4 and 5 headers

### Other changes

- `TestTokenizer`: Tokenizer matching Python booktest rules (whitespace,
  numbers with sign/decimal/scientific notation, alphanumeric words, special
  characters)
- `SuccessState` enum separates snapshot-mismatch from test-logic failure,
  matching Python's two-dimensional result model
- `TestCaseRun` lifecycle: `start()` / `end()` methods for output file and
  snapshot reader management
- `findNextNumber` (used by `peekDouble` / `peekLong`) no longer consumes
  the snapshot token stream

## 0.3.1 (2026-03-13)

- **`--root` CLI flag**: Override package prefix stripping from the command line
  (alternative to setting `root` in `booktest.ini`)
- **Port management API**: `t.acquirePort()`, `t.releasePort()`, `t.withPort { port => }` for
  tests that need network ports
- **Env-configurable port range**: `BOOKTEST_PORT_BASE` and `BOOKTEST_PORT_MAX` environment
  variables for CI environments with restricted ports
- **Suite-level parallel execution**: With `-pN`, suites now run in parallel (tests within
  each suite remain sequential)
- **Fix**: `RelPath` handling for `--output-dir` and `--snapshot-dir` with nested paths

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
- **`t.iMsLn("label") { block }`**: Execute block and print elapsed time as info
- **`t.ms { block }`**: Returns `(elapsed_ms, result)` tuple
- **`t.tln` / `t.iln` without parentheses**: Output empty line without needing `()`
- **Safer test discovery**: Helper methods starting with `test` that take extra
  parameters (beyond `TestCaseRun`) are no longer picked up as phantom tests
  unless they have a `@DependsOn` annotation
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