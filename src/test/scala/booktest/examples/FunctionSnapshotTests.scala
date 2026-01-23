package booktest.examples

import booktest.*
import booktest.FunctionSnapshotting.*
import scala.util.Random

// Example expensive functions to snapshot
object ExpensiveFunctions {
  
  def calculatePi(iterations: Int): Double = {
    // Simulate expensive calculation
    Thread.sleep(10) // Small delay to simulate work
    val random = new Random(42) // Fixed seed for reproducibility
    var inside = 0
    for (_ <- 1 to iterations) {
      val x = random.nextDouble()
      val y = random.nextDouble()
      if (x * x + y * y <= 1.0) inside += 1
    }
    4.0 * inside / iterations
  }
  
  def fetchDataFromAPI(endpoint: String): String = {
    // Simulate API call
    Thread.sleep(20)
    s"""{"endpoint": "$endpoint", "data": [1, 2, 3], "timestamp": "2024-01-01T00:00:00Z"}"""
  }
  
  def processLargeDataset(size: Int): List[Int] = {
    // Simulate data processing
    Thread.sleep(15)
    (1 to size).map(_ * 2).toList
  }
}

class FunctionSnapshotTests extends TestSuite {

  def testBasicFunctionSnapshot(t: TestCaseRun): Unit = {
    t.h1("Basic Function Snapshot Test")
    
    withFunctionSnapshots(t) { manager =>
      // Create snapshottable versions of expensive functions
      val piCalculator = snapshottable("calculatePi") { () =>
        ExpensiveFunctions.calculatePi(1000)
      }
      
      val apiCaller = snapshottable("fetchData") { () =>
        ExpensiveFunctions.fetchDataFromAPI("users")
      }
      
      // Call functions through snapshot manager
      val pi = piCalculator.snapshot(manager)
      val userData = apiCaller.snapshot(manager)
      
      t.tln(s"Pi approximation: $pi")
      t.tln(s"API response: $userData")
      
      // Call again - should use cached results
      val pi2 = piCalculator.snapshot(manager)
      val userData2 = apiCaller.snapshot(manager)
      
      t.tln(s"Pi approximation (cached): $pi2")
      t.tln(s"API response (cached): $userData2")
    }
  }

  def testFunctionSnapshotWithArgs(t: TestCaseRun): Unit = {
    t.h1("Function Snapshot with Arguments Test")
    
    withFunctionSnapshots(t) { manager =>
      // Function with single argument
      val dataProcessor = snapshottable("processData") { (size: Int) =>
        ExpensiveFunctions.processLargeDataset(size)
      }
      
      // Test with different argument values
      val smallData = manager.snapshotFunction("processSmallData", 
        () => ExpensiveFunctions.processLargeDataset(5), 5)
      val mediumData = manager.snapshotFunction("processMediumData", 
        () => ExpensiveFunctions.processLargeDataset(10), 10)
      
      t.tln(s"Small dataset: ${smallData.take(3)}...")
      t.tln(s"Medium dataset: ${mediumData.take(3)}...")
      
      // Call with same arguments - should use cache
      val smallData2 = manager.snapshotFunction("processSmallData", 
        () => ExpensiveFunctions.processLargeDataset(5), 5)
      
      t.tln(s"Small dataset (cached): ${smallData2.take(3)}...")
    }
  }

  def testFunctionMocking(t: TestCaseRun): Unit = {
    t.h1("Function Mocking Test")
    
    withMockedFunctions(
      "calculatePi" -> (() => 3.14159),
      "fetchAPI" -> (() => """{"mocked": true, "data": "test"}""")
    ) { mocker =>
      
      // Use mocked functions
      val mockPi = mocker.callFunction("calculatePi", () => ExpensiveFunctions.calculatePi(1000))
      val mockAPI = mocker.callFunction("fetchAPI", () => ExpensiveFunctions.fetchDataFromAPI("test"))
      val realFunction = mocker.callFunction("realFunction", () => "This is not mocked")
      
      t.tln(s"Mocked Pi: $mockPi")
      t.tln(s"Mocked API: $mockAPI")
      t.tln(s"Real function: $realFunction")
    }
  }

  def testComplexFunctionScenario(t: TestCaseRun): Unit = {
    t.h1("Complex Function Scenario Test")
    
    withFunctionSnapshots(t) { manager =>
      // Simulate a complex workflow with multiple expensive operations
      
      t.tln("=== Step 1: Fetch Configuration ===")
      val config = manager.snapshotFunction("fetchConfig", 
        () => ExpensiveFunctions.fetchDataFromAPI("config"), "production")
      t.tln(s"Configuration: $config")
      
      t.tln("=== Step 2: Calculate Parameters ===")
      val params = manager.snapshotFunction("calculateParams", 
        () => ExpensiveFunctions.calculatePi(500))
      t.tln(s"Parameters: $params")
      
      t.tln("=== Step 3: Process Data ===")
      val processedData = manager.snapshotFunction("processMainData", 
        () => ExpensiveFunctions.processLargeDataset(20))
      t.tln(s"Processed ${processedData.length} items")
      
      t.tln("=== Step 4: Generate Report ===")
      val report = manager.snapshotFunction("generateReport", 
        () => s"Report: Config=${config.take(20)}..., Params=$params, Items=${processedData.length}")
      t.tln(s"Report: $report")
    }
  }
}