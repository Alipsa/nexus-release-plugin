package se.alipsa.gradle.plugin.release

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.gradle.api.GradleException
import org.gradle.testfixtures.ProjectBuilder
import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertThrows
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
    server.setDispatcher(new Dispatcher() {
      @Override
      MockResponse dispatch(RecordedRequest request) {
        if (request.path.startsWith('/api/v1/publisher/upload')) {
          return new MockResponse().setResponseCode(200).setBody('1234567')
        }
        return new MockResponse().setResponseCode(404).setBody('Not Found')
      }
    })

    String urlPath = "/api/v1/publisher/upload?publishingType=AUTOMATIC"
    def url = server.url(urlPath)
    def targetUrl = "${url.scheme()}://${url.host()}:${url.port()}/api/v1".toString()

    String buildScript = TestFixtures.createBuildScript(targetUrl)
    File testProjectDir = TestFixtures.createTestProject(buildScript)

    BuildResult result = GradleRunner.create()
        .withProjectDir(testProjectDir)
        .withArguments("bundle")
        .withPluginClasspath()
        .forwardOutput()
        .build()

    assertEquals(TaskOutcome.SUCCESS, result.task(":bundle").outcome)

    File zipFile = new File(testProjectDir, "build/zips/test-project-1.0.0-bundle.zip")
    assertTrue(zipFile.exists(), "ZIP file should be created")

    def project = ProjectBuilder.builder()
        .withProjectDir(testProjectDir)
        .build()
    CentralPortalClient portalClient = new CentralPortalClient(project.logger, targetUrl, null, null)
    portalClient.upload(zipFile)

    def recorded = server.takeRequest()
    assert recorded.method == "POST"
    assert recorded.path.contains("publishingType=AUTOMATIC")
    assert recorded.getHeader("Authorization")?.startsWith("Bearer ")
    def actualPath = recorded.requestUrl.encodedPath() + "?" + recorded.requestUrl.query()
    assertEquals(urlPath, actualPath)
  }

  @Test
  void shouldUploadAndPollSuccessfully() {
    String deploymentId = '1234567'
    server.setDispatcher(new Dispatcher() {
      @Override
      MockResponse dispatch(RecordedRequest request) {
        if (request.path.startsWith('/api/v1/publisher/upload')) {
          return new MockResponse().setResponseCode(200).setBody(deploymentId)
        }
        if (request.path.startsWith('/api/v1/publisher/status')) {
          def query = request.requestUrl?.queryParameter('id')
          if (query == deploymentId) {
            return new MockResponse().setResponseCode(200).setBody("""{
              "deploymentId": "${deploymentId}",
              "deploymentName": "central-bundle.zip",
              "deploymentState": "PUBLISHING"
            }""".stripIndent())
          }
          return new MockResponse().setResponseCode(404).setBody('Not Found')
        }
        return new MockResponse().setResponseCode(404).setBody("Unknown path: ${request.path}")
      }
    })

    String urlPath = "/api/v1/publisher/upload?publishingType=AUTOMATIC"
    def url = server.url(urlPath)
    def targetUrl = "${url.scheme()}://${url.host()}:${url.port()}/api/v1".toString()

    String buildScript = TestFixtures.createBuildScript(targetUrl)
    File testProjectDir = TestFixtures.createTestProject(buildScript)

    BuildResult result = GradleRunner.create()
        .withProjectDir(testProjectDir)
        .withArguments('release')
        .withPluginClasspath()
        .forwardOutput()
        .build()

    assertEquals(TaskOutcome.SUCCESS, result.task(':release').outcome)

    File zipFile = new File(testProjectDir, 'build/zips/test-project-1.0.0-bundle.zip')
    assertTrue(zipFile.exists(), 'ZIP file should be created')

    def recorded = server.takeRequest()
    assert recorded.method == 'POST'
    assert recorded.path.contains('publishingType=AUTOMATIC')
    assert recorded.getHeader('Authorization')?.startsWith('Bearer ')
    def actualPath = recorded.requestUrl.encodedPath() + '?' + recorded.requestUrl.query()
    assertEquals(urlPath, actualPath)
  }

  @Test
  void upload401ReturnsFriendlyMessage() {
    withUploadStatus(401, '{"error":"bad credentials"}') { String targetUrl ->
      CentralPortalClient client = new CentralPortalClient(ProjectBuilder.builder().build().logger, targetUrl, 'u', 'p')
      GradleException ex = assertThrows(GradleException) {
        client.upload(createDummyZip())
      }
      assertTrue(ex.message.contains('Authentication failed - check sonatypeUsername/sonatypePassword'))
    }
  }

  @Test
  void upload403ReturnsFriendlyMessage() {
    withUploadStatus(403, '{"error":"namespace not verified"}') { String targetUrl ->
      CentralPortalClient client = new CentralPortalClient(ProjectBuilder.builder().build().logger, targetUrl, 'u', 'p')
      GradleException ex = assertThrows(GradleException) {
        client.upload(createDummyZip())
      }
      assertTrue(ex.message.contains('Forbidden - is the namespace verified in your Central Portal account?'))
    }
  }

  @Test
  void upload422ReturnsBodyInMessage() {
    withUploadStatus(422, '{"error":"malformed bundle"}') { String targetUrl ->
      CentralPortalClient client = new CentralPortalClient(ProjectBuilder.builder().build().logger, targetUrl, 'u', 'p')
      GradleException ex = assertThrows(GradleException) {
        client.upload(createDummyZip())
      }
      assertTrue(ex.message.contains('Rejected by Central Portal - see body for details'))
      assertTrue(ex.message.contains('malformed bundle'))
    }
  }

  @Test
  void failedStatusErrorsAreLogged() {
    String deploymentId = '1234567'
    server.setDispatcher(new Dispatcher() {
      @Override
      MockResponse dispatch(RecordedRequest request) {
        if (request.path.startsWith('/api/v1/publisher/upload')) {
          return new MockResponse().setResponseCode(200).setBody(deploymentId)
        }
        if (request.path.startsWith('/api/v1/publisher/status')) {
          return new MockResponse().setResponseCode(200).setBody('''
            {
              "deploymentId": "1234567",
              "deploymentState": "FAILED",
              "errors": [
                {"component": "sieparser-2.0.jar", "message": "Artifact coordinates mismatch"}
              ]
            }
          '''.stripIndent())
        }
        return new MockResponse().setResponseCode(404).setBody('Not Found')
      }
    })

    String urlPath = '/api/v1/publisher/upload?publishingType=AUTOMATIC'
    def url = server.url(urlPath)
    String targetUrl = "${url.scheme()}://${url.host()}:${url.port()}/api/v1"

    String buildScript = TestFixtures.createBuildScript(targetUrl)
    File testProjectDir = TestFixtures.createTestProject(buildScript)

    BuildResult result = GradleRunner.create()
        .withProjectDir(testProjectDir)
        .withArguments('release')
        .withPluginClasspath()
        .forwardOutput()
        .buildAndFail()

    assertTrue(result.output.contains('Deployment FAILED. Validation errors reported by Maven Central:'))
    assertTrue(result.output.contains('sieparser-2.0.jar: Artifact coordinates mismatch'))
    assertTrue(result.output.contains('Failed to release test-project with deploymentId 1234567'))
  }

  private static void withUploadStatus(int code, String body, Closure<?> assertion) {
    server.setDispatcher(new Dispatcher() {
      @Override
      MockResponse dispatch(RecordedRequest request) {
        if (request.path.startsWith('/api/v1/publisher/upload')) {
          return new MockResponse().setResponseCode(code).setBody(body)
        }
        return new MockResponse().setResponseCode(404).setBody('Not Found')
      }
    })
    def url = server.url('/api/v1/publisher/upload?publishingType=AUTOMATIC')
    String targetUrl = "${url.scheme()}://${url.host()}:${url.port()}/api/v1"
    assertion.call(targetUrl)
  }

  private static File createDummyZip() {
    File dir = File.createTempDir('central-upload', '')
    File zipFile = new File(dir, 'bundle.zip')
    try (ZipOutputStream zipOut = new ZipOutputStream(new FileOutputStream(zipFile))) {
      zipOut.putNextEntry(new ZipEntry('dummy.txt'))
      zipOut.write('x'.bytes)
      zipOut.closeEntry()
    }
    return zipFile
  }
}
