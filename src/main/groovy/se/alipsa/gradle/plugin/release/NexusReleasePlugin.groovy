package se.alipsa.gradle.plugin.release

import org.gradle.api.*
import org.gradle.api.provider.Provider
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.tasks.TaskProvider

class NexusReleasePlugin implements Plugin<Project> {

  void apply(Project project) {
    def extension = project.extensions.create('nexusReleasePlugin', NexusReleasePluginExtension)
    project.pluginManager.apply('signing')
    project.pluginManager.apply('maven-publish')

    TaskProvider<Task> bundleTask = project.tasks.register('bundle') { task ->
      task.group = 'publishing'
      task.description = 'Create a release bundle that can be used to publish to Maven Central'
      dependsOn "signMavenPublication"

      task.doLast {
        def pub = getPublication(extension)
        def zipFile = getBundleFile(project, pub)

        def releaseClient = createClient(project, extension, pub)
        zipFile.parentFile.mkdirs()
        releaseClient.createBundle(zipFile)

        if (!zipFile.exists()) {
          throw new GradleException("Failed to create release bundle for publication ${pub.name}")
        }

        project.logger.lifecycle("Bundle created at ${zipFile.absolutePath}")
      }
    }

    TaskProvider<Task> releaseTask = project.tasks.register('release') { task ->
      task.group = 'publishing'
      task.description = 'Create and upload a release bundle to Nexus'

      // Make release depend on the bundle task
      task.dependsOn(bundleTask)

      task.doLast {
        String version = project.version.toString()
        if (version.endsWith("-SNAPSHOT")) {
          project.logger.quiet("Alipsa Nexus Release Plugin: A snapshot cannot be released, publish is enough (or maybe you forgot to change the version?)")
          return
        }

        def pub = getPublication(extension)
        def zipFile = getBundleFile(project, pub) // reuse same logic as bundle task
        if (!zipFile.exists()) {
          throw new GradleException("Expected bundle at ${zipFile.absolutePath} but it was not found.")
        }

        def releaseClient = createClient(project, extension, pub)

        String deploymentId = releaseClient.upload(zipFile)
        if (!deploymentId) {
          throw new GradleException("Failed to find the staging profile id")
        }

        project.logger.lifecycle("Project $project published with deploymentId = $deploymentId")

        String status = releaseClient.getStatus(deploymentId)
        while (!['PUBLISHED', 'FAILED'].contains(status)) {
          project.logger.lifecycle("Deploy status is $status")
          Thread.sleep(10000)
          status = releaseClient.getStatus(deploymentId)
        }

        if (status == 'PUBLISHED') {
          project.logger.lifecycle("Project $project published successfully!")
        } else {
          throw new GradleException("Failed to release $project with deploymentId $deploymentId")
        }
      }
    }

    project.gradle.taskGraph.whenReady { graph ->
      def signTask = project.tasks.findByName('signMavenPublication')
      if (signTask && graph.hasTask(releaseTask.get())) {
        releaseTask.configure { it.dependsOn(signTask) }
      }
    }
  }

  private static MavenPublication getPublication(NexusReleasePluginExtension extension) {
    def pub = extension.mavenPublication instanceof Provider
        ? extension.mavenPublication.orNull
        : extension.mavenPublication
    if (!(pub instanceof MavenPublication)) {
      throw new GradleException("Invalid publication configured in nexusReleasePlugin.mavenPublication")
    }
    return pub
  }

  private static File getBundleFile(Project project, MavenPublication pub) {
    File zipDir = project.layout.buildDirectory.dir("zips").get().asFile
    return new File(zipDir, "${pub.artifactId}-${pub.version}-bundle.zip")
  }

  private static ReleaseClient createClient(Project project, NexusReleasePluginExtension extension, MavenPublication pub) {
    return new ReleaseClient(
        project.logger,
        project,
        extension.nexusUrl.orNull,
        extension.userName.orNull,
        extension.password.orNull,
        pub
    )
  }
}
