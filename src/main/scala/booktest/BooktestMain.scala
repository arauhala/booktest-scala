package booktest

/** Thrown to signal test failure without killing the JVM.
  * SBT's runner.run() catches exceptions and reports them as task failures.
  * This avoids sys.exit() which kills the SBT server during development. */
class BooktestFailure(message: String) extends RuntimeException(message)

object BooktestMain {

  def main(args: Array[String]): Unit = {
    var verbose = false
    var interactive = false
    var outputDir = "books"
    var snapshotDir = "books"
    var testFilter: Option[String] = None
    var listTests = false
    var showLogs = false
    var reviewMode = false
    var batchReview = false
    var treeView = false
    var diffMode: DiffMode = DiffMode.Unified
    var summaryMode = true // Python-style: diffs at end (default)
    var recaptureAll = false  // -S: force regenerate all snapshots
    var updateSnapshots = false  // -s: auto-accept snapshot changes
    var autoAcceptDiff = false  // -a: auto-accept DIFF tests (not FAIL)
    var continueMode = false  // -c: continue from last run, skip successful tests
    var refreshDeps = false  // -r: force re-run of transitive deps (default: load from .bin if present)
    var threads = 1  // -p N: number of threads for parallel execution
    var garbageMode = false  // --garbage: list orphan files
    var cleanMode = false  // --clean: remove orphan files and temp directories
    var rootOverride: Option[String] = None  // --root: package prefix to strip from paths
    var invalidateLiveOnFail = false  // --invalidate-live-on-fail
    var traceFlag = sys.env.contains("BOOKTEST_TRACE")  // --trace / BOOKTEST_TRACE=1
    var buildGraphMode = false  // -b / --build-graph: print task DAG with state
    var capacityOverrides = Map.empty[String, Double]  // --capacity name=value
    val testClasses = scala.collection.mutable.ListBuffer[String]()
    
    var i = 0
    while (i < args.length) {
      args(i) match {
        case "-v" | "--verbose" => verbose = true
        case "-i" | "--interactive" => interactive = true
        case "-l" | "--list" => listTests = true
        case "-L" | "--logs" => showLogs = true
        case "-w" | "--review" => reviewMode = true
        case "--batch-review" => batchReview = true
        case "--tree" => treeView = true
        case "--inline" => summaryMode = false  // Show diffs inline (old behavior)
        case "-S" | "--recapture" => recaptureAll = true  // Force regenerate all snapshots
        case "-s" | "--update" => updateSnapshots = true  // Auto-accept changes
        case "-a" | "--accept" => autoAcceptDiff = true  // Auto-accept DIFF tests
        case "-c" | "--continue" => continueMode = true  // Continue from last run
        case "-r" | "--refresh-deps" => refreshDeps = true  // Force re-run of transitive deps
        case "--garbage" => garbageMode = true  // List orphan files in books/
        case "--clean" => cleanMode = true  // Remove orphan files and .tmp directories
        case "--invalidate-live-on-fail" => invalidateLiveOnFail = true
        case "--trace" => traceFlag = true
        case "-b" | "--build-graph" => buildGraphMode = true
        case "--capacity" =>
          i += 1
          if (i < args.length) {
            args(i).split("=", 2) match {
              case Array(name, value) =>
                try capacityOverrides = capacityOverrides + (name -> value.toDouble)
                catch { case _: NumberFormatException =>
                  println(s"Invalid --capacity value: ${args(i)}")
                }
              case _ => println(s"Invalid --capacity format (expected name=value): ${args(i)}")
            }
          }
        case "-p" =>
          i += 1
          if (i < args.length) {
            try {
              threads = args(i).toInt
              if (threads < 1) threads = 1
            } catch {
              case _: NumberFormatException =>
                println(s"Invalid thread count: ${args(i)}, using 1")
                threads = 1
            }
          }
        case arg if arg.startsWith("-p") && arg.length > 2 && arg(2).isDigit =>
          // Handle -p4, -p8, etc.
          try {
            threads = arg.substring(2).toInt
            if (threads < 1) threads = 1
          } catch {
            case _: NumberFormatException =>
              println(s"Invalid thread count in: $arg, using 1")
              threads = 1
          }
        case "--diff-style" =>
          i += 1
          if (i < args.length) {
            args(i).toLowerCase match {
              case "unified" => diffMode = DiffMode.Unified
              case "side-by-side" => diffMode = DiffMode.SideBySide
              case "inline" => diffMode = DiffMode.Inline
              case "minimal" => diffMode = DiffMode.Minimal
              case other => 
                println(s"Unknown diff style: $other. Using unified.")
                diffMode = DiffMode.Unified
            }
          }
        case "--output-dir" =>
          i += 1
          if (i < args.length) outputDir = args(i)
        case "--snapshot-dir" =>
          i += 1
          if (i < args.length) snapshotDir = args(i)
        case "--root" =>
          i += 1
          if (i < args.length) rootOverride = Some(args(i))
        case "--test-filter" | "-t" =>
          i += 1
          if (i < args.length) testFilter = Some(args(i))
        case "--help" | "-h" =>
          printHelp()
          return
        case arg if !arg.startsWith("-") =>
          testClasses += arg
        case _ =>
          println(s"Unknown option: ${args(i)}")
          throw new BooktestFailure("Unknown option")
      }
      i += 1
    }
    
    // Load booktest.conf if present, with optional --root override
    val booktestConfig = {
      val loaded = BooktestConfig.load().getOrElse(BooktestConfig.empty)
      rootOverride.map(r => loaded.copy(root = Some(r))).getOrElse(loaded)
    }

    // Use books-path from config if not overridden
    if (outputDir == "books" && booktestConfig.booksPath != "books") {
      outputDir = booktestConfig.booksPath
      snapshotDir = booktestConfig.booksPath
    }

    var config = RunConfig(
      outputDir = os.pwd / os.RelPath(outputDir),
      snapshotDir = os.pwd / os.RelPath(snapshotDir),
      verbose = verbose,
      interactive = interactive,
      testFilter = testFilter,
      diffMode = diffMode,
      batchReview = batchReview,
      summaryMode = summaryMode,
      recaptureAll = recaptureAll,
      updateSnapshots = updateSnapshots,
      autoAcceptDiff = autoAcceptDiff,
      continueMode = continueMode,
      refreshDeps = refreshDeps,
      threads = threads,
      booktestConfig = booktestConfig,
      invalidateLiveOnFail = invalidateLiveOnFail,
      trace = traceFlag
    )

    // Apply capacity overrides from CLI to the global ResourceManager.
    // capacity(name, default) reads this map.
    capacityOverrides.foreach { case (name, value) =>
      ResourceManager.default.setCapacityOverride(name, value)
    }

    // Resolve test suites - paths are relative to root namespace
    val suites: List[TestSuite] = if (testClasses.isEmpty) {
      // No args: run default with exclude patterns applied
      booktestConfig.defaultTests match {
        case Some(defaultPath) =>
          // Support comma-separated list (e.g., "examples, test")
          val paths = defaultPath.split(",").map(_.trim).filter(_.nonEmpty)
          val discovered = paths.flatMap(p => discoverTestSuites(booktestConfig.resolvePath(p))).toList
          // Apply exclude patterns for default runs
          discovered.filterNot(s => booktestConfig.isExcluded(s.fullClassName))
        case None =>
          println("No tests specified. Configure 'default' in booktest.conf")
          println("Or specify test path as argument (e.g., 'booktest examples')")
          throw new BooktestFailure("No tests specified")
      }
    } else {
      // Has arguments - resolve each one
      testClasses.flatMap { rawArg =>
        // Normalize: convert slash format to dot format (examples/ImageTest -> examples.ImageTest)
        val arg = rawArg.replace('/', '.')

        def resolveArg(a: String): List[TestSuite] = {
          // Check if it's a group name first
          booktestConfig.getGroup(a) match {
            case Some(packages) =>
              // It's a group - discover all packages, apply exclude
              val discovered = packages.flatMap(discoverTestSuites)
              discovered.filterNot(s => booktestConfig.isExcluded(s.fullClassName))
            case None =>
              // Not a group - resolve as path relative to root
              val fullPath = if (a.startsWith(booktestConfig.root.getOrElse(""))) {
                // Already a full path
                a
              } else {
                // Relative path - prepend root
                booktestConfig.resolvePath(a)
              }
              val discovered = discoverTestSuites(fullPath)
              if (discovered.nonEmpty) {
                // Don't apply exclude for explicit class names
                if (discovered.length == 1) {
                  discovered
                } else {
                  // Multiple matches (package) - apply exclude
                  discovered.filterNot(s => booktestConfig.isExcluded(s.fullClassName))
                }
              } else {
                // Try as explicit class name - no exclude
                loadTestSuite(fullPath).toList
              }
          }
        }

        val resolved = resolveArg(arg)
        if (resolved.nonEmpty) {
          resolved
        } else if (arg.contains('.')) {
          // Try SuiteName.testCase pattern: strip last component and use as test filter
          val lastDot = arg.lastIndexOf('.')
          val suitePart = arg.substring(0, lastDot)
          val testPart = arg.substring(lastDot + 1)
          val suiteResolved = resolveArg(suitePart)
          if (suiteResolved.nonEmpty) {
            testFilter = Some(testPart)
            suiteResolved
          } else {
            Nil
          }
        } else {
          Nil
        }
      }.toList
    }
    
    // Update config if testFilter was set during SuiteName/testCase resolution
    if (testFilter != config.testFilter) {
      config = config.copy(testFilter = testFilter)
    }

    if (suites.isEmpty) {
      throw new BooktestFailure("No valid test suites found.")
    }
    
    if (listTests) {
      // List all test cases with enhanced display
      if (treeView) {
        displayTestsAsTree(suites, config)
      } else {
        displayTestsAsList(suites, config, testClasses.toList)
      }
      return
    }

    if (buildGraphMode) {
      displayBuildGraph(suites, config)
      return
    }
    
    if (showLogs) {
      // Show logs for all test cases
      val logDir = os.pwd / outputDir / ".out"
      suites.foreach { suite =>
        val suiteName = suite.suiteName
        val testCases = suite.testCases
        val filteredTests = config.testFilter match {
          case Some(pattern) => testCases.filter(_.name.contains(pattern))
          case None => testCases
        }
        
        filteredTests.foreach { testCase =>
          val logFile = logDir / suiteName / s"${testCase.name}.log"
          if (os.exists(logFile)) {
            println(s"=== $suiteName/${testCase.name} ===")
            val logContent = os.read(logFile)
            print(logContent)
            if (!logContent.endsWith("\n")) println()
            println()
          } else {
            println(s"No log file found for $suiteName/${testCase.name}")
          }
        }
      }
      return
    }
    
    if (reviewMode) {
      // Review mode - review previous test results without re-running
      val runner = new TestRunner(config)
      val exitCode = runner.reviewResults(suites)
      if (exitCode != 0) throw new BooktestFailure("Review found unresolved diffs")
      return
    }

    if (garbageMode || cleanMode) {
      // Garbage/clean mode - find orphan files in books/ directory
      val garbageFiles = findGarbageFiles(config.snapshotDir, suites, booktestConfig)
      val tmpDirs = findTmpDirectories(config.outputDir / ".out")

      if (garbageMode) {
        // Just print the garbage files
        if (garbageFiles.nonEmpty) {
          println("Orphan files in books/ directory:")
          garbageFiles.foreach(f => println(s"  $f"))
        }
        if (tmpDirs.nonEmpty) {
          println("Temporary directories in .out/:")
          tmpDirs.foreach(d => println(s"  $d"))
        }
        if (garbageFiles.isEmpty && tmpDirs.isEmpty) {
          println("No garbage found.")
        }
      } else {
        // Clean mode - remove garbage files and tmp directories
        var removed = 0
        garbageFiles.foreach { file =>
          try {
            os.remove(file)
            println(s"Removed: $file")
            removed += 1
          } catch {
            case e: Exception => println(s"Failed to remove $file: ${e.getMessage}")
          }
        }
        tmpDirs.foreach { dir =>
          try {
            os.remove.all(dir)
            println(s"Removed: $dir")
            removed += 1
          } catch {
            case e: Exception => println(s"Failed to remove $dir: ${e.getMessage}")
          }
        }
        println(s"Removed $removed items.")
      }
      return
    }

    val runner = new TestRunner(config)
    val result = runner.runMultipleSuites(suites)

    result.printSummary()

    if (!result.success) {
      throw new BooktestFailure(result.summary)
    }
  }
  
