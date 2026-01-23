package booktest.examples

import booktest.*
import booktest.EnvSnapshotting.*

class EnvSnapshotTests extends TestSuite {

  def testEnvSnapshot(t: TestCaseRun): Unit = {
    t.h1("Environment Variable Snapshot Test")
    
    withEnvSnapshots(t, "USER", "HOME", "PATH") {
      val user = sys.env.get("USER").getOrElse("unknown")
      val home = sys.env.get("HOME").getOrElse("unknown")
      val pathExists = sys.env.contains("PATH")
      
      t.tln(s"User: $user")
      t.tln(s"Home: $home")
      t.tln(s"PATH exists: $pathExists")
    }
  }

  def testMockEnv(t: TestCaseRun): Unit = {
    t.h1("Mock Environment Variables Test")
    
    val originalUser = sys.env.get("TEST_USER")
    t.tln(s"Original TEST_USER: ${originalUser.getOrElse("not set")}")
    
    withEnvVars("TEST_USER" -> "test_user_123", "TEST_ENV" -> "testing") {
      val mockUser = sys.env.get("TEST_USER").getOrElse("not found")
      val testEnv = sys.env.get("TEST_ENV").getOrElse("not found")
      
      t.tln(s"Mocked TEST_USER: $mockUser")
      t.tln(s"Mocked TEST_ENV: $testEnv")
    }
    
    val restoredUser = sys.env.get("TEST_USER")
    t.tln(s"Restored TEST_USER: ${restoredUser.getOrElse("not set")}")
  }

  def testRemoveEnvVars(t: TestCaseRun): Unit = {
    t.h1("Remove Environment Variables Test")
    
    // First set a variable
    withEnvVars("TEMP_VAR" -> "temporary_value") {
      val tempValue = sys.env.get("TEMP_VAR").getOrElse("not found")
      t.tln(s"Temporary variable: $tempValue")
      
      // Then remove it
      withoutEnvVars("TEMP_VAR") {
        val removedValue = sys.env.get("TEMP_VAR")
        t.tln(s"Removed variable: ${removedValue.getOrElse("successfully removed")}")
      }
      
      val restoredValue = sys.env.get("TEMP_VAR").getOrElse("not found")
      t.tln(s"Restored variable: $restoredValue")
    }
  }

  def testComplexEnvScenario(t: TestCaseRun): Unit = {
    t.h1("Complex Environment Scenario Test")
    
    withEnvSnapshots(t, "USER", "HOME") {
      t.tln("=== Current Environment ===")
      val user = sys.env.get("USER").getOrElse("unknown")
      val home = sys.env.get("HOME").getOrElse("unknown")
      t.tln(s"Current USER: $user")
      t.tln(s"Current HOME: $home")
      
      withMockEnv(Map(
        "TEST_SCENARIO" -> Some("complex_test"),
        "USER" -> Some("mocked_user"),
        "REMOVED_VAR" -> None
      )) {
        t.tln("=== Mocked Environment ===")
        val mockedUser = sys.env.get("USER").getOrElse("not found")
        val scenario = sys.env.get("TEST_SCENARIO").getOrElse("not found")
        val removedVar = sys.env.get("REMOVED_VAR")
        
        t.tln(s"Mocked USER: $mockedUser")
        t.tln(s"Test scenario: $scenario")
        t.tln(s"Removed var: ${removedVar.getOrElse("successfully removed")}")
      }
      
      t.tln("=== Restored Environment ===")
      val restoredUser = sys.env.get("USER").getOrElse("unknown")
      val noScenario = sys.env.get("TEST_SCENARIO")
      t.tln(s"Restored USER: $restoredUser")
      t.tln(s"Test scenario cleaned up: ${noScenario.isEmpty}")
    }
  }
}