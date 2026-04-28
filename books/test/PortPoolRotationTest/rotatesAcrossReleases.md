# PortPool acquires rotate, not stick to the lowest port

first three: 11050, 11051, 11052
after releasing 11050 and 11051, next acquire: 11053
drained never-used: List(11054, 11055)
first reused port: 11050 (should be 11050)
second reused port: 11051 (should be 11051)
