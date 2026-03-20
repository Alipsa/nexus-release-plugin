package se.alipsa.gradle.plugin.release

import groovy.ant.AntBuilder
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

import java.util.zip.ZipFile

import static org.junit.jupiter.api.Assertions.*

class ReleasePluginTest {

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
  void configurationCacheCompatibility() throws IOException {
    File testProjectDir = TestFixtures.createTestProject()
    try {
      // First run - should store configuration cache
      BuildResult result1 = GradleRunner.create()
          .withProjectDir(testProjectDir)
          .withArguments("bundle", "--configuration-cache")
          .withPluginClasspath()
          .forwardOutput()
          .build()

      assertEquals(TaskOutcome.SUCCESS, result1.task(":bundle").getOutcome())
      assertTrue(result1.output.contains("Configuration cache entry stored"),
          "First run should store configuration cache")

      // Clean build outputs but keep configuration cache
      new File(testProjectDir, "build/zips").deleteDir()
      new File(testProjectDir, "build/libs").deleteDir()

      // Second run - should reuse configuration cache
      BuildResult result2 = GradleRunner.create()
          .withProjectDir(testProjectDir)
          .withArguments("bundle", "--configuration-cache")
          .withPluginClasspath()
          .forwardOutput()
          .build()

      assertEquals(TaskOutcome.SUCCESS, result2.task(":bundle").getOutcome())
      assertTrue(result2.output.contains("Configuration cache entry reused") ||
                 result2.output.contains("Reusing configuration cache"),
          "Second run should reuse configuration cache")
    } finally {
      cleanupTestProject(testProjectDir)
    }
  }

  @Test
  void zipIsCreatedAfterPublish() throws IOException {
    File testProjectDir = TestFixtures.createTestProject()
    try {
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

      // Check contents of zip file
      String groupId = "com.example"
      String artifactId = "test-project"
      String version = "1.0.0"
      String basePath = "${groupId.replace('.', '/')}/${artifactId}/${version}/"

      // Define expected filenames inside the ZIP
      String artifactPath = basePath + artifactId + '-' + version
      List<String> expectedEntries = [
          artifactPath + '.jar',
          artifactPath + '.jar.asc',
          artifactPath + '.jar.md5',
          artifactPath + '.jar.sha1',
          artifactPath + '-sources.jar',
          artifactPath + '-sources.jar.asc',
          artifactPath + '-sources.jar.md5',
          artifactPath + '-sources.jar.sha1',
          artifactPath + '-javadoc.jar',
          artifactPath + '-javadoc.jar.asc',
          artifactPath + '-javadoc.jar.md5',
          artifactPath + '-javadoc.jar.sha1',
          artifactPath + '.pom',
          artifactPath +'.pom.asc',
          artifactPath +'.pom.md5',
          artifactPath +'.pom.sha1'
      ]

      // Read actual ZIP entries
      List<String> actualEntries
      try (ZipFile zip = new ZipFile(zipFile)) {
        actualEntries = zip.entries().collect { it.name }
      }
      expectedEntries.each {
        assertTrue(actualEntries.contains(it), "Expected entry not found in ZIP: $it")
      }
      assertEquals(expectedEntries.size(), actualEntries.size(), "Mismatch in number of entries in ZIP")
    } finally {
      cleanupTestProject(testProjectDir)
    }
  }

  @Test
  void releaseFailsBeforeUploadOnFilenameMismatch() throws IOException {
    String buildScript = TestFixtures.createBuildScript().replace(
        "version = '1.0.0'",
        """version = '1.0.0'
base {
  archivesName = 'sieparser'
}"""
    )
    File testProjectDir = TestFixtures.createTestProject(buildScript)
    try {
      BuildResult result = GradleRunner.create()
          .withProjectDir(testProjectDir)
          .withArguments("release")
          .withPluginClasspath()
          .forwardOutput()
          .buildAndFail()

      assertTrue(result.output.contains("Bundle validation failed."))
      assertTrue(result.output.contains("Filename mismatch"))
      assertTrue(result.output.contains("sieparser-1.0.0.jar"))
    } finally {
      cleanupTestProject(testProjectDir)
    }
  }

