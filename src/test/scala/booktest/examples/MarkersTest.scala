package booktest.examples

import booktest._

/**
 * Migrated from Python booktest: test/test_token_markers.py
 * Tests test markers/tags for filtering and categorization
 */
class MarkersTest extends TestSuite {

  // Mark tests with tags
  mark("fastTest", TestMarkers.Fast, TestMarkers.Unit)
  mark("slowTest", TestMarkers.Slow, TestMarkers.Integration)
  mark("gpuTest", TestMarkers.GPU, TestMarkers.Slow)
  mark("networkTest", TestMarkers.Network, TestMarkers.Integration)

  def testFastTest(t: TestCaseRun): Unit = {
    t.h1("Fast Unit Test")
    t.tln(s"Markers: ${getMarkers("fastTest").mkString(", ")}")
    t.tln("This is a fast unit test")
  }

  def testSlowTest(t: TestCaseRun): Unit = {
    t.h1("Slow Integration Test")
    t.tln(s"Markers: ${getMarkers("slowTest").mkString(", ")}")

    // Simulate slow operation
    Thread.sleep(100)
    t.tln("This is a slow integration test")
  }

  def testGpuTest(t: TestCaseRun): Unit = {
    t.h1("GPU Test")
    t.tln(s"Markers: ${getMarkers("gpuTest").mkString(", ")}")
    t.tln("This test would require a GPU")
    t.iln("(Simulated - no actual GPU usage)")
  }

  def testNetworkTest(t: TestCaseRun): Unit = {
    t.h1("Network Test")
    t.tln(s"Markers: ${getMarkers("networkTest").mkString(", ")}")
    t.tln("This test would require network access")
    t.iln("(Simulated - no actual network calls)")
  }

  def testUnmarkedTest(t: TestCaseRun): Unit = {
    t.h1("Unmarked Test")
    t.tln(s"Markers: ${getMarkers("unmarkedTest").mkString(", ")}")
    t.tln("This test has no markers")
  }

  def testMarkerInspection(t: TestCaseRun): Unit = {
    t.h1("Marker Inspection")

    // Check markers programmatically
    t.tln(s"fastTest is fast: ${hasMarker("fastTest", TestMarkers.Fast)}")
    t.tln(s"slowTest is slow: ${hasMarker("slowTest", TestMarkers.Slow)}")
    t.tln(s"gpuTest needs GPU: ${hasMarker("gpuTest", TestMarkers.GPU)}")
    t.tln(s"networkTest needs network: ${hasMarker("networkTest", TestMarkers.Network)}")
  }
}
