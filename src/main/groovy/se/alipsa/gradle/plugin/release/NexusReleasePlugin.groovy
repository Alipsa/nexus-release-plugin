package se.alipsa.gradle.plugin.release

import groovy.transform.CompileStatic
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.tasks.TaskProvider

import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * This plugin is an alternative to the nexus publish plugin which for some of
 * my more complex projects did not do what I wanted it to do.
 *
 * This implementation is compatible with Gradle 9.x configuration cache.
 */
@CompileStatic
class NexusReleasePlugin implements Plugin<Project> {

    void apply(Project project) {
        def extension = project.extensions.create('nexusReleasePlugin', NexusReleasePluginExtension)
        project.pluginManager.apply('signing')
        project.pluginManager.apply('maven-publish')

        TaskProvider<BundleTask> bundleTask = project.tasks.register('bundle', BundleTask) { BundleTask task ->
            task.dependsOn "publishToMavenLocal"

            // Wire providers to extract publication data at configuration time
            task.groupId.set(extension.mavenPublication.map { it.groupId })
            task.artifactId.set(extension.mavenPublication.map { it.artifactId })
            task.version.set(extension.mavenPublication.map { it.version })
            task.publicationName.set(extension.mavenPublication.map { it.name })

            task.publicationDirectory.set(project.layout.buildDirectory.dir(
                extension.mavenPublication.map { "publications/${it.name}" }
            ))

            task.artifactFiles.set(extension.mavenPublication.map { pub ->
                pub.artifacts.collect { it.file }
            })

            task.bundleFile.set(project.layout.buildDirectory.file(
                extension.mavenPublication.map { pub ->
                    "zips/${pub.artifactId}-${pub.version}-bundle.zip"
                }
            ))
        }

        TaskProvider<ReleaseTask> releaseTask = project.tasks.register('release', ReleaseTask) { ReleaseTask task ->
            // Make release depend on the bundle task
            task.dependsOn(bundleTask)

            // Wire the bundle file from the bundle task
            task.bundleFile.set(bundleTask.flatMap { it.bundleFile })

            // Wire project metadata
            task.publicationVersion.set(extension.mavenPublication.map { it.version })
            task.projectName.set(project.provider { project.name })
            task.groupId.set(extension.mavenPublication.map { it.groupId })
            task.artifactId.set(extension.mavenPublication.map { it.artifactId })

            // Wire credentials from extension
            task.nexusUrl.set(extension.nexusUrl)
            task.userName.set(extension.userName)
            task.password.set(extension.password)
            task.statusCheckRetries.set(extension.statusCheckRetries)
            task.statusCheckIntervalSeconds.set(extension.statusCheckIntervalSeconds)
            task.initialStatusCheckDelaySeconds.set(extension.initialStatusCheckDelaySeconds)
        }

        TaskProvider<LatestMavenVersionsTask> latestMavenVersionsTask = project.tasks.register('latestMavenVersions', LatestMavenVersionsTask) { LatestMavenVersionsTask task ->
            task.metadataBaseUrl.set(extension.metadataBaseUrl)
            task.moduleCoordinates.set(project.provider {
                collectPublishedModules(project)
            })
        }

        TaskProvider<LatestGithubReleaseTask> latestGithubReleaseTask = project.tasks.register('latestGithubRelease', LatestGithubReleaseTask) { LatestGithubReleaseTask task ->
            task.githubApiBaseUrl.set(extension.githubApiBaseUrl)
            task.githubToken.set(extension.githubToken)
            task.repoCoordinates.set(extension.githubRepo.orElse(
                extension.githubApiBaseUrl.map { String apiBaseUrl ->
                    detectGithubRepo(project.rootProject.projectDir, apiBaseUrl)
                }
            ))
        }

        // Expose tasks via the extension for external access
        extension.bundleTask = bundleTask
        extension.releaseTask = releaseTask
        extension.latestMavenVersionsTask = latestMavenVersionsTask
        extension.latestGithubReleaseTask = latestGithubReleaseTask
    }

    private static List<String> collectPublishedModules(Project currentProject) {
        List<Project> candidates = currentProject == currentProject.rootProject
            ? currentProject.rootProject.allprojects.toList()
            : Collections.singletonList(currentProject)

        List<String> modules = candidates.collect { Project candidate ->
            moduleCoordinates(candidate)
        }.findAll { String encoded ->
            encoded != null
        } as List<String>

        modules.unique().sort()
    }

