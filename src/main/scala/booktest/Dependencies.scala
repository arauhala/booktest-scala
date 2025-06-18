package booktest

import scala.annotation.StaticAnnotation

// For now, we'll keep the annotation simple but use method signature inference
case class dependsOn(dependencies: String*) extends StaticAnnotation

case class DependencyResult[T](
  testName: String,
  result: T,
  success: Boolean
)

class DependencyCache(cacheDir: os.Path) {
  private var memoryCache = Map[String, Any]()
  
  // Ensure cache directory exists
  os.makeDir.all(cacheDir)
  
  def put[T](testName: String, result: T): Unit = {
    // Store in memory cache
    memoryCache = memoryCache + (testName -> result)
    
    // Store in filesystem cache (simple JSON-like format for basic types)
    try {
      val cacheFile = cacheDir / s"$testName.cache"
      val serialized = result match {
        case s: String => s"STRING:$s"
        case i: Int => s"INT:$i"
        case d: Double => s"DOUBLE:$d"
        case b: Boolean => s"BOOLEAN:$b"
        case other => s"OBJECT:${other.toString}"
      }
      os.write.over(cacheFile, serialized)
    } catch {
      case e: Exception =>
        // If serialization fails, just keep it in memory
        println(s"Warning: Could not serialize result for $testName: ${e.getMessage}")
    }
  }
  
  def get[T](testName: String): Option[T] = {
    // Try memory cache first
    memoryCache.get(testName) match {
      case Some(value) => Some(value.asInstanceOf[T])
      case None =>
        // Try filesystem cache
        try {
          val cacheFile = cacheDir / s"$testName.cache"
          if (os.exists(cacheFile)) {
            val serialized = os.read(cacheFile)
            val result = serialized.split(":", 2) match {
              case Array("STRING", value) => value
              case Array("INT", value) => value.toInt
              case Array("DOUBLE", value) => value.toDouble
              case Array("BOOLEAN", value) => value.toBoolean
              case Array("OBJECT", value) => value
              case _ => serialized
            }
            
            // Put back in memory cache
            memoryCache = memoryCache + (testName -> result)
            Some(result.asInstanceOf[T])
          } else {
            None
          }
        } catch {
          case e: Exception =>
            println(s"Warning: Could not deserialize result for $testName: ${e.getMessage}")
            None
        }
    }
  }
  
  def contains(testName: String): Boolean = {
    memoryCache.contains(testName) || os.exists(cacheDir / s"$testName.cache")
  }
  
  def clear(): Unit = {
    memoryCache = Map.empty
    try {
      val cacheFiles = os.list(cacheDir).filter(_.ext == "cache")
      cacheFiles.foreach(os.remove)
    } catch {
      case _: Exception => // Ignore cleanup errors
    }
  }
}