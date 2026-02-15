# Python Booktest Migration Status

## Migrated Tests

| Python Test | Scala Test | Status |
|-------------|------------|--------|
| test_info_methods.py | InfoMethodsTest.scala | ✅ Complete (tables, mixed output) |
| test_metrics.py | MetricsTest.scala | ✅ Complete (tolerance) |
| test_metrics.py (direction) | DirectionConstraintsTest.scala | ✅ Complete |
| setup_teardown_test.py | SetupTeardownTest.scala | ✅ Complete |
| test_token_markers.py | MarkersTest.scala | ✅ Complete |
| - | ImageTest.scala | ✅ New (image output) |
| - | SnapshotCacheTest.scala | ✅ New (snapshot caching) |
| - | AsyncTest.scala | ✅ New (ExecutionContext support) |
| dependencies_test.py | DependencyTests.scala | ✅ Complete |
| - | MethodRefTests.scala | ✅ New (method ref API) |
| test_env_config.py | EnvSnapshotTests.scala | ✅ Complete |
| - | HttpSnapshotTests.scala | ✅ New (HTTP snapshotting) |
| - | FunctionSnapshotTests.scala | ✅ New (function snapshotting) |

## Implemented Features

### Output Methods
- [x] `tln()`, `iln()` - Line output (test/info)
- [x] `h1()`, `h2()`, `h3()` - Headers
- [x] `ttable()`, `itable()` - Table output with headers/rows
- [x] `timage()`, `iimage()` - Image output with captions
- [x] `tLong()`, `tDouble()`, `tLongLn()`, `tDoubleLn()` - Numeric output with tolerance

### Metrics
- [x] `tmetric()` - Metric with absolute tolerance
- [x] `tmetricPct()` - Metric with percentage tolerance
- [x] Direction constraints (`min` = must not decrease, `max` = must not increase)

### Assertions
- [x] `assertTrue()`, `assertFalse()`, `assertEquals()`, `assertNotNull()`
- [x] `assertThrows()` - Exception assertion
- [x] `fail()` - Explicit failure

### File Handling
- [x] `file()` - Get file path in test output directory
- [x] `renameFileToHash()` - Rename file to content hash

### Caching
- [x] `snapshot(name, args*) { ... }` - Cached computation with hash-based invalidation

### Lifecycle
- [x] `setup()` / `teardown()` - Per-test hooks
- [x] `beforeAll()` / `afterAll()` - Suite-level hooks

### Markers/Tags
- [x] `mark(testName, markers*)` - Tag tests
- [x] `getMarkers()`, `hasMarker()` - Query markers
- [x] Built-in markers: Fast, Slow, Unit, Integration, GPU, Network, Flaky

### Performance
- [x] Parallel execution (`-j` flag)
- [x] Resource pools (PortPool)
- [x] ExecutionContext for async patterns

## Python Tests Not Migrated (Intentionally)

| Test File | Reason |
|-----------|--------|
| cli_test.py | Meta-test for CLI (covered by manual testing) |
| detect_test.py | Internal test discovery (Scala uses reflection) |
| stderr_test.py | Log capture works differently (LogCapture.scala) |
| test_case_filtering.py | Works via `-t` flag |
| test_colors.py | Internal formatting (uses fansi) |
| test_migration.py | Python-specific migration tool |
| test_names_test.py | Internal naming conventions |
| test_selection.py | Works via CLI flags |
| test_storage.py | Internal snapshot storage |
| test_two_dimensional_results.py | Complex multi-dimensional output (low priority) |

## Feature Parity Summary

| Category | Python | Scala | Notes |
|----------|--------|-------|-------|
| Core Output | ✅ | ✅ | Full parity |
| Metrics | ✅ | ✅ | Full parity |
| Images | ✅ | ✅ | File-based (no matplotlib) |
| Tables | ✅ | ✅ | itable/ttable (no pandas) |
| Caching | ✅ | ✅ | snapshot() with hash |
| HTTP Mocking | ✅ | ✅ | HttpSnapshotting |
| Env Mocking | ✅ | ✅ | EnvSnapshotting |
| Function Mocking | ✅ | ✅ | FunctionSnapshotting |
| Lifecycle | ✅ | ✅ | setup/teardown |
| Markers | ✅ | ✅ | Test tagging |
| Parallelism | ✅ | ✅ | -j flag (threads) |
| CLI | ✅ | ✅ | All major flags |
| SBT Plugin | N/A | ✅ | New in Scala |
