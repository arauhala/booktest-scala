package booktest

import os.Path

case class CaseReport(
  testName: String,
  result: String, // "OK", "DIFF", "FAIL"
  durationMs: Long
)

class CaseReports(val cases: List[CaseReport]) {
  
  def passed(): List[String] = {
    cases.filter(_.result == "OK").map(_.testName)
  }
  
  def failed(): List[String] = {
    cases.filter(c => c.result == "DIFF" || c.result == "FAIL").map(_.testName)
  }
  
  /** Write to legacy text format (cases.txt) */
  def writeToFile(file: Path): Unit = {
    val content = cases.map { case CaseReport(name, result, duration) =>
      s"$name\t$result\t$duration"
    }.mkString("\n")

    os.makeDir.all(file / os.up)
    os.write.over(file, content)
  }

  /** Write to NDJSON format (cases.ndjson) - Python compatible */
  def writeToNdjsonFile(file: Path): Unit = {
    val lines = cases.map { case CaseReport(name, result, duration) =>
      s"""{"name":"$name","result":"$result","duration_ms":$duration}"""
    }
    os.makeDir.all(file / os.up)
    os.write.over(file, lines.mkString("\n") + (if (lines.nonEmpty) "\n" else ""))
  }
  
  /** Python-style continue logic:
    * If continueMode is true, skip tests that passed in the previous run.
    * Returns (done, todo) where done are skipped and todo are to be run.
    */
  def casesToDoneAndTodo(selectedCases: List[String], continueMode: Boolean): (List[CaseReport], List[String]) = {
    val passedSet = passed().toSet
    val caseMap = cases.map(c => c.testName -> c).toMap

    if (continueMode) {
      // Continue mode: skip tests that passed in the previous run
      val done = selectedCases.filter(passedSet.contains).flatMap(caseMap.get)
      val todo = selectedCases.filterNot(passedSet.contains)
      (done, todo)
    } else {
      // Normal mode: run all selected tests
      (List.empty, selectedCases)
    }
  }

  /** Get a case report by name */
  def byName(name: String): Option[CaseReport] = {
    cases.find(_.testName == name)
  }

  /** Get cases not in the selected list (to preserve from previous run) */
  def casesNotIn(selectedCases: List[String]): List[CaseReport] = {
    val selectedSet = selectedCases.toSet
    cases.filterNot(c => selectedSet.contains(c.testName))
  }
}

object CaseReports {
  def empty: CaseReports = new CaseReports(List.empty)

  /** Load from legacy text format (cases.txt) */
  def fromFile(file: Path): CaseReports = {
    if (os.exists(file)) {
      try {
        val content = os.read(file)
        val cases = content.split("\n").toList.filter(_.nonEmpty).map { line =>
          val parts = line.split("\t")
          if (parts.length >= 3) {
            CaseReport(parts(0), parts(1), parts(2).toLong)
          } else {
            CaseReport(parts(0), "FAIL", 0L)
          }
        }
        new CaseReports(cases)
      } catch {
        case _: Exception => empty
      }
    } else {
      empty
    }
  }

  /** Load from NDJSON format (cases.ndjson) - Python compatible */
  def fromNdjsonFile(file: Path): CaseReports = {
    if (os.exists(file)) {
      try {
        val content = os.read(file)
        val cases = content.split("\n").toList.filter(_.nonEmpty).flatMap { line =>
          // Simple JSON parsing (avoiding full JSON library dependency)
          try {
            val name = extractJsonField(line, "name")
            val result = extractJsonField(line, "result")
            val duration = extractJsonField(line, "duration_ms").toLong
            Some(CaseReport(name, result, duration))
          } catch {
            case _: Exception => None
          }
        }
        new CaseReports(cases)
      } catch {
        case _: Exception => empty
      }
    } else {
      empty
    }
  }

  /** Extract a string field from JSON (simple parser, no library) */
  private def extractJsonField(json: String, field: String): String = {
    // Match "field":"value" or "field":number
    val stringPattern = s""""$field":"([^"]*?)"""".r
    val numberPattern = s""""$field":([0-9.]+)""".r

    stringPattern.findFirstMatchIn(json) match {
      case Some(m) => m.group(1)
      case None =>
        numberPattern.findFirstMatchIn(json) match {
          case Some(m) => m.group(1)
          case None => throw new Exception(s"Field $field not found in $json")
        }
    }
  }

  /** Load from directory - prefers NDJSON format over text format */
  def fromDir(outDir: Path): CaseReports = {
    val ndjsonFile = outDir / "cases.ndjson"
    val txtFile = outDir / "cases.txt"

    if (os.exists(ndjsonFile)) {
      fromNdjsonFile(ndjsonFile)
    } else if (os.exists(txtFile)) {
      fromFile(txtFile)
    } else {
      empty
    }
  }
}