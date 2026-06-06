package booktest.test

import booktest._

/** Helper suite: writes one committed asset via file() and one scratch file
  * via tmpFile(). The asset must reach the committed snapshot dir; the scratch
  * file must not.
  */
class AssetAndTmpProducer extends TestSuite {
  def testWriteBoth(t: TestCaseRun): Unit = {
    t.tln("writing an asset and a scratch file")

    val asset = t.file("graph.txt")
    val aw = new java.io.PrintWriter(asset)
    aw.println("committed asset content")
    aw.close()

    val scratch = t.tmpFile("scratch.txt")
    val sw = new java.io.PrintWriter(scratch)
    sw.println("throwaway scratch content")
    sw.close()
  }
}

/** Meta test: verifies tmp files (tmpDir/tmpFile/tmpPath) live in a separate
  * `<test>.tmp/` dir from committed assets (`<test>/`), so accepting a snapshot
  * does not sweep scratch files into Git.
  */
class TmpDirSeparationTest extends TestSuite {

  private def quietConfig(tempDir: os.Path): RunConfig = {
    val logStream = new java.io.PrintStream(
      new java.io.FileOutputStream((tempDir / "runner.log").toIO))
    RunConfig(
      outputDir = tempDir,
      snapshotDir = tempDir,
      verbose = false,
      updateSnapshots = true, // -s: accept, so assets get copied into the snapshot dir
      output = logStream
    )
  }

  def testTmpDirIsSeparateFromAssetDir(t: TestCaseRun): Unit = {
    t.h1("tmp dir is separate from asset dir")

    val tempDir = t.tmpDir("separation")
    val runner = new TestRunner(quietConfig(tempDir))
    runner.runSuite(new AssetAndTmpProducer())

    // Suite path mirrors the package: booktest/test/AssetAndTmpProducer
    val suite = os.rel / "booktest" / "test" / "AssetAndTmpProducer"
    val test = "writeBoth"

    // Working dirs under .out: asset dir <test>/ and tmp dir <test>.tmp/
    val outAssetDir = tempDir / ".out" / suite / test
    val outTmpDir = tempDir / ".out" / suite / s"$test.tmp"

    t.tln(s"asset dir is <test>/         : ${outAssetDir.last}")
    t.tln(s"tmp dir is <test>.tmp/       : ${outTmpDir.last}")
    assert(outTmpDir.last.endsWith(".tmp"), "tmp dir must carry the .tmp suffix")
    assert(outAssetDir.last != outTmpDir.last, "asset dir and tmp dir must differ")

    val assetInAssetDir = os.exists(outAssetDir / "graph.txt")
    val scratchInTmpDir = os.exists(outTmpDir / "scratch.txt")
    val scratchInAssetDir = os.exists(outAssetDir / "scratch.txt")

    t.tln(s"asset graph.txt   in asset dir: $assetInAssetDir")
    t.tln(s"scratch.txt       in tmp dir  : $scratchInTmpDir")
    t.tln(s"scratch.txt       in asset dir: $scratchInAssetDir")
    assert(assetInAssetDir, "file() output must land in the asset dir")
    assert(scratchInTmpDir, "tmpFile() output must land in the tmp dir")
    assert(!scratchInAssetDir, "tmpFile() output must NOT land in the asset dir")

    // The committed snapshot dir gets the asset but never the scratch file.
    val committedDir = tempDir / suite / test
    val assetCommitted = os.exists(committedDir / "graph.txt")
    val scratchCommitted = os.exists(committedDir / "scratch.txt")

    t.tln(s"asset graph.txt   committed   : $assetCommitted")
    t.tln(s"scratch.txt       committed   : $scratchCommitted")
    assert(assetCommitted, "asset must be committed into the snapshot dir")
    assert(!scratchCommitted, "scratch file must NOT be committed into the snapshot dir")

    t.tln("PASS: tmp files stay out of the committed snapshot dir")
  }
}
