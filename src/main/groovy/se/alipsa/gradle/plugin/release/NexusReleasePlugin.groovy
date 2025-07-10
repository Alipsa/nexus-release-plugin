package se.alipsa.gradle.plugin.release

import org.gradle.api.GradleException
import org.gradle.api.*
import org.gradle.api.provider.Provider
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.tasks.TaskProvider

/**
 * This plugin is an alternative to the nexus publish plugin which for some of
 * my more complex projects did not do what I wanted it to do.
 */
class NexusReleasePlugin implements Plugin<Project> {

  void apply(Project project) {
    def extension = project.extensions.create('nexusReleasePlugin', NexusReleasePluginExtension)
    project.pluginManager.apply('signing')
    project.pluginManager.apply('maven-publish')

    TaskProvider<Task> bundleTask = project.tasks.register('bundle') { task ->
      task.group = 'publishing'
      task.description = 'Create a release bundle that can be used to publish to Maven Central'

      task.doLast {
        def pub = extension.mavenPublication instanceof Provider
            ? extension.mavenPublication.orNull
            : extension.mavenPublication

        if (!(pub instanceof MavenPublication)) {
          println("mavenPublication = $pub (${pub?.class})")
          throw new GradleException("Invalid publication configured in nexusReleasePlugin.mavenPublication")
        }

        def log = project.logger
        def releaseClient = new ReleaseClient(
            log,
            project,
            extension.nexusUrl.orNull,
            extension.userName.orNull,
            extension.password.orNull,
            pub as MavenPublication
        )

        File zipDir = project.layout.buildDirectory.dir("zips").get().asFile
        if (!zipDir.exists()) {
          zipDir.mkdirs()
        }
        File bundle = new File(zipDir, "${pub.artifactId}-${pub.version}-bundle.zip")
        releaseClient.createBundle(bundle)
        if (!bundle?.exists()) {
          throw new GradleException("Failed to create release bundle for publication ${pub.name}")
        }
        log.lifecycle("Bundle created at ${bundle.absolutePath}")
      }
    }



    TaskProvider<Task> releaseTask = project.tasks.register('release') { task ->
      task.group = 'publishing'
      task.description = 'Create and upload a release bundle to Nexus'

      // defer execution to task action phase
      task.doLast {
        String version = project.version.toString()
        if (version.endsWith("-SNAPSHOT")) {
          project.logger.quiet("Alipsa Nexus Release Plugin: A snapshot cannot be released, publish is enough (or maybe you forgot to change the version?)")
          return
        }

        def pub = extension.mavenPublication instanceof Provider
            ? extension.mavenPublication.orNull
            : extension.mavenPublication

        if (!(pub instanceof MavenPublication)) {
          throw new GradleException("Invalid publication configured in nexusReleasePlugin.mavenPublication")
        }

        def log = project.logger
        def releaseClient = new ReleaseClient(
            log,
            project,
            extension.nexusUrl.orNull,
            extension.userName.orNull,
            extension.password.orNull,
            pub as MavenPublication
        )

        File zipDir = project.layout.buildDirectory.dir("zips").get().asFile
        File bundle = new File(zipDir, "${pub.artifactId}-${pub.version}-bundle.zip")
        releaseClient.createBundle(bundle)
        if (!bundle?.exists()) {
          throw new GradleException("Failed to create release bundle for publication ${pub.name}")
        }
        log.lifecycle("Bundle created at ${bundle.absolutePath}")

        String deploymentId = releaseClient.upload(bundle)
        if (!deploymentId) {
          throw new GradleException("Failed to find the staging profile id")
        }

        log.lifecycle("Project $project published with deploymentId = $deploymentId")

        String status = releaseClient.getStatus(deploymentId)
        while (!['PUBLISHED', 'FAILED'].contains(status)) {
          log.lifecycle("Deploy status is $status")
          Thread.sleep(10000)
          status = releaseClient.getStatus(deploymentId)
        }

        if (status == 'PUBLISHED') {
          log.lifecycle("Project $project published successfully!")
        } else {
          throw new GradleException("Failed to release $project with deploymentId $deploymentId")
        }
      }
    }

    // Wire in signing task as dependency if it exists — after all tasks are known
    project.gradle.taskGraph.whenReady { graph ->
      def signTask = project.tasks.findByName('signMavenPublication')
      if (signTask && graph.hasTask(releaseTask.get())) {
        releaseTask.configure { it.dependsOn(signTask) }
      }
    }
  }
}