package booktest

/** External-tool configuration (diff_tool, fast_diff_tool, md_viewer, log_viewer).
  *
  * Mirrors Python booktest's three-tier resolution:
  *   1. `~/.booktest`          (personal, gitignored)
  *   2. `./booktest.ini`       (project, committed)
  *   3. `./.booktest`          (project-local, gitignored)
  *   4. `BOOKTEST_*` env vars  (highest priority)
  *
  * CLI flags (`--diff-tool`, `--md-viewer`) sit on top via `setOverride`.
  */
object ToolConfig {

  val ToolKeys: Set[String] = Set("diff_tool", "fast_diff_tool", "md_viewer", "log_viewer")

  val Defaults: Map[String, String] = Map(
    "diff_tool" -> "diff",
    "fast_diff_tool" -> "diff",
    "md_viewer" -> "less",
    "log_viewer" -> "less"
  )

  @volatile private var cached: Option[Map[String, String]] = None
  @volatile private var overrides: Map[String, String] = Map.empty

  /** Test hook: lookup function used for `BOOKTEST_*` env vars. Production code
    * uses `sys.env.get`; tests can substitute a closure over a fake env without
    * having to reflectively mutate the JVM's process-environment map (which
    * requires `--add-opens` on JDK 17+). */
  @volatile private[booktest] var envSource: String => Option[String] = sys.env.get

  /** Resolved file+env layer, memoized. */
  def resolved: Map[String, String] = cached match {
    case Some(m) => m
    case None =>
      val r = resolve()
      cached = Some(r)
      r
  }

  /** CLI override — wins over file and env. */
  def setOverride(toolName: String, cmd: String): Unit = {
    overrides = overrides + (toolName -> cmd)
  }

  /** Look up `toolName`: overrides → resolved (files+env) → defaults. */
  def lookup(toolName: String): Option[String] =
    overrides.get(toolName)
      .orElse(resolved.get(toolName))
      .orElse(Defaults.get(toolName))

  /** Test hook: clear memoized state, overrides, and env-source hook. */
  def reset(): Unit = {
    cached = None
    overrides = Map.empty
    envSource = sys.env.get
  }

  /** Lower-level: read tool settings from the file/env layer in resolution order.
    * Public for tests and --setup which want to display the current view.
    *
    * Reads `user.home` / `user.dir` from system properties each call so tests
    * can redirect lookups — `os.pwd` and `os.home` are vals evaluated at JVM
    * startup and don't pick up later changes. */
  def resolve(): Map[String, String] = {
    val homeProp = Option(System.getProperty("user.home")).filter(_.nonEmpty)
    val dirProp  = Option(System.getProperty("user.dir")).filter(_.nonEmpty)

    val files: List[os.Path] =
      homeProp.map(h => os.Path(h) / ".booktest").toList :::
        dirProp.toList.flatMap(d => List(os.Path(d) / "booktest.ini", os.Path(d) / ".booktest"))

    val fileLayer = files.foldLeft(Map.empty[String, String]) { (acc, p) =>
      if (os.exists(p)) acc ++ parseToolsFromFile(p) else acc
    }

    val envLayer = ToolKeys.flatMap { k =>
      envSource(s"BOOKTEST_${k.toUpperCase}").map(v => k -> v)
    }.toMap

    fileLayer ++ envLayer
  }

  /** Parse only the tool keys out of a Python-style ini file. Group keys and
    * other project settings are ignored here — they belong to `BooktestConfig`. */
  def parseToolsFromFile(path: os.Path): Map[String, String] = {
    val rv = scala.collection.mutable.Map.empty[String, String]
    try {
      os.read.lines(path).foreach { rawLine =>
        val line = rawLine.trim
        if (line.nonEmpty && !line.startsWith("#") && !line.startsWith(";") && !line.startsWith("//")) {
          val idx = line.indexOf('=')
          if (idx > 0) {
            val key = line.substring(0, idx).trim
            val value = line.substring(idx + 1).trim
            if (ToolKeys.contains(key) && value.nonEmpty) rv(key) = value
          }
        }
      }
    } catch {
      case _: Exception => // unreadable file: treat as absent
    }
    rv.toMap
  }
}
