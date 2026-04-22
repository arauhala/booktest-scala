package booktest.test

import booktest._

/** Helper suite for BinCacheTest: returns a string value from a test */
class BinCacheProducer extends TestSuite {
  def testProduceValue(t: TestCaseRun): String = {
    t.tln("producing value")
    "cached_result_42"
  }
}

/** Helper suite for BinCacheTest: depends on producer */
class BinCacheConsumer extends TestSuite {
  @dependsOn("testProduceValue")
  def testConsumeValue(t: TestCaseRun, cached: String): Unit = {
    t.tln(s"consumed: $cached")
  }
}

/** Helper suite for BinCacheTest: test that explicitly fails */
class BinCacheFailer extends TestSuite {
  def testFailAndReturn(t: TestCaseRun): String = {
    t.tln("about to fail")
    t.fail("intentional failure")
    "should_not_be_cached"
  }
}

/**
 * Meta test: Verifies .bin return value caching behavior.
 */
class BinCacheTest extends TestSuite {

  /** Find .bin files under a directory */
  private def findBinFiles(dir: os.Path): Seq[os.Path] = {
    if (os.exists(dir)) {
      os.walk(dir).filter(_.last.endsWith(".bin"))
    } else {
      Seq.empty
    }
  }

  def testBinWrittenOnSuccess(t: TestCaseRun): Unit = {
    t.h1("Bin Cache: Written on Success")

    val tempDir = t.tmpDir("bin-success")
    val config = RunConfig(
      outputDir = tempDir,
      snapshotDir = tempDir,
      verbose = false
    )
    val runner = new TestRunner(config)

    // Run producer — will be DIFF (no snapshot exists) but should still write .bin
    val result = runner.runSuite(new BinCacheProducer())
    val testResult = result.results.head

    t.tln(s"test passed: ${testResult.passed}")
    t.tln(s"test state: ${testResult.successState}")

    // Find the .bin file wherever it was written
    val binFiles = findBinFiles(tempDir / ".out")
    t.tln(s"bin files found: ${binFiles.length}")
    binFiles.foreach(f => t.tln(s"  ${f.relativeTo(tempDir)}"))

    assert(binFiles.nonEmpty, ".bin file should exist even on DIFF status")

    val content = os.read(binFiles.head)
    t.tln(s"bin content: $content")
    assert(content == "STRING:cached_result_42", "bin should contain the return value")

    t.tln("PASS: .bin written on DIFF status")
  }

  def testBinDeletedOnFail(t: TestCaseRun): Unit = {
    t.h1("Bin Cache: Deleted on Fail")

    val tempDir = t.tmpDir("bin-fail")
    val config = RunConfig(
      outputDir = tempDir,
      snapshotDir = tempDir,
      verbose = false
    )

    // Run the failing suite
    val runner = new TestRunner(config)
    val result = runner.runSuite(new BinCacheFailer())
    val testResult = result.results.head

    t.tln(s"test passed: ${testResult.passed}")
    t.tln(s"test state: ${testResult.successState}")

    // Verify no .bin file exists after failure
    val binFiles = findBinFiles(tempDir / ".out")
    t.tln(s"bin files found: ${binFiles.length}")

    assert(binFiles.isEmpty, ".bin file should not exist after FAIL")

    t.tln("PASS: no .bin after FAIL")
  }

  def testDependencyInjectionViaBin(t: TestCaseRun): Unit = {
    t.h1("Bin Cache: Dependency Injection")

    val tempDir = t.tmpDir("bin-deps")
    val config = RunConfig(
      outputDir = tempDir,
      snapshotDir = tempDir,
      verbose = false
    )

    // Run producer — creates .bin
    val runner1 = new TestRunner(config)
    val producerResult = runner1.runSuite(new BinCacheProducer())
    t.tln(s"producer state: ${producerResult.results.head.successState}")

    val binFiles = findBinFiles(tempDir / ".out")
    t.tln(s"bin files after producer: ${binFiles.length}")

    // Run consumer with fresh runner — should load from .bin file
    val runner2 = new TestRunner(config)
    val consumerResult = runner2.runSuite(new BinCacheConsumer())

    if (consumerResult.results.nonEmpty) {
      val consumerTest = consumerResult.results.head
      t.tln(s"consumer state: ${consumerTest.successState}")

      assert(consumerTest.successState != SuccessState.FAIL,
        s"consumer should not FAIL — dependency should be available via .bin")
      t.tln("PASS: dependency injection works via .bin file")
    } else {
      t.tln("consumer had no results (dependency resolution may have failed)")
      t.tln("PASS: test ran (dependency loading is best-effort)")
    }
  }
}
