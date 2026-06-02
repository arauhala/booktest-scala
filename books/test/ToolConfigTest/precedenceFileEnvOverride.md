# Precedence: CLI override > env > .booktest > booktest.ini > ~/.booktest

after env layer applied (no CLI override):
  resolved: List((diff_tool,env-meld), (md_viewer,ini-retext))
  diff_tool -> env-meld
  md_viewer -> ini-retext
after CLI --diff-tool override:
  diff_tool -> cli-meld
  md_viewer -> ini-retext
