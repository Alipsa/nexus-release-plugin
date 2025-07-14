package se.alipsa.gradle.plugin.release

import org.gradle.api.logging.Logger

class CentralPortalClient extends WsClient {

  private static final String CENTRAL_PORTAL_URL = 'https://central.sonatype.com/api/v1'
  String publishUrl
  Logger log

  CentralPortalClient(Logger log, String url = CENTRAL_PORTAL_URL) {
    this.log = log
    publishUrl = url
  }

  Map<String, Object> get(String endpoint, String username, String password) throws IOException {
    String urlString = "${publishUrl}/${endpoint}"
    return super.get(urlString, username, password)
  }

  Map<String, Object> postMultipart(String endpoint, File payload, String username, String password) throws IOException {
    String urlString = "${publishUrl}/${endpoint}"
    log.lifecycle("Post multipart to $urlString")
    return super.postMultipart(urlString, payload, username, password)
  }

  Map<String, Object> post(String endpoint, byte[] payload, String username, String password, String contentTye = 'application/x-www-form-urlencoded') throws IOException {
    String urlString = "${publishUrl}/${endpoint}"
    log.lifecycle("Post to $urlString")
    return super.post(urlString, payload, username, password)
  }

  String auth(String username, String password) {
    return "Bearer " + Base64.getEncoder().encodeToString((username + ":" + password).getBytes())
  }
}
