package se.alipsa.gradle.plugin.release

import org.junit.jupiter.api.Test

import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

import static org.junit.jupiter.api.Assertions.assertTrue
import static org.junit.jupiter.api.Assertions.assertEquals

class BundleValidatorTest {

  @Test
  void pomMetadataMarkerRequiresModuleAndSignature() {
    List<String> artifacts = [
        'demo-1.0.0.jar', 'demo-1.0.0.jar.asc',
        'demo-1.0.0-sources.jar', 'demo-1.0.0-sources.jar.asc',
        'demo-1.0.0-javadoc.jar', 'demo-1.0.0-javadoc.jar.asc',
        'demo-1.0.0.pom.asc'
    ]
    String pom = validPom().replace('<modelVersion>', '<!-- do_not_remove: published-with-gradle-metadata -->\n<modelVersion>')
    [[], ['demo-1.0.0.module'], ['demo-1.0.0.module', 'demo-1.0.0.module.asc']].eachWithIndex { List<String> metadata, int index ->
      File zip = createBundleZip('com.example', 'demo', '1.0.0', pom, artifacts + metadata)
      try {
        List<String> errors = BundleValidator.validateBundle(zip, 'com.example', 'demo', '1.0.0', 'user', 'pass')
        assertEquals(2 - index, errors.size(), "Unexpected validation errors: ${errors}")
        if (index == 0) assertTrue(errors.any { it.endsWith('/demo-1.0.0.module') })
        if (index == 0) assertTrue(errors.any { it.endsWith('/demo-1.0.0.module.asc') })
        if (index == 1) assertTrue(errors.any { it.startsWith('Missing signature') && it.endsWith('/demo-1.0.0.module') })
      } finally {
        zip.parentFile.deleteDir()
      }
    }
  }

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

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = ['war', 'pom'])
  void unsupportedPackagingReportsSingleCause(String packaging) {
    File zip = createBundleZip('com.example', 'demo', '1.0.0', validPom().replace('<modelVersion>', "<packaging>${packaging}</packaging><modelVersion>"), [])
    try {
      def errors = BundleValidator.validateBundle(zip, 'com.example', 'demo', '1.0.0', 'user', 'pass')
      assertEquals(1, errors.size())
      assertTrue(errors[0].contains("Packaging '${packaging}' is not supported"))
      if (packaging == 'pom') assertTrue(errors[0].contains('releaseAll'))
    } finally { zip.parentFile.deleteDir() }
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = ['jar', 'pom', 'module', 'zip'])
  void everyPrimaryEntryNeedsNonemptySignatureAndChecksums(String extension) {
    String name = "demo-1.0.0.${extension}"
    File zip = createBundleZip('com.example', 'demo', '1.0.0', validPom(), extension == 'pom' ? [] : [name])
    try {
      def errors = BundleValidator.validateBundle(zip, 'com.example', 'demo', '1.0.0', 'user', 'pass')
      assertTrue(errors.any { it == "Missing signature for bundle entry: com/example/demo/1.0.0/${name}" })
      rewriteZip(zip, name + '.asc', new byte[0])
      errors = BundleValidator.validateBundle(zip, 'com.example', 'demo', '1.0.0', 'user', 'pass')
      assertTrue(errors.any { it == "Empty signature for bundle entry: com/example/demo/1.0.0/${name}" })
      rewriteZip(zip, name + '.md5', null)
      rewriteZip(zip, name + '.sha1', null)
      errors = BundleValidator.validateBundle(zip, 'com.example', 'demo', '1.0.0', 'user', 'pass')
      assertEquals(2, errors.count { it == "Missing checksum for bundle entry: com/example/demo/1.0.0/${name}" })
    } finally { zip.parentFile.deleteDir() }
  }

  static void rewriteZip(File zip, String filename, byte[] replacement) {
    Map<String, byte[]> entries = [:]
    new java.util.zip.ZipFile(zip).withCloseable { archive ->
      archive.entries().each { entries[it.name] = archive.getInputStream(it).bytes }
    }
    String path = 'com/example/demo/1.0.0/' + filename
    if (replacement == null) entries.remove(path)
    else entries[path] = replacement
    new ZipOutputStream(new FileOutputStream(zip)).withCloseable { output ->
      entries.each { name, bytes -> output.putNextEntry(new ZipEntry(name)); output.write(bytes); output.closeEntry() }
    }
  }

  private static File createBundleZip(String groupId, String artifactId, String version, String pomText, List<String> fileNames) {
    File dir = File.createTempDir('bundle-validator-test', '')
    File zipFile = new File(dir, 'bundle.zip')
    String basePath = "${groupId.replace('.', '/')}/${artifactId}/${version}/"

    try (ZipOutputStream zipOut = new ZipOutputStream(new FileOutputStream(zipFile))) {
      zipOut.putNextEntry(new ZipEntry("${basePath}${artifactId}-${version}.pom"))
      zipOut.write(pomText.getBytes('UTF-8'))
      zipOut.closeEntry()

      List<String> primary = ["${artifactId}-${version}.pom".toString()] + fileNames.findAll { !it.endsWith('.asc') }
      primary.each { String name ->
        ['md5', 'sha1'].each { String suffix ->
          zipOut.putNextEntry(new ZipEntry(basePath + name + '.' + suffix))
          zipOut.write('checksum'.bytes)
          zipOut.closeEntry()
        }
      }
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
