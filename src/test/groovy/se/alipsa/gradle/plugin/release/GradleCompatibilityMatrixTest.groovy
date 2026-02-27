package se.alipsa.gradle.plugin.release

import groovy.ant.AntBuilder
import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertTrue

class GradleCompatibilityMatrixTest {

  @ParameterizedTest(name = "bundle works on Gradle {0}")
  @ValueSource(strings = ['8.13', '9.3.1'])
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
