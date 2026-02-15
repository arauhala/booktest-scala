package booktest.examples

import booktest._
import scala.concurrent.Future

/**
 * Tests for async/ExecutionContext support.
 * Demonstrates handling of Scala Futures in tests.
 */
class AsyncTest extends TestSuite {

  /** Test basic Future awaiting */
  def testAwaitFuture(t: TestCaseRun): Unit = {
    t.h1("Await Future")

    implicit val ec = t.ec

    val future = Future {
      Thread.sleep(50)
      "async result"
    }

    val result = t.await(future)
    t.tln(s"Result: $result")
  }

  /** Test async helper method */
  def testAsyncHelper(t: TestCaseRun): Unit = {
    t.h1("Async Helper")

    val result = t.async { implicit ec =>
      Future {
        Thread.sleep(30)
        42
      }
    }

    t.tln(s"Computed: $result")
  }

  /** Test chained futures */
  def testChainedFutures(t: TestCaseRun): Unit = {
    t.h1("Chained Futures")

    val result = t.async { implicit ec =>
      for {
        a <- Future { Thread.sleep(20); 10 }
        b <- Future { Thread.sleep(20); 20 }
        c <- Future { Thread.sleep(20); a + b }
      } yield c
    }

    t.tln(s"10 + 20 = $result")
  }

  /** Test parallel futures */
  def testParallelFutures(t: TestCaseRun): Unit = {
    t.h1("Parallel Futures")

    implicit val ec = t.ec

    val start = System.currentTimeMillis()

    // Start all futures in parallel
    val f1 = Future { Thread.sleep(50); "result1" }
    val f2 = Future { Thread.sleep(50); "result2" }
    val f3 = Future { Thread.sleep(50); "result3" }

    // Await all
    val results = t.await(Future.sequence(Seq(f1, f2, f3)))
    val elapsed = System.currentTimeMillis() - start

    t.tln(s"Results: ${results.mkString(", ")}")
    // Should take ~50ms not ~150ms due to parallelism
    t.iln(s"Elapsed: ${elapsed}ms (parallel)")
  }
}
