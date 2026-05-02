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

/** Per-test function-snapshot manager. Like EnvSnapshotManager, the maps
  * here are guarded by `lock` so a test that genuinely fans out to multiple
  * threads (or that exposes `snapshotFunction` to a thread pool) won't lose
  * captured calls or trip ConcurrentModificationException. */
class FunctionSnapshotManager(testCaseRun: TestCaseRun) {

  private val snapshotDir = testCaseRun.outDir / "_snapshots"
  private val snapshotFile = snapshotDir / "functions.json"

  private val lock = new Object
  private var snapshots: Map[String, String] = loadSnapshots() // hash -> serialized result
  private val capturedCalls: mutable.Map[String, (FunctionCall, String)] = mutable.Map.empty
  private val runtimeCache: mutable.Map[String, Any] = mutable.Map.empty // runtime cache for actual values

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
    val allSnapshots = lock.synchronized {
      if (capturedCalls.isEmpty) None
      else Some(snapshots ++ capturedCalls.values.map { case (call, result) =>
        call.hash -> result
      }.toMap)
    }
    allSnapshots.foreach { all =>
      os.makeDir.all(snapshotDir)
      val json = ujson.Obj.from(all.map { case (hash, result) =>
        hash -> ujson.Str(result)
      })
      os.write.over(snapshotFile, ujson.write(json, indent = 2))
    }
  }

  def snapshotFunction[T](functionName: String, actualFunction: () => T, args: Any*): T = {
    val call = FunctionCall.fromCall(functionName, args: _*)

    // Fast path: cached result from a previous call in this run.
    val cached = lock.synchronized(runtimeCache.get(call.hash))
    cached match {
      case Some(cachedValue) =>
        testCaseRun.i(s"Using cached result for $functionName")
        cachedValue.asInstanceOf[T]

      case None =>
        // Check if we have a snapshot (even if we can't deserialize it, we know the call was made)
        val hasSnapshot = lock.synchronized(snapshots.contains(call.hash))

        // Run user function OUTSIDE the lock — it can be slow / re-entrant.
        val result = actualFunction()

        lock.synchronized {
          // If a concurrent call beat us to it, prefer their value to keep
          // the snapshot deterministic (first-writer-wins).
          runtimeCache.get(call.hash) match {
            case Some(existing) => existing.asInstanceOf[T]
            case None =>
              runtimeCache(call.hash) = result
              capturedCalls(call.hash) = (call, result.toString)
              if (hasSnapshot) {
                testCaseRun.i(s"Re-executed $functionName (snapshot exists)")
              }
              result
          }
        }
    }
  }

  def logSnapshots(): Unit = {
    val captured = lock.synchronized(capturedCalls.toMap)
    if (captured.nonEmpty) {
      testCaseRun.h1("Function Call Snapshots")
      captured.values.toSeq.sortBy(_._1.functionName).foreach { case (call, result) =>
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
  private val lock = new Object
  private val originalFunctions: mutable.Map[String, () => Any] = mutable.Map.empty
  private val mockFunctions: mutable.Map[String, () => Any] = mutable.Map.empty

  def mockFunction[T](name: String, mockImpl: () => T): Unit = lock.synchronized {
    mockFunctions(name) = mockImpl.asInstanceOf[() => Any]
  }

  def callFunction[T](name: String, defaultImpl: () => T): T = {
    val mock = lock.synchronized(mockFunctions.get(name))
    mock match {
      case Some(m) => m().asInstanceOf[T]
      case None => defaultImpl()
    }
  }

  def clearMocks(): Unit = lock.synchronized {
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