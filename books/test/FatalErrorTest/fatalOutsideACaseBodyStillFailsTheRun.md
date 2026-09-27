# A fatal error with no case to blame still fails the run


This is the shape that actually happened. In the iodf run the
`OutOfMemoryError` came from `TestCaseRun.getTestOutput` — output
finalisation, outside any case's try — so no case-level catch could see
it, every case that finished reported `ok`, and the exit code stayed 0.

Modelled here by a spawned thread, which is the same situation from the
handler's point of view: a throwable reaching a thread's top level with no
enclosing case catch. The run's verdict must still turn red.

Latched from a thread with no case around it:
  java.lang.OutOfMemoryError on thread 'synthetic-fatal-thread'

[OK] the escape was latched even with no case to attribute it to: PASS
[OK] the latch names the throwable type: PASS
[OK] the latch names the thread, which is the only handle a reader has: PASS

`BooktestMain` reads exactly this list after `printSummary` and throws
`BooktestFailure` when it is non-empty, so a non-zero exit follows from
the latch being set — which is what makes the ok-reporting cases in that
iodf run unable to carry the run to green.
