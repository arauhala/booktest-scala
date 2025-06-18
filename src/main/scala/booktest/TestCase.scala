package booktest

case class TestCase(
  name: String,
  testFunction: TestCaseRun => Any,
  dependencies: List[String] = List.empty,
  method: Option[java.lang.reflect.Method] = None,
  testInstance: Option[TestSuite] = None
)

object TestCase {
  def apply(name: String, testFunction: TestCaseRun => Any): TestCase = {
    TestCase(name, testFunction, List.empty)
  }
}