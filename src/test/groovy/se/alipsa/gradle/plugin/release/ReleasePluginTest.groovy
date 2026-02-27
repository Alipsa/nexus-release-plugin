package se.alipsa.gradle.plugin.release

import groovy.ant.AntBuilder
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Test

import java.util.zip.ZipFile

import static org.junit.jupiter.api.Assertions.*

class ReleasePluginTest {

  @Test
  void configurationCacheCompatibility() throws IOException {
    File testProjectDir = TestFixtures.createTestProject()

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

    AntBuilder ant = new AntBuilder()
    ant.delete dir: testProjectDir.parentFile
  }

  @Test
  void zipIsCreatedAfterPublish() throws IOException {
    File testProjectDir = TestFixtures.createTestProject()

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

    AntBuilder ant = new AntBuilder()
    ant.delete dir: testProjectDir.parentFile
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

    BuildResult result = GradleRunner.create()
        .withProjectDir(testProjectDir)
        .withArguments("release")
        .withPluginClasspath()
        .forwardOutput()
        .buildAndFail()

    assertTrue(result.output.contains("Bundle validation failed."))
    assertTrue(result.output.contains("Filename mismatch"))
    assertTrue(result.output.contains("sieparser-1.0.0.jar"))

    AntBuilder ant = new AntBuilder()
    ant.delete dir: testProjectDir.parentFile
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

    BuildResult result = GradleRunner.create()
        .withProjectDir(testProjectDir)
        .withArguments("release")
        .withPluginClasspath()
        .forwardOutput()
        .buildAndFail()

    assertTrue(result.output.contains("Bundle validation failed."))
    assertTrue(result.output.contains("Credentials not configured. Add the following to ~/.gradle/gradle.properties"))
    assertFalse(result.output.contains("Post multipart to"))

    AntBuilder ant = new AntBuilder()
    ant.delete dir: testProjectDir.parentFile
  }

  @Test
  void releaseUsesPublicationVersionWhenProjectVersionDiffers() throws IOException {
    String buildScript = TestFixtures.createBuildScript()
        .replace("version = '1.0.0'", "version = '1.0.0-SNAPSHOT'")
        .replace("artifact(sourcesJar)", "artifact(sourcesJar)\n            version = '1.0.0'")
        .replace("userName = 'sonaTypeUserName'", "userName = ''")
        .replace("password = 'sonaTypePassword'", "password = ''")

    File testProjectDir = TestFixtures.createTestProject(buildScript)

    BuildResult result = GradleRunner.create()
        .withProjectDir(testProjectDir)
        .withArguments("release")
        .withPluginClasspath()
        .forwardOutput()
        .buildAndFail()

    assertTrue(result.output.contains("Credentials not configured. Add the following to ~/.gradle/gradle.properties"))
    assertFalse(result.output.contains("A snapshot cannot be released"))

    AntBuilder ant = new AntBuilder()
    ant.delete dir: testProjectDir.parentFile
  }
}
