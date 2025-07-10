package se.alipsa.gradle.plugin.release

import groovy.ant.AntBuilder
import org.gradle.internal.impldep.org.bouncycastle.bcpg.*
import org.gradle.internal.impldep.org.bouncycastle.jce.provider.BouncyCastleProvider
import org.gradle.internal.impldep.org.bouncycastle.openpgp.*
import org.gradle.internal.impldep.org.bouncycastle.openpgp.operator.*
import org.gradle.internal.impldep.org.bouncycastle.openpgp.operator.jcajce.*
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Test

import java.security.*
import java.util.zip.ZipFile

import static org.junit.jupiter.api.Assertions.*

class ReleasePluginTest {

  @Test
  void zipIsCreatedAfterPublish() throws IOException {
    File tempDir = File.createTempDir('nexus-release-plugin')
    File testProjectDir = new File(tempDir, "test-project")
    println("Creating project in $testProjectDir")
    testProjectDir.mkdirs()
    // Write build.gradle
    def testKey = generateTestPrivateKey()
    File buildFile = new File(testProjectDir, "build.gradle")
    try (PrintWriter writer = new PrintWriter(new FileWriter(buildFile))) {
      writer.println("""\
      import java.security.MessageDigest
      plugins {
        id 'groovy'
        id 'maven-publish'
        id 'se.alipsa.nexus-release-plugin'
        id 'signing'
      }
      group = 'com.example'
      version = '1.0.0'
      ext.nexusReleaseUrl = 'https://central.sonatype.com/api/v1/publisher/upload'
      repositories { mavenCentral() }
      dependencies { implementation localGroovy() }

      tasks.register('javadocJar', Jar) {
        dependsOn groovydoc
        archiveClassifier.set 'javadoc'
        from groovydoc.destinationDir
      }
            
      tasks.register('sourcesJar', Jar) {
        dependsOn classes
        archiveClassifier.set 'sources'
        from sourceSets.main.allSource
      }

      publishing {
        publications {
          maven(MavenPublication) {
            from components.java
            artifact(javadocJar)
            artifact(sourcesJar)
          }
        }
      }
      signing {
        useInMemoryPgpKeys(null, '''$testKey''', '')
        sign publishing.publications.maven
      }
      
      nexusReleasePlugin {
        nexusUrl = nexusReleaseUrl
        userName = 'sonatypeUsername'
        password = 'sonatypePassword'
        mavenPublication = publishing.publications.maven
      }
      """.stripIndent())
    }
    File srcDir = new File(testProjectDir,"src/main/groovy")
    srcDir.mkdirs()

    File groovyFile = new File(srcDir, "Example.groovy")
    try (PrintWriter writer = new PrintWriter(groovyFile)) {
      writer.println("class Example { static void main(String[] args) { println 'Hello' } }")
    }

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
    ant.delete dir: tempDir
  }

  String generateTestPrivateKey(String identity = "test@example.com", char[] passphrase = []) {
    Security.addProvider(new BouncyCastleProvider())
    KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA", "BC")
    kpg.initialize(2048)
    KeyPair keyPair = kpg.generateKeyPair()

    PGPKeyPair pgpKeyPair = new JcaPGPKeyPair(PGPPublicKey.RSA_GENERAL, keyPair, new Date())
    PGPDigestCalculator sha1Calc = new JcaPGPDigestCalculatorProviderBuilder().build().get(HashAlgorithmTags.SHA1)
    PBESecretKeyEncryptor encryptor = new JcePBESecretKeyEncryptorBuilder(PGPEncryptedData.AES_256, sha1Calc)
        .setProvider("BC").build(passphrase)
    PGPSecretKey secretKey = new PGPSecretKey(
        PGPSignature.DEFAULT_CERTIFICATION,
        pgpKeyPair,
        identity,
        sha1Calc,
        null,
        null,
        new JcaPGPContentSignerBuilder(pgpKeyPair.publicKey.algorithm, HashAlgorithmTags.SHA256),
        encryptor
    )

    // Export as ASCII-armored string
    ByteArrayOutputStream out = new ByteArrayOutputStream()
    ArmoredOutputStream armoredOut = new ArmoredOutputStream(out)
    secretKey.encode(armoredOut)
    armoredOut.close()
    return out.toString("UTF-8")
  }
}