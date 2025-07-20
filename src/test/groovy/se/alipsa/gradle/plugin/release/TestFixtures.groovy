package se.alipsa.gradle.plugin.release

import org.gradle.internal.impldep.org.bouncycastle.bcpg.ArmoredOutputStream
import org.gradle.internal.impldep.org.bouncycastle.bcpg.HashAlgorithmTags
import org.gradle.internal.impldep.org.bouncycastle.jce.provider.BouncyCastleProvider
import org.gradle.internal.impldep.org.bouncycastle.openpgp.PGPEncryptedData
import org.gradle.internal.impldep.org.bouncycastle.openpgp.PGPKeyPair
import org.gradle.internal.impldep.org.bouncycastle.openpgp.PGPPublicKey
import org.gradle.internal.impldep.org.bouncycastle.openpgp.PGPSecretKey
import org.gradle.internal.impldep.org.bouncycastle.openpgp.PGPSignature
import org.gradle.internal.impldep.org.bouncycastle.openpgp.operator.PBESecretKeyEncryptor
import org.gradle.internal.impldep.org.bouncycastle.openpgp.operator.PGPDigestCalculator
import org.gradle.internal.impldep.org.bouncycastle.openpgp.operator.jcajce.JcaPGPContentSignerBuilder
import org.gradle.internal.impldep.org.bouncycastle.openpgp.operator.jcajce.JcaPGPDigestCalculatorProviderBuilder
import org.gradle.internal.impldep.org.bouncycastle.openpgp.operator.jcajce.JcaPGPKeyPair
import org.gradle.internal.impldep.org.bouncycastle.openpgp.operator.jcajce.JcePBESecretKeyEncryptorBuilder

import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Security

class TestFixtures {

  static String createBuildScript(String nexusReleaseUrl, String identity = "test@example.com", char[] passphrase = []) {
    buildScript(generateTestPrivateKey(identity, passphrase), nexusReleaseUrl, identity, new String(passphrase))
  }

  static String createBuildScript() {
    String identity = "test@example.com"
    char[] passphrase = []
    buildScript(generateTestPrivateKey(identity, passphrase))
  }

  static String buildScript(String testKey,
                            String nexusReleaseUrl='https://central.sonatype.com/api/v1/publisher/upload',
                            String userName = "sonaTypeUserName",
                            String password = "sonaTypePassword") {
      """
      plugins {
        id 'groovy'
        id 'maven-publish'
        id 'se.alipsa.nexus-release-plugin'
        id 'signing'
      }
      group = 'com.example'
      version = '1.0.0'
      ext.nexusReleaseUrl = '${nexusReleaseUrl}'
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
        nexusUrl = nexusReleaseUrl // Optional, will default to the "standard" sonatype cetral publishing url
        userName = '${userName}'
        password = '${password}'
        mavenPublication = publishing.publications.maven
      }
      """.stripIndent()
  }

  static String generateTestPrivateKey(String identity = "test@example.com", char[] passphrase = []) {
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

  static File createTestProject() {
    createTestProject(createBuildScript())
  }

  static File createTestProject(String buildScript) {
    File tempDir = File.createTempDir('nexus-release-plugin')
    File testProjectDir = new File(tempDir, "test-project")
    println("Creating project in $testProjectDir")
    testProjectDir.mkdirs()
    // Write build.gradle

    File buildFile = new File(testProjectDir, "build.gradle")
    try (PrintWriter writer = new PrintWriter(new FileWriter(buildFile))) {
      writer.println(buildScript)
    }
    File srcDir = new File(testProjectDir,"src/main/groovy/example")
    srcDir.mkdirs()

    File groovyFile = new File(srcDir, "Example.groovy")
    try (PrintWriter writer = new PrintWriter(groovyFile)) {
      writer.println("class Example { static void main(String[] args) { println 'Hello' } }")
    }
    testProjectDir
  }
}
