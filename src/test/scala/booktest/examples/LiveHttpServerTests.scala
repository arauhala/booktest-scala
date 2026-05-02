package booktest.examples

import booktest.*

import com.sun.net.httpserver.{HttpExchange, HttpHandler, HttpServer}
import java.net.{HttpURLConnection, InetSocketAddress, URI}
import java.util.concurrent.atomic.AtomicInteger

/** Live HTTP server resource shared across multiple consumer tests.
  *
  * Drives the liveResource API design from .ai/plan/live-resources*.md.
  *
  * Build counter is used by the test output to prove that the server is
  * built ONCE for all three consumers (Phase 2: SharedReadOnly default).
  */
object StringServerCounters {
  val builds: AtomicInteger = new AtomicInteger(0)
  val closes: AtomicInteger = new AtomicInteger(0)
}

class StringServer(port: Int, payload: String) extends AutoCloseable {

  StringServerCounters.builds.incrementAndGet()

  val baseUrl: String = s"http://127.0.0.1:$port"

  private val server: HttpServer = {
    val s = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0)
    s.createContext("/echo", new HttpHandler {
      def handle(ex: HttpExchange): Unit = {
        val bytes = payload.getBytes("UTF-8")
        ex.sendResponseHeaders(200, bytes.length)
        ex.getResponseBody.write(bytes)
        ex.close()
      }
    })
    s.setExecutor(null)
    s.start()
    Thread.sleep(100)  // simulated warm-up
    s
  }

  def get(path: String): String = {
    val conn = URI.create(s"$baseUrl$path").toURL
      .openConnection.asInstanceOf[HttpURLConnection]
    try scala.io.Source.fromInputStream(conn.getInputStream).mkString
    finally conn.disconnect()
  }

  override def close(): Unit = {
    server.stop(0)
    Thread.sleep(100)  // simulated drain
    StringServerCounters.closes.incrementAndGet()
  }
}

class LiveHttpServerTests extends TestSuite {

  // Test 1: serializable state. Snapshotted, cached in .bin like today.
  val state: TestRef[String] =
    test("createState") { (t: TestCaseRun) =>
      val s = "hello world"
      t.tln(s"State: $s")
      s
    }

  // Live resource: not a test, no snapshot, not cached across runs.
  // Dep list mixes a TestRef and the existing PortPool from ResourceManager.
  val server: ResourceRef[StringServer] =
    liveResource("stringServer", state, resources.ports) {
      (s: String, port: Int) => new StringServer(port, s)
    }

  // Three consumers share the live server thanks to refcount-based reuse.
  test("clientGet", server) { (t: TestCaseRun, http: StringServer) =>
    t.tln(s"GET /echo -> ${http.get("/echo")}")
    t.tln(s"builds so far: ${StringServerCounters.builds.get()}")
  }

  test("clientLength", server) { (t: TestCaseRun, http: StringServer) =>
    val body = http.get("/echo")
    t.tln(s"Response length: ${body.length}")
    t.tln(s"builds so far: ${StringServerCounters.builds.get()}")
  }

  test("clientUpper", server) { (t: TestCaseRun, http: StringServer) =>
    t.tln(s"Upper: ${http.get("/echo").toUpperCase}")
    t.tln(s"builds so far: ${StringServerCounters.builds.get()}")
    t.tln(s"closes so far: ${StringServerCounters.closes.get()}")
  }
}
