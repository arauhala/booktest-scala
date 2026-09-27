# An OutOfMemoryError fails its case and is latched for the run's verdict


Pre-fix this case's error escaped `NonFatal`, so the case got no failing
verdict and the run stayed green. Both halves are asserted: the CASE must
fail, and the fact must be latched process-wide so a run whose fatal error
happened OUTSIDE any case body still cannot report success.


| case           | reported | 
| -------------- | -------- | 
| throwsFatal    | FAIL     | 
| runsAfterFatal | DIFF     | 

Latched fatal escapes:
  java.lang.OutOfMemoryError in case 'throwsFatal'

[OK] the throwing case produced a result at all: PASS
[OK] the throwing case FAILED: PASS
[OK] the whole run reports failure: PASS
[OK] the fatal error was latched for the run's verdict: PASS
[OK] the latch names the case, so a reader knows which book to distrust: PASS
[OK] the latch names the throwable type: PASS
[OK] the sibling case still ran: PASS
[OK] the sibling case was not aborted by the fatal error: PASS
[OK] the latch was restored outside the isolation: PASS
