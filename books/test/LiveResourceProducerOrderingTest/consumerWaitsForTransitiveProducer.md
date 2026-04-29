# Live-resource consumer must wait for its transitive TestRef producer

run 1 (sequential, snapshots populated): match = true
run 2 (-p2): match = true
consumer path == producer path: true
run 2 consumer state: OK
