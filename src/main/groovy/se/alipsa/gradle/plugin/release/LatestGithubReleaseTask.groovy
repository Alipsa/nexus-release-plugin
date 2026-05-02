package se.alipsa.gradle.plugin.release

import groovy.json.JsonSlurper
import groovy.transform.CompileDynamic
import groovy.transform.CompileStatic
import org.gradle.api.DefaultTask
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

/**
 * Displays the latest GitHub release for this repository, detected from the git remote.
 */
@CompileStatic
@DisableCachingByDefault(because = "Queries external GitHub API and should always execute")
abstract class LatestGithubReleaseTask extends DefaultTask {

    @Input
    @Optional
    abstract Property<String> getRepoCoordinates()

    @Input
    abstract Property<String> getGithubApiBaseUrl()

    @Input
    @Optional
    abstract Property<String> getGithubToken()

    LatestGithubReleaseTask() {
        group = 'help'
        description = 'Displays the latest GitHub release for this repository'
    }

    @TaskAction
    void listLatestRelease() {
        String repo = repoCoordinates.orNull
        if (!repo) {
            logger.quiet('No GitHub repository detected. Ensure this project has a GitHub remote named "origin".')
            return
        }

        String baseUrl = githubApiBaseUrl.get().replaceAll('/+$', '')
        String url = "${baseUrl}/repos/${repo}/releases/latest"

        try {
            String tag = fetchLatestRelease(url, githubToken.orNull)
            logger.quiet("${repo}: ${tag}")
        } catch (FileNotFoundException ignored) {
            logger.quiet("${repo}: no releases found")
        } catch (Exception e) {
            logger.quiet("${repo}: error querying GitHub API (${e.message})")
        }
    }

    @CompileDynamic
    private static String fetchLatestRelease(String url, String token) {
        URLConnection connection = URI.create(url).toURL().openConnection()
        connection.connectTimeout = 5000
        connection.readTimeout = 5000
        connection.setRequestProperty('Accept', 'application/vnd.github+json')
        connection.setRequestProperty('User-Agent', 'se.alipsa.nexus-release-plugin')
        if (token) {
            connection.setRequestProperty('Authorization', "Bearer ${token}")
        }

        if (connection instanceof HttpURLConnection) {
            HttpURLConnection http = (HttpURLConnection) connection
            int responseCode = http.responseCode
            if (responseCode == HttpURLConnection.HTTP_NOT_FOUND) {
                throw new FileNotFoundException(url)
            }
            if (responseCode >= 400) {
                throw new IOException("HTTP ${responseCode}")
            }
        }

        connection.inputStream.withCloseable { InputStream stream ->
            def json = new JsonSlurper().parse(stream)
            return json.tag_name as String
        }
    }
}
