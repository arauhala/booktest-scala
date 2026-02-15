package booktest.examples

import booktest.{TestSuite, TestCaseRun, DependsOn}

class DependencyTests extends TestSuite {

  def testCreateData(t: TestCaseRun): String = {
    t.h1("Create Data Test")
    val data = "processed_data_123"
    t.tln(s"Created data: $data")
    data
  }

  @DependsOn(Array("testCreateData"))
  def testUseData(t: TestCaseRun, cachedData: String): String = {
    t.h1("Use Data Test")
    t.tln(s"Using cached data from dependency: $cachedData")
    t.tln("Processing the data further...")
    val result = s"enhanced_${cachedData}"
    t.tln(s"Generated result: $result")
    result
  }

  @DependsOn(Array("testCreateData", "testUseData"))
  def testFinalStep(t: TestCaseRun, originalData: String, processedData: String): Unit = {
    t.h1("Final Step Test")
    t.tln(s"Original data: $originalData")
    t.tln(s"Processed data: $processedData")
    t.tln("All dependencies have completed successfully")
  }
}
