package se.alipsa.groovy

import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project

import static se.alipsa.groovy.NexusClient.*

/**
 * This plugin is an alternative to the nexus publish plugin which for some of
 * my more complex projects did not do what I wanted it to do.
 */
class NexusReleasePlugin implements Plugin<Project> {

  void apply(Project project) {
    def extension = project.extensions.create('nexusReleasePlugin', NexusReleasePluginExtension)
    project.tasks.register('release') {
      //project.task('release') {
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

        if (profileId == null || profileId.isBlank()) {
          throw new GradleException("Failed to find the staging profile id")
        } else {
          println "NexusReleasePlugin found a published project for $project with profileId = $profileId"
        }
        String stagingRepoId = findStagingRepositoryId(
            profileId,
            extension.nexusUrl.getOrNull(),
            extension.userName.getOrNull(),
            extension.password.getOrNull()
        )
        // println "NexusReleasePlugin, stagingRepoId = $stagingRepoId"
        if (stagingRepoId == null || stagingRepoId.isBlank()) {
          throw new GradleException("Failed to find the staging repo id")
        }

        println "Closing staging repo id $stagingRepoId for project $project"
        Map<String, Object> closeResponse = closeStagingRepository(
            stagingRepoId,
            profileId,
            extension.nexusUrl.getOrNull(),
            extension.userName.getOrNull(),
            extension.password.getOrNull(),
            project
        )
        if (closeResponse[RESPONSE_CODE] >= 300) {
          println "Close request failed result = ${closeResponse[RESPONSE_CODE]}, body = ${closeResponse[BODY]}"
          throw new GradleException("Failed to close the staging repo $stagingRepoId")
        } else {
          println "$stagingRepoId closing request sent successfully, waiting 10s to allow cloing to finish"
          Thread.sleep(10000)
        }

        String status = 'open'
        int loopCount = 0
        while(loopCount < 10) {
          sleep(10000)
          status = getStagingRepositoryStatus(
              stagingRepoId,
              extension.nexusUrl.getOrNull(),
              extension.userName.getOrNull(),
              extension.password.getOrNull()
          )
          println "Status is $status"
          if ('closed' == status) {
            println "Closing operation completed!"
            break
          }
          loopCount++
          println("Waiting for close operation to finish, retry $loopCount, status is $status")
        }
        if ('closed' != status) {
          println "Failed to close staging repository $stagingRepoId, status is $status"
          throw new GradleException("Failed to close staging repository $stagingRepoId")
        }

        println "Promoting $stagingRepoId for project $project"
        Map<String, Object> promoteResponse = promoteStagingRepository(
            stagingRepoId,
            profileId,
            extension.nexusUrl.getOrNull(),
            extension.userName.getOrNull(),
            extension.password.getOrNull(),
            project
        )

        if (promoteResponse[RESPONSE_CODE] >= 300) {
          println "Promote request failed result = ${promoteResponse[RESPONSE_CODE]}, body = ${promoteResponse[BODY]}"
          throw new GradleException("Failed to promote the staging repo $stagingRepoId")
        } else {
          println "$stagingRepoId promote request sent successfully (${promoteResponse[RESPONSE_CODE]})"
        }
        println "Waiting 10 seconds..."
        Thread.sleep(10000)
        status = getStagingRepositoryStatus(
            stagingRepoId,
            extension.nexusUrl.getOrNull(),
            extension.userName.getOrNull(),
            extension.password.getOrNull()
        )
        println("Staging repository is now in status '$status'")

        if (status == 'closed') {
          println "Dropping repository $stagingRepoId"
          Map<String, Object> dropResponse = dropStagingRepository(
              stagingRepoId,
              profileId,
              extension.nexusUrl.getOrNull(),
              extension.userName.getOrNull(),
              extension.password.getOrNull(),
              project
          )

          if (dropResponse[RESPONSE_CODE] >= 300) {
            println "Drop request failed, result = ${dropResponse[RESPONSE_CODE]}, body = ${dropResponse[BODY]}"
            throw new GradleException("Failed to drop the staging repo $stagingRepoId after promotion")
          } else {
            println "$stagingRepoId dropped sucessfully"
          }
        } else {
          println("You need to drop $stagingRepoId manually as doing it directly would be too soon")
        }
      }
    }
  }
}
