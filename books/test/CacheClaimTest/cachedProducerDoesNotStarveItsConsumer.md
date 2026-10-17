# A cached producer still runs when its value is needed and absent


Priming: accept snapshots, then one cached run so the manifest claims
every case passes. Then delete ONLY the producer's `.bin` — the value,
not the claim — and run again with the cache on.

producer .bin removed: true; consumer entry dropped, producer entry kept


| what                | value | 
| ------------------- | ----- | 
| producer executions | 1     | 
| consumer executions | 1     | 
| consumer passed     | true  | 
[OK] the consumer was scheduled, so this fixture is not vacuous: PASS
[OK] the consumer does not fail for want of a value it declared: PASS
[OK] the producer re-ran, because its value was needed and absent: PASS
