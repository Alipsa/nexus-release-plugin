package se.alipsa.gradle.plugin.release

import groovy.json.JsonOutput
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
import org.gradle.api.tasks.Optional

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
    abstract Property<String> getPublicationVersion()

    @Input
    abstract Property<String> getProjectName()

    @Input
    abstract Property<String> getNexusUrl()

    @Input
    @Optional
    abstract Property<String> getUserName()

    @Input
    @Optional
    abstract Property<String> getPassword()

    @Input
    abstract Property<String> getGroupId()

    @Input
    abstract Property<String> getArtifactId()

    ReleaseTask() {
        group = 'publishing'
        description = 'Create and upload a release bundle to Nexus'
    }

    @TaskAction
    void release() {
        String version = publicationVersion.get()
        if (version.endsWith("-SNAPSHOT")) {
            logger.quiet("Alipsa Nexus Release Plugin: A snapshot cannot be released, publish is enough (or maybe you forgot to change the version?)")
            return
        }

        File zipFile = bundleFile.get().asFile
        if (!zipFile.exists()) {
            throw new GradleException("Expected bundle at ${zipFile.absolutePath} but it was not found.")
        }

        String userNameValue = userName.getOrElse('')
        String passwordValue = password.getOrElse('')
        List<String> validationErrors = BundleValidator.validateBundle(
            zipFile,
            groupId.get(),
            artifactId.get(),
            version,
            userNameValue,
            passwordValue
        )
        if (!validationErrors.isEmpty()) {
            throw new GradleException(formatValidationErrors(validationErrors))
        }

        String projectNameValue = projectName.get()
        CentralPortalClient releaseClient = new CentralPortalClient(
            logger,
            nexusUrl.get(),
            userNameValue,
            passwordValue
        )

        String deploymentId = releaseClient.upload(zipFile)
        if (!deploymentId) {
            throw new GradleException("Failed to find the staging profile id")
        }

        logger.lifecycle("Project $projectNameValue published with deploymentId = $deploymentId, waiting 10s before checking...")
        Thread.sleep(10000)
        Map<String, Object> status = releaseClient.getDeploymentStatus(deploymentId)
        String state = releaseClient.getDeploymentState(status)

        int retries = 10
        while (!['PUBLISHING', 'PUBLISHED', 'FAILED'].contains(state) && retries-- > 0) {
            logger.lifecycle("Deploy status is $state")
            status = releaseClient.getDeploymentStatus(deploymentId)
            state = releaseClient.getDeploymentState(status)
            Thread.sleep(10000)
        }
        if (state == 'PUBLISHING') {
            logger.lifecycle("Project $projectNameValue uploaded and validated successfully!")
            logger.lifecycle("It is currently publishing, see https://central.sonatype.com/publishing/deployments for details")
        } else if (state == 'PUBLISHED') {
            logger.lifecycle("Project $projectNameValue published successfully!")
        } else if (state == 'FAILED') {
            logFailedDeployment(status)
            throw new GradleException("Failed to release $projectNameValue with deploymentId $deploymentId")
        } else {
            throw new GradleException("Failed to release $projectNameValue with deploymentId $deploymentId")
        }
    }

    private String formatValidationErrors(List<String> errors) {
        StringBuilder sb = new StringBuilder()
        sb.append("Bundle validation failed. ").append(errors.size()).append(" problem(s) found:\n\n")
        errors.eachWithIndex { String err, int i ->
            sb.append("  [").append(i + 1).append("] ").append(err).append("\n\n")
        }
        return sb.toString().trim()
    }

    private void logFailedDeployment(Map<String, Object> status) {
        logger.lifecycle("Deployment FAILED. Validation errors reported by Maven Central:")
        logger.debug("Raw deployment status: ${JsonOutput.toJson(status)}")
        Object errorsObj = status?.get('errors')
        List<Object> errors = errorsObj instanceof List ? (List<Object>) errorsObj : Collections.<Object>emptyList()
        if (errors.isEmpty()) {
            logger.lifecycle("  (no structured errors returned; check https://central.sonatype.com/publishing/deployments)")
            return
        }
        errors.eachWithIndex { Object err, int i ->
            if (err instanceof Map) {
                Map<String, Object> errorMap = (Map<String, Object>) err
                String component = errorMap.get('component') as String
                String message = errorMap.get('message') as String
                if (component) {
                    logger.lifecycle("  [${i + 1}] ${component}: ${message ?: errorMap.toString()}")
                } else {
                    logger.lifecycle("  [${i + 1}] ${message ?: errorMap.toString()}")
                }
            } else {
                logger.lifecycle("  [${i + 1}] ${err}")
            }
        }
    }
}
