package booktest

object BooktestMain {
  
  def main(args: Array[String]): Unit = {
    var verbose = false
    var interactive = false
    var outputDir = "books"
    var snapshotDir = "books"
    val testClasses = scala.collection.mutable.ListBuffer[String]()
    
    var i = 0
    while (i < args.length) {
      args(i) match {
        case "-v" | "--verbose" => verbose = true
        case "-i" | "--interactive" => interactive = true
        case "--output-dir" =>
          i += 1
          if (i < args.length) outputDir = args(i)
        case "--snapshot-dir" =>
          i += 1
          if (i < args.length) snapshotDir = args(i)
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
      interactive = interactive
    )
    
    val runner = new TestRunner(config)
    
    if (testClasses.isEmpty) {
      println("No test classes specified. Use --help for usage information.")
      sys.exit(1)
    }
    
    val suites = testClasses.toList.flatMap { className =>
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
    
    if (suites.isEmpty) {
      println("No valid test suites found.")
      sys.exit(1)
    }
    
    val result = runner.runMultipleSuites(suites)
    
    println()
    println(result.summary)
    
    if (!result.success) {
      sys.exit(1)
    }
  }
  
  def printHelp(): Unit = {
    println("Booktest - Review-driven testing for Scala")
    println("Usage: booktest [options] <test-class-names>")
    println()
    println("Options:")
    println("  -v, --verbose       Verbose output")
    println("  -i, --interactive   Interactive mode (not yet implemented)")
    println("  --output-dir DIR    Output directory for test results (default: books)")
    println("  --snapshot-dir DIR  Snapshot directory (default: books)")
    println("  -h, --help          Show this help message")
    println()
    println("Examples:")
    println("  booktest booktest.examples.ExampleTests")
    println("  booktest -v booktest.examples.ExampleTests")
  }
}