  private def displayTestsAsList(suites: List[TestSuite], config: RunConfig, testClasses: List[String]): Unit = {
    if (testClasses.isEmpty) {
      // Auto-discovery mode - show both class paths and individual test paths
      val knownTestSuites = List(
        "booktest.examples.ExampleTests",
        "booktest.examples.DependencyTests", 
        "booktest.examples.MethodRefTests",
        "booktest.examples.FailingTest",
        "booktest.examples.MultiFail",
        "booktest.examples.EnvSnapshotTests",
        "booktest.examples.HttpSnapshotTests",
        "booktest.examples.FunctionSnapshotTests"
      )
      
      val availableClasses = knownTestSuites.filter { className =>
        try {
          Class.forName(className)
          true
        } catch {
          case _: ClassNotFoundException => false
        }
      }
      
      availableClasses.foreach { className =>
        // Use slash format with root stripped (consistent with test output)
        val suitePath = config.booktestConfig.classNameToPath(className)

        // Apply filter
        val classMatches = config.testFilter match {
          case Some(pattern) => suitePath.contains(pattern) || className.contains(pattern)
          case None => true
        }

        if (classMatches) {
          println(s"  $suitePath")
        }

        // Also show individual tests in this class
        try {
          val clazz = Class.forName(className)
          val constructor = clazz.getDeclaredConstructor()
          val instance = constructor.newInstance().asInstanceOf[TestSuite]
          val testCases = instance.testCases

          val filteredTests = config.testFilter match {
            case Some(pattern) => testCases.filter(tc => tc.name.contains(pattern) || suitePath.contains(pattern))
            case None => testCases
          }

          filteredTests.foreach { testCase =>
            println(s"  $suitePath/${testCase.name}")
          }
        } catch {
          case e: Exception =>
            // Skip if we can't instantiate the class
        }
      }
    } else {
      // Specific class mode - show individual test methods for the specified classes
      suites.foreach { suite =>
        // Use slash format with root stripped
        val suitePath = config.booktestConfig.classNameToPath(suite.fullClassName)
        val testCases = suite.testCases
        val filteredTests = config.testFilter match {
          case Some(pattern) => testCases.filter(_.name.contains(pattern))
          case None => testCases
        }
        filteredTests.foreach { testCase =>
          println(s"  $suitePath/${testCase.name}")
        }
      }
    }
  }
  
