package booktest

import scala.util.Properties
import upickle.default._

case class EnvSnapshot(variables: Map[String, String])

object EnvSnapshot {
  implicit val rw: ReadWriter[EnvSnapshot] = macroRW

  def fromOptionalMap(map: Map[String, Option[String]]): EnvSnapshot = {
    EnvSnapshot(map.collect { case (k, Some(v)) => k -> v })
  }
  
  def toOptionalMap(snapshot: EnvSnapshot): Map[String, Option[String]] = {
    snapshot.variables.map { case (k, v) => k -> Some(v) }
  }
}

class EnvSnapshotManager(testCaseRun: TestCaseRun, envVarNames: List[String]) {
  
  private val snapshotDir = testCaseRun.outDir / "_snapshots"
  private val snapshotFile = snapshotDir / "env.json"
  
  private var originalValues: Map[String, Option[String]] = Map.empty
  private var capturedValues: Map[String, Option[String]] = Map.empty
  private var snapshots: Map[String, Option[String]] = loadSnapshots()
  
  private def loadSnapshots(): Map[String, Option[String]] = {
    if (os.exists(snapshotFile)) {
      try {
        val content = os.read(snapshotFile)
        val parsed = read[EnvSnapshot](content)
        EnvSnapshot.toOptionalMap(parsed)
      } catch {
        case e: Exception =>
          testCaseRun.i(s"Warning: Could not load environment snapshots: ${e.getMessage}")
          Map.empty
      }
    } else {
      Map.empty
    }
  }
  
  private def saveSnapshots(): Unit = {
    if (capturedValues.nonEmpty) {
      os.makeDir.all(snapshotDir)
      val snapshot = EnvSnapshot.fromOptionalMap(capturedValues)
      os.write.over(snapshotFile, write(snapshot, indent = 2))
    }
  }
  
  def start(): Unit = {
    // Store original values
    originalValues = envVarNames.map { name =>
      name -> sys.env.get(name)
    }.toMap
    
    // Apply snapshots or capture current values
    envVarNames.foreach { name =>
      snapshots.get(name) match {
        case Some(value) =>
          // Use snapshot value
          value match {
            case Some(v) => sys.props(name) = v
            case None => sys.props.remove(name)
          }
          capturedValues = capturedValues + (name -> value)
          
        case None =>
          // Capture current value for future snapshots
          val currentValue = sys.env.get(name)
          capturedValues = capturedValues + (name -> currentValue)
      }
    }
  }
  
  def stop(): Unit = {
    // Restore original values
    originalValues.foreach { case (name, value) =>
      value match {
        case Some(v) => sys.props(name) = v
        case None => sys.props.remove(name)
      }
    }
    
    saveSnapshots()
    logSnapshots()
  }
  
  private def logSnapshots(): Unit = {
    if (capturedValues.nonEmpty) {
      testCaseRun.h1("Environment Variables")
      capturedValues.toSeq.sortBy(_._1).foreach { case (name, value) =>
        val valueStr = value.getOrElse("<not set>")
        testCaseRun.tln(s"$name = $valueStr")
      }
    }
  }
}

class EnvMockManager(envVars: Map[String, Option[String]]) {
  
  private var originalValues: Map[String, Option[String]] = Map.empty
  
  def start(): Unit = {
    // Store original values
    originalValues = envVars.keys.map { name =>
      name -> sys.env.get(name)
    }.toMap
    
    // Apply mock values
    envVars.foreach { case (name, value) =>
      value match {
        case Some(v) => sys.props(name) = v
        case None => sys.props.remove(name)
      }
    }
  }
  
  def stop(): Unit = {
    // Restore original values
    originalValues.foreach { case (name, value) =>
      value match {
        case Some(v) => sys.props(name) = v
        case None => sys.props.remove(name)
      }
    }
  }
}

// Utility classes for easy usage
class SnapshotEnv(testCaseRun: TestCaseRun, envVarNames: String*) extends AutoCloseable {
  private val manager = new EnvSnapshotManager(testCaseRun, envVarNames.toList)
  
  manager.start()
  
  override def close(): Unit = {
    manager.stop()
  }
}

class MockEnv(envVars: Map[String, Option[String]]) extends AutoCloseable {
  private val manager = new EnvMockManager(envVars)
  
  manager.start()
  
  override def close(): Unit = {
    manager.stop()
  }
}

object EnvSnapshotting {
  
  // Snapshot specific environment variables
  def withEnvSnapshots[T](testCaseRun: TestCaseRun, envVarNames: String*)(testCode: => T): T = {
    val manager = new EnvSnapshotManager(testCaseRun, envVarNames.toList)
    manager.start()
    try {
      testCode
    } finally {
      manager.stop()
    }
  }
  
  // Mock environment variables for test
  def withMockEnv[T](envVars: Map[String, Option[String]])(testCode: => T): T = {
    val manager = new EnvMockManager(envVars)
    manager.start()
    try {
      testCode
    } finally {
      manager.stop()
    }
  }
  
  // Convenience method for setting environment variables
  def withEnvVars[T](envVars: (String, String)*)(testCode: => T): T = {
    withMockEnv(envVars.map { case (k, v) => k -> Some(v) }.toMap)(testCode)
  }
  
  // Convenience method for removing environment variables
  def withoutEnvVars[T](envVarNames: String*)(testCode: => T): T = {
    withMockEnv(envVarNames.map(_ -> None).toMap)(testCode)
  }
}