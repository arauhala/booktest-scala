package booktest

import java.util.concurrent.locks.ReentrantLock
import scala.collection.mutable

/** Per-resource lifecycle statistics. Filled in by LiveResourceManager. */
final case class LiveResourceStats(
  name: String,
  builds: Int,
  closes: Int,
  resets: Int,
  consumers: Int,
  totalBuildMs: Long,
  totalCloseMs: Long,
  totalAliveMs: Long
)

/** Receives lifecycle events from LiveResourceManager. The runner uses
  * this to print verbose `[build]`/`[close]` lines and to feed the
  * end-of-run summary. */
trait LiveResourceListener {
  def onBuild(name: String, durationMs: Long): Unit = ()
  def onClose(name: String, durationMs: Long): Unit = ()
  def onReset(name: String, durationMs: Long): Unit = ()
  def onAcquire(name: String, consumer: String): Unit = ()
  def onRelease(name: String, consumer: String, failed: Boolean): Unit = ()
}

object LiveResourceListener {
  /** Default no-op listener. */
  val Noop: LiveResourceListener = new LiveResourceListener {}
}

/** Manages lifecycle of live resources declared with `liveResource(...)`,
  * `exclusiveResource(...)`, and `liveResourceWithReset(...)`.
  *
  * Reuses the existing `ResourceManager` for pool/lock infrastructure rather
  * than introducing a parallel hierarchy.
  */
