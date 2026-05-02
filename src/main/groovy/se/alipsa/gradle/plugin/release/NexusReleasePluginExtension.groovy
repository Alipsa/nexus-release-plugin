package se.alipsa.gradle.plugin.release

import groovy.transform.CompileStatic
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.tasks.TaskProvider

import javax.inject.Inject

@CompileStatic
class NexusReleasePluginExtension {

    final Property<String> nexusUrl
    final Property<String> metadataBaseUrl
    final Property<String> userName
    final Property<String> password
    final Property<MavenPublication> mavenPublication
    final Property<Integer> statusCheckRetries
    final Property<Integer> statusCheckIntervalSeconds
    final Property<Integer> initialStatusCheckDelaySeconds
    final Property<String> githubApiBaseUrl
    final Property<String> githubToken
    final Property<String> githubRepo

    // References to tasks with proper types
    TaskProvider<BundleTask> bundleTask
    TaskProvider<ReleaseTask> releaseTask
    TaskProvider<LatestMavenVersionsTask> latestMavenVersionsTask
    TaskProvider<LatestGithubReleaseTask> latestGithubReleaseTask

    @Inject
    NexusReleasePluginExtension(ObjectFactory objects) {
        userName = objects.property(String)
        password = objects.property(String)
        mavenPublication = objects.property(MavenPublication)
        nexusUrl = objects.property(String).convention(CentralPortalClient.CENTRAL_PORTAL_URL)
        metadataBaseUrl = objects.property(String).convention('https://repo1.maven.org/maven2')
        statusCheckRetries = objects.property(Integer).convention(10)
        statusCheckIntervalSeconds = objects.property(Integer).convention(10)
        initialStatusCheckDelaySeconds = objects.property(Integer).convention(10)
        githubApiBaseUrl = objects.property(String).convention('https://api.github.com')
        githubToken = objects.property(String)
        githubRepo = objects.property(String)
    }

    void setNexusUrl(String url) {
        nexusUrl.set(url)
    }

    void setUserName(String user) {
        userName.set(user)
    }

    void setMetadataBaseUrl(String url) {
        metadataBaseUrl.set(url)
    }

    void setPassword(String pass) {
        password.set(pass)
    }

    void setMavenPublication(MavenPublication publication) {
        mavenPublication.set(publication)
    }

    void setStatusCheckRetries(Integer retries) {
        statusCheckRetries.set(retries)
    }

    void setStatusCheckIntervalSeconds(Integer seconds) {
        statusCheckIntervalSeconds.set(seconds)
    }

    void setInitialStatusCheckDelaySeconds(Integer seconds) {
        initialStatusCheckDelaySeconds.set(seconds)
    }

    void setGithubApiBaseUrl(String url) {
        githubApiBaseUrl.set(url)
    }

    void setGithubToken(String token) {
        githubToken.set(token)
    }

    void setGithubRepo(String repo) {
        githubRepo.set(repo)
    }

}
