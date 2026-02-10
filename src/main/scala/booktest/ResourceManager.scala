package booktest

import java.net.ServerSocket
import scala.collection.mutable
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

/**
 * Resource manager for parallel test execution.
 * Manages port pools, memory limits, and other shared resources.
 */
class ResourceManager {
  private val portPool = new PortPool()
  private val lockPool = new LockPool()
  private val resources = mutable.Map[String, ResourcePool[Any]]()

  /** Get the port pool for allocating network ports */
  def ports: PortPool = portPool

  /** Get the lock pool for serializing test execution */
  def locks: LockPool = lockPool

  /** Register a custom resource pool */
  def register[T](name: String, pool: ResourcePool[T]): Unit = {
    resources(name) = pool.asInstanceOf[ResourcePool[Any]]
  }

  /** Get a registered resource pool */
  def pool[T](name: String): Option[ResourcePool[T]] = {
    resources.get(name).map(_.asInstanceOf[ResourcePool[T]])
  }

  /** Release all resources */
  def releaseAll(): Unit = {
    portPool.releaseAll()
    resources.values.foreach(_.releaseAll())
  }
}

/**
 * Generic resource pool interface
 */
trait ResourcePool[T] {
  def acquire(): T
  def release(resource: T): Unit
  def releaseAll(): Unit

  /** Use a resource with automatic release */
  def use[R](block: T => R): R = {
    val resource = acquire()
    try {
      block(resource)
    } finally {
      release(resource)
    }
  }
}

/**
 * Port pool for allocating network ports to parallel tests.
 * Finds available ports and tracks which are in use.
 */
class PortPool(
  basePort: Int = 10000,
  maxPort: Int = 60000
) extends ResourcePool[Int] {

  private val inUse = mutable.Set[Int]()
  private val lock = new Object()

  /** Acquire an available port */
  override def acquire(): Int = lock.synchronized {
    var port = basePort
    while (port <= maxPort) {
      if (!inUse.contains(port) && isPortAvailable(port)) {
        inUse += port
        return port
      }
      port += 1
    }
    throw new RuntimeException(s"No available ports in range $basePort-$maxPort")
  }

  /** Release a port back to the pool */
  override def release(port: Int): Unit = lock.synchronized {
    inUse -= port
  }

  /** Release all ports */
  override def releaseAll(): Unit = lock.synchronized {
    inUse.clear()
  }

  /** Check if a port is available */
  private def isPortAvailable(port: Int): Boolean = {
    Try {
      val socket = new ServerSocket(port)
      socket.close()
      true
    }.getOrElse(false)
  }

  /** Get the number of currently allocated ports */
  def allocated: Int = lock.synchronized { inUse.size }
}

/**
 * Lock pool for serializing access to shared resources.
 * Tests that need exclusive access to a resource acquire the named lock.
 */
class LockPool extends ResourcePool[String] {
  private val locks = mutable.Map[String, java.util.concurrent.Semaphore]()
  private val poolLock = new Object()

  /** Get or create a semaphore for the named lock */
  private def getSemaphore(name: String): java.util.concurrent.Semaphore = poolLock.synchronized {
    locks.getOrElseUpdate(name, new java.util.concurrent.Semaphore(1))
  }

  /** Acquire a named lock (blocks until available) */
  def acquire(name: String): Unit = {
    getSemaphore(name).acquire()
  }

  /** Release a named lock */
  override def release(name: String): Unit = {
    locks.get(name).foreach(_.release())
  }

  /** Try to acquire a lock without blocking */
  def tryAcquire(name: String): Boolean = {
    getSemaphore(name).tryAcquire()
  }

  // ResourcePool interface (for generic use)
  override def acquire(): String = throw new UnsupportedOperationException("Use acquire(name) instead")
  override def releaseAll(): Unit = poolLock.synchronized {
    // Release all held locks
    locks.values.foreach { sem =>
      if (sem.availablePermits() == 0) sem.release()
    }
  }
}

/**
 * Thread pool executor for parallel test execution.
 */
class TestExecutor(
  numThreads: Int,
  resourceManager: ResourceManager = new ResourceManager()
) {
  private val executor = java.util.concurrent.Executors.newFixedThreadPool(numThreads)
  implicit val ec: ExecutionContext = ExecutionContext.fromExecutor(executor)

  /** The resource manager for this executor */
  def resources: ResourceManager = resourceManager

  /** Execute a task asynchronously */
  def submit[T](task: => T): Future[T] = Future(task)

  /** Execute multiple tasks in parallel and wait for all to complete */
  def parallel[T](tasks: Seq[() => T]): Seq[T] = {
    val futures = tasks.map(task => Future(task()))
    futures.map(f => scala.concurrent.Await.result(f, scala.concurrent.duration.Duration.Inf))
  }

  /** Shutdown the executor */
  def shutdown(): Unit = {
    executor.shutdown()
    resourceManager.releaseAll()
  }

  /** Shutdown immediately */
  def shutdownNow(): Unit = {
    executor.shutdownNow()
    resourceManager.releaseAll()
  }
}

/**
 * Test context that provides resources to individual tests.
 * Passed to tests when running in parallel mode.
 */
class TestContext(
  val resourceManager: ResourceManager,
  val threadId: Int = 0
) {
  /** Acquire a port for this test */
  def acquirePort(): Int = resourceManager.ports.acquire()

  /** Release a port */
  def releasePort(port: Int): Unit = resourceManager.ports.release(port)

  /** Use a port with automatic release */
  def withPort[T](block: Int => T): T = resourceManager.ports.use(block)
}

object ResourceManager {
  /** Global default resource manager */
  lazy val default: ResourceManager = new ResourceManager()
}