    private static String moduleCoordinates(Project candidate) {
        MavenPublication publication = publicationFor(candidate)
        if (publication == null) {
            return null
        }

        String groupId = publication.groupId
        String artifactId = publication.artifactId
        if (!groupId || !artifactId || groupId == 'unspecified') {
            return null
        }

        String displayName = candidate == candidate.rootProject ? candidate.name : candidate.path
        "${displayName}\t${groupId}\t${artifactId}"
    }

    private static String detectGithubRepo(File projectDir, String apiBaseUrl) {
        String originUrl = findOriginUrl(projectDir)
        return originUrl ? parseGithubCoordinates(originUrl, apiBaseUrl) : null
    }

    private static String findOriginUrl(File startDir) {
        File dir = startDir
        while (dir != null) {
            File git = new File(dir, '.git')
            if (git.exists()) {
                String url = readOriginUrl(git)
                if (url) return url
            }
            dir = dir.parentFile
        }
        return null
    }

    private static String readOriginUrl(File git) {
        try {
            File configFile
            if (git.isDirectory()) {
                configFile = new File(git, 'config')
            } else if (git.isFile()) {
                // Worktree: .git is a file containing "gitdir: /path/to/.git/worktrees/branch"
                String ref = git.text.trim()
                if (!ref.startsWith('gitdir:')) return null
                File worktreeGitDir = new File(ref.substring('gitdir:'.length()).trim())
                File commondirFile = new File(worktreeGitDir, 'commondir')
                if (!commondirFile.exists()) return null
                String commonPath = commondirFile.text.trim()
                File commonDirFile = new File(commonPath)
                File commonDir = commonDirFile.isAbsolute() ? commonDirFile : new File(worktreeGitDir, commonPath)
                configFile = new File(commonDir, 'config')
            } else {
                return null
            }
            if (!configFile?.exists()) return null
            return parseOriginUrlFromConfig(configFile.text)
        } catch (IOException ignored) {
            return null
        }
    }

    private static String parseOriginUrlFromConfig(String config) {
        boolean inOriginSection = false
        for (String line : config.split('\n')) {
            String trimmed = line.trim()
            if (trimmed == '[remote "origin"]') {
                inOriginSection = true
            } else if (trimmed.startsWith('[')) {
                inOriginSection = false
            } else if (inOriginSection && trimmed.startsWith('url =')) {
                return trimmed.substring(trimmed.indexOf('=') + 1).trim()
            }
        }
        return null
    }

    private static String parseGithubCoordinates(String remoteUrl, String apiBaseUrl) {
        if (!remoteUrl) return null
        String apiHost = URI.create(apiBaseUrl).host ?: 'api.github.com'
        // For api.github.com the remote host is github.com; for Enterprise the api and git hosts match
        String expectedRemoteHost = (apiHost == 'api.github.com') ? 'github.com' : apiHost
        Matcher ssh = Pattern.compile('^git@([^:]+):(.+?)(?:\\.git)?$').matcher(remoteUrl)
        if (ssh.matches() && ssh.group(1) == expectedRemoteHost) return ssh.group(2)
        // Strip port from remote host so http://localhost:8080/x matches API host 'localhost'
        Matcher https = Pattern.compile('^https?://([^/:]+)(?::\\d+)?/(.+?)(?:\\.git)?$').matcher(remoteUrl)
        if (https.matches() && https.group(1) == expectedRemoteHost) return https.group(2)
        return null
    }

    private static MavenPublication publicationFor(Project candidate) {
        NexusReleasePluginExtension extension = candidate.extensions.findByType(NexusReleasePluginExtension)
        if (extension != null && extension.mavenPublication.present) {
            return extension.mavenPublication.get()
        }

        PublishingExtension publishing = candidate.extensions.findByType(PublishingExtension)
        if (publishing == null) {
            return null
        }

        Object named = publishing.publications.findByName('maven')
        if (named instanceof MavenPublication) {
            return (MavenPublication) named
        }

        Set<MavenPublication> publications = publishing.publications.withType(MavenPublication)
        publications.isEmpty() ? null : publications.iterator().next()
    }
}
