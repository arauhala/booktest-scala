package booktest

import scala.annotation.StaticAnnotation
import java.lang.annotation.{Retention, RetentionPolicy}

// Main annotation using string names (current approach)
// Note: @Retention ensures the annotation is visible at runtime via reflection
@Retention(RetentionPolicy.RUNTIME)
case class dependsOn(dependencies: String*) extends StaticAnnotation

// Helper for method name extraction from function references
object MethodRef {
  // This allows extracting method names using a simple naming convention
  // Usage: @dependsOn(MethodRef.names(_.testCreateData, _.testUseData): _*)

  def names[T](refs: (T => Any)*): Array[String] = {
    // For now, we use a convention-based approach since Scala 3 macros
    // for method name extraction are complex
    // This is a placeholder that could be enhanced with macros later
    refs.map(_.toString.split("\\.").last.replace("test", "").toLowerCase).toArray
  }

  // Simplified approach: use method naming convention
  // @dependsOn(MethodRef("testCreateData", "testUseData"): _*)
  def apply(methodNames: String*): Array[String] = methodNames.toArray
}

case class DependencyResult[T](
  testName: String,
  result: T,
  success: Boolean
)

/** In-memory cache for test return values during execution.
  * File-based persistence is handled by TestCaseRun.binFile.
  *
  * Thread-safe: under -pN, multiple worker threads call put/get concurrently
  * (one consumer's resolveDependencyValue may load a producer's .bin while
  * another consumer is `put`-ing a different producer). All reads and writes
  * go through `lock.synchronized` so writes can't be lost and reads always
  * see the latest map.
  */
class DependencyCache {
  private val lock = new Object
  private var memoryCache = Map[String, Any]()

  /** Store a value with qualified key (suitePath/testName) */
  def put[T](key: String, result: T): Unit = lock.synchronized {
    memoryCache = memoryCache + (key -> result)
  }

  /** Store a value using TestCaseRun (also persists to file) */
  def put[T](testRun: TestCaseRun, result: T): Unit = {
    val key = testRun.testName
    lock.synchronized {
      memoryCache = memoryCache + (key -> result)
    }
    testRun.saveReturnValue(result)
  }

  /** Get a value by qualified key */
  def get[T](key: String): Option[T] = lock.synchronized {
    memoryCache.get(key).map(_.asInstanceOf[T])
  }

  /** Get a value, falling back to TestCaseRun's bin file.
    *
    * The .bin read happens outside the lock so a slow disk read doesn't
    * stall every other put/get; the result is then atomically published
    * with double-checked-locking semantics — if a concurrent thread already
    * stored the same key, we keep their value.
    */
  def getOrLoad[T](key: String, testRun: TestCaseRun): Option[T] = {
    lock.synchronized(memoryCache.get(key)) match {
      case Some(value) => Some(value.asInstanceOf[T])
      case None =>
        testRun.loadReturnValue[T].map { value =>
          lock.synchronized {
            memoryCache.get(key) match {
              case Some(existing) => existing.asInstanceOf[T]
              case None =>
                memoryCache = memoryCache + (key -> value)
                value
            }
          }
        }
    }
  }

  /** Check if a value exists in memory cache */
  def contains(key: String): Boolean = lock.synchronized {
    memoryCache.contains(key)
  }

  /** Clear in-memory cache */
  def clear(): Unit = lock.synchronized {
    memoryCache = Map.empty
  }
}
