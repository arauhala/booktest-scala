# --require-stamps fails on a miss, and says how to fix it

passed: false
state : FAIL

failure message:
  No valid result stamp for booktest.test.StampPasser/stable.
  
  This job does not run the suite; it accepts a PR on the proof that the
  suite passed locally, for exactly this tree and environment. There is no
  stamp matching this input hash, so there is nothing to accept.
  
  Run it locally and commit the stamps it writes:
  
      booktest booktest.test.StampPasser/stable
      git add '**/*.stamp' && git commit
  
  A stamp is invalidated by any change to the compiled classpath (so any
  code change invalidates all of them, for now), to this case's accepted
  snapshots, or to locale/timezone/parallelism.
