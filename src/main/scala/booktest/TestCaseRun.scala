package booktest

import os.Path
import java.io.{BufferedReader, FileReader, PrintWriter, BufferedWriter, FileWriter}
import fansi.Color
import fansi.Color.{LightRed, LightYellow, LightCyan}

class TestCaseRun(
  val testName: String,
  val testPath: Path,       // Full path: suitePath / testName
  val outputDir: Path,      // Legacy: same as snapshotDir
  val snapshotDir: Path,    // books/<suite>/
  val outDir: Path,         // books/.out/<suite>/
  private val resourceManager: Option[ResourceManager] = None
) {
  // --- Output state ---
  private var outLine = ""                  // Current line being built
  private val outputBuffer = new StringBuilder  // Full output (for getOutput/getTestOutput)
  private var lineNumber = 0
  private var _failed = false
  private var _failMessage: Option[String] = None

  // --- Line markers (token-level diff tracking) ---
  // Each marker is (startPos, endPos, markerType) within the current line
  private var lineMarkers = List.empty[(Int, Int, String)]
  private var lineDiff: Option[Int] = None   // Position of first diff on current line
  private var lineError: Option[Int] = None  // Position of first error on current line

  // --- Statistics (like Python booktest) ---
  private var _diffs = 0       // Lines with test-content differences
  private var _errors = 0      // Lines with fail markers
  private var _infoDiffs = 0   // Lines with info-only differences

  def diffs: Int = _diffs
  def errors: Int = _errors
  def infoDiffs: Int = _infoDiffs

  // --- Inline diff report (colored, like Python booktest) ---
  private val diffReportBuffer = new StringBuilder
  private val DiffLineWidth = 60
  private def gray(text: String): String = "\u001b[38;2;140;140;140m" + text + "\u001b[0m"

  // --- Snapshot reader state ---
  private var expReader: BufferedReader = _
  private var expLine: String = _           // Current expected line (null if EOF/no snapshot)
  private var expTokens: BufferIterator[String] = _  // Token iterator for current expected line
  private var expLineNumber = 0
  private var expFileExists = false

  // --- Output file writer ---
  private var outWriter: PrintWriter = _
  private var _started = false

  // Test assets directory (for images, generated files)
  // Python style: books/<suite>/<test>/ for assets
  lazy val assetsDir: Path = {
    val dir = outDir / testName
    os.makeDir.all(dir)
    dir
  }

  // Temporary directory for this test (cleared on re-run, persists for dependent tests)
  // Python style: books/.out/<suite>/<test>/
  lazy val tmpDirPath: Path = outDir / testName

  // Return value cache file (for dependency injection between tests)
  // Python style: books/.out/<suite>/<test>.bin
  val binFile: Path = outDir / s"$testName.bin"

  // Snapshot cache directory for this test (for t.snapshot() cached computations)
  // These are stored inside the test's tmp directory
  private lazy val snapshotCacheDir: Path = {
    val dir = tmpDirPath / ".snapshots"
    os.makeDir.all(dir)
    dir
  }

  // HTTP/function snapshots file (Python style: <test>.snapshots.json)
  lazy val snapshotsFile: Path = outDir / s"$testName.snapshots.json"

  // Runtime flags (set by TestRunner)
  private var _recaptureAll: Boolean = false
  private var _updateSnapshots: Boolean = false

  /** Set runtime flags (called by TestRunner) */
  def setFlags(recaptureAll: Boolean, updateSnapshots: Boolean): Unit = {
    _recaptureAll = recaptureAll
    _updateSnapshots = updateSnapshots
  }

  // Pattern for standalone numbers (not part of identifiers like p99_latency)
  private val StandaloneNumberPattern = """^-?(\d+\.?\d*|\.\d+)([eE][+-]?\d+)?$""".r

  val outputFile: Path = outputDir / s"$testName.md"
  val snapshotFile: Path = snapshotDir / s"$testName.md"
  val outFile: Path = outDir / s"$testName.md"  // Results written here first
  val reportFile: Path = outDir / s"$testName.txt"  // Test reports
  val logFile: Path = outDir / s"$testName.log"  // Test logs

  // --- Lifecycle methods ---

  /** Initialize output file and snapshot reader. Called by TestRunner before test execution. */
  def start(): Unit = {
    if (_started) return
    _started = true

    // Open output file for incremental writing
    os.makeDir.all(outFile / os.up)
    outWriter = new PrintWriter(new BufferedWriter(new FileWriter(outFile.toIO)))

    // Open snapshot reader
    resetExpReader()
  }

  /** Finalize output. Called by TestRunner after test execution. */
  def end(): Unit = {
    // Flush any remaining content on current line
    if (outLine.nonEmpty) {
      writeLine()
    }
    // Close output file
    if (outWriter != null) {
      outWriter.close()
      outWriter = null
    }
    // Close snapshot reader
    closeExpReader()
  }

  // --- Snapshot reader methods ---

  /** Reset the snapshot file reader to beginning */
  private def resetExpReader(): Unit = {
    closeExpReader()
    expFileExists = os.exists(snapshotFile)
    if (expFileExists) {
      expReader = new BufferedReader(new FileReader(snapshotFile.toIO))
      expLine = null
      expTokens = null
      expLineNumber = 0
      nextExpLine()
    }
  }

  /** Close the snapshot reader */
  private def closeExpReader(): Unit = {
    if (expReader != null) {
      expReader.close()
      expReader = null
    }
    expLine = null
    expTokens = null
  }

  /** Advance snapshot to next line */
  private def nextExpLine(): Unit = {
    if (expReader == null) {
      expLine = null
      expTokens = null
      return
    }
    val line = expReader.readLine()
    if (line == null) {
      // EOF
      closeExpReader()
      expLine = null
      expTokens = null
    } else {
      expLineNumber += 1
      expLine = line
      expTokens = new BufferIterator[String](new TestTokenizer(expLine))
    }
  }

  /** Peek at next expected token without consuming it */
  def headExpToken: Option[String] = {
    if (expTokens != null && expTokens.hasNext) {
      expTokens.head
    } else if (expLine != null) {
      // End of tokens on this line → next token is newline
      Some("\n")
    } else {
      None
    }
  }

  /** Get next expected token and advance cursor */
  def nextExpToken: Option[String] = {
    if (expTokens != null && expTokens.hasNext) {
      Some(expTokens.next())
    } else if (expLine != null) {
      Some("\n")
    } else {
      None
    }
  }
  
  def h1(title: String): TestCaseRun = header(s"# $title")
  def h2(title: String): TestCaseRun = header(s"## $title")
  def h3(title: String): TestCaseRun = header(s"### $title")
  def h4(title: String): TestCaseRun = header(s"#### $title")
  def h5(title: String): TestCaseRun = header(s"##### $title")

  def tln: TestCaseRun = tln("")

  def tln(text: String = ""): TestCaseRun = {
    t(text)
    t("\n")
    this
  }

  def t(text: String): TestCaseRun = {
    ensureStarted()
    testFeed(text)
    this
  }

  def i(text: String): TestCaseRun = {
    ensureStarted()
    infoFeed(text)
    this
  }

  def iln: TestCaseRun = iln("")

  def iln(text: String = ""): TestCaseRun = {
    i(text)
    i("\n")
    this
  }

  /** Write fail content (always marks test as failed, included in snapshot) */
  def f(text: String): TestCaseRun = {
    ensureStarted()
    failFeed(text)
    this
  }

  def fln: TestCaseRun = fln("")

  def fln(text: String = ""): TestCaseRun = {
    f(text)
    f("\n")
    this
  }

  /** Mark current line as having a diff (entire line) */
  def diff(): TestCaseRun = {
    lineMarkers = lineMarkers :+ (0, 999999, "diff")
    if (lineDiff.isEmpty) lineDiff = Some(outLine.length)
    this
  }

  /** Mark current line as failed (entire line) */
  def fail(): TestCaseRun = {
    lineMarkers = lineMarkers :+ (0, 999999, "fail")
    if (lineError.isEmpty) lineError = Some(outLine.length)
    _failed = true
    this
  }

  /** Mark the test as failed without throwing an exception */
  def fail(message: String): TestCaseRun = {
    _failed = true
    _failMessage = if (message.nonEmpty) Some(message) else None
    fail()
  }

  /** Check if test has been marked as failed */
  def isFailed: Boolean = _failed

  /** Get the failure message if test was marked as failed */
  def failMessage: Option[String] = _failMessage

  /** Get a File in the test assets directory (Python style: books/<suite>/<test>/<name>) */
  def file(name: String): java.io.File = {
    val filePath = assetsDir / name
    os.makeDir.all(filePath / os.up)
    filePath.toIO
  }

  /** Get the test assets directory as a File (for creating subdirs, etc.) */
  def testDir: java.io.File = {
    os.makeDir.all(assetsDir)
    assetsDir.toIO
  }

  /** Get the test assets directory as an os.Path */
  def testOutDir: os.Path = {
    os.makeDir.all(assetsDir)
    assetsDir
  }

  // ============ Return Value Caching ============
  // The return value is cached in testName.bin for dependency injection

  /** Save return value to bin file */
  def saveReturnValue(value: Any): Unit = {
    try {
      val serialized = value match {
        case null => "NULL:"
        case _: Unit => "UNIT:"
        case s: String => s"STRING:$s"
        case i: Int => s"INT:$i"
        case l: Long => s"LONG:$l"
        case d: Double => s"DOUBLE:$d"
        case b: Boolean => s"BOOLEAN:$b"
        case other => s"OBJECT:${other.toString}"
      }
      os.write.over(binFile, serialized)
    } catch {
      case _: Exception => // Silently ignore serialization errors
    }
  }

  /** Load return value from bin file */
  def loadReturnValue[T]: Option[T] = {
    try {
      if (os.exists(binFile)) {
        val serialized = os.read(binFile)
        val result: Any = serialized.split(":", 2) match {
          case Array("NULL", _) => null
          case Array("UNIT", _) => ()
          case Array("STRING", value) => value
          case Array("INT", value) => value.toInt
          case Array("LONG", value) => value.toLong
          case Array("DOUBLE", value) => value.toDouble
          case Array("BOOLEAN", value) => value.toBoolean
          case Array("OBJECT", value) => value
          case _ => serialized
        }
        Some(result.asInstanceOf[T])
      } else {
        None
      }
    } catch {
      case _: Exception => None
    }
  }

  /** Check if return value is cached */
  def hasReturnValue: Boolean = os.exists(binFile)

  // ============ Temporary Directory Methods ============
  // These create files in the test's directory which:
  // - Persists between dependent test runs (can be passed via dependency injection)
  // - Gets deleted when the test is re-run (cleared by clearTmpDir())
  // - Gets deleted by --clean command

  /** Get a path in the test's temporary directory.
    * The tmp directory persists between dependent tests but is cleared on re-run.
    * Supports paths with '/' for subdirectories (e.g., "subdir/file.txt").
    */
  def tmpPath(name: String): os.Path = {
    os.makeDir.all(tmpDirPath)
    // Handle paths with '/' by splitting into segments
    val segments = name.split("/").filter(_.nonEmpty)
    segments.foldLeft(tmpDirPath)(_ / _)
  }

  /** Get a File in the test's temporary directory (alias for tmpPath).
    * The tmp directory persists between dependent tests but is cleared on re-run.
    * Supports paths with '/' for subdirectories (e.g., "subdir/file.txt").
    */
  def tmpFile(name: String): java.io.File = {
    val path = tmpPath(name)
    // Ensure parent directories exist
    os.makeDir.all(path / os.up)
    path.toIO
  }

  /** Create a subdirectory in the test's temporary directory.
    * The tmp directory persists between dependent tests but is cleared on re-run.
    * Returns the path to the created directory.
    * Supports paths with '/' for nested directories (e.g., "level1/level2").
    */
  def tmpDir(name: String): os.Path = {
    val dir = tmpPath(name)
    os.makeDir.all(dir)
    dir
  }

  /** Clear the temporary directory (called before test re-runs).
    * This removes all files created by previous runs of this test.
    */
  def clearTmpDir(): Unit = {
    if (os.exists(tmpDirPath)) {
      os.remove.all(tmpDirPath)
    }
  }

  /** Output a labeled key-value pair as test line (like Python's key()) */
  def key(label: String, value: Any): TestCaseRun = {
    tln(s"$label: $value")
  }

  /** Output a metric with optional tolerance and direction constraint.
    *
    * @param label Metric name
    * @param value Current value
    * @param tolerance Absolute tolerance for value comparison (default 0.0)
    * @param direction Optional direction constraint: "min" (must not decrease), "max" (must not increase)
    */
  def tmetric(label: String, value: Double, tolerance: Double = 0.0,
              direction: Option[String] = None): TestCaseRun = {
    // Skip direction/tolerance checking when recapturing (creating fresh baseline)
    if (_recaptureAll) {
      tln(String.format(java.util.Locale.US, "%s: %.4f", label, value: java.lang.Double))
      return this
    }

    // Write label first (consumes label tokens from snapshot stream)
    t(s"$label: ")
    // Now peek at the next token which should be the number
    val oldValueOpt = peekDouble
    tmetricInternal(value, tolerance, direction, oldValueOpt)
  }

  /** Output a metric with percentage-based tolerance.
    *
    * @param label Metric name
    * @param value Current value
    * @param tolerancePct Tolerance as percentage (e.g., 5.0 for ±5%)
    * @param direction Optional direction constraint
    */
  def tmetricPct(label: String, value: Double, tolerancePct: Double,
                 direction: Option[String] = None): TestCaseRun = {
    // Skip tolerance checking when recapturing (creating fresh baseline)
    if (_recaptureAll) {
      tln(String.format(java.util.Locale.US, "%s: %.4f", label, value: java.lang.Double))
      return this
    }

    // Write label first (consumes label tokens from snapshot stream)
    t(s"$label: ")
    // Now peek at the next token which should be the number
    val oldValueOpt = peekDouble
    val tolerance = oldValueOpt.map(old => math.abs(old) * tolerancePct / 100.0).getOrElse(0.0)
    tmetricInternal(value, tolerance, direction, oldValueOpt)
  }

  /** Internal tmetric implementation that accepts pre-fetched old value.
    * Label is already written; this writes the value and newline. */
  private def tmetricInternal(value: Double, tolerance: Double,
                              direction: Option[String], oldValueOpt: Option[Double]): TestCaseRun = {
    // Check direction constraint
    val directionViolation = (direction, oldValueOpt) match {
      case (Some("min"), Some(oldValue)) if value < oldValue - tolerance =>
        Some(s" [REGRESSION: was $oldValue]")
      case (Some("max"), Some(oldValue)) if value > oldValue + tolerance =>
        Some(s" [REGRESSION: was $oldValue]")
      case _ => None
    }

    // Determine output value (use old if within tolerance)
    val outputValue = if (tolerance > 0.0) {
      oldValueOpt match {
        case Some(oldValue) if isWithinTolerance(value, oldValue, tolerance) =>
          oldValue
        case _ =>
          value
      }
    } else {
      value
    }

    val warning = directionViolation.getOrElse("")
    // Label is already written by caller; just write value + warning + newline
    tln(String.format(java.util.Locale.US, "%.4f%s", outputValue: java.lang.Double, warning))

    // Mark as failed if direction constraint violated
    directionViolation.foreach { msg =>
      _failed = true
      _failMessage = Some(msg)
    }

    this
  }

  /** Output a metric with optional tolerance as info line (not checked in snapshot) */
  def imetric(label: String, value: Double): TestCaseRun = {
    // Use US locale for consistent decimal point
    iln(String.format(java.util.Locale.US, "%s: %.4f", label, value: java.lang.Double))
  }

  // ============ Image Output Methods ============

  /** Output an image file as test content (reference stored in snapshot).
    * The image file is copied to the output directory and referenced by hash.
    */
  def timage(imageFile: java.io.File, caption: String = ""): TestCaseRun = {
    if (imageFile.exists()) {
      val hash = computeFileHash(imageFile)
      // Python style: store images without extension, in test assets directory
      val destFile = assetsDir / hash

      // Copy file to assets directory
      os.copy(os.Path(imageFile.toPath), destFile, replaceExisting = true)

      val captionText = if (caption.nonEmpty) s" ($caption)" else ""
      // Reference as testName/hash (relative to suite directory)
      tln(s"![image]($testName/$hash)$captionText")
    } else {
      tln(s"[missing image: ${imageFile.getName}]")
    }
    this
  }

  /** Output an image file as info content (not in snapshot). */
  def iimage(imageFile: java.io.File, caption: String = ""): TestCaseRun = {
    if (imageFile.exists()) {
      val hash = computeFileHash(imageFile)
      // Python style: store images without extension, in test assets directory
      val destFile = assetsDir / hash

      os.copy(os.Path(imageFile.toPath), destFile, replaceExisting = true)

      val captionText = if (caption.nonEmpty) s" ($caption)" else ""
      // Reference as testName/hash (relative to suite directory)
      iln(s"![image]($testName/$hash)$captionText")
    } else {
      iln(s"[missing image: ${imageFile.getName}]")
    }
    this
  }

  /** Compute SHA-256 hash of a file for deterministic naming */
  private def computeFileHash(file: java.io.File): String = {
    val md = java.security.MessageDigest.getInstance("SHA-256")
    val fis = new java.io.FileInputStream(file)
    try {
      val buffer = new Array[Byte](8192)
      var bytesRead = fis.read(buffer)
      while (bytesRead != -1) {
        md.update(buffer, 0, bytesRead)
        bytesRead = fis.read(buffer)
      }
      md.digest().map("%02x".format(_)).mkString.take(16)
    } finally {
      fis.close()
    }
  }

  /** Rename a file in the output directory to its content hash (for determinism) */
  def renameFileToHash(fileName: String): String = {
    val file = (outDir / fileName).toIO
    if (file.exists()) {
      val hash = computeFileHash(file)
      val ext = fileName.split("\\.").lastOption.getOrElse("")
      val newName = if (ext.nonEmpty) s"$hash.$ext" else hash
      val newFile = outDir / newName
      os.move(os.Path(file.toPath), newFile, replaceExisting = true)
      newName
    } else {
      fileName
    }
  }

  /** Snapshot an expensive computation with automatic cache invalidation.
    *
    * The result is cached on disk with a hash of the arguments.
    * If arguments change, the cache is automatically invalidated.
    * Use -S to force recomputation, -s to update snapshots.
    *
    * Example:
    * {{{
    * val model = t.snapshot("loadModel", modelPath, config) {
    *   loadExpensiveModel(modelPath, config)
    * }
    * t.tln(s"Model accuracy: ${model.evaluate(testData)}")
    * }}}
    *
    * @param name Unique name for this snapshot within the test
    * @param args Arguments that affect the computation (used for cache invalidation)
    * @param compute The expensive computation to cache
    * @return The computed or cached result
    */
  def snapshot[T](name: String, args: Any*)(compute: => T): T = {
    val argsHash = computeArgsHash(args)
    val cacheFile = snapshotCacheDir / s"$name.snapshot"
    val hashFile = snapshotCacheDir / s"$name.hash"

    // Check if we should use cached value
    val shouldRecompute = _recaptureAll || !os.exists(cacheFile) || !os.exists(hashFile) || {
      val storedHash = os.read(hashFile).trim
      storedHash != argsHash
    }

    if (shouldRecompute) {
      // Compute and cache
      val result = compute
      val serialized = serializeValue(result)
      os.write.over(cacheFile, serialized)
      os.write.over(hashFile, argsHash)
      result
    } else {
      // Load from cache
      val serialized = os.read(cacheFile)
      deserializeValue[T](serialized)
    }
  }

  /** Snapshot with no arguments (simple named cache) */
  def snapshot[T](name: String)(compute: => T): T = {
    snapshot[T](name, ())(compute)
  }

  /** Compute a stable hash of arguments for cache invalidation */
  private def computeArgsHash(args: Seq[Any]): String = {
    val content = args.map {
      case null => "null"
      case arr: Array[_] => arr.map(_.toString).mkString("[", ",", "]")
      case seq: Seq[_] => seq.map(_.toString).mkString("[", ",", "]")
      case other => other.toString
    }.mkString("|")

    // Use SHA-256 for stable hashing
    val md = java.security.MessageDigest.getInstance("SHA-256")
    val hashBytes = md.digest(content.getBytes("UTF-8"))
    hashBytes.map("%02x".format(_)).mkString.take(16)  // First 16 chars
  }

  /** Serialize a value for disk storage */
  private def serializeValue[T](value: T): String = value match {
    case null => "NULL:"
    case _: Unit => "UNIT:"
    case s: String => s"STRING:$s"
    case i: Int => s"INT:$i"
    case l: Long => s"LONG:$l"
    case d: Double => s"DOUBLE:$d"
    case f: Float => s"FLOAT:$f"
    case b: Boolean => s"BOOLEAN:$b"
    case arr: Array[Byte] => s"BYTES:${java.util.Base64.getEncoder.encodeToString(arr)}"
    case other => s"OBJECT:${other.toString}"
  }

  /** Deserialize a value from disk storage */
  private def deserializeValue[T](serialized: String): T = {
    val result: Any = serialized.split(":", 2) match {
      case Array("NULL", _) => null
      case Array("UNIT", _) => ()
      case Array("STRING", value) => value
      case Array("INT", value) => value.toInt
      case Array("LONG", value) => value.toLong
      case Array("DOUBLE", value) => value.toDouble
      case Array("FLOAT", value) => value.toFloat
      case Array("BOOLEAN", value) => value.toBoolean
      case Array("BYTES", value) => java.util.Base64.getDecoder.decode(value)
      case Array("OBJECT", value) => value  // Returns as String, caller must handle
      case _ => serialized
    }
    result.asInstanceOf[T]
  }

  /** Check if a snapshot exists and is valid */
  def hasSnapshot(name: String, args: Any*): Boolean = {
    val argsHash = computeArgsHash(args)
    val cacheFile = snapshotCacheDir / s"$name.snapshot"
    val hashFile = snapshotCacheDir / s"$name.hash"

    os.exists(cacheFile) && os.exists(hashFile) && {
      val storedHash = os.read(hashFile).trim
      storedHash == argsHash
    }
  }

  /** Invalidate a specific snapshot */
  def invalidateSnapshot(name: String): Unit = {
    val cacheFile = snapshotCacheDir / s"$name.snapshot"
    val hashFile = snapshotCacheDir / s"$name.hash"
    if (os.exists(cacheFile)) os.remove(cacheFile)
    if (os.exists(hashFile)) os.remove(hashFile)
  }

  /** Invalidate all snapshots for this test */
  def invalidateAllSnapshots(): Unit = {
    if (os.exists(snapshotCacheDir)) {
      os.list(snapshotCacheDir).foreach(os.remove)
    }
  }

  // ============ Review Workflow Methods ============

  // Review state
  private var _inReview = false
  private val reviewItems = scala.collection.mutable.ListBuffer[(String, String, Boolean)]()

  /** Start a review section for AI-assisted evaluation.
    * Items added with reviewln() will be collected for batch review.
    *
    * Example:
    * {{{
    * t.startReview()
    * t.reviewln("Model output quality", modelOutput)
    * t.reviewln("Response time acceptable", s"${responseTime}ms < 100ms")
    * t.endReview()  // Triggers interactive review if any items need attention
    * }}}
    */
  def startReview(): TestCaseRun = {
    _inReview = true
    reviewItems.clear()
    tln("")
    tln("## Review Section")
    this
  }

  /** Add an item for review.
    * @param label Description of what to review
    * @param value The value or content to review
    * @param passed Optional: pre-computed pass/fail (None = needs human review)
    */
  def reviewln(label: String, value: String, passed: Option[Boolean] = None): TestCaseRun = {
    val status = passed match {
      case Some(true) => "[OK]"
      case Some(false) => "[FAIL]"
      case None => "[REVIEW]"
    }
    tln(s"$status $label: $value")

    // Track items that need review or failed
    if (passed.isEmpty || !passed.get) {
      reviewItems += ((label, value, passed.getOrElse(false)))
    }
    this
  }

  /** Shorthand for reviewln with boolean condition */
  def reviewln(label: String, condition: Boolean): TestCaseRun = {
    reviewln(label, if (condition) "PASS" else "FAIL", Some(condition))
  }

  /** End review section and trigger interactive review if needed.
    * Returns true if all items passed or were accepted.
    */
  def endReview(): Boolean = {
    _inReview = false
    val needsReview = reviewItems.filter { case (_, _, passed) => !passed }

    if (needsReview.isEmpty) {
      tln("")
      tln("All review items passed.")
      true
    } else {
      tln("")
      tln(s"${needsReview.size} item(s) need review.")

      // In non-interactive mode, just report
      // In interactive mode, TestRunner will handle the review
      false
    }
  }

  /** Get items that need review (for TestRunner to handle interactively) */
  def getReviewItems: Seq[(String, String, Boolean)] = reviewItems.toSeq

  /** Check if currently in a review section */
  def inReview: Boolean = _inReview

  // ============ Async/ExecutionContext Support ============

  /** Run an async block and wait for completion.
    * Useful for tests that need to await Futures.
    *
    * Example:
    * {{{
    * val result = t.await {
    *   for {
    *     data <- fetchDataAsync()
    *     processed <- processAsync(data)
    *   } yield processed
    * }
    * t.tln(s"Result: $result")
    * }}}
    */
  def await[T](future: scala.concurrent.Future[T],
               timeout: scala.concurrent.duration.Duration = scala.concurrent.duration.Duration.Inf): T = {
    scala.concurrent.Await.result(future, timeout)
  }

  /** Provide an ExecutionContext for async operations within the test.
    * Uses a cached thread pool suitable for blocking I/O.
    */
  lazy val executionContext: scala.concurrent.ExecutionContext = {
    scala.concurrent.ExecutionContext.fromExecutor(
      java.util.concurrent.Executors.newCachedThreadPool()
    )
  }

  /** Implicit execution context for convenience */
  implicit def ec: scala.concurrent.ExecutionContext = executionContext

  /** Run an async block with the test's ExecutionContext.
    *
    * Example:
    * {{{
    * val result = t.async {
    *   implicit ec =>
    *     for {
    *       a <- Future(computeA())
    *       b <- Future(computeB())
    *     } yield a + b
    * }
    * t.tln(s"Result: $result")
    * }}}
    */
  def async[T](block: scala.concurrent.ExecutionContext => scala.concurrent.Future[T],
               timeout: scala.concurrent.duration.Duration = scala.concurrent.duration.Duration.Inf): T = {
    val future = block(executionContext)
    await(future, timeout)
  }

  // ============ Table Output Methods ============

  /** Output a table as markdown (test output - checked in snapshot).
    * @param headers Column headers
    * @param rows Data rows (each row is a sequence of cell values)
    */
  def ttable(headers: Seq[String], rows: Seq[Seq[Any]]): TestCaseRun = {
    testFeed(formatMarkdownTable(headers, rows))
    this
  }

  /** Output a table as markdown (info output - not checked in snapshot). */
  def itable(headers: Seq[String], rows: Seq[Seq[Any]]): TestCaseRun = {
    infoFeed(formatMarkdownTable(headers, rows))
    this
  }

  /** Output a map as a two-column table (test output). */
  def ttable(data: Map[String, Any]): TestCaseRun = {
    val headers = Seq("Key", "Value")
    val rows = data.toSeq.sortBy(_._1).map { case (k, v) => Seq(k, v) }
    ttable(headers, rows)
  }

  /** Output a map as a two-column table (info output). */
  def itable(data: Map[String, Any]): TestCaseRun = {
    val headers = Seq("Key", "Value")
    val rows = data.toSeq.sortBy(_._1).map { case (k, v) => Seq(k, v) }
    itable(headers, rows)
  }

  /** Output a sequence of case class instances as a table (test output).
    * Uses reflection to extract field names and values.
    */
  def ttable[T <: Product](items: Seq[T]): TestCaseRun = {
    if (items.isEmpty) {
      tln("(empty table)")
    } else {
      val headers = getProductFieldNames(items.head)
      val rows = items.map(item => item.productIterator.toSeq)
      ttable(headers, rows)
    }
  }

  /** Output a sequence of case class instances as a table (info output). */
  def itable[T <: Product](items: Seq[T]): TestCaseRun = {
    if (items.isEmpty) {
      iln("(empty table)")
    } else {
      val headers = getProductFieldNames(items.head)
      val rows = items.map(item => item.productIterator.toSeq)
      itable(headers, rows)
    }
  }

  /** Get field names from a Product (case class) using reflection.
    * Works across Scala 2.12, 2.13, and 3.x.
    */
  private def getProductFieldNames(product: Product): Seq[String] = {
    try {
      // Try Scala 2.13+ productElementNames first
      val method = product.getClass.getMethod("productElementNames")
      method.invoke(product).asInstanceOf[Iterator[String]].toSeq
    } catch {
      case _: NoSuchMethodException =>
        // Fall back to reflection for Scala 2.12
        val clazz = product.getClass
        clazz.getDeclaredFields
          .filterNot(_.getName.startsWith("$"))
          .map(_.getName)
          .toSeq
    }
  }

  private def formatMarkdownTable(headers: Seq[String], rows: Seq[Seq[Any]]): String = {
    if (headers.isEmpty) return "(empty table)\n"

    val allRows = Seq(headers.map(_.toString)) ++ rows.map(_.map(cellToString))

    // Calculate column widths
    val colWidths = headers.indices.map { col =>
      allRows.map(row => if (col < row.length) row(col).length else 0).max.max(3)
    }

    val sb = new StringBuilder
    sb.append("\n")

    // Header row
    sb.append("| ")
    headers.zipWithIndex.foreach { case (h, i) =>
      sb.append(h.padTo(colWidths(i), ' '))
      sb.append(" | ")
    }
    sb.append("\n")

    // Separator row
    sb.append("| ")
    colWidths.foreach { w =>
      sb.append("-" * w)
      sb.append(" | ")
    }
    sb.append("\n")

    // Data rows
    rows.foreach { row =>
      sb.append("| ")
      headers.indices.foreach { i =>
        val cell = if (i < row.length) cellToString(row(i)) else ""
        sb.append(cell.padTo(colWidths(i), ' '))
        sb.append(" | ")
      }
      sb.append("\n")
    }

    sb.toString
  }

  private def cellToString(value: Any): String = value match {
    case null => ""
    case None => ""
    case Some(v) => cellToString(v)
    // Use US locale for consistent decimal point
    case d: Double => String.format(java.util.Locale.US, "%.4f", d: java.lang.Double)
    case f: Float => String.format(java.util.Locale.US, "%.4f", f: java.lang.Float)
    case other => other.toString
  }

  /** Execute block, print elapsed time as info line, return block result */
  def iMsLn[T](block: => T): T = {
    iMsLn("")(block)
  }

  /** Execute block with label, print elapsed time as info line, return block result */
  def iMsLn[T](label: String)(block: => T): T = {
    val start = System.currentTimeMillis()
    val result = block
    val elapsed = System.currentTimeMillis() - start
    if (label.nonEmpty) {
      iln(s"$label: ${elapsed}ms")
    } else {
      iln(s"${elapsed}ms")
    }
    result
  }

  /** Execute block, return (elapsed_ms, result) tuple */
  def ms[T](block: => T): (Long, T) = {
    val start = System.currentTimeMillis()
    val result = block
    val elapsed = System.currentTimeMillis() - start
    (elapsed, result)
  }

  /** Execute block once, print us/operation as info line, return result.
    * opCount is the number of operations performed within the block (for calculating us/op). */
  def iUsPerOpLn[T](opCount: Int, label: String = "")(block: => T): T = {
    val start = System.nanoTime()
    val result: T = block
    val elapsed = System.nanoTime() - start
    val usPerOp = elapsed / 1000.0 / opCount
    // Use US locale for consistent decimal point
    if (label.nonEmpty) {
      iln(String.format(java.util.Locale.US, "%s: %.2f us/op", label, usPerOp: java.lang.Double))
    } else {
      iln(String.format(java.util.Locale.US, "%.2f us/op", usPerOp: java.lang.Double))
    }
    result
  }

  /** Execute block once, print us/operation as test line, return result.
    * opCount is the number of operations performed within the block (for calculating us/op). */
  def tUsPerOpLn[T](opCount: Int, label: String = "")(block: => T): T = {
    val start = System.nanoTime()
    val result: T = block
    val elapsed = System.nanoTime() - start
    val usPerOp = elapsed / 1000.0 / opCount
    // Use US locale for consistent decimal point
    if (label.nonEmpty) {
      tln(String.format(java.util.Locale.US, "%s: %.2f us/op", label, usPerOp: java.lang.Double))
    } else {
      tln(String.format(java.util.Locale.US, "%.2f us/op", usPerOp: java.lang.Double))
    }
    result
  }

  /** Output a long value with unit as test line.
    * If tolerance is set (0.0 to 1.0), the value is compared against the previous
    * snapshot value. If within tolerance, the old value is written to keep the
    * snapshot stable. If outside tolerance, the new value is written, causing a diff. */
  def tLongLn(value: Long, unit: String = "", max: Long = Long.MaxValue,
              tolerance: Double = 0.0): TestCaseRun = {
    val suffix = if (unit.nonEmpty) s" $unit" else ""
    val warning = if (value > max) " [EXCEEDED]" else ""
    // Skip tolerance checking when recapturing (creating fresh baseline)
    val outputValue = if (_recaptureAll || tolerance <= 0.0) {
      value
    } else {
      peekLong match {
        case Some(oldValue) if isWithinTolerance(value.toDouble, oldValue.toDouble, tolerance) =>
          oldValue  // within tolerance, keep old value stable
        case _ =>
          value  // outside tolerance or no previous value
      }
    }
    tln(s"$outputValue$suffix$warning")
  }

  /** Output a double value with unit as test line.
    * If tolerance is set (0.0 to 1.0), the value is compared against the previous
    * snapshot value. If within tolerance, the old value is written to keep the
    * snapshot stable. If outside tolerance, the new value is written, causing a diff. */
  def tDoubleLn(value: Double, unit: String = "", max: Double = Double.MaxValue,
                tolerance: Double = 0.0): TestCaseRun = {
    val suffix = if (unit.nonEmpty) s" $unit" else ""
    val warning = if (value > max) " [EXCEEDED]" else ""
    // Skip tolerance checking when recapturing (creating fresh baseline)
    val outputValue = if (_recaptureAll || tolerance <= 0.0) {
      value
    } else {
      peekDouble match {
        case Some(oldValue) if isWithinTolerance(value, oldValue, tolerance) =>
          oldValue
        case _ =>
          value
      }
    }
    // Use US locale for consistent decimal point
    tln(String.format(java.util.Locale.US, "%.2f%s%s", outputValue: java.lang.Double, suffix, warning))
  }

  /** Check if value is within relative tolerance of reference */
  private def isWithinTolerance(value: Double, reference: Double, tolerance: Double): Boolean = {
    if (reference == 0.0) {
      value == 0.0
    } else {
      math.abs(value - reference) / math.abs(reference) <= tolerance
    }
  }

  /** Assert condition, output result as test line */
  def assertln(condition: Boolean): TestCaseRun = {
    if (condition) {
      tln("OK")
    } else {
      tln("FAILED")
      fail("Assertion failed")
    }
  }

  /** Assert condition with label, output result as test line */
  def assertln(label: String, condition: Boolean): TestCaseRun = {
    if (condition) {
      tln(s"$label: OK")
    } else {
      tln(s"$label: FAILED")
      fail(s"Assertion failed: $label")
    }
  }

  /** Peek at next expected token in snapshot, try to parse as Double.
    * Scans forward through tokens to find the next number. */
  def peekDouble: Option[Double] = {
    findNextNumber.flatMap { token =>
      try Some(token.toDouble)
      catch { case _: NumberFormatException => None }
    }
  }

  /** Peek at next expected token in snapshot, try to parse as Long */
  def peekLong: Option[Long] = {
    findNextNumber.flatMap { token =>
      try Some(token.toLong)
      catch { case _: NumberFormatException => None }
    }
  }

  /** Peek at next expected token in snapshot as String */
  def peekToken: Option[String] = headExpToken

  /** Skip to next expected token in snapshot */
  def skipToken(): Unit = { nextExpToken }

  /** Find next numeric token in snapshot by scanning ahead WITHOUT consuming tokens.
    * Re-tokenizes the current expected line to peek without affecting the main token stream. */
  private def findNextNumber: Option[String] = {
    if (expLine == null) return None
    // Re-tokenize the full expected line to scan for numbers without consuming main tokens
    val tokens = new TestTokenizer(expLine)
    while (tokens.hasNext) {
      val token = tokens.next()
      if (StandaloneNumberPattern.findFirstIn(token).exists(_ == token)) {
        return Some(token)
      }
    }
    None
  }

  /** Ensure start() has been called (auto-start for backward compatibility) */
  private def ensureStarted(): Unit = {
    if (!_started) start()
  }

  // --- Header with anchor support ---

  private def header(headerText: String): TestCaseRun = {
    ensureStarted()
    // Add leading newline if there's already content (before seeking)
    if (outputBuffer.nonEmpty) {
      infoFeedToken("\n")
    }
    // Seek to matching line in snapshot (non-linear matching like Python booktest)
    seekLine(headerText)
    // Write header as tested content
    testFeed(headerText)
    testFeedToken("\n")
    // Blank line after header
    infoFeedToken("\n")
    this
  }

  // --- Anchor/seek system for non-linear snapshot matching ---

  /** Seek snapshot cursor to a line matching the predicate.
    * Scans forward from current position, wraps around if not found. */
  def seek(isLineOk: String => Boolean, begin: Int = 0, end: Int = Int.MaxValue): Unit = {
    if (expLine == null) return  // No snapshot

    val atLineNumber = expLineNumber
    // Scan forward
    while (expLine != null && !isLineOk(expLine) && expLineNumber < end) {
      nextExpLine()
    }
    // If not found forward and we didn't start from beginning, wrap around
    if ((expLine == null || !isLineOk(expLine)) && atLineNumber > begin) {
      resetExpReader()
      // Scan from beginning to where we started
      while (expLine != null && !isLineOk(expLine) && expLineNumber < atLineNumber) {
        nextExpLine()
      }
    }
  }

  /** Seek to exact line match in snapshot */
  def seekLine(anchor: String): Unit = {
    seek(line => line == anchor)
  }

  /** Seek to line starting with prefix in snapshot */
  def seekPrefix(prefix: String): Unit = {
    seek(line => line != null && line.startsWith(prefix))
  }

  /** Jump snapshot cursor to specific line number */
  def jump(targetLine: Int): Unit = {
    if (targetLine < expLineNumber) {
      resetExpReader()
    }
    while (expLine != null && expLineNumber < targetLine) {
      nextExpLine()
    }
  }

  /** Seek to prefix in snapshot, then write as tested content */
  def anchor(prefix: String): TestCaseRun = {
    seekPrefix(prefix)
    t(prefix)
  }

  /** Seek to exact line in snapshot, then write as tested content with newline */
  def anchorln(line: String): TestCaseRun = {
    seekLine(line)
    tln(line)
  }

  // --- Token-by-token feed system ---

  /** Core token feeding: compare against snapshot and build output line.
    * @param token The token to feed
    * @param check If true, differences are marked as 'diff' (test failure)
    * @param infoCheck If true, differences are marked as 'info' (noted but no failure)
    */
  private def feedToken(token: String, check: Boolean = false, infoCheck: Boolean = false): Unit = {
    val expToken = nextExpToken

    if (token == "\n") {
      commitLine()
    } else {
      val startPos = outLine.length
      outLine = outLine + token

      if (expFileExists && expToken.isDefined && token != expToken.get) {
        if (check) {
          lineMarkers = lineMarkers :+ (startPos, outLine.length, "diff")
          if (lineDiff.isEmpty) lineDiff = Some(startPos)
        } else if (infoCheck) {
          lineMarkers = lineMarkers :+ (startPos, outLine.length, "info")
        }
      } else if (expFileExists && expToken.isEmpty) {
        // Beyond end of snapshot
        if (check) {
          lineMarkers = lineMarkers :+ (startPos, outLine.length, "diff")
          if (lineDiff.isEmpty) lineDiff = Some(startPos)
        } else if (infoCheck) {
          lineMarkers = lineMarkers :+ (startPos, outLine.length, "info")
        }
      }
    }
  }

  private def testFeedToken(token: String): Unit = feedToken(token, check = true)
  private def infoFeedToken(token: String): Unit = feedToken(token, infoCheck = true)
  private def failFeedToken(token: String): Unit = {
    val startPos = outLine.length
    feedToken(token, check = false)
    val endPos = outLine.length
    if (token != "\n") {
      lineMarkers = lineMarkers :+ (startPos, endPos, "fail")
      if (lineError.isEmpty) lineError = Some(startPos)
    }
  }

  /** Tokenize text and feed each token as test content */
  private def testFeed(text: String): Unit = {
    val tokenizer = new TestTokenizer(text)
    while (tokenizer.hasNext) {
      testFeedToken(tokenizer.next())
    }
  }

  /** Tokenize text and feed each token as info content */
  private def infoFeed(text: String): Unit = {
    val tokenizer = new TestTokenizer(text)
    while (tokenizer.hasNext) {
      infoFeedToken(tokenizer.next())
    }
  }

  /** Tokenize text and feed each token as fail content */
  private def failFeed(text: String): Unit = {
    _failed = true
    val tokenizer = new TestTokenizer(text)
    while (tokenizer.hasNext) {
      failFeedToken(tokenizer.next())
    }
  }

  // --- Line commit system ---

  /** Flush current line: update stats, write to output file, advance snapshot */
  private def commitLine(): Unit = {
    val hasMarkers = lineMarkers.nonEmpty
    val hasLineMarkers = lineError.isDefined || lineDiff.isDefined

    val hasError = lineError.isDefined || lineMarkers.exists(_._3 == "fail")
    val hasDiff = lineDiff.isDefined || lineMarkers.exists(_._3 == "diff")
    val hasInfo = lineMarkers.exists(_._3 == "info")

    if (hasMarkers || hasLineMarkers) {
      if (hasError) {
        _errors += 1
      } else if (hasDiff) {
        _diffs += 1
      } else if (hasInfo) {
        _infoDiffs += 1
      }
    }

    // Build inline diff report line (like Python booktest)
    // Symbols: ! (red) = error/fail, ? (yellow) = content diff, . (cyan) = info-only diff
    val expectedStr = if (expLine != null) expLine else "EOF"
    if (hasError) {
      val padded = outLine.take(DiffLineWidth).padTo(DiffLineWidth, ' ')
      diffReportBuffer.append(s"${LightRed("!")} ${LightRed(padded)} | ${gray(expectedStr)}\n")
    } else if (hasDiff) {
      val padded = outLine.take(DiffLineWidth).padTo(DiffLineWidth, ' ')
      diffReportBuffer.append(s"${LightYellow("?")} ${LightYellow(padded)} | ${gray(expectedStr)}\n")
    } else if (hasInfo) {
      val padded = outLine.take(DiffLineWidth).padTo(DiffLineWidth, ' ')
      diffReportBuffer.append(s"${LightCyan(".")} ${LightCyan(padded)} | ${gray(expectedStr)}\n")
    } else {
      diffReportBuffer.append(s"  ${outLine.take(DiffLineWidth)}\n")
    }

    writeLine()

    // Clear line state
    lineMarkers = List.empty
    lineDiff = None
    lineError = None
  }

  /** Write current line to output file and advance snapshot */
  private def writeLine(): Unit = {
    outputBuffer.append(outLine)
    outputBuffer.append("\n")
    if (outWriter != null) {
      outWriter.println(outLine)
      outWriter.flush()
    }
    outLine = ""
    nextExpLine()
    lineNumber += 1
  }

  /** Get full output including all content */
  def getOutput: String = outputBuffer.toString

  /** Get full output — same as getOutput. All feeds write to snapshot. */
  def getTestOutput: String = outputBuffer.toString

  /** Get inline diff report with colored markers (like Python booktest).
    * Uses ! (red) for errors/diffs, . (cyan) for info-only diffs. */
  def getDiffReport: String = diffReportBuffer.toString

  /** Write output is now handled incrementally via start()/end().
    * This method is kept for backward compatibility but is a no-op if start() was called. */
  def writeOutput(): Unit = {
    if (!_started) {
      // Legacy path: write buffer to file
      os.makeDir.all(outFile / os.up)
      os.write.over(outFile, getOutput)
    }
    // If started, output was written incrementally — nothing to do
  }
  
  def writeReport(message: String): Unit = {
    os.makeDir.all(reportFile / os.up)
    os.write.over(reportFile, message)
  }
  
  def acceptSnapshot(): Unit = {
    // Move from .out to final snapshot location after review acceptance
    if (os.exists(outFile)) {
      os.makeDir.all(snapshotFile / os.up)
      os.copy.over(outFile, snapshotFile)
    }
  }
  
  def hasSnapshot: Boolean = os.exists(snapshotFile)
  
  def getSnapshot: Option[String] = {
    if (hasSnapshot) {
      Some(os.read(snapshotFile))
    } else {
      None
    }
  }
  
  /** Check if test output matches snapshot (based on token comparison stats) */
  def compareWithSnapshot(): Boolean = {
    _errors == 0 && _diffs == 0 && !_failed
  }

  // -- Port management (delegates to ResourceManager's PortPool) --

  /** Acquire a port from the resource manager's port pool */
  def acquirePort(): Int = resourceManager match {
    case Some(rm) => rm.ports.acquire()
    case None => throw new IllegalStateException("No ResourceManager configured - cannot acquire port")
  }

  /** Release a port back to the pool */
  def releasePort(port: Int): Unit = resourceManager.foreach(_.ports.release(port))

  /** Use a port with automatic release */
  def withPort[T](block: Int => T): T = resourceManager match {
    case Some(rm) => rm.ports.use(block)
    case None => throw new IllegalStateException("No ResourceManager configured - cannot acquire port")
  }
}
