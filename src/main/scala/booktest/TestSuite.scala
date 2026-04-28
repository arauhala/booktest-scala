package booktest

// Test reference for type-safe dependency injection
case class TestRef[T](name: String, dependencies: List[TestRef[?]] = List.empty)

// Test markers/tags for filtering and categorization
object TestMarkers {
  val Slow = "slow"
  val Fast = "fast"
  val Integration = "integration"
  val Unit = "unit"
  val GPU = "gpu"
  val Network = "network"
  val Flaky = "flaky"
}

abstract class TestSuite {
  private var _testCases: List[TestCase] = List.empty
  private var _markers: Map[String, Set[String]] = Map.empty  // testName -> markers
  private var _liveResources: List[LiveResourceDef[?]] = List.empty

  lazy val testCases: List[TestCase] = discoverTests()

  /** All live-resource declarations made in this suite. */
  def liveResources: List[LiveResourceDef[?]] = _liveResources

  /** Access to the runner's ResourceManager for declaring pool deps in
    * `liveResource(...)`. Forwards to the global default; the runner threads
    * its own ResourceManager into the same instance via fromEnv(). */
  protected def resources: ResourceManager = ResourceManager.default

  // ============ Setup/Teardown Hooks ============

  /** Override to run setup before each test */
  protected def setup(t: TestCaseRun): Unit = {}

  /** Override to run teardown after each test (even if test fails) */
  protected def teardown(t: TestCaseRun): Unit = {}

  /** Override to run once before all tests in the suite */
  protected def beforeAll(): Unit = {}

  /** Override to run once after all tests in the suite */
  protected def afterAll(): Unit = {}

  /** Get setup function for TestRunner */
  def getSetup: TestCaseRun => Unit = setup

  /** Get teardown function for TestRunner */
  def getTeardown: TestCaseRun => Unit = teardown

  /** Get beforeAll function for TestRunner */
  def getBeforeAll: () => Unit = () => beforeAll()

  /** Get afterAll function for TestRunner */
  def getAfterAll: () => Unit = () => afterAll()

  // ============ Resource Locks ============

  /** Override to specify locks that must be held during test execution.
    * Tests in different suites with the same lock name will not run in parallel.
    * Useful for tests that share mutable state or external resources.
    *
    * Example: override def resourceLocks: List[String] = List("database", "shared-state")
    */
  protected def resourceLocks: List[String] = List.empty

  /** Get resource locks for TestRunner */
  def getResourceLocks: List[String] = resourceLocks

  // ============ Test Markers ============

  /** Mark a test with tags for filtering */
  protected def mark(testName: String, markers: String*): Unit = {
    val existing = _markers.getOrElse(testName, Set.empty)
    _markers = _markers + (testName -> (existing ++ markers.toSet))
  }

  /** Get markers for a test */
  def getMarkers(testName: String): Set[String] = _markers.getOrElse(testName, Set.empty)

  /** Check if a test has a specific marker */
  def hasMarker(testName: String, marker: String): Boolean = getMarkers(testName).contains(marker)

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

  // -- Test consumers of live resources (ResourceRef in dep list) --
  // Mirror the test(...) shape but accept a ResourceRef for one of the deps.
  // Type system distinguishes the consumer parameter as the live handle T,
  // not ResourceRef[T] — runner unwraps at the dep edge.

  def test[T, R1](name: String, res1: ResourceRef[R1])(testFunction: (TestCaseRun, R1) => T): TestRef[T] = {
    val testRef = TestRef[T](name, List(TestRef[R1](res1.name)))
    val wrappedFunction: TestCaseRun => Any = { tcr =>
      testFunction(tcr, null.asInstanceOf[R1])
    }
    _testCases = _testCases :+ TestCase(
      name, wrappedFunction, List(res1.name),
      originalFunction = Some(testFunction)
    )
    testRef
  }

  def test[T, D1, R1](name: String, dep1: TestRef[D1], res1: ResourceRef[R1])(testFunction: (TestCaseRun, D1, R1) => T): TestRef[T] = {
    val testRef = TestRef[T](name, List(dep1, TestRef[R1](res1.name)))
    val wrappedFunction: TestCaseRun => Any = { tcr =>
      testFunction(tcr, null.asInstanceOf[D1], null.asInstanceOf[R1])
    }
    _testCases = _testCases :+ TestCase(
      name, wrappedFunction, List(dep1.name, res1.name),
      originalFunction = Some(testFunction)
    )
    testRef
  }

