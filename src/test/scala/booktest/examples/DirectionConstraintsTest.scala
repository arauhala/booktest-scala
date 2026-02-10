package booktest.examples

import booktest._

/**
 * Migrated from Python booktest: test/test_metrics.py (direction_constraints)
 * Tests direction constraints for metrics
 */
class DirectionConstraintsTest extends TestSuite {

  /** Test metrics that should not decrease (direction=min) */
  def testMinDirection(t: TestCaseRun): Unit = {
    t.h1("Min Direction Constraints")

    // Accuracy should not decrease
    t.tmetric("accuracy", 0.95, tolerance = 0.02, direction = Some("min"))
    t.tmetric("precision", 0.92, tolerance = 0.02, direction = Some("min"))
    t.tmetric("recall", 0.89, tolerance = 0.02, direction = Some("min"))

    t.tln("All min-direction metrics passed")
  }

  /** Test metrics that should not increase (direction=max) */
  def testMaxDirection(t: TestCaseRun): Unit = {
    t.h1("Max Direction Constraints")

    // Error rate should not increase
    t.tmetric("error_rate", 0.05, tolerance = 0.01, direction = Some("max"))
    t.tmetric("latency_ms", 45.0, tolerance = 5.0, direction = Some("max"))
    t.tmetric("memory_mb", 256.0, tolerance = 10.0, direction = Some("max"))

    t.tln("All max-direction metrics passed")
  }

  /** Test percentage-based tolerance */
  def testPercentageTolerance(t: TestCaseRun): Unit = {
    t.h1("Percentage Tolerance")

    // Allow ±5% variation
    t.tmetricPct("throughput", 1250.0, tolerancePct = 5.0)
    t.tmetricPct("requests_per_sec", 500.0, tolerancePct = 5.0)

    // ±10% for noisier metrics
    t.tmetricPct("cache_hit_rate", 0.85, tolerancePct = 10.0)

    t.tln("All percentage tolerance metrics passed")
  }

  /** Test combined direction and percentage tolerance */
  def testCombinedConstraints(t: TestCaseRun): Unit = {
    t.h1("Combined Constraints")

    // Accuracy with min direction and percentage tolerance
    t.tmetricPct("model_accuracy", 0.945, tolerancePct = 2.0, direction = Some("min"))

    // Latency with max direction and percentage tolerance
    t.tmetricPct("p99_latency", 120.0, tolerancePct = 10.0, direction = Some("max"))

    t.tln("Combined constraints passed")
  }
}
