
# Complex Function Scenario Test

=== Step 1: Fetch Configuration ===
Re-executed fetchConfig (snapshot exists)Configuration: {"endpoint": "config", "data": [1, 2, 3], "timestamp": "2024-01-01T00:00:00Z"}
=== Step 2: Calculate Parameters ===
Re-executed calculateParams (snapshot exists)Parameters: 3.072
=== Step 3: Process Data ===
Re-executed processMainData (snapshot exists)Processed 20 items
=== Step 4: Generate Report ===
Re-executed generateReport (snapshot exists)Report: Report: Config={"endpoint": "config..., Params=3.072, Items=20

# Function Call Snapshots

calculateParams() -> 3.072
fetchConfig(production) -> {"endpoint": "config", "data": [1, 2, 3], "timesta...
generateReport() -> Report: Config={"endpoint": "config..., Params=3.0...
processMainData() -> List(2, 4, 6, 8, 10, 12, 14, 16, 18, 20, 22, 24, 2...
