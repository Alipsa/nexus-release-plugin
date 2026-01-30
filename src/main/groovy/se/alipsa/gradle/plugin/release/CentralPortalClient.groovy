package se.alipsa.gradle.plugin.release

import groovy.json.JsonSlurper
import groovy.transform.CompileStatic
import org.gradle.api.logging.Logger

/**
 * HTTP client for interacting with the Central Portal API.
 * This class is configuration cache compatible - it only stores serializable values.
 */
@CompileStatic
class CentralPortalClient extends WsClient {

    static final String CENTRAL_PORTAL_URL = 'https://central.sonatype.com/api/v1'
    String publishUrl
    Logger log
    String userName
    String password

    CentralPortalClient(Logger logger, String nexusUrl, String userName, String password) {
        this.log = logger
        this.publishUrl = nexusUrl ?: CENTRAL_PORTAL_URL
        this.userName = userName
        this.password = password
    }

    Map<String, Object> get(String endpoint, String username, String password) throws IOException {
        String urlString = "${publishUrl}/${endpoint}"
        return super.get(urlString, username, password)
    }

    String auth(String username, String password) {
        return "Bearer " + Base64.getEncoder().encodeToString((username + ":" + password).getBytes())
    }

    /**
     * Uploads the given file to Central portal.
     * $ curl --request POST \
     * --verbose \
     * --header 'Authorization: Bearer ZXhhbXBsZV91c2VybmFtZTpleGFtcGxlX3Bhc3N3b3Jk' \
     * --form bundle=@central-bundle.zip \
     * https://central.sonatype.com/api/v1/publisher/upload?publishingType=AUTOMATIC
     *
     * @param file The file to upload.
     * @return The deployment ID or null if the upload failed.
     */
    String upload(File file) {
        String urlString = "${publishUrl}/publisher/upload?publishingType=AUTOMATIC"
        log.debug("Post multipart to $urlString")
        Map<String, Object> result = postMultipart(urlString, file, userName, password)
        log.debug("Upload result: $result")
        return result.get(BODY)
    }

    /**
     * Gets the status of a deployment by its ID.
     * $ curl --request POST \
     * --verbose \
     * --header 'Authorization: Bearer ZXhhbXBsZV91c2VybmFtZTpleGFtcGxlX3Bhca3N3b3JkCg==' \
     * 'https://central.sonatype.com/api/v1/publisher/status?id=28570f16-da32-4c14-bd2e-c1acc0782365'
     *
     * Response:
     * {
     * "deploymentId": "28570f16-da32-4c14-bd2e-c1acc0782365",
     * "deploymentName": "central-bundle.zip",
     * "deploymentState": "PUBLISHED",
     * "purls": [
     *  "pkg:maven/com.sonatype.central.example/example_java_project@0.0.7"
     * ]
     * }
     * @param deploymentId The ID of the deployment.
     * @return The status of the deployment or null if not found.
     */
    String getStatus(String deploymentId) {
        String endPoint = "$publishUrl/publisher/status?id=${deploymentId}"
        Map<String, Object> result = post(endPoint, null, userName, password)
        def body
        try {
            body = new JsonSlurper().parseText(result.get(BODY) as String) as Map
        } catch (Exception e) {
            log.error("Failed to parse JSON response for deployment ID ${deploymentId}: ${e.message}")
            return null
        }
        body?.deploymentState ?: null
    }
}
