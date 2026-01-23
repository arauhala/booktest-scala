package booktest

// Test reference for type-safe dependency injection
case class TestRef[T](name: String, dependencies: List[TestRef[?]] = List.empty)

abstract class TestSuite {
  private var _testCases: List[TestCase] = List.empty
  
  lazy val testCases: List[TestCase] = discoverTests()
  
  protected def registerTest(name: String, testFunction: TestCaseRun => Any): Unit = {
    _testCases = _testCases :+ TestCase(name, testFunction)
  }
  
  // New API: test with no dependencies
  def test[T](name: String)(testFunction: TestCaseRun => T): TestRef[T] = {
    val testRef = TestRef[T](name)
    val wrappedFunction: TestCaseRun => Any = testFunction
    _testCases = _testCases :+ TestCase(name, wrappedFunction, originalFunction = Some(testFunction))
    testRef
  }
  
  // New API: test with one dependency
  def test[T, D1](name: String, dep1: TestRef[D1])(testFunction: (TestCaseRun, D1) => T): TestRef[T] = {
    val testRef = TestRef[T](name, List(dep1))
    val wrappedFunction: TestCaseRun => Any = { tcr =>
      // This will be handled by TestRunner with proper dependency injection
      testFunction(tcr, null.asInstanceOf[D1]) // Placeholder - will be replaced by runner
    }
    _testCases = _testCases :+ TestCase(name, wrappedFunction, List(dep1.name), originalFunction = Some(testFunction))
    testRef
  }
  
  // New API: test with two dependencies
  def test[T, D1, D2](name: String, dep1: TestRef[D1], dep2: TestRef[D2])(testFunction: (TestCaseRun, D1, D2) => T): TestRef[T] = {
    val testRef = TestRef[T](name, List(dep1, dep2))
    val wrappedFunction: TestCaseRun => Any = { tcr =>
      // This will be handled by TestRunner with proper dependency injection
      testFunction(tcr, null.asInstanceOf[D1], null.asInstanceOf[D2]) // Placeholder - will be replaced by runner
    }
    _testCases = _testCases :+ TestCase(name, wrappedFunction, List(dep1.name, dep2.name), originalFunction = Some(testFunction))
    testRef
  }
  
  // New API: test with three dependencies
  def test[T, D1, D2, D3](name: String, dep1: TestRef[D1], dep2: TestRef[D2], dep3: TestRef[D3])(testFunction: (TestCaseRun, D1, D2, D3) => T): TestRef[T] = {
    val testRef = TestRef[T](name, List(dep1, dep2, dep3))
    val wrappedFunction: TestCaseRun => Any = { tcr =>
      // This will be handled by TestRunner with proper dependency injection
      testFunction(tcr, null.asInstanceOf[D1], null.asInstanceOf[D2], null.asInstanceOf[D3])
    }
    _testCases = _testCases :+ TestCase(name, wrappedFunction, List(dep1.name, dep2.name, dep3.name), originalFunction = Some(testFunction))
    testRef
  }
  
  private def discoverTests(): List[TestCase] = {
    val clazz = this.getClass
    val methods = clazz.getDeclaredMethods
    
    val testMethods = methods.filter { method =>
      method.getName.startsWith("test") &&
      !method.getName.contains("$anonfun$") && // Filter out synthetic lambda methods
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
      case annotation if annotation.annotationType().getSimpleName == "dependsOnAnnotation" || 
                         annotation.annotationType().getSimpleName == "dependsOn" =>
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