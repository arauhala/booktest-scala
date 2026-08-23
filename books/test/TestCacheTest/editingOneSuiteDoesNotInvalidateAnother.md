# Editing one suite's test code invalidates that suite only

A per-case key folds in the test code that case's suite REACHES, so an
edit to one test file moves only the suites whose closure contains it.
What follows checks the three properties that have to hold for it to be useful.

Note this book prints RELATIONS, never digests: a digest of the test tree
changes whenever this very file is edited, so printing one would make the
snapshot diff on every unrelated change and teach the reviewer to ignore it.
[OK] the classpath carries compiled test code: PASS

## 1. Distinct suites get distinct digests

If two suites hashed alike, editing one would invalidate the other and the
granularity would be fictional.
[OK] CachePasser and CacheFailer hash differently: PASS

## 2. A suite that drives another DEPENDS on it

This test class instantiates both helper suites, so both must appear in its
closure. Miss that edge and editing a helper leaves a stale pass for its driver —
the under-approximation that makes a cache lie.
[OK] TestCacheTest's closure contains CachePasser: PASS
[OK] TestCacheTest's closure contains CacheFailer: PASS

## 3. ...but the dependency is not symmetric

The helper must NOT reach back into its driver. Without this asymmetry every
suite would transitively reach every other and the closure would be the module.
[OK] CachePasser's closure excludes TestCacheTest: PASS
[OK] the driver's closure is strictly larger than the helper's: PASS
