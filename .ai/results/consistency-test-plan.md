# Consistency Test Plan: Booktest Tests for Python-Scala Parity

Tests to create in `src/test/scala/booktest/examples/` that exercise the inconsistencies
found between Python and Scala booktest. Running these tests will reveal the actual
behavior and serve as regression tests after fixes.

---

## 1. ExceptionHandlingTests

**Port from**: `examples/failures/book/failing_book.py`

Tests to verify exception output appears in `.md` snapshot (Python behavior).

```
testExceptionInOutput
  - Write some output via t.tln()
  - Throw RuntimeException("boom")
  - Expected: .md contains the output AND the exception traceback
  - Currently: .md only contains output before exception (BUG)

testExplicitFail
  - Call t.fail("reason")
  - Continue writing output
  - Expected: test marked FAIL, output includes content after fail()
  
testExceptionAfterPartialOutput
  - Write 3 lines, throw on 4th
  - Expected: .md has all 3 lines + exception traceback
  - Verifies partial output preservation

testExceptionInDependency
  - testA throws exception
  - testB depends on testA
  - Expected: testB gets clear skip/error, not IllegalStateException
```

**Why**: Python writes `t.iln(traceback.format_exc())` on exception. Scala doesn't.
This is the #1 critical inconsistency.

---

## 2. CacheRoundTripTests

**Port from**: `test/examples/example_book.py` (test_cache, test_cache_use, test_two_cached)

Tests to verify cache serialization handles various types.

```
testCacheString
  - Return "hello" -> verify dependent receives "hello": String

testCacheInt  
  - Return 42 -> verify dependent receives 42: Int

testCacheLong
  - Return 123456789L -> verify dependent receives Long

testCacheDouble
  - Return 3.14 -> verify dependent receives Double

testCacheBoolean
  - Return true -> verify dependent receives Boolean

testCacheNull
  - Return null -> verify dependent receives null

testCacheUnit
  - Return Unit -> verify dependent receives Unit

testChainedCache
  - A returns "foo", B(A) returns s"bar_$a", C(A,B) uses both
  - Port of Python's test_two_cached pattern

testCacheComplexType (EXPECTED TO REVEAL BUG)
  - Return a Map or List
  - Dependent test tries to use it as Map/List
  - Expected: ClassCastException or lossy String (reveals #2 inconsistency)
```

**Why**: Python uses pickle (lossless). Scala uses TYPE:toString (lossy for complex types).

---

## 3. DependencyFailureTests

**Port from**: `examples/failures/book/failing_book.py` + dependency patterns

Tests to verify behavior when upstream dependencies fail.

```
testUpstreamFails
  - Throws RuntimeException
  - Returns nothing (cache not written)

@DependsOn(Array("testUpstreamFails"))
testDownstreamOfFailure
  - Depends on testUpstreamFails
  - Expected Python behavior: receives None, can handle gracefully
  - Actual Scala behavior: IllegalStateException before test body runs

testUpstreamDiffs (returns value but output differs from snapshot)
  - Returns "data" but output differs
  - Expected: .bin IS written (DIFF != FAIL)
  
@DependsOn(Array("testUpstreamDiffs"))  
testDownstreamOfDiff
  - Should receive "data" even though upstream differed
  - Verifies .bin is written for DIFF (not just OK)
```

**Why**: Python and Scala handle dependency failures differently. Python gives the
dependent test a chance; Scala hard-crashes it.

---

## 4. FilterBehaviorTests

**Note**: These need CLI-level verification, not just booktest output.

Create a suite with carefully named tests:

```
testData          - should match filter "Data"
testDataLoader    - should NOT match filter "Data" (Python prefix behavior)
testLoadData      - depends on filter semantics
testMetaData      - should NOT match filter "Data" (Python prefix behavior)
```

Run with: `sbt "Test/runMain booktest.BooktestMain -t Data booktest.examples.FilterBehaviorTests"`

**Why**: Python uses path-prefix matching with `/` boundary. Scala uses `.contains()`.
With Python semantics, `-t Data` should only match `testData` exactly (as a path segment),
not `testDataLoader`.

---

## 5. LogCaptureTests

Tests to verify log capture format.

```
testStdoutCapture
  - System.out.println("stdout line")
  - Verify .log file content and format

testStderrCapture  
  - System.err.println("stderr line")
  - Verify .log file content

testMixedCapture
  - Write to both stdout and stderr
  - Verify whether headers (=== STDOUT ===) are present
  - Python: no headers, stderr only
  - Scala: headers, both streams
```

**Why**: Log format differs between implementations. Affects `-L` output.

---

## 6. MetricsFormatTests

**Extend existing**: `MetricsTest.scala`

```
testMetricWithinTolerance
  - tmetric("value", 100.5, tolerance = 5.0) when snapshot has 98.0
  - Verify output format: should it show "98.0" (old value) or "100.5" (new value)?
  - Python: shows old value when within tolerance (keeps snapshot stable)

testMetricExceedsTolerance
  - tmetric("value", 200.0, tolerance = 5.0) when snapshot has 98.0
  - Verify output format includes "[exceeds!]" or similar marker
  - Python format: "200.0 (was 98.0, +102.0) [exceeds!]"

testMetricDirectionViolation
  - tmetric("latency", 150.0, direction = Some("max")) when snapshot has 100.0
  - Verify test fails with clear message about direction constraint
```

---

## Implementation Order

1. **ExceptionHandlingTests** - most critical, reveals #1 bug
2. **CacheRoundTripTests** - reveals #2 bug with complex types  
3. **DependencyFailureTests** - reveals #6 behavioral difference
4. **FilterBehaviorTests** - reveals #3 semantic mismatch (needs manual CLI verification)
5. **LogCaptureTests** - documents #8 format difference
6. **MetricsFormatTests** - extends existing coverage for #11

## Running the Tests

```bash
# Run all consistency tests
sbt "Test/runMain booktest.BooktestMain -v booktest.examples.ExceptionHandlingTests"
sbt "Test/runMain booktest.BooktestMain -v booktest.examples.CacheRoundTripTests"
sbt "Test/runMain booktest.BooktestMain -v booktest.examples.DependencyFailureTests"

# Filter test (manual verification)
sbt "Test/runMain booktest.BooktestMain -v -t Data booktest.examples.FilterBehaviorTests"

# First run will create initial snapshots (all DIFF)
# Review with: sbt "Test/runMain booktest.BooktestMain -i booktest.examples.ExceptionHandlingTests"
```
