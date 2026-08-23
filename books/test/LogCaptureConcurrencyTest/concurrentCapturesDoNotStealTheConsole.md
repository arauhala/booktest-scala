# Concurrent captures leave the console intact

Four threads capture with deliberately overlapping lifetimes — the
interleaving that broke save-and-restore. Afterwards the console must
still be the console, and each thread's output must be in its own log.
[OK] System.out is the same stream after concurrent captures: PASS
[OK] every capture wrote its own log: PASS
[OK] no capture stole another thread's output: PASS