  def test[T, R1, R2](name: String, res1: ResourceRef[R1], res2: ResourceRef[R2])(testFunction: (TestCaseRun, R1, R2) => T): TestRef[T] = {
    val testRef = TestRef[T](name, List(TestRef[R1](res1.name), TestRef[R2](res2.name)))
    val wrappedFunction: TestCaseRun => Any = { tcr =>
      testFunction(tcr, null.asInstanceOf[R1], null.asInstanceOf[R2])
    }
    _testCases = _testCases :+ TestCase(
      name, wrappedFunction, List(res1.name, res2.name),
      originalFunction = Some(testFunction)
    )
    testRef
  }

  // -- liveResource declarations --
  // Parallel to test(...): takes a name, dep list, and a build closure.
  // Build receives resolved dep values in declared order. Returns an
  // AutoCloseable. Lifecycle managed by the runner.

  /** Internal helper that registers a LiveResourceDef and returns its ref.
    * Qualifies the resource name with the suite's fully-qualified class name
    * so resources in different suites don't collide. Throws if a resource
    * with the same short name was already declared in this suite. */
  private def registerLiveResource[T](defn: LiveResourceDef[T]): ResourceRef[T] = {
    val qName = s"${this.fullClassName}/${defn.name}"
    if (_liveResources.exists(_.name == qName)) {
      throw new IllegalArgumentException(
        s"Duplicate live resource name '${defn.name}' in suite ${this.fullClassName}")
    }
    val qDef = defn.copy(name = qName)
    _liveResources = _liveResources :+ qDef
    qDef.ref
  }

  // ---- Default: SharedReadOnly ----

  def liveResource[T <: AutoCloseable]
      (name: String)
      (build: => T): ResourceRef[T] =
    registerLiveResource(LiveResourceDef[T](
      name, List.empty, _ => build))

  def liveResource[T <: AutoCloseable, D1]
      (name: String, dep1: Dep[D1])
      (build: D1 => T): ResourceRef[T] =
    registerLiveResource(LiveResourceDef[T](
      name, List(dep1),
      vs => build(vs(0).asInstanceOf[D1])))

  def liveResource[T <: AutoCloseable, D1, D2]
      (name: String, dep1: Dep[D1], dep2: Dep[D2])
      (build: (D1, D2) => T): ResourceRef[T] =
    registerLiveResource(LiveResourceDef[T](
      name, List(dep1, dep2),
      vs => build(vs(0).asInstanceOf[D1], vs(1).asInstanceOf[D2])))

  def liveResource[T <: AutoCloseable, D1, D2, D3]
      (name: String, dep1: Dep[D1], dep2: Dep[D2], dep3: Dep[D3])
      (build: (D1, D2, D3) => T): ResourceRef[T] =
    registerLiveResource(LiveResourceDef[T](
      name, List(dep1, dep2, dep3),
      vs => build(vs(0).asInstanceOf[D1], vs(1).asInstanceOf[D2], vs(2).asInstanceOf[D3])))

  // ---- Exclusive: each consumer gets its own instance ----

  def exclusiveResource[T <: AutoCloseable]
      (name: String)
      (build: => T): ResourceRef[T] =
    registerLiveResource(LiveResourceDef[T](
      name, List.empty, _ => build, ShareMode.Exclusive))

  def exclusiveResource[T <: AutoCloseable, D1]
      (name: String, dep1: Dep[D1])
      (build: D1 => T): ResourceRef[T] =
    registerLiveResource(LiveResourceDef[T](
      name, List(dep1),
      vs => build(vs(0).asInstanceOf[D1]),
      ShareMode.Exclusive))

  def exclusiveResource[T <: AutoCloseable, D1, D2]
      (name: String, dep1: Dep[D1], dep2: Dep[D2])
      (build: (D1, D2) => T): ResourceRef[T] =
    registerLiveResource(LiveResourceDef[T](
      name, List(dep1, dep2),
      vs => build(vs(0).asInstanceOf[D1], vs(1).asInstanceOf[D2]),
      ShareMode.Exclusive))

  // ---- SharedSerialized: shared instance, serialized consumers, no reset ----

  def liveResourceSerialized[T <: AutoCloseable]
      (name: String)
      (build: => T): ResourceRef[T] =
    registerLiveResource(LiveResourceDef[T](
      name, List.empty, _ => build, ShareMode.SharedSerialized))

  def liveResourceSerialized[T <: AutoCloseable, D1]
      (name: String, dep1: Dep[D1])
      (build: D1 => T): ResourceRef[T] =
    registerLiveResource(LiveResourceDef[T](
      name, List(dep1),
      vs => build(vs(0).asInstanceOf[D1]),
      ShareMode.SharedSerialized))

