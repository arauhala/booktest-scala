package booktest.examples

import booktest.*
import booktest.HttpSnapshotting.*

class LeHttpSnapshotTests extends TestSuite {

  def testSimpleHttpGet(t: TestCaseRun): Unit = {
    t.h1("Simple HTTP GET Test")
    
    withHttpSnapshots(t) { client =>
      val response = client.get("https://api.github.com/users/octocat")
      
      t.tln(s"Status: ${response.statusCode}")
      t.tln(s"Content-Type: ${response.headers.getOrElse("Content-Type", "unknown")}")
      t.tln(s"Body: ${response.body}")
    }
  }

  def testHttpPost(t: TestCaseRun): Unit = {
    t.h1("HTTP POST Test")
    
    withHttpSnapshots(t) { client =>
      val postData = """{"name": "Test User", "email": "test@example.com"}"""
      val response = client.post(
        "https://jsonplaceholder.typicode.com/users",
        body = postData,
        headers = Map("Content-Type" -> "application/json")
      )
      
      t.tln(s"Status: ${response.statusCode}")
      t.tln(s"Response: ${response.body}")
    }
  }

  def testMultipleHttpRequests(t: TestCaseRun): Unit = {
    t.h1("Multiple HTTP Requests Test")
    
    withHttpSnapshots(t) { client =>
      // First request
      val user = client.get("https://jsonplaceholder.typicode.com/users/1")
      t.tln(s"User data: ${user.body}")
      
      // Second request
      val posts = client.get("https://jsonplaceholder.typicode.com/users/1/posts")
      t.tln(s"Posts count: ${posts.statusCode}")
      
      // Third request with different method
      val newPost = client.post(
        "https://jsonplaceholder.typicode.com/posts",
        body = """{"title": "Test Post", "body": "This is a test", "userId": 1}""",
        headers = Map("Content-Type" -> "application/json")
      )
      t.tln(s"Created post status: ${newPost.statusCode}")
    }
  }

  def testHttpErrorHandling(t: TestCaseRun): Unit = {
    t.h1("HTTP Error Handling Test")
    
    withHttpSnapshots(t) { client =>
      val response = client.get("https://httpstat.us/404")
      
      t.tln(s"Status: ${response.statusCode}")
      t.tln(s"Body: ${response.body}")
      
      // Test that we can handle different status codes
      if (response.statusCode == 404) {
        t.tln("Successfully captured 404 response")
      }
    }
  }
}