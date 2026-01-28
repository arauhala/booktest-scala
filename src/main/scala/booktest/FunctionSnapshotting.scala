package booktest

import java.security.MessageDigest
import scala.collection.mutable
import upickle.default._

case class FunctionCall(
  functionName: String,
  args: List[String], // Serialized arguments
  hash: String
)

object FunctionCall {
  implicit val rw: ReadWriter[FunctionCall] = macroRW

  def fromCall[T](functionName: String, args: Any*): FunctionCall = {
    val argsStr = args.map(_.toString).toList
    val hashInput = s"$functionName:${argsStr.mkString(",")}"
    val hash = sha1Hash(hashInput)
    FunctionCall(functionName, argsStr, hash)
  }
  
  private def sha1Hash(input: String): String = {
    val digest = MessageDigest.getInstance("SHA-1")
    val hashBytes = digest.digest(input.getBytes("UTF-8"))
    hashBytes.map("%02x".format(_)).mkString
  }
}

case class FunctionCallSnapshot[T](
  call: FunctionCall,
  result: T
)

class FunctionSnapshotManager(testCaseRun: TestCaseRun) {
  
  private val snapshotDir = testCaseRun.outDir / "_snapshots"
  private val snapshotFile = snapshotDir / "functions.json"
  
  private var snapshots: Map[String, String] = loadSnapshots() // hash -> serialized result
  private var capturedCalls: mutable.Map[String, (FunctionCall, String)] = mutable.Map.empty
  private var runtimeCache: mutable.Map[String, Any] = mutable.Map.empty // runtime cache for actual values
  
  private def loadSnapshots(): Map[String, String] = {
    if (os.exists(snapshotFile)) {
      try {
        val content = os.read(snapshotFile)
        val parsed = ujson.read(content)
        parsed.obj.map { case (hash, value) =>
          hash -> value.str
        }.toMap
      } catch {
        case e: Exception =>
          testCaseRun.i(s"Warning: Could not load function snapshots: ${e.getMessage}")
          Map.empty
      }
    } else {
      Map.empty
    }
  }
  
  private def saveSnapshots(): Unit = {
    if (capturedCalls.nonEmpty) {
      os.makeDir.all(snapshotDir)
      val allSnapshots = snapshots ++ capturedCalls.values.map { case (call, result) =>
        call.hash -> result
      }.toMap
      
      val json = ujson.Obj.from(allSnapshots.map { case (hash, result) =>
        hash -> ujson.Str(result)
      })
      os.write.over(snapshotFile, ujson.write(json, indent = 2))
    }
  }
  
  def snapshotFunction[T](functionName: String, actualFunction: () => T, args: Any*): T = {
    val call = FunctionCall.fromCall(functionName, args: _*)
    
    // Check runtime cache first
    runtimeCache.get(call.hash) match {
      case Some(cachedValue) =>
        testCaseRun.i(s"Using cached result for $functionName")
        cachedValue.asInstanceOf[T]
        
      case None =>
        // Check if we have a snapshot (even if we can't deserialize it, we know the call was made)
        val hasSnapshot = snapshots.contains(call.hash)
        
        // Execute function 
        val result = actualFunction()
        
        // Cache the actual result in runtime
        runtimeCache(call.hash) = result
        
        // Store serialized version for persistence
        val serializedResult = result.toString
        capturedCalls(call.hash) = (call, serializedResult)
        
        if (hasSnapshot) {
          testCaseRun.i(s"Re-executed $functionName (snapshot exists)")
        }
        
        result
    }
  }
  
  def logSnapshots(): Unit = {
    if (capturedCalls.nonEmpty) {
      testCaseRun.h1("Function Call Snapshots")
      capturedCalls.values.toSeq.sortBy(_._1.functionName).foreach { case (call, result) =>
        testCaseRun.tln(s"${call.functionName}(${call.args.mkString(", ")}) -> ${result.take(50)}${if (result.length > 50) "..." else ""}")
      }
    }
  }
  
  def close(): Unit = {
    saveSnapshots()
    logSnapshots()
  }
}

// Simple function mocking utility
class FunctionMocker {
  private var originalFunctions: mutable.Map[String, () => Any] = mutable.Map.empty
  private var mockFunctions: mutable.Map[String, () => Any] = mutable.Map.empty
  
  def mockFunction[T](name: String, mockImpl: () => T): Unit = {
    mockFunctions(name) = mockImpl.asInstanceOf[() => Any]
  }
  
  def callFunction[T](name: String, defaultImpl: () => T): T = {
    mockFunctions.get(name) match {
      case Some(mock) => mock().asInstanceOf[T]
      case None => defaultImpl()
    }
  }
  
  def clearMocks(): Unit = {
    mockFunctions.clear()
  }
}

// Utilities for type-safe function snapshotting
trait SnapshotableFunction[T] {
  def name: String
  def call(args: Any*): T
  
  def snapshot(manager: FunctionSnapshotManager, args: Any*): T = {
    manager.snapshotFunction(name, () => call(args: _*), args: _*)
  }
}

case class SnapshotableFunction0[T](name: String, fn: () => T) extends SnapshotableFunction[T] {
  def call(args: Any*): T = {
    require(args.isEmpty, s"Function $name expects 0 arguments, got ${args.length}")
    fn()
  }
}

case class SnapshotableFunction1[A, T](name: String, fn: A => T) extends SnapshotableFunction[T] {
  def call(args: Any*): T = {
    require(args.length == 1, s"Function $name expects 1 argument, got ${args.length}")
    fn(args(0).asInstanceOf[A])
  }
}

case class SnapshotableFunction2[A, B, T](name: String, fn: (A, B) => T) extends SnapshotableFunction[T] {
  def call(args: Any*): T = {
    require(args.length == 2, s"Function $name expects 2 arguments, got ${args.length}")
    fn(args(0).asInstanceOf[A], args(1).asInstanceOf[B])
  }
}

object FunctionSnapshotting {
  
  def withFunctionSnapshots[T](testCaseRun: TestCaseRun)(testCode: FunctionSnapshotManager => T): T = {
    val manager = new FunctionSnapshotManager(testCaseRun)
    try {
      testCode(manager)
    } finally {
      manager.close()
    }
  }
  
  def withMockedFunctions[T](mocks: (String, () => Any)*)(testCode: FunctionMocker => T): T = {
    val mocker = new FunctionMocker
    mocks.foreach { case (name, impl) => mocker.mockFunction(name, impl) }
    try {
      testCode(mocker)
    } finally {
      mocker.clearMocks()
    }
  }
  
  // Helper to create snapshottable functions
  def snapshottable[T](name: String)(fn: () => T): SnapshotableFunction0[T] = 
    SnapshotableFunction0(name, fn)
    
  def snapshottable[A, T](name: String)(fn: A => T): SnapshotableFunction1[A, T] = 
    SnapshotableFunction1(name, fn)
    
  def snapshottable[A, B, T](name: String)(fn: (A, B) => T): SnapshotableFunction2[A, B, T] = 
    SnapshotableFunction2(name, fn)
}