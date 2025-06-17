package booktest

import scala.annotation.StaticAnnotation

case class dependsOn(dependencies: String*) extends StaticAnnotation

case class DependencyResult[T](
  testName: String,
  result: T,
  success: Boolean
)

class DependencyCache {
  private var cache = Map[String, Any]()
  
  def put[T](testName: String, result: T): Unit = {
    cache = cache + (testName -> result)
  }
  
  def get[T](testName: String): Option[T] = {
    cache.get(testName).map(_.asInstanceOf[T])
  }
  
  def contains(testName: String): Boolean = cache.contains(testName)
  
  def clear(): Unit = {
    cache = Map.empty
  }
}