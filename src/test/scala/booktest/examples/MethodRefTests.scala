package booktest.examples

import booktest.{TestSuite, TestCaseRun}

class MethodRefTests extends TestSuite {
  
  val data = test("createData") { (t: TestCaseRun) =>
    t.h1("Create Data Test")
    val data = "processed_data_456"  // Changed value to create diff
    t.tln(s"Created data: $data")
    data
  }
  
  // Enhanced approach: Using MethodRef helper for cleaner syntax
  val data2 = test("testUseData", data) { (t: TestCaseRun, cachedData: String) =>
    t.h1("Use Data Test")
    t.tln(s"Using cached data from dependency: $cachedData")
    t.tln("Processing the data further...")
    val result = s"enhanced_${cachedData}"
    t.tln(s"Generated result: $result")
    result
  }
  
  // Multiple dependencies with MethodRef
  test("testFinalStep", data, data2) { (t: TestCaseRun, originalData: String, processedData: String) =>
    t.h1("Final Step Test")
    t.tln(s"Original data: $originalData")
    t.tln(s"Processed data: $processedData")
    t.tln("All dependencies have completed successfully")
  }
}