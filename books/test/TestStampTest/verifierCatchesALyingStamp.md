# The verifier catches a stamp that claims a pass the code does not deliver

forged a stamp claiming alwaysFails passes: true
with --stamps, the forged claim is believed: passed=true
with --verify-stamps, the claim is refused: passed=false
stale stamps reported: booktest.test.StampFailer/alwaysFails
the lying stamp was deleted: true
