package booktest.examples

import booktest._

/**
 * Tests for computation snapshotting with automatic cache invalidation.
 * Similar to Python booktest's caching and function snapshotting.
 */
class SnapshotCacheTest extends TestSuite {

  /** Test basic snapshot caching */
  def testBasicSnapshot(t: TestCaseRun): Unit = {
    t.h1("Basic Snapshot Caching")

    // This expensive computation is cached
    val result = t.snapshot("expensive_computation") {
      t.iln("Computing expensive value...")
      Thread.sleep(50)  // Simulate expensive work
      42
    }

    t.tln(s"Result: $result")
    t.tln("Computation complete")
  }

  /** Test snapshot with arguments for cache invalidation */
  def testSnapshotWithArgs(t: TestCaseRun): Unit = {
    t.h1("Snapshot with Arguments")

    val config = "model_v2"
    val dataSize = 1000

    // Cache key includes arguments - changes if args change
    val result = t.snapshot("parameterized_computation", config, dataSize) {
      t.iln(s"Computing with config=$config, size=$dataSize")
      Thread.sleep(30)
      s"result_${config}_$dataSize"
    }

    t.tln(s"Computed: $result")
  }

  /** Test multiple snapshots in same test */
  def testMultipleSnapshots(t: TestCaseRun): Unit = {
    t.h1("Multiple Snapshots")

    // Note: snapshot() serializes complex types as strings, so use primitives
    // or handle complex types separately
    val dataSum = t.snapshot("load_data_sum") {
      t.iln("Loading data...")
      val data = Seq(1, 2, 3, 4, 5)
      data.sum  // Return primitive
    }

    val processedSum = t.snapshot("process_data_sum", dataSum) {
      t.iln("Processing data...")
      dataSum * 2  // Double the sum
    }

    val aggregated = t.snapshot("aggregate", processedSum) {
      t.iln("Aggregating...")
      processedSum + 100
    }

    t.tln(s"Data sum: $dataSum")
    t.tln(s"Processed sum: $processedSum")
    t.tln(s"Aggregated: $aggregated")
  }

  /** Test snapshot with complex arguments */
  def testSnapshotComplexArgs(t: TestCaseRun): Unit = {
    t.h1("Snapshot with Complex Arguments")

    case class ModelConfig(name: String, layers: Int, dropout: Double)
    val config = ModelConfig("transformer", 6, 0.1)

    // Args are converted to strings for hashing
    // Return a simple string representation of result (complex types serialize as strings)
    val result = t.snapshot("train_model", config.name, config.layers, config.dropout) {
      t.iln(s"Training ${config.name} with ${config.layers} layers...")
      Thread.sleep(20)
      "accuracy=0.95, loss=0.05"  // Return simple string instead of Map
    }

    t.tln(s"Training result: $result")
  }

  /** Test hasSnapshot and invalidation */
  def testSnapshotManagement(t: TestCaseRun): Unit = {
    t.h1("Snapshot Management")

    // Check if snapshot exists
    val exists = t.hasSnapshot("managed_value", "arg1")
    t.iln(s"Snapshot exists before: $exists")

    // Create snapshot
    val value = t.snapshot("managed_value", "arg1") {
      "computed_value"
    }

    t.tln(s"Value: $value")
    t.tln("Snapshot created and cached")
  }
}
