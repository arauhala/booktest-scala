package booktest

case class TestCase(
  name: String,
  testFunction: TestCaseRun => Any,
  dependencies: List[String] = List.empty
)

object TestCase {
  def apply(name: String, testFunction: TestCaseRun => Any): TestCase = {
    TestCase(name, testFunction, List.empty)
  }
}