package booktest.examples

import booktest._

/**
 * Migrated from Python booktest: test/test_metrics.py
 * Tests metric tracking with tolerance
 */
class MetricsTest extends TestSuite {

  /** Test absolute tolerance for metrics */
  def testAbsoluteTolerance(t: TestCaseRun): Unit = {
    t.h1("Metrics with Absolute Tolerance")

    // Simulate metrics that might vary slightly between runs
    val accuracy = 0.9523 + (Math.random() * 0.01 - 0.005)  // ±0.005 noise
    val precision = 0.9187 + (Math.random() * 0.01 - 0.005)
    val recall = 0.8945 + (Math.random() * 0.01 - 0.005)

    // Use tmetric with tolerance - values within tolerance use snapshot value
    t.tmetric("accuracy", accuracy, tolerance = 0.02)
    t.tmetric("precision", precision, tolerance = 0.02)
    t.tmetric("recall", recall, tolerance = 0.02)
  }

  /** Test metrics with units */
  def testWithUnits(t: TestCaseRun): Unit = {
    t.h1("Metrics with Units")

    val accuracy = 95.0 + (Math.random() * 2 - 1)  // ±1% noise
    val latencyMs = 45.0 + (Math.random() * 5 - 2.5)
    val throughput = 1250.0 + (Math.random() * 50 - 25)

    // Using tDoubleLn with tolerance and unit
    t.tDoubleLn(accuracy, unit = "%", tolerance = 0.05)
    t.tDoubleLn(latencyMs, unit = "ms", tolerance = 0.1)
    t.tDoubleLn(throughput, unit = "req/s", tolerance = 0.05)
  }

  /** Test long metrics with tolerance */
  def testLongMetrics(t: TestCaseRun): Unit = {
    t.h1("Long Metrics")

    val totalSamples = 50000L + (Math.random() * 100).toLong
    val processedItems = 49850L + (Math.random() * 50).toLong

    t.tLongLn(totalSamples, unit = "samples", tolerance = 0.01)
    t.tLongLn(processedItems, unit = "processed", tolerance = 0.01)
  }

  /** Test ML pipeline example */
  def testMlPipelineExample(t: TestCaseRun): Unit = {
    t.h1("ML Model Evaluation")

    t.h2("Classification Metrics")

    // Simulate model evaluation results
    val accuracy = 0.9456
    val precision = 0.9234
    val recall = 0.9012
    val f1Score = 2 * precision * recall / (precision + recall)
    val aucRoc = 0.9678

    t.tmetric("accuracy", accuracy, tolerance = 0.02)
    t.tmetric("precision", precision, tolerance = 0.02)
    t.tmetric("recall", recall, tolerance = 0.02)
    t.tmetric("f1_score", f1Score, tolerance = 0.02)
    t.tmetric("auc_roc", aucRoc, tolerance = 0.02)

    t.h2("Performance Metrics")

    // These are info-only (timing varies)
    t.imetric("inference_time_ms", 12.5)
    t.imetric("memory_mb", 256.0)

    t.tln("Evaluation complete")
  }

  /** Test key-value output */
  def testKeyValueOutput(t: TestCaseRun): Unit = {
    t.h1("Key-Value Metrics")

    t.key("model", "XGBoost")
    t.key("version", "1.7.0")
    t.key("features", 128)
    t.key("training_samples", 50000)

    t.tln("Configuration logged")
  }
}