class LiveResourceManager(
  var listener: LiveResourceListener = LiveResourceListener.Noop
) {

  private case class SharedEntry(
    defn: LiveResourceDef[Any],
    var instance: Option[Any] = None,
    /** Number of outstanding holders (test consumers + parent resources). */
    var refCount: Int = 0,
    var allocations: List[Allocation] = List.empty,
    var instanceBuildAtMs: Long = 0L,
    var stats: LiveResourceStats =
      LiveResourceStats("?", 0, 0, 0, 0, 0L, 0L, 0L),
    /** SharedReadOnly + SharedWithReset: serializes the build operation
      * itself so concurrent first-time consumers don't each build their
      * own. Held briefly only during build/close, not during use. */
    buildLock: ReentrantLock = new ReentrantLock(),
    /** SharedWithReset only: serializes consumer access. */
    instanceLock: ReentrantLock = new ReentrantLock(),
    /** SharedWithReset only: true until the first consumer has finished
      * (no reset needed before the first acquire). */
    var firstConsumerStillPending: Boolean = true
  )

  /** Per-(resource, consumer) state for Exclusive resources. */
  private case class ExclusiveHolding(
    instance: Any,
    allocations: List[Allocation],
    builtAtMs: Long = System.currentTimeMillis()
  )

  private val entries = mutable.Map[String, SharedEntry]()
  /** Per-consumer holdings for Exclusive resources, keyed by
    * `s"$resourceName/$consumerName"`. */
  private val exclusiveHoldings = mutable.Map[String, ExclusiveHolding]()
  private val tableLock = new Object()

  /** Register a definition. Idempotent. */
  def register(defn: LiveResourceDef[?]): Unit = tableLock.synchronized {
    val e = entries.getOrElseUpdate(defn.name,
      SharedEntry(defn.asInstanceOf[LiveResourceDef[Any]]))
    e.stats = e.stats.copy(name = defn.name)
  }

  /** Pre-reserve refcount for an upcoming test consumer. Called by the
    * runner's pre-pass so the instance survives between tests that share
    * it. Without this, refcount would drop to 0 between tests, closing
    * the instance prematurely. */
  def reserve(resourceName: String, consumerTestName: String): Unit = {
    val _ = consumerTestName  // reserved for symmetry with release(); suppresses -Wunused
    tableLock.synchronized {
      entries.get(resourceName).foreach { e =>
        e.defn.shareMode match {
          case ShareMode.Exclusive => () // each consumer gets its own
          case _ => e.refCount += 1
        }
      }
    }
  }

  /** Get-or-build a live instance for a consumer.
    *
    * For SharedReadOnly: get-or-build, return same instance to all consumers.
    * For SharedWithReset: acquire per-instance lock, reset (if not first), return.
    * For Exclusive: build a fresh instance for this consumer.
    *
    * `resolveDep` is the runner-supplied callback that turns a Dep[?] into a
    * runtime value (loading TestRefs from cache, recursing into ResourceRefs,
    * acquiring from pools/capacities).
    */
  def acquire[T](
    resourceName: String,
    consumerTestName: String,
    resolveDep: Dep[?] => Any
  ): T = {
    val entry = tableLock.synchronized {
      entries.getOrElse(resourceName,
        throw new IllegalStateException(s"Live resource '$resourceName' not registered"))
    }
    entry.defn.shareMode match {
      case ShareMode.Exclusive =>
        acquireExclusive[T](entry, resourceName, consumerTestName, resolveDep)
      case ShareMode.SharedReadOnly =>
        acquireShared[T](entry, resolveDep, resetOnReuse = false)
      case ShareMode.SharedSerialized =>
        // Serialize access; no reset between consumers.
        entry.instanceLock.lock()
        acquireShared[T](entry, resolveDep, resetOnReuse = false)
      case ShareMode.SharedWithReset(_) =>
        // Serialize access; reset between consumers.
        entry.instanceLock.lock()
        acquireShared[T](entry, resolveDep, resetOnReuse = true)
    }
  }


  /** Release a consumer's hold on a resource.
    *
    * @param failed         did the consumer test fail? Used to decide
    *                       whether to invalidate (close+rebuild) the
    *                       shared instance. Always invalidates on
    *                       SharedWithReset; invalidates on SharedReadOnly
    *                       only when `invalidateOnFail` is true.
    * @param invalidateOnFail if true and `failed`, force-close a shared
    *                       instance even if it would otherwise be reused.
    */
  def release(
    resourceName: String,
    consumerTestName: String,
    failed: Boolean = false,
    invalidateOnFail: Boolean = false
  ): Unit = {
    val entryOpt = tableLock.synchronized(entries.get(resourceName))
    entryOpt.foreach { entry =>
      entry.defn.shareMode match {
        case ShareMode.Exclusive =>
          releaseExclusive(resourceName, consumerTestName)
        case ShareMode.SharedReadOnly =>
          val invalidate = failed && invalidateOnFail
          releaseShared(entry, consumerTestName, invalidate)
        case ShareMode.SharedSerialized =>
          // Same invalidation policy as SharedReadOnly (no implicit reset),
          // but consumers are serialized so we must release the lock too.
          val invalidate = failed && invalidateOnFail
          try releaseShared(entry, consumerTestName, invalidate)
          finally if (entry.instanceLock.isHeldByCurrentThread)
            entry.instanceLock.unlock()
        case ShareMode.SharedWithReset(_) =>
          // SharedWithReset always invalidates on failure: state is unknown
          // since the consumer aborted mid-use.
          val invalidate = failed
          try releaseShared(entry, consumerTestName, invalidate)
          finally if (entry.instanceLock.isHeldByCurrentThread)
            entry.instanceLock.unlock()
      }
    }
  }

  /** Force teardown of every live instance. Called at end of run. */
  def shutdownAll(): Unit = {
    val sharedToClose = tableLock.synchronized {
      val xs = entries.values.filter(_.instance.isDefined).toList
      xs
    }
    sharedToClose.foreach(closeSharedInstance)
    val exclusiveToClose = tableLock.synchronized {
      val xs = exclusiveHoldings.toList
      exclusiveHoldings.clear()
      xs
    }
    exclusiveToClose.foreach { case (k, h) =>
      val resName = k.substring(0, k.lastIndexOf('/'))
      closeExclusiveHolding(resName, h)
    }
  }

  /** Snapshot of stats per registered resource. Used by the runner's
    * end-of-run summary. */
  def statsSnapshot: List[LiveResourceStats] = tableLock.synchronized {
    entries.values.map(_.stats).toList
  }

  /** Whether the named resource has been registered. */
  def isRegistered(resourceName: String): Boolean = tableLock.synchronized {
    entries.contains(resourceName)
  }

  /** Look up a registered definition by qualified name. */
  def lookup(resourceName: String): Option[LiveResourceDef[Any]] = tableLock.synchronized {
    entries.get(resourceName).map(_.defn)
  }

  /** Transitive set of registered live resources reachable from the given
    * direct dependency names (which may include test deps; non-resource
    * names are ignored). Returned in dep order — direct deps first, then
    * their nested resources. Used for pre-pass reservation and end-of-test
    * release so a nested resource stays alive across all transitive
    * consumers. */
  def transitiveResourceClosure(directDepNames: Iterable[String]): List[String] = {
    val ordered = mutable.ArrayBuffer[String]()
    val seen = mutable.Set[String]()
    def walk(name: String): Unit = {
      if (!isRegistered(name) || seen.contains(name)) return
      seen += name
      ordered += name
      lookup(name).foreach(_.deps.foreach {
        case ResourceDep(ref) => walk(ref.name)
        case _ => ()
      })
    }
    directDepNames.foreach(walk)
    ordered.toList
  }

  /** All registered live resources — used for end-of-run summary. */
  def allRegistered: List[LiveResourceDef[Any]] = tableLock.synchronized {
    entries.values.map(_.defn).toList
  }

  /** Is the named (shared) instance currently materialized? Used by the
    * locality-aware scheduler. Always returns false for Exclusive
    * resources (no shared state). */
  def isAlive(resourceName: String): Boolean = tableLock.synchronized {
    entries.get(resourceName).exists(_.instance.isDefined)
  }

  /** How many consumers still need this resource (haven't released yet)? */
  def pendingConsumerCount(resourceName: String): Int = tableLock.synchronized {
    entries.get(resourceName).map(_.refCount).getOrElse(0)
  }

  // ----------------------- shared (RO + reset) -----------------------

  private def acquireShared[T](
    entry: SharedEntry,
    resolveDep: Dep[?] => Any,
    resetOnReuse: Boolean
  ): T = {
    // Note: refcount is pre-incremented by `reserve` (test path) or by
    // `acquireHold` (build path). acquireShared just materializes if
    // needed and returns the instance; it does NOT bump refcount.

    // Coordinate first-time build under a per-entry build lock so concurrent
    // consumers in parallel mode don't each materialize their own instance.
    if (tableLock.synchronized(entry.instance.isEmpty)) {
      entry.buildLock.lock()
      try {
        if (tableLock.synchronized(entry.instance.isEmpty)) {
          val t0 = System.currentTimeMillis()
          val (built, allocs) = buildInstance(entry.defn, resolveDep)
          val dt = System.currentTimeMillis() - t0
          tableLock.synchronized {
            entry.instance = Some(built)
            entry.allocations = allocs
            entry.firstConsumerStillPending = true
            entry.instanceBuildAtMs = System.currentTimeMillis()
            entry.stats = entry.stats.copy(
              builds = entry.stats.builds + 1,
              totalBuildMs = entry.stats.totalBuildMs + dt)
          }
          listener.onBuild(entry.defn.name, dt)
        }
      } finally entry.buildLock.unlock()
    }
    if (resetOnReuse) {
      val resetFn = entry.defn.shareMode match {
        case ShareMode.SharedWithReset(r) => r.asInstanceOf[Any => Unit]
        case _ => (_: Any) => ()
      }
      val firstStill = tableLock.synchronized {
        val v = entry.firstConsumerStillPending
        if (v) entry.firstConsumerStillPending = false
        v
      }
      if (!firstStill) {
        val t0 = System.currentTimeMillis()
        var ok = true
        try resetFn(entry.instance.get)
        catch { case _: Exception => ok = false }
        val dt = System.currentTimeMillis() - t0
        tableLock.synchronized {
          entry.stats = entry.stats.copy(resets = entry.stats.resets + 1)
        }
        listener.onReset(entry.defn.name, dt)
        // Reset failure → invalidate the instance for the next consumer.
        if (!ok) {
          val toClose = tableLock.synchronized {
            if (entry.instance.isDefined) Some(entry) else None
          }
          toClose.foreach(closeSharedInstance)
          // Build fresh for this consumer.
          val t1 = System.currentTimeMillis()
          val (built, allocs) = buildInstance(entry.defn, resolveDep)
          val dt1 = System.currentTimeMillis() - t1
          tableLock.synchronized {
            entry.instance = Some(built)
            entry.allocations = allocs
            entry.firstConsumerStillPending = true
            entry.instanceBuildAtMs = System.currentTimeMillis()
            entry.stats = entry.stats.copy(
              builds = entry.stats.builds + 1,
              totalBuildMs = entry.stats.totalBuildMs + dt1)
          }
          listener.onBuild(entry.defn.name, dt1)
        }
      }
    }
    entry.instance.get.asInstanceOf[T]
  }

  private def releaseShared(
    entry: SharedEntry,
    consumerTestName: String,
    invalidate: Boolean = false
  ): Unit = {
    val _ = consumerTestName  // accepted for symmetry with releaseExclusive; suppresses -Wunused
    val toClose = tableLock.synchronized {
      entry.refCount -= 1
      // For SharedWithReset, mark "first consumer is now done" once the
      // first release happens, so the next consumer sees a reset.
      if (entry.firstConsumerStillPending) entry.firstConsumerStillPending = false
      val noConsumersLeft = entry.refCount <= 0 && entry.instance.isDefined
      if (noConsumersLeft || (invalidate && entry.instance.isDefined)) Some(entry) else None
    }
    toClose.foreach(closeSharedInstance)
  }

  private def closeSharedInstance(entry: SharedEntry): Unit = {
    val instOpt = tableLock.synchronized(entry.instance)
    val t0 = System.currentTimeMillis()
    instOpt.foreach { i =>
      try i match {
        case c: AutoCloseable => c.close()
        case _ => ()
      } catch { case _: Exception => () }
    }
    val dt = System.currentTimeMillis() - t0
    val (allocs, aliveMs) = tableLock.synchronized {
      val a = entry.allocations
      val alive = if (entry.instanceBuildAtMs > 0)
        System.currentTimeMillis() - entry.instanceBuildAtMs else 0L
      entry.instance = None
      entry.allocations = List.empty
      entry.firstConsumerStillPending = true
      entry.instanceBuildAtMs = 0L
      entry.stats = entry.stats.copy(
        closes = entry.stats.closes + 1,
        totalCloseMs = entry.stats.totalCloseMs + dt,
        totalAliveMs = entry.stats.totalAliveMs + alive)
      (a, alive)
    }
    val _ = aliveMs
    allocs.foreach { a => try a.release() catch { case _: Exception => () } }
    listener.onClose(entry.defn.name, dt)
  }

  // ----------------------- exclusive -----------------------

  private def acquireExclusive[T](
    entry: SharedEntry,
    resourceName: String,
    consumerTestName: String,
    resolveDep: Dep[?] => Any
  ): T = {
    val key = s"$resourceName/$consumerTestName"
    val t0 = System.currentTimeMillis()
    val (built, allocs) = buildInstance(entry.defn, resolveDep)
    val dt = System.currentTimeMillis() - t0
    tableLock.synchronized {
      exclusiveHoldings(key) = ExclusiveHolding(built, allocs, System.currentTimeMillis())
      entry.stats = entry.stats.copy(
        builds = entry.stats.builds + 1,
        totalBuildMs = entry.stats.totalBuildMs + dt)
    }
    listener.onBuild(resourceName, dt)
    built.asInstanceOf[T]
  }

  private def releaseExclusive(resourceName: String, consumerTestName: String): Unit = {
    val key = s"$resourceName/$consumerTestName"
    val holdingOpt = tableLock.synchronized(exclusiveHoldings.remove(key))
    holdingOpt.foreach(h => closeExclusiveHolding(resourceName, h))
  }

  private def closeExclusiveHolding(resourceName: String, h: ExclusiveHolding): Unit = {
    val t0 = System.currentTimeMillis()
    try h.instance match {
      case c: AutoCloseable => c.close()
      case _ => ()
    } catch { case _: Exception => () }
    val dt = System.currentTimeMillis() - t0
    h.allocations.foreach { a => try a.release() catch { case _: Exception => () } }
    val alive = System.currentTimeMillis() - h.builtAtMs
    tableLock.synchronized {
      entries.get(resourceName).foreach { e =>
        e.stats = e.stats.copy(
          closes = e.stats.closes + 1,
          totalCloseMs = e.stats.totalCloseMs + dt,
          totalAliveMs = e.stats.totalAliveMs + alive)
      }
    }
    listener.onClose(resourceName, dt)
  }

  // ----------------------- common build -----------------------

  /** Resolve all deps, recording pool/capacity allocations against this
    * instance so they're released on close. Nested ResourceDeps are NOT
    * recorded as holds here — refcount management for them goes through
    * the runner's transitive-reserve / transitive-release path.
    *
    * If dep resolution or the build closure throws, every already-acquired
    * pool/capacity allocation is released before propagating — otherwise a
    * failed build leaks ports / capacity slots that nothing will ever
    * release. */
  private def buildInstance(
    defn: LiveResourceDef[Any],
    resolveDep: Dep[?] => Any
  ): (Any, List[Allocation]) = {
    val allocs = mutable.ListBuffer[Allocation]()
    try {
      val resolved = defn.deps.map { dep =>
        val value = resolveDep(dep)
        dep match {
          case PoolDep(pool) =>
            allocs += PoolAllocation(pool.asInstanceOf[ResourcePool[Any]], value)
          case CapacityDep(cap, amount) =>
            allocs += CapacityAllocation(
              cap.asInstanceOf[ResourceCapacity[Any]], amount.asInstanceOf[Any])
          case _ => ()
        }
        value
      }
      val built = defn.build(resolved)
      (built, allocs.toList)
    } catch {
      case e: Throwable =>
        allocs.foreach { a => try a.release() catch { case _: Exception => () } }
        throw e
    }
  }
}
