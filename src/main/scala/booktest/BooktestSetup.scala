package booktest

/** `booktest --setup` — interactive personal-tool configuration.
  *
  * Writes `~/.booktest` with `diff_tool`, `fast_diff_tool`, `md_viewer`,
  * `log_viewer`. Mirrors Python booktest's `setup_personal`. */
object BooktestSetup {

  private val PersonalHeader =
    """#
      |# This file is meant for personal UI configuration with booktest.
      |#
      |# This file should be included in .gitignore and never committed to version control!
      |#
      |""".stripMargin

  private case class ToolPrompt(key: String, defaultCmd: String, comment: String)

  private val Prompts: List[ToolPrompt] = List(
    ToolPrompt(
      "diff_tool",
      "meld",
      """#
        |# diff_tool is used to see changes between expected and actual snapshots.
        |#
        |# One option is Meld: https://meldmerge.org/
        |#   sudo apt install meld
        |#
        |""".stripMargin
    ),
    ToolPrompt(
      "fast_diff_tool",
      "diff",
      """#
        |# fast_diff_tool is used to see changes quickly (default: diff).
        |#
        |""".stripMargin
    ),
    ToolPrompt(
      "md_viewer",
      "retext --preview",
      """#
        |# md_viewer is used to view the markdown content (tables, lists, images).
        |#
        |# One option is ReText: https://github.com/retext-project/retext
        |#   sudo apt install retext
        |#
        |""".stripMargin
    ),
    ToolPrompt(
      "log_viewer",
      "less",
      """#
        |# log_viewer is used to view logs (default: less).
        |#
        |""".stripMargin
    )
  )

  /** Run the interactive setup. Returns process exit code. */
  def run(): Int = {
    println()
    println("setup asks you to specify external tools for your personal booktest config in ~/.booktest")
    println("==========================================================================================")
    println()

    val current = ToolConfig.resolve()
    val answers = Prompts.map { p =>
      print(p.comment)
      val effectiveDefault = current.getOrElse(p.key, p.defaultCmd)
      print(s"specify ${p.key} (default '$effectiveDefault'): ")
      val raw = Option(scala.io.StdIn.readLine()).map(_.trim).getOrElse("")
      val value = if (raw.isEmpty) effectiveDefault else raw
      println()
      println(s"${p.key}=$value")
      println()
      p -> value
    }

    val homeOpt = sys.props.get("user.home").map(os.Path(_))
    homeOpt match {
      case Some(home) =>
        val target = home / ".booktest"
        val sb = new StringBuilder
        sb.append(PersonalHeader)
        sb.append('\n')
        answers.foreach { case (p, value) =>
          sb.append(p.comment)
          sb.append(s"${p.key}=$value\n\n")
        }
        os.write.over(target, sb.toString)
        // Invalidate the cache so subsequent lookups see the new file.
        ToolConfig.reset()
        println(s"updated $target")
        0
      case None =>
        println("Could not determine home directory (user.home is unset).")
        1
    }
  }
}
