package se.alipsa.gradle.plugin.release

import groovy.ant.AntBuilder
import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource

import java.util.stream.Stream

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertTrue

class GradleCompatibilityMatrixTest {

  static Stream<Arguments> gradleVersions() {
    // Allow CI to specify a single Gradle version via system property
    String ciVersion = System.getProperty("testGradleVersion")
    if (ciVersion != null && !ciVersion.isEmpty()) {
      return Stream.of(Arguments.of(ciVersion))
    }
    // Default versions to test when running locally or without specific version
    return Stream.of(
        Arguments.of('8.13'),
        Arguments.of('9.4.0')
    )
  }

  @ParameterizedTest(name = "bundle works on Gradle {0}")
  @MethodSource("gradleVersions")
  void bundleTaskIsCompatibleAcrossSupportedGradleVersions(String gradleVersion) throws IOException {
    File testProjectDir = TestFixtures.createTestProject()
    try {
      BuildResult result = GradleRunner.create()
          .withProjectDir(testProjectDir)
          .withGradleVersion(gradleVersion)
          .withArguments('bundle')
          .withPluginClasspath()
          .forwardOutput()
          .build()

      assertEquals(TaskOutcome.SUCCESS, result.task(':bundle').outcome)
      File zipFile = new File(testProjectDir, 'build/zips/test-project-1.0.0-bundle.zip')
      assertTrue(zipFile.exists(), "ZIP file should be created when running on Gradle ${gradleVersion}")
    } finally {
      AntBuilder ant = new AntBuilder()
      ant.delete dir: testProjectDir.parentFile
    }
  }
}
