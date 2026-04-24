# Python vs Scala Booktest: Logical Inconsistencies

Systematic comparison of behavioral differences between Python booktest
(`/home/arau/home/src/booktest/booktest/`) and the Scala port.

---

## 1. Exception Handling: Traceback Not Written to .md Output

**Python**: When a test throws an exception, the full traceback is written to the `.md`
output file via `t.iln(traceback.format_exc())`. This means the exception becomes part of
the snapshot and can be reviewed/accepted.

**Scala**: Exception message goes to the `.txt` report and console (verbose mode), but
**NOT to the `.md` output file**. Only partial output (written before the exception) appears
in the snapshot.

**Impact**: In Python, you can snapshot-test error scenarios. In Scala, exceptions are
invisible in snapshots. This also means the `.md` file may be truncated mid-line, producing
confusing diffs.

**Suggested test**: `ExceptionOutputTest` - test that throws after writing some output,
verify the exception traceback appears in the `.md` file.

---

## 2. Cache Serialization: Pickle vs Text Format (Lossy for Complex Types)

**Python**: Uses `pickle` for `.bin` files. Any serializable Python object round-trips
perfectly (dicts, lists, custom classes, etc.).

**Scala**: Uses simple `TYPE:value` text format. Complex objects fall back to
`OBJECT:toString()`, which is **lossy** - deserialization returns a `String`, not the
original type. A dependent test expecting e.g. `List[Int]` would receive a `String` like
`"List(1, 2, 3)"`.

**Impact**: Dependency injection silently breaks for non-primitive types. The dependent
test receives a `String` and may fail with a `ClassCastException` at runtime, or worse,
silently produce wrong results.

**Suggested test**: `CacheRoundTripTest` - cache a `Map`, `List`, or case class as a
return value, then verify the dependent test receives the correct type (not a String).

---

## 3. Filter Semantics: Substring vs Path-Prefix Matching

**Python**: Uses **path-prefix matching with boundary checking**. Pattern `test` matches
`test/foo` but NOT `testing/bar` (requires `/` boundary after match).

**Scala**: Uses **`.contains()` substring matching**. Pattern `test` would match both
`test/foo` AND `testing/bar`.

**Impact**: Filters are overly broad in Scala. A user filtering with `-t Data` would
accidentally match `DataLoader`, `MetaData`, `TestDataProcessing`, etc.

**Suggested test**: `FilterBoundaryTest` - suite with tests named `testFoo`, `testFooBar`,
`testFooBaz`. Filter with `-t Foo` and verify only `testFoo` matches (not `testFooBar`).

---

## 4. Missing CLI Flags

Several Python CLI flags have no Scala equivalent:

| Python Flag | Purpose | Scala Status |
|-------------|---------|--------------|
| `-r` | Refresh sources (re-run all deps) | Missing |
| `-f` | Fail fast (stop after first failure) | Missing |
| `-a` | Auto-accept DIFF tests | Missing (only `-s` exists) |
| `-u` | Auto-update OK tests | Missing |
| `-I` | Always interactive (even OK tests) | Missing |
| `--forget` | Remove review status | Missing |
| `--config` | Print configuration | Missing |
| `--view` | Open snapshots in viewer | Missing |
| `--print` | Print snapshot contents | Missing |
| `--path` | Print snapshot file paths | Missing |
| `--timeout` | Timeout for parallel runs | Missing |

**Impact**: Missing `-f` (fail-fast) is significant for large test suites. Missing `-a`
means there's no way to auto-accept diffs without also recapturing.

---

## 5. Exit Code Difference

**Python**: Returns explicit exit code `-1` for any failure (DIFF or FAIL), `0` for success.

**Scala**: Throws `BooktestFailure` exception instead of returning an exit code. While this
works for SBT, it may not produce the expected exit code when run in other contexts (CI
scripts, shell wrappers).

**Suggested test**: Verify that the process exit code is non-zero on test failure.

---

## 6. Dependency Failure Propagation: No Skip Mechanism

**Python**: When dependency A fails, dependent test B still runs. B calls
`get_test_result()` which loads A's cached result (possibly `None`). B runs with degraded
input and likely fails.

**Scala**: Same behavior - B still runs. But since `.bin` is deleted on failure, B gets an
`IllegalStateException("Dependency not found in cache")` instead of running with the
failed result.

**Difference**: Python gives B a chance to handle the failure gracefully (receives `None`).
Scala hard-fails B before it even starts.

**Neither implementation skips dependent tests**, but the failure mode differs.

**Suggested test**: `DependencyFailureTest` - test A that fails, test B depends on A.
Verify B's behavior is consistent (ideally: B should be skipped or get a clear message,
not an internal error).

---

## 7. Continue Mode (-c): Subtle Behavior Difference