  @Test
  void releaseFailsBeforeUploadOnMissingCredentials() throws IOException {
    String buildScript = TestFixtures.buildScript(
        TestFixtures.generateTestPrivateKey(),
        'http://localhost:1/api/v1',
        '',
        ''
    )
    File testProjectDir = TestFixtures.createTestProject(buildScript)
    try {
      BuildResult result = GradleRunner.create()
          .withProjectDir(testProjectDir)
          .withArguments("release")
          .withPluginClasspath()
          .forwardOutput()
          .buildAndFail()

      assertTrue(result.output.contains("Bundle validation failed."))
      assertTrue(result.output.contains("Credentials not configured. Add the following to ~/.gradle/gradle.properties"))
      assertFalse(result.output.contains("Post multipart to"))
    } finally {
      cleanupTestProject(testProjectDir)
    }
  }

  @Test
  void releaseUsesPublicationVersionWhenProjectVersionDiffers() throws IOException {
    String buildScript = TestFixtures.createBuildScript()
        .replace("version = '1.0.0'", "version = '1.0.0-SNAPSHOT'")
        .replace("artifact(sourcesJar)", "artifact(sourcesJar)\n            version = '1.0.0'")
        .replace("userName = 'sonaTypeUserName'", "userName = ''")
        .replace("password = 'sonaTypePassword'", "password = ''")

    File testProjectDir = TestFixtures.createTestProject(buildScript)
    try {
      BuildResult result = GradleRunner.create()
          .withProjectDir(testProjectDir)
          .withArguments("release")
          .withPluginClasspath()
          .forwardOutput()
          .buildAndFail()

      assertTrue(result.output.contains("Credentials not configured. Add the following to ~/.gradle/gradle.properties"))
      assertFalse(result.output.contains("A snapshot cannot be released"))
    } finally {
      cleanupTestProject(testProjectDir)
    }
  }

  @Test
  void latestMavenVersionsListsRootAndSubprojects() throws IOException {
    Map<String, String> versionsByArtifact = [
        'multi-module-project': '1.2.0',
        'lib-one'            : '2.3.4',
        'lib-two'            : '3.4.5'
    ]
    server.setDispatcher(new MetadataDispatcher(versionsByArtifact))

    String metadataBaseUrl = server.url('/maven2').toString()
    File testProjectDir = TestFixtures.createMultiModuleProject(metadataBaseUrl)
    try {
      BuildResult firstRun = GradleRunner.create()
          .withProjectDir(testProjectDir)
          .withArguments('latestMavenVersions', '--configuration-cache')
          .withPluginClasspath()
          .forwardOutput()
          .build()

      assertEquals(TaskOutcome.SUCCESS, firstRun.task(':latestMavenVersions').outcome)
      assertTrue(firstRun.output.contains(':lib-one: com.example:lib-one:2.3.4'))
      assertTrue(firstRun.output.contains(':lib-two: com.example:lib-two:3.4.5'))
      assertTrue(firstRun.output.contains('multi-module-project: com.example:multi-module-project:1.2.0'))
      assertTrue(firstRun.output.contains('Configuration cache entry stored'))

      BuildResult secondRun = GradleRunner.create()
          .withProjectDir(testProjectDir)
          .withArguments('latestMavenVersions', '--configuration-cache')
          .withPluginClasspath()
          .forwardOutput()
          .build()

      assertEquals(TaskOutcome.SUCCESS, secondRun.task(':latestMavenVersions').outcome)
      assertTrue(secondRun.output.contains('Configuration cache entry reused') ||
          secondRun.output.contains('Reusing configuration cache'))
    } finally {
      cleanupTestProject(testProjectDir)
    }
  }

  private static final class MetadataDispatcher extends Dispatcher {
    private final Map<String, String> versionsByArtifact

    private MetadataDispatcher(Map<String, String> versionsByArtifact) {
      this.versionsByArtifact = versionsByArtifact
    }

    @Override
    MockResponse dispatch(RecordedRequest request) {
      String path = request.path?.split('\\?')[0]
      String artifact = path?.tokenize('/')?.reverse()?.getAt(1)
      String version = artifact == null ? null : versionsByArtifact.get(artifact)
      if (version == null) {
        return new MockResponse().setResponseCode(404).setBody('Not Found')
      }
      new MockResponse()
          .setResponseCode(200)
          .setBody("""
            <metadata>
              <groupId>com.example</groupId>
              <artifactId>${artifact}</artifactId>
              <versioning>
                <release>${version}</release>
                <latest>${version}</latest>
              </versioning>
            </metadata>
          """.stripIndent().trim())
    }
  }

  private static void cleanupTestProject(File testProjectDir) {
    AntBuilder ant = new AntBuilder()
    ant.delete dir: testProjectDir.parentFile
  }
}
