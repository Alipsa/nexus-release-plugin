package se.alipsa.gradle.plugin.release

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.gradle.api.GradleException
import org.gradle.testfixtures.ProjectBuilder
import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

import static org.junit.jupiter.api.Assertions.assertFalse
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

  @org.junit.jupiter.api.BeforeEach
  void drainRecordedRequests() {
    while (server.takeRequest(1, java.util.concurrent.TimeUnit.MILLISECONDS) != null) { }
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
        .replace(
            'mavenPublication = publishing.publications.maven',
            '''mavenPublication = publishing.publications.maven
        initialStatusCheckDelaySeconds = 0
        statusCheckIntervalSeconds = 0'''
        )
    File testProjectDir = TestFixtures.createTestProject(buildScript)

    BuildResult result = MatrixRunner.create()
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
    assertEquals(recorded.bodySize.toString(), recorded.getHeader("Content-Length"))
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
        .replace(
            'mavenPublication = publishing.publications.maven',
            '''mavenPublication = publishing.publications.maven
        initialStatusCheckDelaySeconds = 0
        statusCheckIntervalSeconds = 0'''
        )
    File testProjectDir = TestFixtures.createTestProject(buildScript)

    BuildResult result = MatrixRunner.create()
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

    BuildResult result = MatrixRunner.create()
        .withProjectDir(testProjectDir)
        .withArguments('release')
        .withPluginClasspath()
        .forwardOutput()
        .buildAndFail()

    assertTrue(result.output.contains('Deployment FAILED. Validation errors reported by Maven Central:'))
    assertTrue(result.output.contains('sieparser-2.0.jar: Artifact coordinates mismatch'))
    assertTrue(result.output.contains('Failed to release test-project with deploymentId 1234567'))
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.MethodSource('pollScenarios')
  @org.junit.jupiter.api.Timeout(120)
  void pollingUsesExactAttemptBudget(int retries, List responses, String expected, int requests) {
    java.util.concurrent.atomic.AtomicInteger uploads = new java.util.concurrent.atomic.AtomicInteger()
    java.util.concurrent.atomic.AtomicInteger statuses = new java.util.concurrent.atomic.AtomicInteger()
    server.setDispatcher(new Dispatcher() {
      @Override MockResponse dispatch(RecordedRequest request) {
        if (request.path.startsWith('/api/v1/publisher/upload')) {
          uploads.incrementAndGet()
          return new MockResponse().setBody('poll-id')
        }
        if (request.path.startsWith('/api/v1/publisher/status')) {
          int index = statuses.getAndIncrement()
          Object response = responses[Math.min(index, responses.size() - 1)]
          if (response instanceof Integer) return new MockResponse().setResponseCode((Integer) response).setBody('temporary error')
          if (response == 'malformed') return new MockResponse().setBody('{')
          return new MockResponse().setBody('{"deploymentState":"' + response + '"}')
        }
        return new MockResponse().setResponseCode(404)
      }
    })
    String script = TestFixtures.createBuildScript(server.url('/api/v1').toString()) + """
nexusReleasePlugin {
  initialStatusCheckDelaySeconds = 0
  statusCheckIntervalSeconds = 0
  statusCheckRetries = ${retries}
}
"""
    File dir = TestFixtures.createTestProject(script)
    try {
      def runner = MatrixRunner.create().withProjectDir(dir).withArguments('release').withPluginClasspath()
      def result = expected == 'success' ? runner.build() : runner.buildAndFail()
      if (expected != 'success') assertTrue(result.output.contains(expected), result.output)
      responses.take(requests).eachWithIndex { response, index ->
        if (response in ['PENDING', 'VALIDATING']) {
          assertTrue(result.output.contains("Deploy status is ${response} (check ${index + 1}/${retries + 1})"), result.output)
        }
      }
      assertEquals(1, uploads.get())
      assertEquals(requests, statuses.get())
    } finally { dir.parentFile.deleteDir() }
  }

  static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> pollScenarios() {
    def rows = [
      [0, ['PUBLISHED'], 'success', 1],
      [0, ['PENDING'], 'still PENDING after 1 status checks', 1],
      [0, [502], 'Last error:', 1],
      [2, ['PENDING', 'VALIDATING', 'PENDING'], 'still PENDING after 3 status checks', 3],
      [2, [502, 'PUBLISHED'], 'success', 2],
      [2, [429, 'PUBLISHED'], 'success', 2],
      [2, ['malformed', 'PUBLISHED'], 'success', 2],
      [2, [401], 'Authentication failed', 1],
      [2, [403], 'Forbidden - is the namespace verified', 1]
    ]
    rows.stream().map { org.junit.jupiter.params.provider.Arguments.of(it as Object[]) }
  }

  @Test @org.junit.jupiter.api.Timeout(120)
  void releaseUploadDeadlineBoundsStalledWrite() {
    ServerSocket stalled = new ServerSocket(0)
    Socket peer = null
    java.util.concurrent.atomic.AtomicLong acceptedAt = new java.util.concurrent.atomic.AtomicLong()
    Thread accept = Thread.startDaemon { try { peer = stalled.accept(); acceptedAt.set(System.nanoTime()) } catch (IOException ignored) { } }
    String script = TestFixtures.createBuildScript("http://127.0.0.1:${stalled.localPort}/api/v1") + """
nexusReleasePlugin { httpRequestTimeoutSeconds = 1 }
tasks.named('release') {
  doFirst { new RandomAccessFile(bundleFile.get().asFile, 'rw').withCloseable { it.setLength(64L * 1024 * 1024) } }
}
"""
    // Appending padding keeps the ZIP readable only within the ZIP trailing-data tolerance,
    // so use a real large, incompressible artifact instead.
    script = script.substring(0, script.indexOf("tasks.named('release')")) + '''
tasks.register('largeArtifact') {
  def output = layout.buildDirectory.file('large.bin')
  outputs.file(output)
  doLast {
    output.get().asFile.parentFile.mkdirs()
    output.get().asFile.withOutputStream { stream ->
      def random = new Random(42)
      byte[] bytes = new byte[65536]
      1024.times { random.nextBytes(bytes); stream.write(bytes) }
    }
  }
}
publishing.publications.maven.artifact(layout.buildDirectory.file('large.bin')) {
  classifier = 'large'; extension = 'bin'; builtBy tasks.named('largeArtifact')
}
'''
    File dir = TestFixtures.createTestProject(script)
    try {
      def result = MatrixRunner.create().withProjectDir(dir).withArguments('release').withPluginClasspath().buildAndFail()
      assertTrue(result.output.contains('Request exceeded overall deadline'), result.output)
      assertTrue(acceptedAt.get() > 0)
      assertTrue(java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - acceptedAt.get()) <= 6000, 'Stalled upload did not return promptly')
    } finally { stalled.close(); peer?.close(); accept.join(1000); dir.parentFile.deleteDir() }
  }

  @Test @org.junit.jupiter.api.Timeout(120)
  void stalledStatusIsRetriedWithoutAnotherUpload() {
    java.util.concurrent.atomic.AtomicInteger uploads = new java.util.concurrent.atomic.AtomicInteger()
    java.util.concurrent.atomic.AtomicInteger statuses = new java.util.concurrent.atomic.AtomicInteger()
    server.setDispatcher(new Dispatcher() {
      @Override MockResponse dispatch(RecordedRequest request) {
        if (request.path.startsWith('/api/v1/publisher/upload')) {
          uploads.incrementAndGet(); return new MockResponse().setBody('stall-id')
        }
        if (request.path.startsWith('/api/v1/publisher/status')) {
          if (statuses.incrementAndGet() == 1) return new MockResponse().setBody('{"deploymentState":"PENDING"}').setBodyDelay(3, java.util.concurrent.TimeUnit.SECONDS)
          return new MockResponse().setBody('{"deploymentState":"PUBLISHED"}')
        }
        return new MockResponse().setResponseCode(404)
      }
    })
    String script = TestFixtures.createBuildScript(server.url('/api/v1').toString()) + '''
nexusReleasePlugin {
  httpRequestTimeoutSeconds = 1
  initialStatusCheckDelaySeconds = 0
  statusCheckIntervalSeconds = 0
  statusCheckRetries = 1
}
'''
    File dir = TestFixtures.createTestProject(script)
    try {
      def result = MatrixRunner.create().withProjectDir(dir).withPluginClasspath().withArguments('release').build()
      assertTrue(result.output.contains('Status check failed (check 1/2)'))
      assertTrue(result.output.contains('Request exceeded overall deadline'))
      assertEquals(1, uploads.get()); assertEquals(2, statuses.get())
    } finally { dir.parentFile.deleteDir() }
  }

  @Test @org.junit.jupiter.api.Timeout(30)
  void combinedTimeoutRecordsUnknownUploadAndRetriesOnlyStatus() {
    File dir = File.createTempDir('combined-timeout', '')
    File record = new File(dir, 'record.json')
    try {
      def client = new CentralPortalClient(org.gradle.api.logging.Logging.getLogger(CentralUploadApiTest), server.url('/api/v1').toString(), 'u', 'p', 1, 1)
      java.util.concurrent.atomic.AtomicInteger uploads = new java.util.concurrent.atomic.AtomicInteger()
      java.util.concurrent.atomic.AtomicInteger polls = new java.util.concurrent.atomic.AtomicInteger()
      server.setDispatcher(new Dispatcher() {
        @Override MockResponse dispatch(RecordedRequest request) {
          if (request.path.startsWith('/api/v1/publisher/upload')) {
            uploads.incrementAndGet()
            return new MockResponse().setBody('combined-id').setBodyDelay(3, java.util.concurrent.TimeUnit.SECONDS)
          }
          polls.incrementAndGet()
          if (polls.get() == 1) return new MockResponse().setBody('{"deploymentState":"PENDING"}').setBodyDelay(3, java.util.concurrent.TimeUnit.SECONDS)
          return new MockResponse().setBody('{"deploymentState":"PUBLISHED"}')
        }
      })
      File bundle = new File(dir, 'bundle.zip'); bundle.text = 'upload'
      def manifest = [selected: []]
      assertThrows(IOException) { CombinedDeployment.submit(record, bundle, manifest, new PublicationStatus(), client) }
      assertEquals('UPLOAD_ERROR', ReleaseManifest.read(record).state)
      ReleaseManifest.write(record, [deploymentId: 'combined-id', state: 'UPLOADED', manifest: manifest])
      def pollingClient = new CentralPortalClient(org.gradle.api.logging.Logging.getLogger(CentralUploadApiTest), server.url('/api/v1').toString(), 'u', 'p', java.time.Duration.ofSeconds(1), 1800000L, 1000L)
      CombinedDeployment.resume(record, 'combined-id', pollingClient, { true }, 2, 0)
      def state = ReleaseManifest.read(record)
      assertEquals('AVAILABLE', state.state)
      assertTrue(state.lastPollingError.contains('Request exceeded overall deadline'))
      assertEquals(1, uploads.get()); assertEquals(2, polls.get())
    } finally { dir.deleteDir() }
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = ['war', 'pom'])
  @org.junit.jupiter.api.Timeout(120)
  void packagingRejectedBeforeAnyUpload(String packaging) {
    java.util.concurrent.atomic.AtomicInteger requests = new java.util.concurrent.atomic.AtomicInteger()
    server.setDispatcher(new Dispatcher() {
      @Override MockResponse dispatch(RecordedRequest request) {
        requests.incrementAndGet(); return new MockResponse().setResponseCode(500)
      }
    })
    String key = TestFixtures.generateTestPrivateKey()
    String plugin = packaging == 'war' ? 'war' : 'java-platform'
    String component = packaging == 'war' ? 'web' : 'javaPlatform'
    String script = """
plugins { id '${plugin}'; id 'se.alipsa.nexus-release-plugin' }
group = 'com.example'
version = '1.0.0'
publishing {
 publications {
  maven(MavenPublication) {
   from components.${component}
   pom {
    name = 'fixture'; description = 'fixture'; url = 'https://example.com'
    licenses { license { name = 'MIT'; url = 'https://example.com/license' } }
    developers { developer { id = 'tester' } }
    scm { url = 'https://example.com'; connection = 'scm:git:https://example.com' }
   }
  }
 }
}
signing { useInMemoryPgpKeys(null, '''${key}''', ''); sign publishing.publications.maven }
nexusReleasePlugin {
 mavenPublication = publishing.publications.maven
 userName = 'u'; password = 'p'; nexusUrl = '${server.url('/api/v1')}'
}
"""
    File dir = TestFixtures.createTestProject(script)
    try {
      def result = MatrixRunner.create().withProjectDir(dir).withPluginClasspath().withArguments('release').buildAndFail()
      assertTrue(result.output.contains("Packaging '${packaging}' is not supported"), result.output)
      assertFalse(result.output.contains('Missing required bundle entry'))
      if (packaging == 'pom') assertTrue(result.output.contains('releaseAll'))
      assertEquals(0, requests.get())
    } finally { dir.parentFile.deleteDir() }
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = ['module', 'zip'])
  @org.junit.jupiter.api.Timeout(120)
  void missingExtraSignatureFailsBeforeUpload(String kind) {
    java.util.concurrent.atomic.AtomicInteger requests = new java.util.concurrent.atomic.AtomicInteger()
    server.setDispatcher(new Dispatcher() {
      @Override MockResponse dispatch(RecordedRequest request) { requests.incrementAndGet(); return new MockResponse().setResponseCode(500) }
    })
    String script = TestFixtures.createBuildScript(server.url('/api/v1').toString())
    if (kind == 'zip') script += '''
tasks.register('distZip', Zip) { archiveClassifier = 'dist'; from('src') }
publishing.publications.maven.artifact(tasks.named('distZip'))
'''
    String fileExpression = kind == 'module' ? "moduleMetadataFile.get().asFile" : "tasks.named('distZip').get().archiveFile.get().asFile"
    script += """
tasks.named('bundle') { doFirst { new File(${fileExpression}.path + '.asc').delete() } }
"""
    File dir = TestFixtures.createTestProject(script)
    try {
      def result = MatrixRunner.create().withProjectDir(dir).withPluginClasspath().withArguments('release').buildAndFail()
      assertTrue(result.output.contains('Missing signature for bundle entry:'), result.output)
      assertTrue(result.output.contains(kind == 'module' ? 'test-project-1.0.0.module' : 'test-project-1.0.0-dist.zip'))
      assertEquals(0, requests.get())
    } finally { dir.parentFile.deleteDir() }
  }

  static abstract class ShortDeadlineReleaseTask extends ReleaseTask {
    @Override protected CentralPortalClient createClient(String user, String pass) {
      new CentralPortalClient(logger, nexusUrl.get(), user, pass, java.time.Duration.ofSeconds(1),
          httpRequestTimeoutSeconds.get() * 1000L, 1000L)
    }
  }

  @Test @org.junit.jupiter.api.Timeout(30)
  void singleReleaseUsesSmallCallDeadlineWithLongUploadDeadline() {
    java.util.concurrent.atomic.AtomicInteger uploads = new java.util.concurrent.atomic.AtomicInteger()
    java.util.concurrent.atomic.AtomicInteger statuses = new java.util.concurrent.atomic.AtomicInteger()
    server.setDispatcher(new Dispatcher() {
      @Override MockResponse dispatch(RecordedRequest request) {
        if (request.path.startsWith('/api/v1/publisher/upload')) { uploads.incrementAndGet(); return new MockResponse().setBody('cap-id') }
        if (statuses.incrementAndGet() == 1) return new MockResponse().setBody('{"deploymentState":"PENDING"}').setBodyDelay(3, java.util.concurrent.TimeUnit.SECONDS)
        return new MockResponse().setBody('{"deploymentState":"PUBLISHED"}')
      }
    })
    File dir = File.createTempDir('small-call-release', '')
    try {
      File zip = new File(dir, 'bundle.zip')
      Map<String, byte[]> entries = [:]
      String pom = '<project><name>Demo</name><description>Demo</description><url>https://example.com</url><licenses><license><name>MIT</name><url>https://example.com/license</url></license></licenses><developers><developer><id>tester</id></developer></developers><scm><url>https://example.com</url><connection>scm:git:https://example.com</connection></scm></project>'
      ['.jar', '-sources.jar', '-javadoc.jar', '.pom'].each { suffix ->
        String name = 'com/example/demo/1.0.0/demo-1.0.0' + suffix
        entries[name] = (suffix == '.pom' ? pom : 'artifact').getBytes('UTF-8')
        ['.asc', '.md5', '.sha1'].each { entries[name + it] = 'fixture'.bytes }
      }
      new ZipOutputStream(new FileOutputStream(zip)).withCloseable { out ->
        entries.each { name, bytes -> out.putNextEntry(new ZipEntry(name)); out.write(bytes); out.closeEntry() }
      }
      def project = ProjectBuilder.builder().withProjectDir(dir).build()
      def task = project.tasks.create('smallDeadlineRelease', ShortDeadlineReleaseTask)
      task.bundleFile.set(zip); task.publicationVersion.set('1.0.0'); task.projectName.set('demo')
      task.groupId.set('com.example'); task.artifactId.set('demo'); task.userName.set('u'); task.password.set('p')
      task.nexusUrl.set(server.url('/api/v1').toString()); task.statusCheckRetries.set(1)
      task.initialStatusCheckDelaySeconds.set(0); task.statusCheckIntervalSeconds.set(0)
      task.httpRequestTimeoutSeconds.set(1800)
      long start = System.nanoTime()
      task.release()
      assertTrue(java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 7000)
      assertEquals(1, uploads.get()); assertEquals(2, statuses.get())
    } finally { dir.deleteDir() }
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
