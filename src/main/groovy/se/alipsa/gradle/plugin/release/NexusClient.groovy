package se.alipsa.gradle.plugin.release

import groovy.json.JsonSlurper
import org.gradle.api.Project
import org.gradle.api.logging.Logger

import java.time.Instant

/**
 * This contains basic REST operations towards a Sonatype Nexus server
 * The REST documentation is here: https://oss.sonatype.org/nexus-staging-plugin/default/docs/rest.html
 */
class NexusClient extends WsClient {

  Logger log

  NexusClient(Logger log) {
    this.log = log
  }

  String findStagingProfileId(String groupName, String url, String userName, String password) {
    //println("Searching for a match for $groupName")
    if (url == null) {
      log.error("nexusUrl is not set cannot continue")
      return null
    }
    Map<String, Object> response = get("${baseUrl(url)}/service/local/staging/profiles", userName, password)

    String body = response[BODY]
    def stagingProfiles = new JsonSlurper().parseText(body)
    // First try exact match
    def profile = stagingProfiles.data.find { stagingProfile ->
      groupName == stagingProfile.name
    }
    // If that didnt work, look for something similar enough instead
    if (profile == null || profile == '') {
      Collection profiles = stagingProfiles.data.findAll { stagingProfile ->
        groupName.startsWith(stagingProfile.name)
      }
      profile = profiles[0]
      if (profiles.size() > 1) {
        // Not sure what to do here, maybe fail and require configuration to be set
        log.warn "multiple profiles matching $groupName found, picking $profile.name"
      }
    }
    if (profile == null) {
      log.error("Failed to find a matching profile")
    }
    return profile.id
  }

  String findStagingRepositoryId(String profileId, String url, String userName, String password) {
    //https://oss.sonatype.org/service/local/staging/profile_repositories
    Map<String, Object> response = get("${baseUrl(url)}/service/local/staging/profile_repositories", userName, password)
    String body = response[BODY]
    def stagingRepos = new JsonSlurper().parseText(body)

    def repo = null
    stagingRepos.data.each { stageRepo ->
      if (profileId == stageRepo.profileId) {
        if (repo == null && stageRepo.type == 'open') {
          repo = stageRepo
        }
        // For some reason, the shorthand instantA > instantB is not working properly in gradle
        if (repo != null && stageRepo.type == 'open' && Instant.parse(stageRepo.created).isAfter(Instant.parse(repo.created))) {
          repo = stageRepo
        }
      }
    }
    return repo?.repositoryId
  }

  String getStagingRepositoryStatus(String stagingRepositoryId, String url, String userName, String password) {
    //https://oss.sonatype.org/service/local/staging/profile_repositories
    Map<String, Object> response = get("${baseUrl(url)}/service/local/staging/repository/$stagingRepositoryId", userName, password)
    String body = response[BODY]
    def stagingRepo = new JsonSlurper().parseText(body)

    return stagingRepo?.type
  }

  Map<String, Object> closeStagingRepository(String stagingRepoId, String profileId, String publishUrl,
                                                    String userName, String password, Project project) {
    // /service/local/staging/profiles/<profile-id>/finish
    String url = "${baseUrl(publishUrl)}/service/local/staging/profiles/$profileId/finish"
    String payload = """{
      "data":{
        "stagedRepositoryId":"${stagingRepoId}",
        "description":"${project.group}:${project.name} closed by nexus release plugin"
      }
    }"""
    return post(url, payload.bytes, userName, password)
  }

  Map<String, Object> promoteStagingRepository(String stagingRepoId, String profileId, String publishUrl,
                                                      String userName, String password, Project project) {
    // /staging/bulk/promote
    String url = "${baseUrl(publishUrl)}/service/local/staging/profiles/$profileId/promote"
    String payload = """{
      "data":{
        "stagedRepositoryId":"${stagingRepoId}",
        "description":"${project.group}:${project.name} promoted by nexus release plugin"
      }
    }"""
    return post(url, payload.bytes, userName, password)
  }

  Map<String, Object> dropStagingRepository(String stagingRepoId, String profileId, String publishUrl,
                                                   String userName, String password, Project project) {
    // /staging/bulk/promote
    String url = "${baseUrl(publishUrl)}/service/local/staging/profiles/$profileId/drop"
    String payload = """{
      "data":{
        "stagedRepositoryId":"${stagingRepoId}",
        "description":"${project.group}:${project.name} dropped by nexus release plugin"
      }
    }"""
    return post(url, payload.bytes, userName, password)
  }

  String auth(String username, String password) {
    return "Basic " + Base64.getEncoder().encodeToString((username + ":" + password).getBytes())
  }

  static String baseUrl(String url) {
    URL publishUrl = new URL(url)
    String protocol = publishUrl.getProtocol()
    String host = publishUrl.getHost()
    String port = publishUrl.getPort() == -1 ? "" : ":${publishUrl.getPort()}"
    return "$protocol://$host$port"
  }
}
