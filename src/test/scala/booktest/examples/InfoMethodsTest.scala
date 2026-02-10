package booktest.examples

import booktest._

/**
 * Migrated from Python booktest: test/test_info_methods.py
 * Tests info output methods: itable, mixed info and tested output
 */
class InfoMethodsTest extends TestSuite {

  /** Test itable method for dictionary-based tables */
  def testItableMethod(t: TestCaseRun): Unit = {
    t.h1("Dataset Size Information")

    // Using Map for key-value table (like Python dict)
    val datasetSizes = Map(
      "train" -> 50000,
      "validation" -> 10000,
      "test" -> 10000
    )

    t.iln("Dataset split sizes:")
    t.itable(datasetSizes.map { case (k, v) => k -> v.asInstanceOf[Any] })

    t.tln("Total samples: 70000")
  }

  /** Test ttable method for structured tables */
  def testTtableMethod(t: TestCaseRun): Unit = {
    t.h1("Performance Metrics Table")

    // Table with headers and rows
    val headers = Seq("Metric", "Value", "Threshold")
    val rows = Seq(
      Seq("Accuracy", "0.95", ">0.90"),
      Seq("Precision", "0.92", ">0.85"),
      Seq("Recall", "0.89", ">0.80")
    )

    t.ttable(headers, rows)
    t.tln("All metrics within acceptable range")
  }

  /** Test case class table output */
  def testCaseClassTable(t: TestCaseRun): Unit = {
    t.h1("Case Class Table Output")

    case class Metric(name: String, value: Double, passed: Boolean)

    val metrics = Seq(
      Metric("accuracy", 0.95, true),
      Metric("precision", 0.92, true),
      Metric("recall", 0.89, true)
    )

    t.ttable(metrics)
    t.tln("Metrics displayed from case class")
  }

  /** Test mixed info and tested output */
  def testMixedInfoAndTested(t: TestCaseRun): Unit = {
    t.h1("Mixed Output Types")

    t.iln("Starting model evaluation...")  // Info only
    t.tln("Model: RandomForest v2.1")       // Tested

    // Info table (not in snapshot)
    t.iln("Hyperparameters:")
    t.itable(Map(
      "n_estimators" -> 100,
      "max_depth" -> 10,
      "min_samples_split" -> 2
    ).map { case (k, v) => k -> v.asInstanceOf[Any] })

    // Tested output
    t.tln("Final accuracy: 0.945")
    t.tln("Training complete")

    t.iln("Detailed logs available in output directory")
  }
}
