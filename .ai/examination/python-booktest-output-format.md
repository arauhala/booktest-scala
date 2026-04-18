# Python Booktest Output Format Reference

This document captures the exact colors, symbols, and layout used by the Python
booktest framework for all types of output. Use this as a reference when aligning
the Scala port's output.

## Color Definitions (colors.py)

```
RESET       = \033[0m
RED         = \033[91m      # bright red - errors, FAIL status
YELLOW      = \033[93m      # bright yellow - diffs, DIFF status
GREEN       = \033[92m      # bright green - OK status
BLUE        = \033[94m      # bright blue
CYAN        = \033[96m      # bright cyan - info markers
GRAY        = \033[90m      # gray (bright black) - expected text in diffs
DIM         = \033[2m       # dim/faint modifier
BOLD        = \033[1m       # bold modifier
BRIGHT_WHITE = \033[97m
```

Color functions:
- `red(text)` = `\033[91m{text}\033[0m`
- `yellow(text)` = `\033[93m{text}\033[0m`
- `green(text)` = `\033[92m{text}\033[0m`
- `cyan(text)` = `\033[96m{text}\033[0m`
- `gray(text)` = `\033[90m{text}\033[0m`
- `dim_gray(text)` = `\033[2m\033[90m{text}\033[0m` (dim + gray, used for expected text base)

Color disabled when `NO_COLOR` env var is set or not a TTY.

---

## Test Status Labels

| State | Verbose format                          | Non-verbose format                   |
|-------|----------------------------------------|--------------------------------------|
| Pass  | `{green('OK')} {ms} ms.`              | `{green(f'{ms} ms')}`              |
| Diff  | `{yellow('DIFF')} {ms} ms`            | `{yellow('DIFF')} {ms} ms`         |
| Fail  | `{red('FAIL')} {ms} ms`               | `{red('FAIL')} {ms} ms`            |

Optional suffixes appended: ` (snapshot failure)`, ` (snapshots updated)`,
` ({gray('AI: ' + summary)})`.

---

## Test Line Layout

### Verbose mode
```
test {suite_path}/{test_name}
                                          <- blank line
  {test output lines...}                  <- 2-space indent, from report()
                                          <- blank line
{status} {ms} ms.
```

### Non-verbose mode (default, summary style)
```
  {suite_path}/{test_name} - {status} {ms} ms
```
Two-space indent, single line per test.

---

## Diff Display (commit_line in testcaserun.py)

### Format
```
{symbol} {actual_line:60s} | {expected_line}
```

- **actual_line** is padded to exactly 60 characters with spaces
- **separator** is literal ` | ` (space pipe space)
- **expected_line** shown as-is (not padded)

### Symbols

| Symbol | Meaning              | Color  | ANSI              | When used                        |
|--------|----------------------|--------|-------------------|----------------------------------|
| `!`    | Error/fail           | RED    | `\033[91m!\033[0m` | `fail` markers on line           |
| `?`    | Content diff         | YELLOW | `\033[93m?\033[0m` | `diff` markers, no `fail`        |
| `.`    | Info-only diff       | CYAN   | `\033[96m.\033[0m` | Only `info` markers, no diff/fail |
| (none) | Match (no diff)      | -      | `  `              | No markers at all                |

### Matching lines (no diff)
```
  {out_line}
```
Two spaces, then the line content. No `|` separator, no expected column.

### EOF case
When expected line is exhausted (no more snapshot):
```
{symbol} {actual:60s} | {gray('EOF')}
```
`EOF` displayed in gray.

### Token-level coloring

The actual line is colorized per-token based on markers:
- Each marker is `(start_pos, end_pos, marker_type)`
- Priority: `fail` (3) > `diff` (2) > `info` (1)
- Higher priority overrides at overlapping positions
- Token colors: `fail` -> red, `diff` -> yellow, `info` -> cyan
- Unmarked portions of a diff line use the line's primary color

The expected line (right side) is colorized:
- Base color: `dim_gray()` (DIM + GRAY = `\033[2m\033[90m`)
- Tokens that differ from actual: `gray()` (regular GRAY = `\033[90m`, slightly brighter)

### Position error pointer (optional)
```
  {spaces}^
```
Caret points to exact position of first error on line.

