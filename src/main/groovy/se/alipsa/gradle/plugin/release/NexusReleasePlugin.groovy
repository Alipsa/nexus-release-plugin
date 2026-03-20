package se.alipsa.gradle.plugin.release

import groovy.transform.CompileStatic
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.tasks.TaskProvider

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
        }

        TaskProvider<LatestMavenVersionsTask> latestMavenVersionsTask = project.tasks.register('latestMavenVersions', LatestMavenVersionsTask) { LatestMavenVersionsTask task ->
            task.metadataBaseUrl.set(extension.metadataBaseUrl)
            task.moduleCoordinates.set(project.provider {
                collectPublishedModules(project)
            })
        }

        // Expose tasks via the extension for external access
        extension.bundleTask = bundleTask
        extension.releaseTask = releaseTask
        extension.latestMavenVersionsTask = latestMavenVersionsTask
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
