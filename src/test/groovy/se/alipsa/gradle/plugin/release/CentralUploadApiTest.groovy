package se.alipsa.gradle.plugin.release

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.*
import org.gradle.testfixtures.ProjectBuilder

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertTrue

class CentralUploadApiTest {
  static MockWebServer server

  @BeforeAll
  static void startServer() {
    server = new MockWebServer()
    server.start()
  }

  @AfterAll
  static void stopServer() {
    server.shutdown()
  }

  @Test
  void testCentralPortalClientUpload() {
    // Mock server response
    server.enqueue(new MockResponse()
        .setResponseCode(200)
        .setBody('1234567')
    )

    String urlPath = "/api/v1/publisher/upload?publishingType=AUTOMATIC"
    def url = server.url(urlPath)
    def targetUrl = "${url.scheme()}://${url.host()}:${url.port()}/api/v1".toString()
    //return
    // Prepare zip file
    String buildScript = TestFixtures.createBuildScript(targetUrl)
    File testProjectDir = TestFixtures.createTestProject(buildScript)

    // Run build
    BuildResult result = GradleRunner.create()
        .withProjectDir(testProjectDir)
        .withArguments("bundle")
        .withPluginClasspath()
        .forwardOutput()
        .build()

    assertEquals(TaskOutcome.SUCCESS, result.task(":bundle").getOutcome())

    File zipFile = new File(testProjectDir,"build/zips/test-project-1.0.0-bundle.zip")
    assertTrue(zipFile.exists(), "ZIP file should be created")

    def project = ProjectBuilder.builder()
        .withProjectDir(testProjectDir)
        .build()
    println "TargetUrl = $targetUrl"
    CentralPortalClient portalClient = new CentralPortalClient(project.logger, targetUrl, null, null)
    portalClient.upload(zipFile)

    // Inspect request to the mock server
    def recorded = server.takeRequest()
    assert recorded.method == "POST"
    assert recorded.path.contains("publishingType=AUTOMATIC")
    assert recorded.getHeader("Authorization")?.startsWith("Bearer ")
    def actualPath = recorded.requestUrl.encodedPath()  + "?" + recorded.requestUrl.query()
    assertEquals(urlPath, actualPath)
  }

  @Test
  void 'should upload bundle successfully'() {
    // Mock server response
    String deploymentId = '1234567'
    // Mock server response
    server.setDispatcher(new Dispatcher() {
      @Override
      MockResponse dispatch(RecordedRequest request) {
        if (request.path.startsWith("/api/v1/publisher/upload")) {
          return new MockResponse()
              .setResponseCode(200)
              .setBody(deploymentId)
        } else if (request.path.startsWith("/api/v1/publisher/status")) {
          def query = request.requestUrl?.queryParameter("id")
          if (query == deploymentId) {
            return new MockResponse()
                .setResponseCode(200)
                .setBody("""{  
                "deploymentId": "${deploymentId}",
                "deploymentName": "central-bundle.zip",
                "deploymentState": "PUBLISHING"
                }""".stripIndent())
          } else {
            return new MockResponse()
                .setResponseCode(404)
                .setBody("Not Found")
          }
        }
        return new MockResponse()
            .setResponseCode(404)
            .setBody("Unknown path: ${request.path}")
      }
    })
    String urlPath = "/api/v1/publisher/upload?publishingType=AUTOMATIC"
    def url = server.url(urlPath)
    def targetUrl = "${url.scheme()}://${url.host()}:${url.port()}/api/v1".toString()

    // Prepare zip file
    String buildScript = TestFixtures.createBuildScript(targetUrl)
    File testProjectDir = TestFixtures.createTestProject(buildScript)

    // Run build
    BuildResult result = GradleRunner.create()
        .withProjectDir(testProjectDir)
        .withArguments("release")
        .withPluginClasspath()
        .forwardOutput()
        .build()

    assertEquals(TaskOutcome.SUCCESS, result.task(":release").getOutcome())

    File zipFile = new File(testProjectDir,"build/zips/test-project-1.0.0-bundle.zip")
    assertTrue(zipFile.exists(), "ZIP file should be created")


    // Inspect request to the mock server
    def recorded = server.takeRequest()
    assert recorded.method == "POST"
    assert recorded.path.contains("publishingType=AUTOMATIC")
    assert recorded.getHeader("Authorization")?.startsWith("Bearer ")
    def actualPath = recorded.requestUrl.encodedPath()  + "?" + recorded.requestUrl.query()
    assertEquals(urlPath, actualPath)
  }
}
