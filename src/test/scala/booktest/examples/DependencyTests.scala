package booktest.examples

import booktest.{TestSuite, TestCaseRun, dependsOn}

class DependencyTests extends TestSuite {
  
  def testCreateData(t: TestCaseRun): String = {
    t.h1("Create Data Test")
    val data = "processed_data_123"
    t.tln(s"Created data: $data")
    data
  }
  
  @dependsOn("createData")
  def testUseData(t: TestCaseRun): String = {
    t.h1("Use Data Test")
    t.tln("This test depends on createData and runs after it")
    t.tln("Processing the data further...")
    val result = "enhanced_data_456"
    t.tln(s"Generated result: $result")
    result
  }
  
  @dependsOn("createData", "useData")
  def testFinalStep(t: TestCaseRun): Unit = {
    t.h1("Final Step Test")
    t.tln("This test depends on both createData and useData")
    t.tln("All dependencies have completed successfully")
  }
}