  def liveResourceSerialized[T <: AutoCloseable, D1, D2]
      (name: String, dep1: Dep[D1], dep2: Dep[D2])
      (build: (D1, D2) => T): ResourceRef[T] =
    registerLiveResource(LiveResourceDef[T](
      name, List(dep1, dep2),
      vs => build(vs(0).asInstanceOf[D1], vs(1).asInstanceOf[D2]),
      ShareMode.SharedSerialized))

  def liveResourceSerialized[T <: AutoCloseable, D1, D2, D3]
      (name: String, dep1: Dep[D1], dep2: Dep[D2], dep3: Dep[D3])
      (build: (D1, D2, D3) => T): ResourceRef[T] =
    registerLiveResource(LiveResourceDef[T](
      name, List(dep1, dep2, dep3),
      vs => build(vs(0).asInstanceOf[D1], vs(1).asInstanceOf[D2], vs(2).asInstanceOf[D3]),
      ShareMode.SharedSerialized))

  // ---- SharedWithReset: shared instance, reset between consumers ----

  def liveResourceWithReset[T <: AutoCloseable]
      (name: String)
      (build: => T)
      (reset: T => Unit): ResourceRef[T] =
    registerLiveResource(LiveResourceDef[T](
      name, List.empty, _ => build, ShareMode.SharedWithReset(reset)))

  def liveResourceWithReset[T <: AutoCloseable, D1]
      (name: String, dep1: Dep[D1])
      (build: D1 => T)
      (reset: T => Unit): ResourceRef[T] =
    registerLiveResource(LiveResourceDef[T](
      name, List(dep1),
      vs => build(vs(0).asInstanceOf[D1]),
      ShareMode.SharedWithReset(reset)))

  def liveResourceWithReset[T <: AutoCloseable, D1, D2]
      (name: String, dep1: Dep[D1], dep2: Dep[D2])
      (build: (D1, D2) => T)
      (reset: T => Unit): ResourceRef[T] =
    registerLiveResource(LiveResourceDef[T](
      name, List(dep1, dep2),
      vs => build(vs(0).asInstanceOf[D1], vs(1).asInstanceOf[D2]),
      ShareMode.SharedWithReset(reset)))

  // ---- Capacity declaration ----

  /** Declare a numeric capacity (e.g. 4096 MB of RAM). The returned value
    * can produce dep entries via `cap.reserve(amount)`.
    *
    * Capacities are scoped *globally* (via the runner's ResourceManager) so
    * suites sharing a name like "ram" share one budget. The `total` here is
    * a default; override at runtime via `--capacity name=value` or the env
    * var `BOOKTEST_CAPACITY_<NAME>`. */
  protected def capacity(name: String, total: Double): ResourceCapacity[Double] =
    resources.capacity(name, total)
  
  private def discoverTests(): List[TestCase] = {
    val clazz = this.getClass
    val methods = clazz.getDeclaredMethods
    
    val testMethods = methods.filter { method =>
      method.getName.startsWith("test") &&
      !method.getName.contains("$anonfun$") && // Filter out synthetic lambda methods
      method.getParameterCount >= 1 &&
      method.getParameterTypes()(0) == classOf[TestCaseRun] &&
      // Only discover multi-param methods if they have @DependsOn annotation.
      // This prevents helper methods like testAllColumns(t, columns) from being
      // picked up as phantom tests and failing with "wrong number of arguments".
      (method.getParameterCount == 1 || method.getAnnotation(classOf[DependsOn]) != null)
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
    // Try Java @DependsOn annotation first (has proper runtime retention)
    val javaAnnotation = method.getAnnotation(classOf[DependsOn])
    if (javaAnnotation != null) {
      return javaAnnotation.value().toList.map(cleanMethodName)
    }

    // Try Scala annotation-based dependencies (legacy, may not have runtime retention)
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

    // If Scala annotation dependencies found, clean them to match test names
    annotationDeps.map(cleanMethodName)
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
  
  /** Simple class name (without package) */
  def suiteName: String = {
    val className = this.getClass.getSimpleName
    if (className.endsWith("$")) {
      className.dropRight(1)
    } else {
      className
    }
  }

  /** Fully qualified class name (with package) */
  def fullClassName: String = {
    val className = this.getClass.getName
    if (className.endsWith("$")) {
      className.dropRight(1)
    } else {
      className
    }
  }
}