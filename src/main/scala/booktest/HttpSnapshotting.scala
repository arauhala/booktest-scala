package booktest

import java.security.MessageDigest
import java.util.Base64
import upickle.default._

case class HttpRequest(
  url: String,
  method: String,
  headers: Map[String, String] = Map.empty,
  body: Option[String] = None
)
object HttpRequest {
  implicit val rw: ReadWriter[HttpRequest] = macroRW
}

case class HttpResponse(
  statusCode: Int,
  headers: Map[String, String] = Map.empty,
  body: String = ""
)
object HttpResponse {
  implicit val rw: ReadWriter[HttpResponse] = macroRW
}

case class HttpSnapshot(
  request: HttpRequest,
  response: HttpResponse,
  hash: String
)
object HttpSnapshot {
  implicit val rw: ReadWriter[HttpSnapshot] = macroRW
}

class HttpSnapshotManager(testCaseRun: TestCaseRun) {
  
  private val snapshotDir = testCaseRun.outDir / "_snapshots"
  private val snapshotFile = snapshotDir / "http.json"
  
  private var snapshots: Map[String, HttpSnapshot] = loadSnapshots()
  private var capturedSnapshots: Map[String, HttpSnapshot] = Map.empty
  
  private def loadSnapshots(): Map[String, HttpSnapshot] = {
    if (os.exists(snapshotFile)) {
      try {
        val content = os.read(snapshotFile)
        val parsed = ujson.read(content)
        parsed.obj.map { case (hash, value) =>
          hash -> read[HttpSnapshot](value.toString)
        }.toMap
      } catch {
        case e: Exception =>
          testCaseRun.i(s"Warning: Could not load HTTP snapshots: ${e.getMessage}")
          Map.empty
      }
    } else {
      Map.empty
    }
  }
  
  private def saveSnapshots(): Unit = {
    if (capturedSnapshots.nonEmpty) {
      os.makeDir.all(snapshotDir)
      val allSnapshots = snapshots ++ capturedSnapshots
      val json = ujson.Obj.from(allSnapshots.map { case (hash, snapshot) =>
        hash -> ujson.read(write(snapshot))
      })
      os.write.over(snapshotFile, ujson.write(json, indent = 2))
    }
  }
  
  private def createHash(request: HttpRequest): String = {
    val jsonStr = write(request)
    val digest = MessageDigest.getInstance("SHA-1")
    val hashBytes = digest.digest(jsonStr.getBytes("UTF-8"))
    hashBytes.map("%02x".format(_)).mkString
  }
  
  def captureRequest(request: HttpRequest, response: HttpResponse): Unit = {
    val hash = createHash(request)
    val snapshot = HttpSnapshot(request, response, hash)
    capturedSnapshots = capturedSnapshots + (hash -> snapshot)
  }
  
  def findSnapshot(request: HttpRequest): Option[HttpSnapshot] = {
    val hash = createHash(request)
    snapshots.get(hash) orElse capturedSnapshots.get(hash)
  }
  
  def mockRequest(request: HttpRequest): Option[HttpResponse] = {
    findSnapshot(request).map(_.response)
  }
  
  def logSnapshots(): Unit = {
    if (capturedSnapshots.nonEmpty) {
      testCaseRun.h1("HTTP Snapshots")
      capturedSnapshots.values.toSeq.sortBy(_.request.url).foreach { snapshot =>
        testCaseRun.tln(s"${snapshot.request.method} ${snapshot.request.url} -> ${snapshot.response.statusCode}")
      }
    }
  }
  
  def close(): Unit = {
    saveSnapshots()
    logSnapshots()
  }
}

// Simple HTTP client with snapshotting support
class SnapshotHttpClient(manager: HttpSnapshotManager) {
  
  def get(url: String, headers: Map[String, String] = Map.empty): HttpResponse = {
    request("GET", url, headers)
  }
  
  def post(url: String, body: String = "", headers: Map[String, String] = Map.empty): HttpResponse = {
    request("POST", url, headers, Some(body))
  }
  
  def put(url: String, body: String = "", headers: Map[String, String] = Map.empty): HttpResponse = {
    request("PUT", url, headers, Some(body))
  }
  
  def delete(url: String, headers: Map[String, String] = Map.empty): HttpResponse = {
    request("DELETE", url, headers)
  }
  
  private def request(method: String, url: String, headers: Map[String, String], body: Option[String] = None): HttpResponse = {
    val httpRequest = HttpRequest(url, method, headers, body)
    
    // Try to find existing snapshot first
    manager.findSnapshot(httpRequest) match {
      case Some(snapshot) => 
        snapshot.response
      case None =>
        // Make real HTTP request (simplified - in real implementation would use actual HTTP client)
        val response = makeRealRequest(httpRequest)
        manager.captureRequest(httpRequest, response)
        response
    }
  }
  
  private def makeRealRequest(request: HttpRequest): HttpResponse = {
    // This is a placeholder - in a real implementation, this would make actual HTTP calls
    // For now, return a mock response
    HttpResponse(
      statusCode = 200,
      headers = Map("Content-Type" -> "application/json"),
      body = s"""{"url": "${request.url}", "method": "${request.method}", "mock": true}"""
    )
  }
}

// Annotation for HTTP snapshotting
class snapshotHttp extends scala.annotation.StaticAnnotation

object HttpSnapshotting {
  
  def withHttpSnapshots[T](testCaseRun: TestCaseRun)(testCode: SnapshotHttpClient => T): T = {
    val manager = new HttpSnapshotManager(testCaseRun)
    val client = new SnapshotHttpClient(manager)
    
    try {
      testCode(client)
    } finally {
      manager.close()
    }
  }
}