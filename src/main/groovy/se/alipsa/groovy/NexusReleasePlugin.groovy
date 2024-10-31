package se.alipsa.groovy

import static se.alipsa.groovy.NexusClient.*

import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * This plugin is an alternative to the nexus publish plugin which for some of
 * my more complex projects did not do what I wanted it to do.
 */
class NexusReleasePlugin implements Plugin<Project> {

  void apply(Project project) {
    def extension = project.extensions.create('nexusReleasePlugin', NexusReleasePluginExtension)
    project.tasks.register('release') {
      project.logger.info("Alipsa Nexus Release Plugin, releasing $project.group:$project.name:$project.version")
      dependsOn(project.tasks.named('publishMavenPublicationToMavenRepository'))
      def log = project.logger
      ReleaseClient releaseClient = new ReleaseClient(
          log,
          project,
          extension.nexusUrl.getOrNull(),
          extension.userName.getOrNull(),
          extension.password.getOrNull(),
      )
      if (project.version.endsWith("-SNAPSHOT")) {
        log.quiet("NexusReleasePlugin: A snapshot cannot be released, publish is enough (or maybe you forgot to change the version?")
        return
      }
      doLast {

        String profileId = releaseClient.findStagingProfileId()

        if (profileId == null || profileId.isBlank()) {
          throw new GradleException("Failed to find the staging profile id")
        } else {
          log.lifecycle "NexusReleasePlugin found a published project for $project with profileId = $profileId"
        }
        String stagingRepoId = releaseClient.findStagingRepositoryId(profileId)
        // println "NexusReleasePlugin, stagingRepoId = $stagingRepoId"
        if (stagingRepoId == null || stagingRepoId.isBlank()) {
          throw new GradleException("Failed to find the staging repo id")
        }

        log.lifecycle("Closing staging repo id $stagingRepoId for project $project")
        Map<String, Object> closeResponse = releaseClient.closeStagingRepository(
            stagingRepoId,
            profileId
        )
        Number closeResponseCode = closeResponse[RESPONSE_CODE] as Number
        if (closeResponseCode >= 300) {
          log.error "Close request failed result = $closeResponseCode, body = ${closeResponse[BODY]}"
          throw new GradleException("Failed to close the staging repo $stagingRepoId")
        } else {
          log.lifecycle "$stagingRepoId closing request sent successfully with response code $closeResponseCode, waiting 15s to allow closing to finish"
          Thread.sleep(15000)
        }

        String status = 'open'
        int loopCount = 0
        while(loopCount < 20) {
          sleep(15000)
          status = releaseClient.getStagingRepositoryStatus(stagingRepoId)
          //log.lifecycle"Status is $status"
          if ('closed' == status) {
            log.lifecycle "Closing operation completed!"
            break
          }
          loopCount++
          log.lifecycle("Waiting for close operation to finish, retry $loopCount, status is $status")
        }
        if ('closed' != status) {
          log.error "Failed to close staging repository $stagingRepoId, status is $status"
          throw new GradleException("Failed to close staging repository $stagingRepoId")
        }
        log.lifecycle("Waiting 15s before attempting to promote...")
        Thread.sleep(15000)
        log.lifecycle "Promoting $stagingRepoId for project $project"
        Map<String, Object> promoteResponse = releaseClient.promoteStagingRepository(
            stagingRepoId,
            profileId
        )

        if (promoteResponse[RESPONSE_CODE] >= 300) {
          log.error "Promote request failed result = ${promoteResponse[RESPONSE_CODE]}, body = ${promoteResponse[BODY]}"
          throw new GradleException("Failed to promote the staging repo $stagingRepoId")
        } else {
          log.lifecycle "$stagingRepoId promote request sent successfully (${promoteResponse[RESPONSE_CODE]})"
        }

        log.lifecycle"Waiting 20 seconds..."
        Thread.sleep(20000)
        loopCount = 0
        while(loopCount < 10) {

          status = releaseClient.getStagingRepositoryStatus(stagingRepoId)
          //log.lifecycle"Status is $status"
          if ('closed' == status) {
            log.lifecycle "Closing operation completed!"
            break
          }
          loopCount++
          sleep(15000)
          log.lifecycle("Waiting for close operation to finish, retry $loopCount, status is $status")
        }

        log.lifecycle("Staging repository is now in status '$status'")

        if (status == 'closed') {
          log.lifecycle "Dropping repository $stagingRepoId"
          Map<String, Object> dropResponse = releaseClient.dropStagingRepository(
              stagingRepoId,
              profileId
          )

          if (dropResponse[RESPONSE_CODE] >= 300) {
            log.error "Drop request failed, result = ${dropResponse[RESPONSE_CODE]}, body = ${dropResponse[BODY]}"
            throw new GradleException("Failed to drop the staging repo $stagingRepoId after promotion")
          } else {
            log.lifecycle "$stagingRepoId dropped sucessfully"
          }
        } else {
          log.warn("You need to drop $stagingRepoId manually as doing it directly would be too soon")
        }
      }
    }
  }
}
