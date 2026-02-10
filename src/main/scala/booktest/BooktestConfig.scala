package booktest

import os.Path

/**
 * Configuration for booktest read from booktest.conf
 *
 * Format (Python-style):
 *   test-root = booktest             # Package prefix to strip from test paths
 *   test-packages = booktest.examples # Packages to scan for TestSuite classes
 *   default = booktest.examples       # What to run by default (package or class)
 *   books-path = books                # Where to store snapshots
 */
case class BooktestConfig(
  root: Option[String] = None,          // Root namespace (e.g., "booktest")
  defaultTests: Option[String] = None,  // Default tests to run (relative to root)
  groups: Map[String, List[String]] = Map.empty,  // Named groups (e.g., "all" -> List("examples", "perf"))
  excludePatterns: List[String] = List.empty,  // Patterns to exclude from default runs
  booksPath: String = "books"           // Where to store snapshots
) {

  /** Resolve a relative path to full package name by prepending root */
  def resolvePath(relativePath: String): String = {
    root match {
      case Some(r) => s"$r.$relativePath"
      case None => relativePath
    }
  }

  /** Strip root prefix from a fully qualified class name to get the relative path */
  def stripRoot(className: String): String = {
    root match {
      case Some(r) if className.startsWith(r + ".") =>
        className.drop(r.length + 1)
      case _ =>
        className
    }
  }

  /** Convert a class name to a filesystem path (replacing . with /) */
  def classNameToPath(className: String): String = {
    stripRoot(className).replace('.', '/')
  }

  /** Get a group by name, resolving paths relative to root */
  def getGroup(name: String): Option[List[String]] = {
    groups.get(name).map(_.map(resolvePath))
  }

  /** Check if a class name matches any exclude pattern */
  def isExcluded(className: String): Boolean = {
    excludePatterns.exists(pattern => className.contains(pattern))
  }
}

object BooktestConfig {

  val ConfigFileName = "booktest.ini"

  /**
   * Load configuration from booktest.conf in the current directory or parent directories
   */
  def load(startDir: Path = os.pwd): Option[BooktestConfig] = {
    findConfigFile(startDir).flatMap { configPath =>
      try {
        Some(parseConfigFile(configPath))
      } catch {
        case e: Exception =>
          System.err.println(s"Warning: Failed to parse $configPath: ${e.getMessage}")
          None
      }
    }
  }

  /**
   * Find booktest.conf by searching current directory and parent directories
   */
  private def findConfigFile(startDir: Path): Option[Path] = {
    var current = startDir
    while (current != os.root) {
      val configPath = current / ConfigFileName
      if (os.exists(configPath)) {
        return Some(configPath)
      }
      current = current / os.up
    }
    None
  }

  /**
   * Parse the configuration file
   */
  private def parseConfigFile(path: Path): BooktestConfig = {
    val lines = os.read.lines(path)
    var root: Option[String] = None
    var defaultTests: Option[String] = None
    var excludePatterns: List[String] = List.empty
    var booksPath: String = "books"
    val groups = scala.collection.mutable.Map[String, List[String]]()

    lines.foreach { line =>
      val trimmed = line.trim

      // Skip empty lines and comments
      if (trimmed.nonEmpty && !trimmed.startsWith("#") && !trimmed.startsWith("//")) {
        // Parse key = value format
        val parts = trimmed.split("=", 2)
        if (parts.length == 2) {
          val key = parts(0).trim
          val value = parts(1).trim

          key match {
            case "root" | "test-root" =>  // Support both for compatibility
              if (value.nonEmpty) root = Some(value)
            case "default" =>
              if (value.nonEmpty) defaultTests = Some(value)
            case "exclude" =>
              excludePatterns = value.split(",").map(_.trim).filter(_.nonEmpty).toList
            case "books-path" =>
              if (value.nonEmpty) booksPath = value
            case _ =>
              // Treat as a group definition (e.g., "all = examples, perf")
              val packages = value.split(",").map(_.trim).filter(_.nonEmpty).toList
              if (packages.nonEmpty) {
                groups(key) = packages
              }
          }
        }
      }
    }

    BooktestConfig(root, defaultTests, groups.toMap, excludePatterns, booksPath)
  }

  /**
   * Create a default configuration with no groups defined
   */
  def empty: BooktestConfig = BooktestConfig()
}
