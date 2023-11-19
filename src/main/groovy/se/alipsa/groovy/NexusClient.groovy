package se.alipsa.groovy

import groovy.json.JsonSlurper
import org.gradle.api.Project

import java.time.Instant

class NexusClient {
  // REST documentation is here: https://oss.sonatype.org/nexus-staging-plugin/default/docs/rest.html
  static final String APPLICATION_JSON = 'application/json'
  //static final String APPLICATION_XML = 'application/xml'
  static final String BODY = 'body'
  static final String RESPONSE_CODE = 'responseCode'
  static final String HEADERS = 'headers'

  static String findStagingProfileId(String groupName, String url, String userName, String password) {
    //println("Searching for a match for $groupName")
    if (url == null) {
      println("nexusUrl is not set cannot continue")
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
        println "multiple profiles matching $groupName found, picking $profile.name"
      }
    }
    if (profile == null) {
      System.err.println("Failed to find a matching profile")
    }
    return profile.id
  }

  static String findStagingRepositoryId(String profileId, String url, String userName, String password) {
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
        if (stageRepo.type == 'open' && Instant.parse(repo.created) > Instant.parse(stageRepo.created)) {
          repo = stageRepo
        }
      }
    }
    return repo?.repositoryId
  }

  static Map<String, Object> closeStagingRepository(String stagingRepoId, String profileId, String publishUrl,
                                                    String userName, String password, Project project) {
    // /service/local/staging/profiles/<profile-id>/finish
    String url = "${baseUrl(publishUrl)}/service/local/staging/profiles/$profileId/finish"
    String payload = """<promoteRequest>
      <data>
          <stagedRepositoryId>${stagingRepoId}</stagedRepositoryId>
          <description>${project.group}:${project.name} closed by nexus release plugin</description>
      </data>
    </promoteRequest>"""
    payload = """{
      "data":{
        "stagedRepositoryId":"${stagingRepoId}",
        "description":"${project.group}:${project.name} closed by nexus release plugin"
      }
    }"""
    return post(url, payload, userName, password)
  }

  static Map<String, Object> promoteStagingRepository(String stagingRepoId, String profileId, String publishUrl,
                                                      String userName, String password, Project project) {
    // /staging/bulk/promote
    String url = "${baseUrl(publishUrl)}/service/local/staging/profiles/$profileId/promote"
    String payload = """{
      "data":{
        "stagedRepositoryId":"${stagingRepoId}",
        "description":"${project.group}:${project.name} promoted by nexus release plugin"
      }
    }"""
    return post(url, payload, userName, password)
  }

  static Map<String, Object> dropStagingRepository(String stagingRepoId, String profileId, String publishUrl,
                                                   String userName, String password, Project project) {
    // /staging/bulk/promote
    String url = "${baseUrl(publishUrl)}/service/local/staging/profiles/$profileId/drop"
    String payload = """{
      "data":{
        "stagedRepositoryId":"${stagingRepoId}",
        "description":"${project.group}:${project.name} dropped by nexus release plugin"
      }
    }"""
    return post(url, payload, userName, password)
  }

  static Map<String, Object> get(String urlString, String username, String password) {
    StringBuilder writer = new StringBuilder()
    URL url = new URL(urlString)
    HttpURLConnection conn = (HttpURLConnection) url.openConnection()
    conn.setRequestMethod("GET")
    conn.setRequestProperty("Accept", APPLICATION_JSON)
    conn.setRequestProperty("Authorization", basicAuth(username, password))
    conn.connect()
    int responseCode = conn.getResponseCode();
    var responseHeaders = conn.getHeaderFields();
    BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()));
    String line;
    while ((line = br.readLine()) != null) {
      writer.append(line).append('\n');
    }
    conn.disconnect();
    return [(BODY): writer.toString(), (RESPONSE_CODE): responseCode, (HEADERS): responseHeaders]
  }

  static Map<String, Object> post(String urlString, String payload, String username, String password) {
    StringBuilder writer = new StringBuilder()
    URL url = new URL(urlString)
    HttpURLConnection conn = (HttpURLConnection) url.openConnection()
    conn.setRequestMethod('POST')
    conn.setRequestProperty('Content-Type', APPLICATION_JSON)
    conn.setRequestProperty("Accept", APPLICATION_JSON)
    conn.setRequestProperty("Authorization", basicAuth(username, password))
    conn.setDoOutput(true)
    conn.connect()
    OutputStream os = conn.getOutputStream();
    os.write(payload.getBytes())
    os.flush()
    os.close()
    int responseCode = conn.getResponseCode()
    var responseHeaders = conn.getHeaderFields()
    InputStream is = null
    try {
      is = conn.getInputStream()
    } catch (IOException e) {
      // no content
    }
    if (is != null) {
      BufferedReader br = new BufferedReader(new InputStreamReader(is))

      String line
      while ((line = br.readLine()) != null) {
        writer.append(line).append('\n')
      }
      is.close()
    }
    conn.disconnect()
    return [(BODY): writer.toString(), (RESPONSE_CODE): responseCode, (HEADERS): responseHeaders]
  }

  static String basicAuth(String username, String password) {
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
