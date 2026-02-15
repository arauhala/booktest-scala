package booktest.examples

import booktest._

/**
 * Tests for temporary directory functionality.
 * Demonstrates tmpPath(), tmpFile(), and tmpDir() for creating temporary files
 * that persist between dependent tests but are cleared on re-run.
 */
class TmpDirTest extends TestSuite {

  // Test 1: Create data in a temp directory and return the path
  val createTmpData = test("createTmpData") { (t: TestCaseRun) =>
    t.h1("Create Temporary Data")

    // Create a temp directory for this test's data
    val dataDir = t.tmpDir("data")
    t.tln(s"Created temp directory")

    // Write some data files
    val dataFile = t.tmpFile("data/values.txt")
    val writer = new java.io.PrintWriter(dataFile)
    writer.println("value1=100")
    writer.println("value2=200")
    writer.println("value3=300")
    writer.close()
    t.tln("Wrote values.txt")

    // Also create a config file
    val configFile = t.tmpFile("config.json")
    val configWriter = new java.io.PrintWriter(configFile)
    configWriter.println("""{"setting": "test", "count": 3}""")
    configWriter.close()
    t.tln("Wrote config.json")

    // Return the tmp directory path for dependent tests
    t.tmpDirPath.toString
  }

  // Test 2: Use the temp directory from the previous test
  val useTmpData = test("useTmpData", createTmpData) { (t: TestCaseRun, tmpPath: String) =>
    t.h1("Use Temporary Data")

    val tmpDir = os.Path(tmpPath)
    t.tln(s"Received temp directory from dependency")

    // Verify the files exist
    val valuesFile = tmpDir / "data" / "values.txt"
    val configFile = tmpDir / "config.json"

    if (os.exists(valuesFile)) {
      t.tln("values.txt exists: OK")
      val content = os.read(valuesFile)
      val lines = content.split("\n").length
      t.tln(s"values.txt has $lines lines")
    } else {
      t.tln("values.txt exists: MISSING")
      t.fail("Expected values.txt to exist")
    }

    if (os.exists(configFile)) {
      t.tln("config.json exists: OK")
    } else {
      t.tln("config.json exists: MISSING")
      t.fail("Expected config.json to exist")
    }

    // Process the data and return a result
    "processed"
  }

  // Test 3: Simple temp file usage (no dependencies)
  def testTmpFile(t: TestCaseRun): Unit = {
    t.h1("Simple Temp File Usage")

    // Create a temp file
    val tempFile = t.tmpFile("scratch.txt")
    val writer = new java.io.PrintWriter(tempFile)
    writer.println("temporary data")
    writer.close()

    // Verify it exists
    if (tempFile.exists()) {
      t.tln("Temp file created successfully")
    } else {
      t.tln("Failed to create temp file")
      t.fail("Temp file creation failed")
    }

    // Read it back
    val content = scala.io.Source.fromFile(tempFile).mkString
    t.tln(s"Content length: ${content.length} chars")
  }

  // Test 4: Nested temp directories
  def testNestedTmpDirs(t: TestCaseRun): Unit = {
    t.h1("Nested Temp Directories")

    // Create nested structure
    val level1 = t.tmpDir("level1")
    val level2Path = t.tmpPath("level1/level2")
    os.makeDir.all(level2Path)
    val level3Path = t.tmpPath("level1/level2/level3")
    os.makeDir.all(level3Path)

    // Write a file in the deepest level
    val deepFile = t.tmpFile("level1/level2/level3/deep.txt")
    val writer = new java.io.PrintWriter(deepFile)
    writer.println("deep content")
    writer.close()

    t.tln("Created nested directories")
    t.tln(s"Deepest file exists: ${deepFile.exists()}")
  }
}
