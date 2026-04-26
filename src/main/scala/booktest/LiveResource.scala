package booktest

import scala.language.implicitConversions

/** A reference to a live resource declared with `liveResource(...)`.
  *
  * Consumed exactly like a `TestRef[T]`: pass to `test(name, ref) { (t, h) => ... }`
  * and the runner injects the live `T` (an `AutoCloseable`).
  *
  * The runner manages lifecycle: built once on first consumer, closed when the
  * last consumer finishes. Pool allocations are held for the instance lifetime.
  */
case class ResourceRef[T](name: String, deps: List[Dep[?]] = List.empty)

/** A heterogeneous dependency: a test result, another resource, a pool item,
  * or a slice of capacity. */
sealed trait Dep[T]
final case class TestDep[T](ref: TestRef[T])               extends Dep[T]
final case class ResourceDep[T](ref: ResourceRef[T])       extends Dep[T]
final case class PoolDep[T](pool: ResourcePool[T])         extends Dep[T]
final case class CapacityDep[N](cap: ResourceCapacity[N], amount: N) extends Dep[N]

object Dep {
  implicit def fromTestRef[T](r: TestRef[T]): Dep[T]         = TestDep(r)
  implicit def fromResourceRef[T](r: ResourceRef[T]): Dep[T] = ResourceDep(r)
  implicit def fromPool[T](p: ResourcePool[T]): Dep[T]       = PoolDep(p)
}

/** Sharing semantics for a live resource. Parameterized over the handle type
  * so SharedWithReset can carry a typed reset closure. */
sealed trait ShareMode[+T]
object ShareMode {
  /** One instance, many concurrent readers. Default. */
  case object SharedReadOnly extends ShareMode[Nothing]
  /** Each consumer gets its own instance (build + close per consumer). */
  case object Exclusive extends ShareMode[Nothing]
  /** Shared, but the runner serializes consumer access and calls reset
    * between consumers. */
  final case class SharedWithReset[T](reset: T => Unit) extends ShareMode[T]
}

/** A booked allocation against some resource. Released when the live
  * resource that holds it closes. */
sealed trait Allocation {
  def release(): Unit
}
final case class PoolAllocation[T](pool: ResourcePool[T], value: T) extends Allocation {
  def release(): Unit = pool.release(value)
}
final case class CapacityAllocation[N](cap: ResourceCapacity[N], amount: N) extends Allocation {
  def release(): Unit = cap.release(amount)
}

/** Internal definition of a live resource registered on a TestSuite. */
final case class LiveResourceDef[T](
  name: String,
  deps: List[Dep[?]],
  build: Seq[Any] => T,
  shareMode: ShareMode[T] = ShareMode.SharedReadOnly
) {
  val ref: ResourceRef[T] = ResourceRef[T](name, deps)
}
