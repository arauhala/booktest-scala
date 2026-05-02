# Cached deps are loaded from .bin instead of re-executed

after warm-up: producer=1 consumer=1
after filter -t consumer: producer=1 consumer=2
  results in run: booktest/test/RefreshDepsHelper/consumer
PASS: cached transitive dep was loaded from .bin, not re-executed
