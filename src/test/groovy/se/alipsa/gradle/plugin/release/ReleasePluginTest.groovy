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
}