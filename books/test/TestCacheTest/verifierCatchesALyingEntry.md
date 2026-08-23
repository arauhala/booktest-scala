# The verifier catches an entry that claims a pass the code does not deliver

forged an entry claiming alwaysFails passes: true
with --cache, the forged claim is believed: passed=true
with --verify-cache, the claim is refused: passed=false
stale entries reported: booktest.test.CacheFailer/alwaysFails
the lying cache entry was deleted: true
