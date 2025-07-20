package se.alipsa.gradle.plugin.release

import groovy.transform.CompileStatic
import org.gradle.api.*
import org.gradle.api.provider.Provider
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.tasks.TaskProvider

import java.security.DigestOutputStream
import java.security.MessageDigest

@CompileStatic
class CentralPublishing {
  static final List<String> CHECKSUM_ALGOS = ['MD5', 'SHA-1']
  NexusReleasePluginExtension extension

  CentralPublishing(NexusReleasePluginExtension extension) {
    this.extension = extension
  }

  void apply(Project project) {
    project.pluginManager.apply('signing')
    project.pluginManager.apply('maven-publish')

    TaskProvider<Task> bundleTask = project.tasks.register('bundle') { task ->
      task.group = 'publishing'
      task.description = 'Create a release bundle that can be used to publish to Maven Central'
      task.dependsOn "publishToMavenLocal"

      task.doLast {
        File buildDir = project.getLayout()?.buildDirectory?.asFile?.get()
        def pub = getPublication(extension)
        def zipFile = getBundleFile(buildDir, pub)

        def releaseClient = createClient(project, extension)
        zipFile.parentFile.mkdirs()

        // Add md5 and sha1 checksums to all artifacts
        project.logger.debug("Adding checksums to artifacts...")
        pub.artifacts.each { artifact ->
          CHECKSUM_ALGOS.each { algo ->
            generateChecksum(artifact.file, algo, project)
          }
        }

        File publicationDir = new File(buildDir,"publications/${pub.name}")
        File pomFile = new File(publicationDir, "pom-default.xml")
        if (!pomFile.exists()) {
          throw new GradleException("Expected POM file at ${pomFile.absolutePath} but it was not found. Has 'publishToMavenLocal' completed successfully?")
        }
        CHECKSUM_ALGOS.each { algo ->
          generateChecksum(pomFile, algo, project)
        }

        releaseClient.createBundle(zipFile)

        if (!zipFile.exists()) {
          throw new GradleException("Failed to create release bundle for publication ${pub.name}")
        }
      }
    }

    TaskProvider<Task> releaseTask = project.tasks.register('release') { task ->
      task.group = 'publishing'
      task.description = 'Create and upload a release bundle to Nexus'

      // Make release depend on the bundle task
      task.dependsOn(bundleTask)

      task.doLast {
        File buildDir = project.getLayout()?.buildDirectory?.asFile?.get()
        String version = project.version.toString()
        if (version.endsWith("-SNAPSHOT")) {
          project.logger.quiet("Alipsa Nexus Release Plugin: A snapshot cannot be released, publish is enough (or maybe you forgot to change the version?)")
          return
        }

        def pub = getPublication(extension)
        def zipFile = getBundleFile(buildDir, pub) // reuse same logic as bundle task
        if (!zipFile.exists()) {
          throw new GradleException("Expected bundle at ${zipFile.absolutePath} but it was not found.")
        }

        def releaseClient = createClient(project, extension)

        String deploymentId = releaseClient.upload(zipFile)
        if (!deploymentId) {
          throw new GradleException("Failed to find the staging profile id")
        }

        project.logger.lifecycle("Project $project published with deploymentId = $deploymentId, waiting 10s before checking...")
        Thread.sleep(10000)
        String status = releaseClient.getStatus(deploymentId)

        int retries = 10
        while (!['PUBLISHING', 'PUBLISHED', 'FAILED'].contains(status) && retries-- > 0) {
          project.logger.lifecycle("Deploy status is $status")
          status = releaseClient.getStatus(deploymentId)
          Thread.sleep(10000)
        }
        if (status == 'PUBLISHING') {
          project.logger.lifecycle("Project $project uploaded and validated successfully!")
          project.logger.lifecycle("It is currently publishing, see https://central.sonatype.com/publishing/deployments for details")
        } else if (status == 'PUBLISHED') {
          project.logger.lifecycle("Project $project published successfully!")
        } else {
          throw new GradleException("Failed to release $project with deploymentId $deploymentId")
        }
      }
    }

    // ✅ Expose tasks via the extension for external access
    extension.bundleTask = bundleTask
    extension.releaseTask = releaseTask
  }

  private static MavenPublication getPublication(NexusReleasePluginExtension extension) {
    def pub = extension.mavenPublication instanceof Provider
        ? extension.mavenPublication.orNull
        : extension.mavenPublication
    if (!(pub instanceof MavenPublication)) {
      throw new GradleException("Invalid publication configured in nexusReleasePlugin.mavenPublication")
    }
    return pub as MavenPublication
  }

  private static File getBundleFile(File buildDir, MavenPublication pub) {
    File zipDir = new File(buildDir, "zips")
    return new File(zipDir, "${pub.artifactId}-${pub.version}-bundle.zip")
  }

  private static CentralPortalClient createClient(Project project, NexusReleasePluginExtension extension) {
    new CentralPortalClient(project, extension)
  }

  static def generateChecksum(File file, String algo, Project project) {
    String extension = algo.toLowerCase().replace('-', '')
    def checksumFile = new File("${file.absolutePath}.${extension}")
    if (checksumFile.exists()) {
      project.logger.debug("${checksumFile.name} already exists")
      return
    }
    def digest = MessageDigest.getInstance(algo)
    //file.withInputStream { is -> digest.update(is.bytes) }
    // Less memory consuming:
    file.withInputStream { is ->
      new DigestOutputStream(OutputStream.nullOutputStream(), digest)
          .withCloseable { dos -> is.transferTo(dos) }
    }
    def hash = digest.digest().encodeHex().toString()
    checksumFile.text = hash
    project.logger.debug("Generated ${algo} for ${file.name} → ${checksumFile.name}")
  }
}
