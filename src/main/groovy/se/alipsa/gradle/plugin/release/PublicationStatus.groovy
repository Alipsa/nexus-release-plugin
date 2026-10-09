package se.alipsa.gradle.plugin.release

import groovy.json.JsonSlurper
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** Fail-closed exact-version availability checks, with bounded transient retries. */
class PublicationStatus {
    String repositoryUrl = 'https://repo.maven.apache.org/maven2'
    String portalUrl = CentralPortalClient.CENTRAL_PORTAL_URL
    String userName
    String password
    int attempts = 3
    boolean requireFixture = false
    File fixtureFile
    HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build()

    /** Returns true only for a successful repository response or explicit Portal publication. */
    boolean published(ReleaseCoordinate coordinate, boolean checkBoth = false) {
        coordinate.validate()
        int status = request("${repositoryUrl}/${coordinate.path()}/${coordinate.artifactId}-${coordinate.version}.pom", false).statusCode()
        if (status != 200 && status != 404) throw new IOException("Repository availability returned HTTP ${status} for ${coordinate.key()}")
        if (status == 200 && !checkBoth) return true
        boolean portal = portalPublished(coordinate)
        status == 200 || portal
    }

    /** Implements checkComponentPublished; errors, including 403, never mean absence. */
    boolean portalPublished(ReleaseCoordinate coordinate) {
        if (requireFixture) {
            if (!fixtureFile?.isFile()) throw new IOException('Capture confirmed Portal fixtures before absence-based discovery/upload')
            Map fixture = ReleaseManifest.read(fixtureFile)
            if (fixture.namespacePolicy != 'groupId' || fixture.published?.body?.published != true || fixture.absent?.body?.published != false) {
                throw new IOException('Portal namespace/absence fixtures are not confirmed')
            }
        }
        if (!userName || !password) throw new IOException('Portal checks require sonatypeUsername and sonatypePassword')
        String query = [namespace: coordinate.groupId, name: coordinate.artifactId, version: coordinate.version]
            .collect { String key, String value -> "${key}=${URLEncoder.encode(value, 'UTF-8')}" }.join('&')
        def response = request("${portalUrl}/publisher/published?${query}", true)
        if (response.statusCode() != 200) throw new IOException("Portal published-status returned HTTP ${response.statusCode()} for ${coordinate.key()}")
        def body
        try { body = new JsonSlurper().parseText(response.body()) }
        catch (Exception ignored) { throw new IOException('Portal published-status returned invalid JSON') }
        if (!(body instanceof Map) || !(body.published instanceof Boolean)) throw new IOException('Portal published-status requires a boolean published field')
        body.published
    }

    private HttpResponse<String> request(String url, boolean authenticated) {
        for (int attempt = 1; attempt <= attempts; attempt++) {
            def builder = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30)).GET()
            if (authenticated) builder.header('Authorization', 'Bearer ' + Base64.encoder.encodeToString("${userName}:${password}".getBytes('UTF-8')))
            try {
                def response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
                if (!(response.statusCode() in [429, 500, 502, 503, 504]) || attempt == attempts) return response
            } catch (IOException ignored) {
                if (attempt == attempts) throw new IOException('Availability request failed after bounded retries')
            }
            Thread.sleep(250L * attempt)
        }
        throw new IOException('Availability request failed')
    }
}
