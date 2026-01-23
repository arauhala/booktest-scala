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
  
  def writeToFile(file: Path): Unit = {
    val content = cases.map { case CaseReport(name, result, duration) =>
      s"$name\t$result\t$duration"
    }.mkString("\n")
    
    os.makeDir.all(file / os.up)
    os.write.over(file, content)
  }
  
  def casesToDoneAndTodo(selectedCases: List[String], config: RunConfig): (List[String], List[String]) = {
    val continueMode = false // TODO: implement continue mode
    val passedSet = passed().toSet
    
    if (continueMode) {
      val done = selectedCases.filter(passedSet.contains)
      val todo = selectedCases.filterNot(passedSet.contains)
      (done, todo)
    } else {
      (List.empty, selectedCases)
    }
  }
}

object CaseReports {
  def empty: CaseReports = new CaseReports(List.empty)
  
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
  
  def fromDir(outDir: Path): CaseReports = {
    fromFile(outDir / "cases.txt")
  }
}