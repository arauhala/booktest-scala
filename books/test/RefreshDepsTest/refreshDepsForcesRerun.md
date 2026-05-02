# --refresh-deps forces transitive deps to re-run

after warm-up: producer=1
after filter -t consumer -r: producer=2
  results in run: booktest/test/RefreshDepsHelper/producer, booktest/test/RefreshDepsHelper/consumer
PASS: --refresh-deps re-executed the cached dep
