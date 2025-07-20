package se.alipsa.gradle.plugin.release

import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.*
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

import java.time.Duration

import static org.junit.jupiter.api.Assertions.assertEquals

@Testcontainers
class NexusPublishTest {

  static String nexusUrl
  @Container
  static GenericContainer nexus = new GenericContainer<>("sonatype/nexus3:latest")
      .withExposedPorts(8081)
      .withReuse(false) // Optional: reuse container between tests
      .withStartupTimeout(Duration.ofMinutes(3))
      .waitingFor(Wait.forHttp("/service/rest/v1/status").forPort(8081).forStatusCode(200))

  @BeforeAll
  static void beforeAll() {
    nexusUrl = "http://${nexus.host}:${nexus.getMappedPort(8081)}"
    println "Nexus URL: $nexusUrl"
    // Optionally wait for readiness endpoint
  }

  @Test
  @Disabled
  void 'test nexus is accessible'() {
    def url = new URL("http://${nexus.host}:${nexus.getMappedPort(8081)}/service/rest/v1/status")
    def conn = url.openConnection()
    conn.connect()
    assert conn.responseCode == 200
  }

  @Test
  void testPublishToNexus() {
    String targetUrl = "http://${nexus.host}:${nexus.getMappedPort(8081)}/service/rest/v1/status"
    String buildScript = TestFixtures.createNexusBuildScript(targetUrl)
    File testProjectDir = TestFixtures.createTestProject(buildScript)

    // Run build
    BuildResult result = GradleRunner.create()
        .withProjectDir(testProjectDir)
        .withArguments("release")
        .withPluginClasspath()
        .forwardOutput()
        .build()

    assertEquals(TaskOutcome.SUCCESS, result.task(":release").getOutcome())
  }
}
