package booktest

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
    var threads = 1  // -t N: number of threads for parallel execution
    var garbageMode = false  // --garbage: list orphan files
    var cleanMode = false  // --clean: remove orphan files and temp directories
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
        case "--garbage" => garbageMode = true  // List orphan files in books/
        case "--clean" => cleanMode = true  // Remove orphan files and .tmp directories
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
          sys.exit(1)
      }
      i += 1
    }
    
    // Load booktest.conf if present
    val booktestConfig = BooktestConfig.load().getOrElse(BooktestConfig.empty)

    // Use books-path from config if not overridden
    if (outputDir == "books" && booktestConfig.booksPath != "books") {
      outputDir = booktestConfig.booksPath
      snapshotDir = booktestConfig.booksPath
    }

    val config = RunConfig(
      outputDir = os.pwd / outputDir,
      snapshotDir = os.pwd / snapshotDir,
      verbose = verbose,
      interactive = interactive,
      testFilter = testFilter,
      diffMode = diffMode,
      batchReview = batchReview,
      summaryMode = summaryMode,
      recaptureAll = recaptureAll,
      updateSnapshots = updateSnapshots,
      threads = threads,
      booktestConfig = booktestConfig
    )

    // Resolve test suites - paths are relative to root namespace
    val suites: List[TestSuite] = if (testClasses.isEmpty) {
      // No args: run default with exclude patterns applied
      booktestConfig.defaultTests match {
        case Some(defaultPath) =>
          val fullPath = booktestConfig.resolvePath(defaultPath)
          val discovered = discoverTestSuites(fullPath)
          // Apply exclude patterns for default runs
          discovered.filterNot(s => booktestConfig.isExcluded(s.fullClassName))
        case None =>
          println("No tests specified. Configure 'default' in booktest.conf")
          println("Or specify test path as argument (e.g., 'booktest examples')")
          sys.exit(1)
      }
    } else {
      // Has arguments - resolve each one
      testClasses.flatMap { rawArg =>
        // Normalize: convert slash format to dot format (examples/ImageTest -> examples.ImageTest)
        val arg = rawArg.replace('/', '.')

        // Check if it's a group name first
        booktestConfig.getGroup(arg) match {
          case Some(packages) =>
            // It's a group - discover all packages, apply exclude
            val discovered = packages.flatMap(discoverTestSuites)
            discovered.filterNot(s => booktestConfig.isExcluded(s.fullClassName))
          case None =>
            // Not a group - resolve as path relative to root
            val fullPath = if (arg.startsWith(booktestConfig.root.getOrElse(""))) {
              // Already a full path
              arg
            } else {
              // Relative path - prepend root
              booktestConfig.resolvePath(arg)
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
      }.toList
    }
    
    if (suites.isEmpty) {
      println("No valid test suites found.")
      sys.exit(1)
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
      sys.exit(exitCode)
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
    
    println()
    println(result.summary)
    
    if (!result.success) {
      sys.exit(1)
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

    suites.foreach { suite =>
      val suiteName = suite.suiteName
      val suitePath = config.booktestConfig.classNameToPath(suite.fullClassName)
      val testCases = suite.testCases
      val filteredTests = config.testFilter match {
        case Some(pattern) => testCases.filter(_.name.contains(pattern))
        case None => testCases
      }

      if (filteredTests.nonEmpty) {
        println(s"${Color.Blue("📂")} ${fansi.Bold.On(suiteName)}")

        // Build dependency graph for ordering
        val orderedTests = topologicalSort(filteredTests)

        orderedTests.zipWithIndex.foreach { case (testCase, index) =>
          val isLast = index == orderedTests.length - 1
          val prefix = if (isLast) "└── " else "├── "

          // Get cache status by checking bin file
          val binFile = config.outputDir / ".out" / os.RelPath(suitePath) / s"${testCase.name}.bin"
          val cacheStatus = if (os.exists(binFile)) {
            Color.Green("✅")
          } else {
            Color.Yellow("⏳")
          }
          
          // Get test duration from logs if available
          val duration = getTestDuration(config.outputDir, suiteName, testCase.name)
          val durationStr = duration.map(d => f"${d}%.2fs").getOrElse("--")
          
          // Build dependency info
          val depInfo = if (testCase.dependencies.nonEmpty) {
            s" ${Color.Cyan(s"(depends: ${testCase.dependencies.mkString(", ")})")}"
          } else {
            ""
          }
          
          println(s"$prefix${Color.Yellow("🧪")} ${testCase.name} (${durationStr}, cached: ${cacheStatus})${depInfo}")
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
    println("  -L, --logs          Show logs")
    println("  -w, --review        Review previous test results")
    println("  --batch-review      Review multiple failed tests in sequence")
    println("  --tree              Show tests in hierarchical tree format (with -l)")
    println("  --inline            Show diffs inline (default: show at end)")
    println("  -s, --update        Auto-accept snapshot changes (update mode)")
    println("  -S, --recapture     Force regenerate all snapshots")
    println("  -pN, -p N           Run tests in parallel using N threads (e.g., -p4)")
    println("  --diff-style STYLE  Diff display style: unified, side-by-side, inline, minimal")
    println("  --output-dir DIR    Output directory for test results (default: books)")
    println("  --snapshot-dir DIR  Snapshot directory (default: books)")
    println("  -t, --test-filter   Filter tests by name pattern")
    println("  --garbage           List orphan files in books/ and temp directories")
    println("  --clean             Remove orphan files and temp directories")
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
    val classLoader = Thread.currentThread().getContextClassLoader
    val packagePath = packageName.replace('.', '/')

    // Find all class files in the package
    val classNames = scala.collection.mutable.ListBuffer[String]()

    // Try to find classes from the classpath
    try {
      val resources = classLoader.getResources(packagePath)
      while (resources.hasMoreElements) {
        val resource = resources.nextElement()
        val path = resource.getPath
        if (path.contains("target/scala")) {
          // Scan the directory for .class files
          val dir = new java.io.File(java.net.URLDecoder.decode(path, "UTF-8"))
          if (dir.exists() && dir.isDirectory) {
            scanClassDirectory(dir, packageName, classNames)
          }
        }
      }
    } catch {
      case _: Exception => // Ignore errors during discovery
    }

    // If no classes found via classpath, try hardcoded fallback for known packages
    if (classNames.isEmpty && packageName.startsWith("booktest.examples")) {
      classNames ++= getKnownTestClasses(packageName)
    }

    // Instantiate discovered classes that extend TestSuite
    classNames.flatMap(loadTestSuite).toList
  }

  /** Recursively scan a directory for class files */
  private def scanClassDirectory(dir: java.io.File, packageName: String, classNames: scala.collection.mutable.ListBuffer[String]): Unit = {
    dir.listFiles().foreach { file =>
      if (file.isDirectory) {
        scanClassDirectory(file, s"$packageName.${file.getName}", classNames)
      } else if (file.getName.endsWith(".class") && !file.getName.contains("$")) {
        val className = s"$packageName.${file.getName.dropRight(6)}"
        classNames += className
      }
    }
  }

  /** Get known test classes for common packages (fallback when classpath scanning fails) */
  private def getKnownTestClasses(packageName: String): List[String] = {
    val allKnown = List(
      "booktest.examples.ExampleTests",
      "booktest.examples.DependencyTests",
      "booktest.examples.MethodRefTests",
      "booktest.examples.FailingTest",
      "booktest.examples.MultiFail",
      "booktest.examples.NewFeaturesTest",
      "booktest.examples.InfoMethodsTest",
      "booktest.examples.MetricsTest",
      "booktest.examples.SnapshotCacheTest",
      "booktest.examples.AsyncTest",
      "booktest.examples.SetupTeardownTest",
      "booktest.examples.DirectionConstraintsTest",
      "booktest.examples.MarkersTest",
      "booktest.examples.ImageTest",
      "booktest.examples.TmpDirTest",
      "booktest.examples.EnvSnapshotTests",
      "booktest.examples.HttpSnapshotTests",
      "booktest.examples.FunctionSnapshotTests"
    )
    allKnown.filter(_.startsWith(packageName))
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