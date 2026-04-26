# Worked Example: Live HTTP Server Sharing State

This example illustrates the `liveResource` API end-to-end. It is the
smallest scenario that exercises every interesting code path:

- Test 1: produces serializable state (a plain `String`). Cached in `.bin`,
  snapshotted in `.md`.
- A `liveResource` declaration: builds a live HTTP server that serves that
  state. Depends on the state (a test result) and on a port (a pool
  resource). The port is held by the resource for its lifetime and returned
  to the pool on `close()`.
- Tests 2, 3, 4: each consumer hits the live server and snapshots the
  response.

The point: the server should be built **once** and closed **once** for the
three consumers, not three times. The port is allocated once.

## File: `src/test/scala/booktest/examples/LiveHttpServerTests.scala`

```scala
package booktest.examples

import booktest.*

import com.sun.net.httpserver.{HttpExchange, HttpHandler, HttpServer}
import java.net.{HttpURLConnection, InetSocketAddress, URI}

// ---------- The live thing: a plain AutoCloseable ----------
// Constructor takes its real dependencies (port, payload). Nothing
// booktest-specific in this class; it could be used outside tests.

class StringServer(port: Int, payload: String) extends AutoCloseable {

  val baseUrl = s"http://127.0.0.1:$port"

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
  }
}

// ---------- The suite ----------

class LiveHttpServerTests extends TestSuite {

  // Test 1: serializable state. Snapshotted, cached in .bin like today.
  val state: TestRef[String] =
    test("createState") { (t: TestCaseRun) =>
      val s = "hello world"
      t.tln(s"State: $s")
      s
    }

  // Live resource declaration. Not a test:
  //   - no TestCaseRun parameter
  //   - no snapshot file
  //   - not cached across runs
  //
  // Dependency list mixes a TestRef and a ResourcePool[Int] from the
  // existing ResourceManager (no new pool API). Resolved in declared order:
  //   - state (TestRef[String])    -> reads "hello world" from .bin
  //   - resources.ports (PortPool) -> port = pool.acquire(), HELD until close()
  //
  // Lifecycle: built on first consumer, close() called when last consumer
  // finishes. After close(), the runner calls resources.ports.release(port).
  val server: ResourceRef[StringServer] =
    liveResource("stringServer", state, resources.ports) {
      (s: String, port: Int) => new StringServer(port, s)
    }

  // Consumers depend on the resource ref and receive the live object,
  // exactly the same call shape as a TestRef dependency.
  test("clientGet", server) { (t: TestCaseRun, http: StringServer) =>
    t.tln(s"GET /echo -> ${http.get("/echo")}")
  }

  test("clientLength", server) { (t: TestCaseRun, http: StringServer) =>
    val body = http.get("/echo")
    t.tln(s"Response length: ${body.length}")
  }

  test("clientUpper", server) { (t: TestCaseRun, http: StringServer) =>
    t.tln(s"Upper: ${http.get("/echo").toUpperCase}")
  }
}
```

## What the runner does

Sequential mode (`-p1`):

```
createState                     ok      ~0 ms
[allocate port 41123 from resources.ports]
[build StringServer(41123, "hello world")]   # ~100 ms, refcount = 3
clientGet                       ok    ~10 ms     # refcount: 3 -> 2
clientLength                    ok    ~10 ms     # refcount: 2 -> 1
clientUpper                     ok    ~10 ms     # refcount: 1 -> 0
[close StringServer]                              # ~100 ms
[release port 41123 to pool]
```

Total: ~230 ms.
Without sharing: 3 × (100 + 10 + 100) ≈ 630 ms. Roughly 3× faster, and the
ratio improves with more consumers and slower setup.

Parallel mode (`-p3`): same build-once / close-once behavior. The three
consumer tests run concurrently against the single live instance (legal
under the default `SharedReadOnly`). The scheduler's locality preference
keeps all three on the same instance rather than spinning up extras.

## Snapshots produced

`books/examples/LiveHttpServerTests/createState.md`:

```
State: hello world
```

`books/examples/LiveHttpServerTests/clientGet.md`:

```
GET /echo -> hello world
```

`books/examples/LiveHttpServerTests/clientLength.md`:

```
Response length: 11
```

`books/examples/LiveHttpServerTests/clientUpper.md`:

```
Upper: HELLO WORLD
```

The `liveResource` declaration produces no snapshot — it is not a test.
The port number does not appear in any snapshot; it's an allocation
detail. If a test ever needs to log the URL, prefer `t.iln` so the
snapshot stays stable across runs.

## What this exercises

| Feature                                  | Where in this example                       |
|------------------------------------------|---------------------------------------------|
| Test → state via `TestRef[T]`            | `createState` -> `state`                    |
| `liveResource` declaration               | `liveResource("stringServer", ...)`         |
| Dep mix: `TestRef` + `ResourcePool`      | `state, resources.ports`                    |
| Pool allocation held by resource         | port allocated at build, released at close  |
| Build receives resolved values directly  | `(s: String, port: Int) => ...`             |
| AutoCloseable handle (no spec/handle split) | `class StringServer extends AutoCloseable` |
| Refcount-based teardown                  | `close` after `clientUpper`                 |
| Sharing across consumers                 | default `SharedReadOnly`                    |

## Variant: running just one consumer

`sbt "Test/runMain booktest.BooktestMain -t clientLength booktest.examples.LiveHttpServerTests"`

The runner walks the dep chain: `clientLength` needs `server`, `server`
needs `state` (TestRef) and a port (the existing `PortPool`). If
`state.bin` exists from a prior run, the runner deserializes "hello world"
straight from disk. The port is allocated fresh via
`resources.ports.acquire()`. `StringServer` is built, `clientLength` runs,
the server is closed, `resources.ports.release(port)` returns the port to
the pool.

Live resources are never persisted — only test return values are.

## Variant: stateful resource (`withReset`)

If consumers mutated server state (a counter, a database table) and tests
asserted on it, switch to:

```scala
val server: ResourceRef[StringServer] =
  liveResource.withReset("stringServer", state, resources.ports) {
    (s, port) => new StringServer(port, s)
  } { server =>
    server.get("/admin/reset")
  }
```

The runner serializes consumer execution on this instance via a named lock
from the existing `LockPool` (`ResourceManager.locks`) and calls the reset
closure between consumers. Build and close still happen once; the port
stays allocated for the whole run.

## Variant: per-consumer instance (`exclusive`)

If a test needs a private instance (e.g., it modifies global server
config):

```scala
val server: ResourceRef[StringServer] =
  liveResource.exclusive("stringServer", state, resources.ports) {
    (s, port) => new StringServer(port, s)
  }
```

Each consumer triggers its own build, gets its own port allocation, and
closes the instance when done. Effectively the same as today's per-test
setup/teardown — useful as the explicit fallback when sharing isn't safe.

## Variant: resource depending on another resource

A `ResourceRef` can appear in another `liveResource`'s dep list:

```scala
val db: ResourceRef[PostgresHandle] =
  liveResource("pg", schemaSql, resources.ports) {
    (sql, port) => PostgresHandle.start(port, sql)
  }

val app: ResourceRef[AppServer] =
  liveResource("app", db, resources.ports) {
    (database, port) => new AppServer(port, database.jdbcUrl)
  }
```

`app` is built once `db` is built. `db` stays alive as long as `app` is
alive (refcount holds it). When the last consumer of `app` finishes, `app`
closes; `db`'s refcount drops; if no other consumers need `db`, it closes
too and both ports return to the pool.
