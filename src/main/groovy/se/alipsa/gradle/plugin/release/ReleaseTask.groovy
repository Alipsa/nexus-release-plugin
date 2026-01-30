package se.alipsa.gradle.plugin.release

import groovy.transform.CompileStatic
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Task that uploads a release bundle to Maven Central and monitors its status.
 * This task is configuration cache compatible.
 */
@CompileStatic
abstract class ReleaseTask extends DefaultTask {

    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    abstract RegularFileProperty getBundleFile()

    @Input
    abstract Property<String> getProjectVersion()

    @Input
    abstract Property<String> getProjectName()

    @Input
    abstract Property<String> getNexusUrl()

    @Input
    abstract Property<String> getUserName()

    @Input
    abstract Property<String> getPassword()

    ReleaseTask() {
        group = 'publishing'
        description = 'Create and upload a release bundle to Nexus'
    }

    @TaskAction
    void release() {
        String version = projectVersion.get()
        if (version.endsWith("-SNAPSHOT")) {
            logger.quiet("Alipsa Nexus Release Plugin: A snapshot cannot be released, publish is enough (or maybe you forgot to change the version?)")
            return
        }

        File zipFile = bundleFile.get().asFile
        if (!zipFile.exists()) {
            throw new GradleException("Expected bundle at ${zipFile.absolutePath} but it was not found.")
        }

        String projectNameValue = projectName.get()
        CentralPortalClient releaseClient = new CentralPortalClient(
            logger,
            nexusUrl.get(),
            userName.get(),
            password.get()
        )

        String deploymentId = releaseClient.upload(zipFile)
        if (!deploymentId) {
            throw new GradleException("Failed to find the staging profile id")
        }

        logger.lifecycle("Project $projectNameValue published with deploymentId = $deploymentId, waiting 10s before checking...")
        Thread.sleep(10000)
        String status = releaseClient.getStatus(deploymentId)

        int retries = 10
        while (!['PUBLISHING', 'PUBLISHED', 'FAILED'].contains(status) && retries-- > 0) {
            logger.lifecycle("Deploy status is $status")
            status = releaseClient.getStatus(deploymentId)
            Thread.sleep(10000)
        }
        if (status == 'PUBLISHING') {
            logger.lifecycle("Project $projectNameValue uploaded and validated successfully!")
            logger.lifecycle("It is currently publishing, see https://central.sonatype.com/publishing/deployments for details")
        } else if (status == 'PUBLISHED') {
            logger.lifecycle("Project $projectNameValue published successfully!")
        } else {
            throw new GradleException("Failed to release $projectNameValue with deploymentId $deploymentId")
        }
    }
}
