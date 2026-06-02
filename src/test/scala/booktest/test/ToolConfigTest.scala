package booktest.test

import booktest._

/** Meta test: verifies ToolConfig resolution order
  * (CLI override → file layer → env → defaults).
  *
  * Drives the parser via temp files and a stubbed env source; isolates the
  * global ToolConfig singleton via reset()/setOverride. Does not exec any
  * real tools. */
class ToolConfigTest extends TestSuite {

  // All tests in this suite mutate the global ToolConfig — serialize them.
  override protected def resourceLocks: List[String] = List("ToolConfig")

  def testDefaultsWhenNothingConfigured(t: TestCaseRun): Unit = {
    t.h1("Defaults when no config / env / override is present")
    val sandbox = t.tmpDir("empty")
    withSandbox(sandbox, env = Map.empty) {
      val resolved = ToolConfig.resolve()
      t.tln(s"resolved file+env layer: ${resolved.toList.sortBy(_._1)}")
      ToolConfig.ToolKeys.toList.sorted.foreach { k =>
        t.tln(s"$k -> ${ToolConfig.lookup(k).getOrElse("(none)")}")
      }
    }
  }

  def testIniFileLayer(t: TestCaseRun): Unit = {
    t.h1("booktest.ini provides tool values")
    val sandbox = t.tmpDir("ini-layer")
    os.write(sandbox / "booktest.ini",
      """diff_tool = meld
        |md_viewer = retext --preview
        |""".stripMargin)
    withSandbox(sandbox, env = Map.empty) {
      val resolved = ToolConfig.resolve()
      t.tln(s"resolved: ${resolved.toList.sortBy(_._1)}")
      t.tln(s"diff_tool -> ${ToolConfig.lookup("diff_tool").getOrElse("(none)")}")
      t.tln(s"md_viewer -> ${ToolConfig.lookup("md_viewer").getOrElse("(none)")}")
      t.tln(s"log_viewer (default) -> ${ToolConfig.lookup("log_viewer").getOrElse("(none)")}")
    }
  }

  def testPrecedenceFileEnvOverride(t: TestCaseRun): Unit = {
    t.h1("Precedence: CLI override > env > .booktest > booktest.ini > ~/.booktest")
    val sandbox = t.tmpDir("precedence")
    val fakeHome = sandbox / "home"
    os.makeDir.all(fakeHome)
    os.write(fakeHome / ".booktest", "diff_tool = home-meld\nmd_viewer = home-retext\n")
    os.write(sandbox / "booktest.ini", "diff_tool = ini-meld\nmd_viewer = ini-retext\n")
    os.write(sandbox / ".booktest", "diff_tool = dot-meld\n")

    withSandbox(
      sandbox,
      homeOverride = Some(fakeHome),
      env = Map("BOOKTEST_DIFF_TOOL" -> "env-meld")
    ) {
      t.tln("after env layer applied (no CLI override):")
      val resolved = ToolConfig.resolve()
      t.tln(s"  resolved: ${resolved.toList.sortBy(_._1)}")
      t.tln(s"  diff_tool -> ${ToolConfig.lookup("diff_tool").getOrElse("(none)")}")
      t.tln(s"  md_viewer -> ${ToolConfig.lookup("md_viewer").getOrElse("(none)")}")

      ToolConfig.setOverride("diff_tool", "cli-meld")
      t.tln("after CLI --diff-tool override:")
      t.tln(s"  diff_tool -> ${ToolConfig.lookup("diff_tool").getOrElse("(none)")}")
      t.tln(s"  md_viewer -> ${ToolConfig.lookup("md_viewer").getOrElse("(none)")}")
    }
  }

  def testParserIgnoresNonToolKeys(t: TestCaseRun): Unit = {
    t.h1("Parser ignores comments, blank lines, and non-tool keys")
    val sandbox = t.tmpDir("parser")
    val cfg = sandbox / "booktest.ini"
    os.write(cfg,
      """# project settings
        |root = booktest
        |default = examples
        |
        |; group definition (should not become a tool)
        |all = test, examples
        |
        |diff_tool = meld
        |md_viewer =
        |fast_diff_tool = diff
        |""".stripMargin)
    val parsed = ToolConfig.parseToolsFromFile(cfg)
    t.tln(s"parsed tools: ${parsed.toList.sortBy(_._1)}")
  }

  // --- helpers ---

  /** Run `body` with `user.dir`/`user.home` pointed at the sandbox and the
    * env-source hook stubbed to `env`. ToolConfig reads these via
    * `System.getProperty` and the `envSource` hook, so this gives clean
    * test isolation without touching the real process env. */
  private def withSandbox(
    sandbox: os.Path,
    env: Map[String, String],
    homeOverride: Option[os.Path] = None
  )(body: => Unit): Unit = {
    val originalDir = System.getProperty("user.dir")
    val originalHome = System.getProperty("user.home")

    System.setProperty("user.dir", sandbox.toString)
    // Point user.home at an empty dir so a real ~/.booktest never leaks in.
    val home = homeOverride.getOrElse {
      val h = sandbox / "empty-home"
      os.makeDir.all(h)
      h
    }
    System.setProperty("user.home", home.toString)

    val originalEnvSource = ToolConfig.envSource
    ToolConfig.envSource = env.get
    ToolConfig.reset() // clear cached resolution and overrides
    ToolConfig.envSource = env.get // reset() restored sys.env.get; re-apply

    try body
    finally {
      System.setProperty("user.dir", originalDir)
      System.setProperty("user.home", originalHome)
      ToolConfig.reset()
      ToolConfig.envSource = originalEnvSource
    }
  }
}
