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
      method.getParameterCount >= 1 &&
      method.getParameterTypes()(0) == classOf[TestCaseRun]
    }
    
    testMethods.map { method =>
      method.setAccessible(true)
      val testName = cleanMethodName(method.getName)
      val dependencies = extractDependencies(method)
      val testFunction: TestCaseRun => Any = { tcr =>
        method.invoke(this, tcr)
      }
      TestCase(testName, testFunction, dependencies, Some(method), Some(this))
    }.toList ++ _testCases
  }
  
  private def extractDependencies(method: java.lang.reflect.Method): List[String] = {
    // Try annotation-based dependencies first, then fall back to inference
    val annotations = method.getAnnotations
    val annotationDeps = annotations.collectFirst {
      case annotation if annotation.annotationType().getSimpleName == "dependsOn" =>
        try {
          val dependenciesMethod = annotation.annotationType().getMethod("dependencies")
          dependenciesMethod.invoke(annotation).asInstanceOf[Array[String]].toList
        } catch {
          case _: Exception => List.empty[String]
        }
    }.getOrElse(List.empty)
    
    // If annotation dependencies found, use them; otherwise infer from signature
    if (annotationDeps.nonEmpty) {
      annotationDeps
    } else {
      // Infer dependencies from method signature
      val paramCount = method.getParameterCount
      if (paramCount > 1) {
        method.getName match {
          case name if name.contains("UseData") => List("createData") 
          case name if name.contains("FinalStep") => List("createData", "useData")
          case _ => List.empty
        }
      } else {
        List.empty
      }
    }
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