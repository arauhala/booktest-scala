# Missing .bin causes the dep to run even without -r

.bin removed: true
after filter -t consumer (with .bin missing): producer=2
  results in run: booktest/test/RefreshDepsHelper/producer, booktest/test/RefreshDepsHelper/consumer
PASS: missing .bin promoted the dep into the run list
