# Printing after a capture goes on, not into a dead buffer

The corruption only bites AFTER a capture ends: the next thing printed
disappears. That is what swallowed the end-of-run summary.

Observed with an OUTER capture rather than by swapping System.out — the
multiplexer IS System.out, so replacing it would defeat the mechanism
under test and the assertions would hold for the wrong reason. Nesting
also exercises that an inner capture ending restores the outer one.
[OK] output during the inner capture went to the inner log: PASS
[OK] the inner capture did not leak into the outer one: PASS
[OK] output AFTER the inner capture still goes somewhere — the outer one: PASS
