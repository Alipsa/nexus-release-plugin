package se.alipsa.groovy

import org.gradle.api.Project
import org.gradle.api.logging.Logger

class ReleaseClient {

  NexusClient nexusClient
  Project project
  String projectGroup
  String nexusUrl
  String userName
  String password

  ReleaseClient(Logger log, Project project, String nexusUrl, String userName, String password) {
    nexusClient = new NexusClient(log)
    this.project = project
    this.projectGroup = String.valueOf(project.group)
    this.nexusUrl = nexusUrl
    this.userName = userName
    this.password = password
  }

  String findStagingProfileId() {
    nexusClient.findStagingProfileId(projectGroup, nexusUrl, userName, password)
  }

  String findStagingRepositoryId(String profileId) {
    nexusClient.findStagingRepositoryId(profileId, nexusUrl, userName, password)
  }

  Map<String, Object> closeStagingRepository(String stagingRepoId, String profileId) {
    nexusClient.closeStagingRepository(
        stagingRepoId,
        profileId,
        nexusUrl,
        userName,
        password,
        project
    )
  }

  String getStagingRepositoryStatus(String stagingRepoId) {
    nexusClient.getStagingRepositoryStatus(
        stagingRepoId,
        nexusUrl,
        userName,
        password
    )
  }

  Map<String, Object> promoteStagingRepository(String stagingRepoId, String profileId) {
    nexusClient.promoteStagingRepository(
        stagingRepoId,
        profileId,
        nexusUrl,
        userName,
        password,
        project
    )
  }

  Map<String, Object> dropStagingRepository(String stagingRepoId, String profileId) {
    nexusClient.dropStagingRepository(
        stagingRepoId,
        profileId,
        nexusUrl,
        userName,
        password,
        project
    )
  }
}
