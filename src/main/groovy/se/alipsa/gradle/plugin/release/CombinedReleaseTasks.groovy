package se.alipsa.gradle.plugin.release

import groovy.transform.CompileStatic
import groovy.transform.PackageScope
import org.gradle.api.tasks.TaskProvider

/** Immutable task references returned by combined registration. */
@CompileStatic
@PackageScope
final class CombinedReleaseTasks {
    final TaskProvider<ReleaseAllPlanTask> planTask
    final TaskProvider<SigningPreflightTask> signingCheckTask
    final TaskProvider<ReleaseSourceGuardTask> guardTask
    final TaskProvider<PrepareMavenTask> prepareMavenTask
    final TaskProvider<BundleAllTask> bundleAllTask
    final TaskProvider<ReleaseAllTask> releaseAllTask

    CombinedReleaseTasks(TaskProvider<ReleaseAllPlanTask> planTask, TaskProvider<SigningPreflightTask> signingCheckTask, TaskProvider<ReleaseSourceGuardTask> guardTask, TaskProvider<PrepareMavenTask> prepareMavenTask, TaskProvider<BundleAllTask> bundleAllTask, TaskProvider<ReleaseAllTask> releaseAllTask) {
        this.planTask = planTask
        this.signingCheckTask = signingCheckTask
        this.guardTask = guardTask
        this.prepareMavenTask = prepareMavenTask
        this.bundleAllTask = bundleAllTask
        this.releaseAllTask = releaseAllTask
    }
}
