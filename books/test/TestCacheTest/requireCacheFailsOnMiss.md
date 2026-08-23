# --require-cache fails on a miss, and says how to fix it

passed: false
state : FAIL
[OK] a miss under --require-cache does NOT pass: PASS
[OK] the failure names the flag that writes the manifest: PASS
[OK] the failure names the file to commit: PASS
[OK] --require-cache writes no entries itself: PASS

failure message:
  No valid cache entry for booktest.test.CachePasser/stable.
  
  This job does not run the suite; it accepts a PR on the proof that the
  suite passed locally, for exactly this tree and environment. No entry
  matches this fingerprint, so there is nothing to accept.
  
  Run the suite locally with the cache enabled and commit the manifest
  it writes (running more than this one case is fine — one manifest
  holds them all):
  
      booktest -C booktest.test.CachePasser
      git add '**/_test-cache.manifest' && git commit
  
  An entry is invalidated by a change to the main code or dependencies (which
  invalidates all of them), to the test code this suite reaches (which
  invalidates this suite), to this case's accepted snapshots, or to
  locale/timezone/parallelism.
