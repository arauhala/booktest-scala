package booktest

case class TestCase(
  name: String,
  testFunction: TestCaseRun => Unit,
  dependencies: List[String] = List.empty
)

object TestCase {
  def apply(name: String, testFunction: TestCaseRun => Unit): TestCase = {
    TestCase(name, testFunction, List.empty)
  }
}