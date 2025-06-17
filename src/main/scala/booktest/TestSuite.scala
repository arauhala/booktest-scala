package booktest

abstract class TestSuite {
  private var _testCases: List[TestCase] = List.empty
  
  lazy val testCases: List[TestCase] = discoverTests()
  
  protected def registerTest(name: String, testFunction: TestCaseRun => Any): Unit = {
    _testCases = _testCases :+ TestCase(name, testFunction)
  }
  
  private def discoverTests(): List[TestCase] = {
    val clazz = this.getClass
    val methods = clazz.getDeclaredMethods
    
    val testMethods = methods.filter { method =>
      method.getName.startsWith("test") &&
      method.getParameterCount == 1 &&
      method.getParameterTypes()(0) == classOf[TestCaseRun]
    }
    
    testMethods.map { method =>
      method.setAccessible(true)
      val testName = cleanMethodName(method.getName)
      val dependencies = extractDependencies(method)
      val testFunction: TestCaseRun => Any = { tcr =>
        method.invoke(this, tcr)
      }
      TestCase(testName, testFunction, dependencies)
    }.toList ++ _testCases
  }
  
  private def extractDependencies(method: java.lang.reflect.Method): List[String] = {
    val annotations = method.getAnnotations
    annotations.collectFirst {
      case annotation if annotation.annotationType().getSimpleName == "dependsOn" =>
        try {
          val dependenciesMethod = annotation.annotationType().getMethod("dependencies")
          dependenciesMethod.invoke(annotation).asInstanceOf[Array[String]].toList
        } catch {
          case _: Exception => List.empty[String]
        }
    }.getOrElse(List.empty)
  }
  
  private def cleanMethodName(methodName: String): String = {
    if (methodName.startsWith("test")) {
      val cleanName = methodName.substring(4)
      if (cleanName.nonEmpty) {
        s"${cleanName.head.toLower}${cleanName.tail}"
      } else {
        methodName
      }
    } else {
      methodName
    }
  }
  
  def suiteName: String = {
    val className = this.getClass.getSimpleName
    if (className.endsWith("$")) {
      className.dropRight(1)
    } else {
      className
    }
  }
}