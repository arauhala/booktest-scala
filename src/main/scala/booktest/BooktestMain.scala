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
    
    val config = RunConfig(
      outputDir = os.pwd / outputDir,
      snapshotDir = os.pwd / snapshotDir,
      verbose = verbose,
      interactive = interactive,
      testFilter = testFilter,
      diffMode = diffMode,
      batchReview = batchReview
    )
    
    if (testClasses.isEmpty && !listTests) {
      println("No test classes specified. Use --help for usage information.")
      sys.exit(1)
    }
    
    val suites = if (testClasses.isEmpty && listTests) {
      // Auto-discover all test suites when listing without arguments
      discoverAllTestSuites()
    } else {
      testClasses.toList.flatMap { className =>
        try {
          val clazz = Class.forName(className)
          val constructor = clazz.getDeclaredConstructor()
          val instance = constructor.newInstance().asInstanceOf[TestSuite]
          Some(instance)
        } catch {
          case e: ClassNotFoundException =>
            println(s"Error: Test class '$className' not found")
            None
          case e: Exception =>
            println(s"Error creating instance of '$className': ${e.getMessage}")
            None
        }
      }
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
        // Apply filter to class name
        val classMatches = config.testFilter match {
          case Some(pattern) => className.contains(pattern)
          case None => true
        }
        
        if (classMatches) {
          println(s"  $className")
        }
        
        // Also show individual tests in this class
        try {
          val clazz = Class.forName(className)
          val constructor = clazz.getDeclaredConstructor()
          val instance = constructor.newInstance().asInstanceOf[TestSuite]
          val testCases = instance.testCases
          
          val filteredTests = config.testFilter match {
            case Some(pattern) => testCases.filter(tc => tc.name.contains(pattern) || className.contains(pattern))
            case None => testCases
          }
          
          filteredTests.foreach { testCase =>
            println(s"  $className/${testCase.name}")
          }
        } catch {
          case e: Exception =>
            // Skip if we can't instantiate the class
        }
      }
    } else {
      // Specific class mode - show individual test methods for the specified classes
      suites.foreach { suite =>
        val suiteName = suite.suiteName
        val testCases = suite.testCases
        val filteredTests = config.testFilter match {
          case Some(pattern) => testCases.filter(_.name.contains(pattern))
          case None => testCases
        }
        filteredTests.foreach { testCase =>
          println(s"  $suiteName/${testCase.name}")
        }
      }
    }
  }
  
  private def displayTestsAsTree(suites: List[TestSuite], config: RunConfig): Unit = {
    import fansi._
    
    val cacheDir = config.outputDir / ".cache"
    val cache = new DependencyCache(cacheDir)
    
    suites.foreach { suite =>
      val suiteName = suite.suiteName
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
          
          // Get cache status
          val cacheStatus = if (cache.contains(testCase.name)) {
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
        val cachedTests = filteredTests.count(tc => cache.contains(tc.name))
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
    println("Usage: booktest [options] <test-class-names>")
    println()
    println("Options:")
    println("  -v, --verbose       Verbose output")
    println("  -i, --interactive   Interactive mode")
    println("  -l, --list          List test classes and individual test paths")
    println("  -L, --logs          Show logs")
    println("  -w, --review        Review previous test results")
    println("  --batch-review      Review multiple failed tests in sequence")
    println("  --tree              Show tests in hierarchical tree format (with -l)")
    println("  --diff-style STYLE  Diff display style: unified, side-by-side, inline, minimal")
    println("  --output-dir DIR    Output directory for test results (default: books)")
    println("  --snapshot-dir DIR  Snapshot directory (default: books)")
    println("  -t, --test-filter   Filter tests by name pattern")
    println("  -h, --help          Show this help message")
    println()
    println("Examples:")
    println("  booktest booktest.examples.ExampleTests")
    println("  booktest -v booktest.examples.ExampleTests")
    println("  booktest -l                                  # List all test classes and individual tests")
    println("  booktest -l booktest.examples.DependencyTests")
    println("  booktest -l --tree booktest.examples.MethodRefTests")
    println("  booktest -t Data booktest.examples.DependencyTests")
    println("  booktest -i booktest.examples.FailingTest")
    println("  booktest --batch-review booktest.examples.FailingTest")
    println("  booktest --diff-style side-by-side -v booktest.examples.FailingTest")
  }
  
  private def discoverAllTestSuites(): List[TestSuite] = {
    // Known test suite classes - in a full implementation, this could use reflection
    // to scan the classpath, but for now we'll use a hardcoded list of known examples
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
    
    knownTestSuites.flatMap { className =>
      try {
        val clazz = Class.forName(className)
        val constructor = clazz.getDeclaredConstructor()
        val instance = constructor.newInstance().asInstanceOf[TestSuite]
        Some(instance)
      } catch {
        case _: ClassNotFoundException =>
          // Class doesn't exist, skip it
          None
        case e: Exception =>
          println(s"Warning: Could not instantiate $className: ${e.getMessage}")
          None
      }
    }
  }
}