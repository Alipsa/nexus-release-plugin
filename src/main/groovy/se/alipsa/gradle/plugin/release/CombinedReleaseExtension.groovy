package se.alipsa.gradle.plugin.release

import groovy.transform.CompileStatic
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.TaskProvider
import javax.inject.Inject

/** Opt-in combined configuration with read-only task references. */
@CompileStatic
class CombinedReleaseExtension {
    private final CombinedReleaseProperties shared
    private final TaskProvider<ReleaseAllPlanTask> planTask
    private final TaskProvider<SigningPreflightTask> signingCheckTask
    private final TaskProvider<ReleaseSourceGuardTask> guardTask
    private final TaskProvider<PrepareMavenTask> prepareMavenTask
    private final TaskProvider<BundleAllTask> bundleAllTask
    private final TaskProvider<ReleaseAllTask> releaseAllTask

    @Inject
    CombinedReleaseExtension(CombinedReleaseProperties shared, CombinedReleaseTasks tasks) {
        this.shared = shared
        this.planTask = tasks.planTask
        this.signingCheckTask = tasks.signingCheckTask
        this.guardTask = tasks.guardTask
        this.prepareMavenTask = tasks.prepareMavenTask
        this.bundleAllTask = tasks.bundleAllTask
        this.releaseAllTask = tasks.releaseAllTask
    }

    DirectoryProperty getSourceDirectory() { shared.sourceDirectory }
    RegularFileProperty getManifestFile() { shared.manifestFile }
    DirectoryProperty getStagingDirectory() { shared.stagingDirectory }
    RegularFileProperty getBundleFile() { shared.bundleFile }
    RegularFileProperty getReceiptFile() { shared.receiptFile }
    RegularFileProperty getRecordFile() { shared.recordFile }
    Property<String> getRepositoryUrl() { shared.repositoryUrl }
    Property<String> getPortalUrl() { shared.portalUrl }
    Property<String> getUserName() { shared.userName }
    Property<String> getPassword() { shared.password }
    TaskProvider<ReleaseAllPlanTask> getPlanTask() { this.planTask }
    TaskProvider<SigningPreflightTask> getSigningCheckTask() { this.signingCheckTask }
    TaskProvider<ReleaseSourceGuardTask> getGuardTask() { this.guardTask }
    TaskProvider<PrepareMavenTask> getPrepareMavenTask() { this.prepareMavenTask }
    TaskProvider<BundleAllTask> getBundleAllTask() { this.bundleAllTask }
    TaskProvider<ReleaseAllTask> getReleaseAllTask() { this.releaseAllTask }
}