  private def displayTestsAsTree(suites: List[TestSuite], config: RunConfig): Unit = {
    import fansi._
    import fansi.Color.{LightBlue, LightGreen, LightYellow, LightCyan}

    suites.foreach { suite =>
      val suiteName = suite.suiteName
      val suitePath = config.booktestConfig.classNameToPath(suite.fullClassName)
      val testCases = suite.testCases
      val filteredTests = config.testFilter match {
        case Some(pattern) => testCases.filter(_.name.contains(pattern))
        case None => testCases
      }

      if (filteredTests.nonEmpty) {
        println(s"${LightBlue("📂")} ${fansi.Bold.On(suiteName)}")

        // Build dependency graph for ordering
        val orderedTests = topologicalSort(filteredTests)

        orderedTests.zipWithIndex.foreach { case (testCase, index) =>
          val isLast = index == orderedTests.length - 1
          val prefix = if (isLast) "└── " else "├── "

          // Get cache status by checking bin file
          val binFile = config.outputDir / ".out" / os.RelPath(suitePath) / s"${testCase.name}.bin"
          val cacheStatus = if (os.exists(binFile)) {
            LightGreen("✅")
          } else {
            LightYellow("⏳")
          }

          // Get test duration from logs if available
          val duration = getTestDuration(config.outputDir, suiteName, testCase.name)
          val durationStr = duration.map(d => f"${d}%.2fs").getOrElse("--")

          // Build dependency info
          val depInfo = if (testCase.dependencies.nonEmpty) {
            s" ${LightCyan(s"(depends: ${testCase.dependencies.mkString(", ")})")}"
          } else {
            ""
          }

          println(s"$prefix${LightYellow("🧪")} ${testCase.name} (${durationStr}, cached: ${cacheStatus})${depInfo}")
        }
        
        // Summary
        val totalTests = filteredTests.length
        val cachedTests = filteredTests.count { tc =>
          val binFile = config.outputDir / ".out" / os.RelPath(suitePath) / s"${tc.name}.bin"
          os.exists(binFile)
        }
        val dependencyChains = countDependencyChains(filteredTests)
        
        println()
        println(s"${totalTests} tests found, ${cachedTests} cached, ${dependencyChains} dependency chains")
        println()
      }
    }
  }
  
  
  /** Print the cross-suite task DAG (tests + live resources) annotated
    * with each task's last-run state, drawn from `<outputDir>/.out`'s
    * cases.ndjson and `.bin` cache. Live resources don't persist
    * cross-run state, so they're shown with their declared share mode
    * and the names of their deps.
    *
    * Style: one suite per "## " block, tasks listed in topological
    * order so producers come before consumers, deps drawn as `←`
    * arrows under each task. */
  private def displayBuildGraph(suites: List[TestSuite], config: RunConfig): Unit = {
    import fansi.Color.{LightBlue, LightGreen, LightRed, LightYellow, LightCyan}
    import fansi.Bold

    val outDir = config.outputDir / ".out"
    // Last-run case reports keyed by full test name ("suitePath/testName").
    val reportByName: Map[String, CaseReport] =
      try {
        val cases = CaseReports.fromDir(outDir).cases
        cases.map(c => c.testName -> c).toMap
      } catch { case _: Throwable => Map.empty }

    def stateBadge(testKey: String): fansi.Str = reportByName.get(testKey) match {
      case Some(r) if r.result == "OK"   => LightGreen(f"ok    ${r.durationMs}%5d ms")
      case Some(r) if r.result == "DIFF" => LightYellow(f"DIFF  ${r.durationMs}%5d ms")
      case Some(r) if r.result == "FAIL" => LightRed(f"FAIL  ${r.durationMs}%5d ms")
      case Some(r)                       => fansi.Color.White(f"${r.result}%-5s ${r.durationMs}%5d ms")
      case None                          => fansi.Color.DarkGray("(not run)         ")
    }

    suites.foreach { suite =>
      val suitePath = config.booktestConfig.classNameToPath(suite.fullClassName)
      val testCases = suite.testCases
      val filtered = config.testFilter match {
        case Some(pattern) =>
          val matched = testCases.filter(_.name.contains(pattern)).map(_.name).toSet
          val testMap = testCases.map(tc => tc.name -> tc).toMap
          val needed = scala.collection.mutable.LinkedHashSet[String]()
          def collect(name: String): Unit = if (!needed.contains(name)) {
            testMap.get(name).foreach(_.dependencies.foreach(collect))
            needed += name
          }
          matched.foreach(collect)
          testCases.filter(tc => needed.contains(tc.name))
        case None => testCases
      }

      if (filtered.isEmpty && suite.liveResources.isEmpty) {
        // skip empty suite
      } else {
        println()
        println(s"${LightBlue("##")} ${Bold.On(suitePath)}")
        println()

        // Live resources first (they're often producers of state for
        // tests). Then tests in topological order.
        val resourceNames = suite.liveResources.map(_.name).toSet
        suite.liveResources.foreach { lr =>
          val mode = lr.shareMode match {
            case ShareMode.SharedReadOnly => "shared"
            case ShareMode.SharedSerialized => "serialized"
            case _: ShareMode.SharedWithReset[_] => "with-reset"
            case ShareMode.Exclusive => "exclusive"
          }
          val depNames = lr.deps.map {
            case TestDep(ref) => ref.name
            case ResourceDep(ref) => ref.name
            case PoolDep(_) => "<pool>"
            case CapacityDep(_, _) => "<capacity>"
          }
          val depStr = if (depNames.isEmpty) "" else " ← " + depNames.mkString(", ")
          println(s"  ${LightCyan("●")} ${Bold.On(lr.name)}  ${fansi.Color.DarkGray(s"(live: $mode)")}$depStr")
        }
        if (suite.liveResources.nonEmpty && filtered.nonEmpty) println()

        val ordered = topologicalSort(filtered)
        ordered.foreach { tc =>
          val key = s"$suitePath/${tc.name}"
          val state = stateBadge(key)
          val deps = tc.dependencies
          val depStr = if (deps.isEmpty) ""
            else {
              val annotated = deps.map { d =>
                if (resourceNames.contains(d)) s"${LightCyan(d)}"
                else d
              }
              " ← " + annotated.mkString(", ")
            }
          println(s"  ${LightYellow("○")} ${Bold.On(tc.name)}  $state$depStr")
        }

        // Per-suite summary line.
        val total = filtered.size
        val ok = filtered.count(tc => reportByName.get(s"$suitePath/${tc.name}").exists(_.result == "OK"))
        val diff = filtered.count(tc => reportByName.get(s"$suitePath/${tc.name}").exists(_.result == "DIFF"))
        val fail = filtered.count(tc => reportByName.get(s"$suitePath/${tc.name}").exists(_.result == "FAIL"))
        val never = total - ok - diff - fail
        println()
        println(s"  ${fansi.Color.DarkGray(s"$total tasks: $ok ok, $diff diff, $fail fail, $never not-run")}")
      }
    }
    println()
    if (reportByName.isEmpty) {
      println(fansi.Color.DarkGray("(no prior run found at " + outDir + " — all tasks marked not-run)"))
      println()
    }
  }

  private def topologicalSort(testCases: List[TestCase]): List[TestCase] = {
    val testCaseMap = testCases.map(tc => tc.name -> tc).toMap
    val graph = testCases.map(tc => tc.name -> tc.dependencies).toMap
    
    def sortRec(remaining: List[String], sorted: List[String]): List[String] = {
      if (remaining.isEmpty) sorted
      else {
        val independentTests = remaining.filter(test => 
          graph.getOrElse(test, List.empty).forall(dep => sorted.contains(dep))
        )
        if (independentTests.isEmpty) {
          // Circular dependency or missing dependency - just add remaining tests
          sorted ++ remaining
        } else {
          sortRec(remaining.filterNot(independentTests.contains), sorted ++ independentTests)
        }
      }
    }
    
    val sortedNames = sortRec(testCases.map(_.name), List.empty)
    sortedNames.flatMap(name => testCaseMap.get(name))
  }
  
  private def getTestDuration(outputDir: os.Path, suiteName: String, testName: String): Option[Double] = {
    try {
      val logFile = outputDir / ".out" / suiteName / s"${testName}.log"
      if (os.exists(logFile)) {
        val logContent = os.read(logFile)
        // Look for duration pattern in log
        val durationPattern = """Duration: ([0-9.]+)s""".r
        durationPattern.findFirstMatchIn(logContent).map(_.group(1).toDouble)
      } else {
        None
      }
    } catch {
      case _: Exception => None
    }
  }
  
  private def countDependencyChains(testCases: List[TestCase]): Int = {
    // Count unique dependency chains
    val rootTests = testCases.filter(_.dependencies.isEmpty)
    
    def countChainsFromRoot(testName: String, visited: Set[String]): Int = {
      if (visited.contains(testName)) 0
      else {
        val dependents = testCases.filter(_.dependencies.contains(testName))
        if (dependents.isEmpty) 1
        else dependents.map(dep => countChainsFromRoot(dep.name, visited + testName)).sum
      }
    }
    
    rootTests.map(test => countChainsFromRoot(test.name, Set.empty)).sum
  }

  def printHelp(): Unit = {
    println("Booktest - Review-driven testing for Scala")
    println("Usage: booktest [options] [group-name | test-class-names...]")
    println()
    println("Options:")
    println("  -v, --verbose       Verbose output")
    println("  -i, --interactive   Interactive mode")
    println("  -l, --list          List test classes and individual test paths")
    println("  -b, --build-graph   Print the task DAG (tests + live resources) annotated")
    println("                      with each task's last-run state (ok/DIFF/FAIL/not-run)")
    println("                      and dep edges. Reads <output-dir>/.out/cases.ndjson.")
    println("  -L, --logs          Show logs")
    println("  -w, --review        Review previous test results")
    println("  --batch-review      Review multiple failed tests in sequence")
    println("  --tree              Show tests in hierarchical tree format (with -l)")
    println("  --inline            Show diffs inline (default: show at end)")
    println("  -s, --update        Auto-accept snapshot changes (update mode)")
    println("  -S, --recapture     Force regenerate all snapshots")
    println("  -c, --continue      Continue from last run, skip successful tests")
    println("  -r, --refresh-deps  Force re-run of transitive dependencies (default: load")
    println("                      cached deps from .bin instead of re-executing them)")
    println("  -pN, -p N           Run tests in parallel using N threads (e.g., -p4)")
    println("  --diff-style STYLE  Diff display style: unified, side-by-side, inline, minimal")
    println("  --output-dir DIR    Output directory for test results (default: books)")
    println("  --snapshot-dir DIR  Snapshot directory (default: books)")
    println("  -t, --test-filter   Filter tests by name pattern")
    println("  --garbage           List orphan files in books/ and temp directories")
    println("  --clean             Remove orphan files and temp directories")
    println("  --trace             Write structured event log to <output-dir>/.booktest.log")
    println("                      (also enabled by BOOKTEST_TRACE=1; failure-time trace")
    println("                       blocks attach automatically at -pN ≥ 2 either way)")
    println("  -h, --help          Show this help message")
    println()
    println("Configuration (booktest.conf):")
    println("  test-root = booktest              # Package prefix to strip from paths")
    println("  test-packages = booktest.examples # Packages to scan for tests")
    println("  default = booktest.examples       # Default tests to run")
    println("  books-path = books                # Where to store snapshots")
    println()
    println("Examples:")
    println("  booktest                             # Run tests from 'default' package")
    println("  booktest booktest.examples           # Run all tests in package")
    println("  booktest booktest.examples.ImageTest # Run specific test suite")
    println("  booktest -v booktest.examples        # Verbose output")
    println("  booktest -l                          # List all discovered tests")
    println("  booktest -t Data                     # Filter tests by pattern")
    println("  booktest -i booktest.examples        # Interactive mode")
    println("  booktest --garbage                   # List orphan files")
    println("  booktest --clean                     # Remove orphan files")
  }
  
  /** Discover TestSuite classes in a package by scanning the classpath.
    * Uses the compiled class files in target/ to find all classes.
    */
  private def discoverTestSuites(packageName: String): List[TestSuite] = {
    val packagePath = packageName.replace('.', '/')
    val classNames = scala.collection.mutable.ListBuffer[String]()

    // Scan target directories for compiled class files
    // Works with SBT's classloader which may not expose directories via getResources
    val targetDir = os.pwd / "target"
    if (os.exists(targetDir)) {
      os.list(targetDir).filter(_.last.startsWith("scala-")).foreach { scalaDir =>
        // Check both main classes and test-classes
        for (classesDir <- List(scalaDir / "classes", scalaDir / "test-classes")) {
          val packageDir = classesDir / os.RelPath(packagePath)
          if (os.exists(packageDir) && os.isDir(packageDir)) {
            scanClassDirectory(packageDir.toIO, packageName, classNames)
          }
        }
      }
    }

    // Instantiate discovered classes that extend TestSuite
    classNames.distinct.flatMap(loadTestSuite).toList
  }

  /** Recursively scan a directory for class files */
  private def scanClassDirectory(dir: java.io.File, packageName: String, classNames: scala.collection.mutable.ListBuffer[String]): Unit = {
    val files = dir.listFiles()
    if (files != null) {
      files.foreach { file =>
        if (file.isDirectory) {
          scanClassDirectory(file, s"$packageName.${file.getName}", classNames)
        } else if (file.getName.endsWith(".class") && !file.getName.contains("$")) {
          val className = s"$packageName.${file.getName.dropRight(6)}"
          classNames += className
        }
      }
    }
  }

  /** Load a single TestSuite class by name */
  private def loadTestSuite(className: String): Option[TestSuite] = {
    try {
      val clazz = Class.forName(className)
      if (classOf[TestSuite].isAssignableFrom(clazz) && !clazz.isInterface) {
        val constructor = clazz.getDeclaredConstructor()
        val instance = constructor.newInstance().asInstanceOf[TestSuite]
        Some(instance)
      } else {
        None
      }
    } catch {
      case _: ClassNotFoundException => None
      case _: NoSuchMethodException => None  // No default constructor
      case e: Exception =>
        // Only warn for actual instantiation errors, not for non-TestSuite classes
        if (classOf[TestSuite].isAssignableFrom(Class.forName(className))) {
          println(s"Warning: Could not instantiate $className: ${e.getMessage}")
        }
        None
    }
  }

  /** Find orphan files in books/ directory that don't correspond to known tests.
    * Protects:
    * - <suite>/<test>.md files
    * - <suite>/<test>/ asset directories
    * - <suite>/<test>.snapshots.json files
    * - index.md (if present)
    */
  private def findGarbageFiles(booksDir: os.Path, suites: List[TestSuite], config: BooktestConfig): List[os.Path] = {
    if (!os.exists(booksDir)) return List.empty

    // Build set of protected paths
    val protectedPaths = scala.collection.mutable.Set[os.Path]()

    suites.foreach { suite =>
      val suitePath = config.classNameToPath(suite.fullClassName)
      val suiteDir = booksDir / os.RelPath(suitePath)

      // Protect the suite directory itself
      protectedPaths += suiteDir

      suite.testCases.foreach { testCase =>
        // Protect: <test>.md, <test>/, <test>.snapshots.json
        protectedPaths += suiteDir / s"${testCase.name}.md"
        protectedPaths += suiteDir / testCase.name
        protectedPaths += suiteDir / s"${testCase.name}.snapshots.json"
      }
    }

    // Also protect index.md if present
    protectedPaths += booksDir / "index.md"

    // Walk books/ directory (excluding .out/)
    val garbageFiles = scala.collection.mutable.ListBuffer[os.Path]()

    def walkDir(dir: os.Path): Unit = {
      if (!os.exists(dir)) return

      os.list(dir).foreach { path =>
        val name = path.last
        // Skip .out directory
        if (name == ".out") {
          // Skip
        } else if (os.isDir(path)) {
          // Check if this directory is protected
          if (!isProtected(path, protectedPaths)) {
            // Check if any files inside are protected
            val hasProtectedContent = os.walk(path).exists(p => isProtected(p, protectedPaths))
            if (!hasProtectedContent) {
              garbageFiles += path
            } else {
              // Recurse into the directory
              walkDir(path)
            }
          } else {
            // Directory is protected (it's a test assets dir), but check for orphan files inside
            // Actually, don't check inside protected asset directories
          }
        } else {
          // It's a file
          if (!isProtected(path, protectedPaths)) {
            garbageFiles += path
          }
        }
      }
    }

    walkDir(booksDir)
    garbageFiles.toList
  }

  /** Check if a path is protected (exact match or parent of protected path) */
  private def isProtected(path: os.Path, protectedPaths: scala.collection.mutable.Set[os.Path]): Boolean = {
    protectedPaths.contains(path) || protectedPaths.exists(p => p.startsWith(path))
  }

  /** Find all test tmp directories in .out/ that can be cleaned.
    * Tmp directories are identified as directories that have corresponding test files
    * (e.g., testName/ is a tmp dir if testName.md or testName.bin exists).
    */
  private def findTmpDirectories(outDir: os.Path): List[os.Path] = {
    if (!os.exists(outDir)) return List.empty

    val tmpDirs = scala.collection.mutable.ListBuffer[os.Path]()

    def walkDir(dir: os.Path): Unit = {
      if (!os.exists(dir)) return

      os.list(dir).foreach { path =>
        if (os.isDir(path)) {
          val name = path.last
          // Check if this is a test tmp directory (has corresponding .md or .bin file)
          val mdFile = dir / s"$name.md"
          val binFile = dir / s"$name.bin"
          if (os.exists(mdFile) || os.exists(binFile)) {
            tmpDirs += path
          } else {
            // It's a suite directory, recurse
            walkDir(path)
          }
        }
      }
    }

    walkDir(outDir)
    tmpDirs.toList
  }
}