---

## Interactive Review Prompts (review.py)

### Prompt format
```python
prompt = ", ".join(options[:-1]) + " or " + options[-1] + "? "
```

### Options (in order, some conditional)

| Key | Option          | Condition                        | Action                          |
|-----|-----------------|----------------------------------|---------------------------------|
| `a` | `(a)ccept`      | Test is not FAIL (only DIFF)     | Freeze/update snapshot          |
| `c` | `(c)ontinue`    | Always                           | Skip, move to next test         |
| `q` | `(q)uit`        | Always                           | Abort entire run                |
| `v` | `(v)iew`        | Always                           | Open md_viewer with output      |
| `l` | `(l)ogs`        | Always                           | Open log_viewer with log file   |
| `d` | `(d)iff`        | Always                           | Open diff_tool (default: meld)  |
| `D` | `fast (D)iff`   | Always                           | Open fast_diff_tool (default: diff) |
| `R` | `AI (R)eview`   | DIFF test, no prior AI review    | Trigger AI review               |

### Example prompts
```
(a)ccept, (c)ontinue, (q)uit, (v)iew, (l)ogs, (d)iff, fast (D)iff or AI (R)eview? 
(c)ontinue, (q)uit, (v)iew, (l)ogs, (d)iff or fast (D)iff?      # FAIL test, no accept
```

### Non-verbose indent
In non-verbose mode, 4 spaces printed before prompt:
```python
if not config.get("verbose", False):
    print("    ", end="")
```

---

## Summary Output

### Header (always printed)
```

# test results:

```
Blank line, `# test results:`, blank line.

### All passed
```
{total}/{total} test succeeded in {ms} ms
```

### Some failed
```
{failed}/{total} test {summary} in {ms} ms:

  {path}::{test} - {status}
  {path}::{test} - {status}
  ...
```

Summary string built from parts:
- `"{n} differed"` if diffs > 0
- `"{n} failed"` if fails > 0
- Joined with `" and "`: e.g., `"2 differed and 1 failed"`

Failed test status colors:
- DIFF: `yellow('DIFF')`
- FAIL: `red('FAIL')`

Trailing blank line always printed.

---

## Report Files (.txt)

- Location: `{out_dir}/{test_name}.txt`
- Contains the same diff visualization as terminal but WITHOUT ANSI color codes
- Written via `report()` method which prints to file (and stdout if verbose)
- Format: same `{symbol} {actual:60} | {expected}` but plain text

---

## Tool Configuration

Tools launched from interactive prompts are configured in `booktest.ini`,
`~/.booktest`, or `BOOKTEST_*` environment variables:

| Tool           | Config key       | Default    |
|----------------|-----------------|------------|
| Diff viewer    | `diff_tool`     | `meld`     |
| Fast diff      | `fast_diff_tool`| `diff`     |
| Markdown viewer| `md_viewer`     | `retext --preview` |
| Log viewer     | `log_viewer`    | `less`     |

Resolution order (highest priority first):
1. `BOOKTEST_DIFF_TOOL` etc. environment variables
2. `.booktest` (project-local personal config)
3. `booktest.ini` (project config)
4. `~/.booktest` (user home config)
5. Built-in defaults

---

## Key Differences: Scala Port vs Python

Areas where the Scala port should match but currently differs:

1. **Diff symbol `?` for content diffs** - Python uses `?` (yellow), Scala uses `!` (red)
   for test-content diffs. `!` is only for `fail` markers in Python.

2. **Token-level coloring** - Python colors individual tokens within a diff line
   (e.g., only the changed word is yellow). Scala colors the entire line.

3. **Expected line color** - Python uses `dim_gray` (DIM+GRAY) as base with
   `gray` highlights for differing tokens. Scala uses plain gray.

4. **Matching lines** - Python shows `  {line}` (no pipe, no expected column).
   Scala should match this.

5. **Non-verbose test line format** - Python: `  {name} - {status} {ms} ms`.
   Check Scala matches the dash separator and indent.

6. **Summary format** - Python separates "differed" from "failed" counts.
   Check Scala summary matches.

7. **Report files** - Python writes plain-text (no ANSI) diff report to .txt.
   Check if Scala does the same.
