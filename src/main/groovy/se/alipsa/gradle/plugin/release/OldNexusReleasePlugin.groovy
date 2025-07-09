package se.alipsa.gradle.plugin.release;

import groovy.transform.CompileStatic
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.provider.Provider
import org.gradle.api.publish.maven.MavenPublication

/**
 * This plugin is an alternative to the nexus publish plugin which for some of
 * my more complex projects did not do what I wanted it to do.
 */
class OldNexusReleasePlugin implements Plugin<Project> {

  void apply(Project project) {
    def extension = project.extensions.create('nexusReleasePlugin', NexusReleasePluginExtension)
    project.pluginManager.apply('signing')
    project.pluginManager.apply('maven-publish')

    def releaseTask = project.tasks.register('release') {
      it.group = "publishing"
      it.description = "Create and upload a release bundle to Nexus"
    }

    project.afterEvaluate {

      if (project.version.endsWith("-SNAPSHOT")) {
        project.logger.quiet("Alipsa Nexus Release Plugin: A snapshot cannot be released, publish is enough (or maybe you forgot to change the version?")
        return
      }
      project.logger.lifecycle("Alipsa Nexus Release Plugin, releasing $project.group:$project.name:$project.version")

      def signTask = project.tasks.findByName('signMavenPublication')
      if (signTask != null) {
        releaseTask.configure { it.dependsOn(signTask) }
      } else {
        project.logger.warn("NexusReleasePlugin: signMavenPublication task not found, skipping dependency wiring")
      }

      releaseTask.configure {
        doLast {

          def pub = extension.mavenPublication instanceof Provider
              ? extension.mavenPublication.get()
              : extension.mavenPublication
          if (!(pub instanceof MavenPublication)) {
            throw new GradleException("Invalid publication configured in nexusReleasePlugin.mavenPublication")
          }
          def log = project.logger
          ReleaseClient releaseClient = new ReleaseClient(
              log,
              project,
              extension.nexusUrl.getOrNull(),
              extension.userName.getOrNull(),
              extension.password.getOrNull(),
              pub as MavenPublication
          )
          try {
            File bundle = releaseClient.createBundle()
            if (bundle == null || !bundle.exists()) {
              throw new GradleException("Failed to create release bundle for publication ${pub.name}")
            }
            log.lifecycle("Bundle created at ${bundle.absolutePath}")

            String deploymentId = releaseClient.upload(bundle)

            if (deploymentId == null || deploymentId.isBlank()) {
              throw new GradleException("Failed to find the staging profile id")
            } else {
              log.lifecycle "Project $project published with deploymentId = $deploymentId"
            }
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
          } catch (IOException e) {
            throw new GradleException("Failed to publish the project: $e.message", e)
          }
        }
      }
    }
  }
}