**Python**: Checks `cases.ndjson` for previous OK results. Tests that were DIFF or FAIL are
re-run. Tests that were OK are skipped.

**Scala**: Same logic, checks `cases.ndjson`. BUT: does not check whether the `.bin` cache
file still exists. If a previous OK test's `.bin` was deleted, its dependents will fail
even though continue mode considers the test "done".

**Suggested test**: `ContinueModeTest` - run tests, delete a `.bin` file, re-run with `-c`,
verify behavior.

---

## 8. Log Capture Format Differences

**Python**: Captures **stderr only** in `.log` file. Redirects `sys.stderr` and Python
`logging` to the log file. No headers.

**Scala**: Uses `TeeOutputStream` that captures **both stdout and stderr** with section
headers (`=== STDOUT ===`, `=== STDERR ===`).

**Impact**: Log files have different structure. Python logs are raw stderr; Scala logs
include stdout and have headers. This affects `-L` (show logs) output and review workflows.

**Suggested test**: `LogCaptureTest` - test that writes to both stdout and stderr, verify
log file format matches expected structure.

---

## 9. Diff Display: `fast (D)iff` Missing

**Python**: Interactive mode includes `fast (D)iff` option (capital D) that uses a
configured `fast_diff_tool` for quick diff viewing.

**Scala**: Only has `(d)iff` option. No fast diff variant.

**Impact**: Minor UX difference but worth noting for workflow consistency.

---

## 10. Auto-Accept Semantics: `-s` vs `-a`/`-u`

**Python**: Has fine-grained auto-accept:
- `-a`: Auto-accept tests with DIFF result (changed output)
- `-u`: Auto-update tests with OK result (freeze unchanged)
- These can be combined

**Scala**: Has `-s` (update snapshots) which auto-accepts all changes, and `-S` (recapture
all) which forces re-run. No way to auto-accept only DIFFs without also affecting OK tests.

**Impact**: Less control over snapshot management workflow. Users can't selectively
auto-accept only changed tests.

---

## 11. Metrics Tolerance: Output Format Differences

**Python**: Metric output format: `0.973 (was 0.950, +0.023)` or
`0.920 (was 0.950, -0.030) [exceeds!]`

**Scala**: Need to verify exact output format matches Python. The tolerance logic exists
but the output formatting may differ.

**Suggested test**: `MetricsConsistencyTest` - test metrics with known values and
tolerances, verify output format matches Python convention.

---

## 12. Test Naming: Case Convention

**Python**: Test name derived by stripping `test_` prefix (snake_case).
Example: `test_create_data` -> `create_data`

**Scala**: Test name derived by stripping `test` prefix and lowercasing first letter.
Example: `testCreateData` -> `createData`

**Impact**: This is expected (language convention difference), but matters for
cross-language snapshot compatibility.

---

## 13. Missing `--setup` Command

**Python**: Has `--setup` command to initialize booktest configuration in a project.

**Scala**: No equivalent. Configuration must be created manually.

---

## 14. No `skip:` Negative Filter

**Python**: Filter supports `skip:pattern` syntax to exclude tests matching a pattern.

**Scala**: No negative filter support. Can only include, not exclude.

**Suggested test**: Verify negative filter support or document the gap.

---

## Priority Ranking

### Critical (affects correctness):
1. **Exception traceback not in .md** (#1) - breaks error scenario testing
2. **Cache serialization lossy** (#2) - silently corrupts dependency injection
3. **Filter substring vs prefix** (#3) - over-matches tests

### Important (affects workflow):
4. **Dependency failure hard-crash** (#6) - confusing error messages
5. **Missing `-f` fail-fast** (#4) - slow feedback on large suites
6. **Auto-accept granularity** (#10) - less workflow control
7. **Exit code behavior** (#5) - CI integration issues

### Minor (UX/completeness):
8. **Log capture format** (#8)
9. **Missing CLI flags** (#4, others)
10. **Fast diff missing** (#9)
11. **Metrics format** (#11)

---

## Suggested Test Plan

Create a new test suite `booktest.examples.ConsistencyTests` (or multiple suites) that
exercises each of these behaviors. Running them will either:
- Confirm the Scala behavior matches Python (snapshot passes)
- Reveal the inconsistency (snapshot differs or test fails)

### Test Suites to Create:

1. **`ExceptionHandlingTests`** - exception output in .md, partial output preservation
2. **`CacheTests`** - round-trip of various types through .bin cache
3. **`DependencyFailureTests`** - behavior when upstream dependency fails
4. **`FilterTests`** - boundary matching behavior (may need CLI-level test)
5. **`LogCaptureTests`** - stdout/stderr capture format
6. **`MetricsFormatTests`** - metric output format consistency
