package se.alipsa.gradle.plugin.release

import groovy.transform.CompileDynamic
import groovy.transform.CompileStatic
import groovy.xml.XmlSlurper
import org.gradle.api.DefaultTask
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

/**
 * Lists the latest published version for the configured Maven modules.
 */
@CompileStatic
@DisableCachingByDefault(because = "Queries external Maven metadata and should always execute")
abstract class LatestMavenVersionsTask extends DefaultTask {

    @Input
    abstract Property<String> getMetadataBaseUrl()

    @Input
    abstract ListProperty<String> getModuleCoordinates()

    LatestMavenVersionsTask() {
        group = 'help'
        description = 'Lists the latest published version for the project and published subprojects'
    }

    @TaskAction
    void listLatestVersions() {
        List<String> modules = new ArrayList<>(moduleCoordinates.getOrElse(Collections.<String>emptyList()))
        if (modules.isEmpty()) {
            logger.quiet('No Maven publications configured for version lookup.')
            return
        }

        String baseUrl = normalizeBaseUrl(metadataBaseUrl.get())
        modules.sort().each { String encoded ->
            ModuleCoordinates module = ModuleCoordinates.parse(encoded)
            try {
                String latest = fetchLatestVersion(baseUrl, module)
                if (latest) {
                    logger.quiet("${module.displayName}: ${module.groupId}:${module.artifactId}:${latest}")
                } else {
                    logger.quiet("${module.displayName}: no release version found")
                }
            } catch (FileNotFoundException ignored) {
                logger.quiet("${module.displayName}: not found in metadata repository")
            } catch (Exception e) {
                logger.quiet("${module.displayName}: error querying metadata repository (${e.message})")
            }
        }
    }

    private static String fetchLatestVersion(String baseUrl, ModuleCoordinates module) {
        String groupPath = module.groupId.replace('.', '/')
        URL url = URI.create("${baseUrl}/${groupPath}/${module.artifactId}/maven-metadata.xml").toURL()
        URLConnection connection = url.openConnection()
        connection.connectTimeout = 5000
        connection.readTimeout = 5000
        connection.useCaches = false

        if (connection instanceof HttpURLConnection) {
            HttpURLConnection http = (HttpURLConnection) connection
            http.instanceFollowRedirects = true
            int responseCode = http.responseCode
            if (responseCode == HttpURLConnection.HTTP_NOT_FOUND) {
                throw new FileNotFoundException(url.toString())
            }
            if (responseCode >= 400) {
                throw new IOException("HTTP ${responseCode}")
            }
        }

        connection.inputStream.withCloseable { InputStream stream ->
            return extractLatestVersion(new XmlSlurper().parse(stream))
        }
    }

    private static String normalizeBaseUrl(String baseUrl) {
        baseUrl.endsWith('/') ? baseUrl[0..-2] : baseUrl
    }

    @CompileDynamic
    private static String extractLatestVersion(Object metadata) {
        String release = metadata.versioning.release.text()
        String latest = metadata.versioning.latest.text()
        release ?: latest
    }

    @CompileStatic
    private static class ModuleCoordinates {
        final String displayName
        final String groupId
        final String artifactId

        private ModuleCoordinates(String displayName, String groupId, String artifactId) {
            this.displayName = displayName
            this.groupId = groupId
            this.artifactId = artifactId
        }

        static ModuleCoordinates parse(String encoded) {
            List<String> parts = encoded.split('\t', 3) as List<String>
            if (parts.size() != 3) {
                throw new IllegalArgumentException("Invalid module coordinate '${encoded}'")
            }
            new ModuleCoordinates(parts[0], parts[1], parts[2])
        }
    }
}
