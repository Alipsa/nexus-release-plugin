package se.alipsa.groovy

import org.gradle.api.Plugin
import org.gradle.api.Project
import static se.alipsa.groovy.NexusClient.*

// See https://docs.gradle.org/current/userguide/custom_plugins.html
class NexusReleasePlugin implements Plugin<Project> {

  void apply(Project project) {
    def extension = project.extensions.create('nexusReleasePlugin', NexusReleasePluginExtension)
    project.task('release') {
      dependsOn(project.tasks.named('publishMavenPublicationToMavenRepository'))
      if (project.version.endsWith("-SNAPSHOT")) {
        println("NexusReleasePlugin: A snapshot cannot be released, publish is enough (or maybe you forgot to change the version?")
        return
      }
      doLast {
        String profileId = findStagingProfileId(
            String.valueOf(project.group),
            extension.nexusUrl.getOrNull(),
            extension.userName.getOrNull(),
            extension.password.getOrNull()
        )
        println "Hello from the NexusReleasePlugin, profileId = $profileId"
        String stagingRepoId = findStagingRepositoryId(
            profileId,
            extension.nexusUrl.getOrNull(),
            extension.userName.getOrNull(),
            extension.password.getOrNull()
        )
        println "NexusReleasePlugin, stagingRepoId = $stagingRepoId"
        Map<String, Object> closeResponse = closeStagingRepository(
            stagingRepoId,
            profileId,
            extension.nexusUrl.getOrNull(),
            extension.userName.getOrNull(),
            extension.password.getOrNull(),
            project
        )
        println "Close request result = ${closeResponse[RESPONSE_CODE]}, body = ${closeResponse[BODY]}"

        Map<String, Object> promoteResponse = promoteStagingRepository(
            stagingRepoId,
            profileId,
            extension.nexusUrl.getOrNull(),
            extension.userName.getOrNull(),
            extension.password.getOrNull(),
            project
        )
        println "Promote request result = ${promoteResponse[RESPONSE_CODE]}, body = ${promoteResponse[BODY]}"

        Map<String, Object> dropResponse = dropStagingRepository(
            stagingRepoId,
            profileId,
            extension.nexusUrl.getOrNull(),
            extension.userName.getOrNull(),
            extension.password.getOrNull(),
            project
        )
        println "Drop request result = ${dropResponse[RESPONSE_CODE]}, body = ${dropResponse[BODY]}"
      }
    }
  }
}
