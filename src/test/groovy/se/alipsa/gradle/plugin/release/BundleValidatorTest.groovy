package se.alipsa.gradle.plugin.release

import org.junit.jupiter.api.Test

import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

import static org.junit.jupiter.api.Assertions.assertTrue
import static org.junit.jupiter.api.Assertions.assertEquals

class BundleValidatorTest {

  @Test
  void validBundleHasNoErrors() {
    File zipFile = createBundleZip('com.example', 'demo', '1.0.0', validPom(), [
        'demo-1.0.0.jar',
        'demo-1.0.0.jar.asc',
        'demo-1.0.0-sources.jar',
        'demo-1.0.0-sources.jar.asc',
        'demo-1.0.0-javadoc.jar',
        'demo-1.0.0-javadoc.jar.asc',
        'demo-1.0.0.pom.asc'
    ])

    List<String> errors = BundleValidator.validateBundle(zipFile, 'com.example', 'demo', '1.0.0', 'user', 'pass')

    assertTrue(errors.isEmpty(), "Expected no validation errors but got: ${errors}")
  }

  @Test
  void missingCredentialsIsReported() {
    File zipFile = createBundleZip('com.example', 'demo', '1.0.0', validPom(), [
        'demo-1.0.0.jar',
        'demo-1.0.0.jar.asc',
        'demo-1.0.0-sources.jar',
        'demo-1.0.0-sources.jar.asc',
        'demo-1.0.0-javadoc.jar',
        'demo-1.0.0-javadoc.jar.asc',
        'demo-1.0.0.pom.asc'
    ])

    List<String> errors = BundleValidator.validateBundle(zipFile, 'com.example', 'demo', '1.0.0', '', '')

    assertTrue(errors.any { it.contains('Credentials not configured') })
  }

  @Test
  void missingRequiredEntriesAreReported() {
    File zipFile = createBundleZip('com.example', 'demo', '1.0.0', validPom(), [
        'demo-1.0.0.jar',
        'demo-1.0.0.pom.asc'
    ])

    List<String> errors = BundleValidator.validateBundle(zipFile, 'com.example', 'demo', '1.0.0', 'user', 'pass')

    assertTrue(errors.any { it.contains('Missing required bundle entry') && it.contains('demo-1.0.0-sources.jar') })
    assertTrue(errors.any { it.contains('Missing required bundle entry') && it.contains('demo-1.0.0-javadoc.jar') })
  }

  @Test
  void filenameMismatchIsReported() {
    File zipFile = createBundleZip('com.example', 'demo', '1.0.0', validPom(), [
        'wrong-1.0.0.jar',
        'wrong-1.0.0.jar.asc',
        'wrong-1.0.0-sources.jar',
        'wrong-1.0.0-sources.jar.asc',
        'wrong-1.0.0-javadoc.jar',
        'wrong-1.0.0-javadoc.jar.asc',
        'demo-1.0.0.pom.asc'
    ])

    List<String> errors = BundleValidator.validateBundle(zipFile, 'com.example', 'demo', '1.0.0', 'user', 'pass')

    assertTrue(errors.any { it.contains("Filename mismatch: 'wrong-1.0.0.jar'") })
  }

  @Test
  void pomValidationErrorsAccumulate() {
    String invalidPom = '''
      <project>
        <modelVersion>4.0.0</modelVersion>
        <groupId>com.example</groupId>
        <artifactId>demo</artifactId>
        <version>1.0.0</version>
      </project>
    '''.stripIndent()

    File zipFile = createBundleZip('com.example', 'demo', '1.0.0', invalidPom, [
        'demo-1.0.0.jar',
        'demo-1.0.0.jar.asc',
        'demo-1.0.0-sources.jar',
        'demo-1.0.0-sources.jar.asc',
        'demo-1.0.0-javadoc.jar',
        'demo-1.0.0-javadoc.jar.asc',
        'demo-1.0.0.pom.asc'
    ])

    List<String> errors = BundleValidator.validateBundle(zipFile, 'com.example', 'demo', '1.0.0', 'user', 'pass')

    assertTrue(errors.any { it.contains('POM is missing required field: <name>') })
    assertTrue(errors.any { it.contains('POM is missing required field: <description>') })
    assertTrue(errors.any { it.contains('POM is missing required field: <licenses>') })
    assertTrue(errors.any { it.contains('POM is missing required field: <developers>') })
    assertTrue(errors.any { it.contains('POM <scm> must contain both <url> and <connection>') })
    assertTrue(errors.size() >= 5)
  }

  private static File createBundleZip(String groupId, String artifactId, String version, String pomText, List<String> fileNames) {
    File dir = File.createTempDir('bundle-validator-test', '')
    File zipFile = new File(dir, 'bundle.zip')
    String basePath = "${groupId.replace('.', '/')}/${artifactId}/${version}/"

    try (ZipOutputStream zipOut = new ZipOutputStream(new FileOutputStream(zipFile))) {
      zipOut.putNextEntry(new ZipEntry("${basePath}${artifactId}-${version}.pom"))
      zipOut.write(pomText.getBytes('UTF-8'))
      zipOut.closeEntry()

      fileNames.each { String fileName ->
        zipOut.putNextEntry(new ZipEntry(basePath + fileName))
        zipOut.write('x'.bytes)
        zipOut.closeEntry()
      }
    }
    return zipFile
  }

  private static String validPom() {
    return '''
      <project>
        <modelVersion>4.0.0</modelVersion>
        <groupId>com.example</groupId>
        <artifactId>demo</artifactId>
        <version>1.0.0</version>
        <name>Demo</name>
        <description>Demo artifact</description>
        <url>https://example.com/demo</url>
        <licenses>
          <license>
            <name>MIT</name>
            <url>https://opensource.org/licenses/MIT</url>
          </license>
        </licenses>
        <developers>
          <developer>
            <id>demo</id>
            <name>Demo User</name>
          </developer>
        </developers>
        <scm>
          <url>https://example.com/demo</url>
          <connection>scm:git:https://example.com/demo.git</connection>
        </scm>
      </project>
    '''.stripIndent()
  }
}
