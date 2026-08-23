# A parallel run uses the cache, like a sequential one

Each check below is built so it FAILS if the parallel path ignores the
cache. That is not automatic: the obvious assertions ("the run passed",
"the gated run failed") hold either way, because a bypassed run simply
executes the tests and reaches the same verdict by doing the work.

## It WRITES entries

[OK] the parallel run passes: PASS
[OK] a parallel run writes entries: PASS

## It CONSULTS entries

Proven with a case that CANNOT pass: forge an entry for it, then run it
under -p4. A consulted cache skips it and reports a pass; a bypassed one
runs it and it fails. The verdict itself is the evidence.
[OK] a parallel run skips a case that has an entry: PASS

## The --require-cache gate still bites

With snapshots primed but NO manifest, these cases would pass if they ran.
So a failure here can only come from the gate refusing — not from the
tests themselves failing.
[OK] precondition: these cases pass when actually run: PASS
[OK] with no manifest, --require-cache refuses under -p4: PASS
[OK] and it refuses for the right reason: PASS
