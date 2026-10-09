package se.alipsa.gradle.plugin.release

import org.gradle.api.Plugin
import org.gradle.api.Project

/** Exposes combined-release support independently of per-publication task registration. */
class ReleaseAllSupportPlugin implements Plugin<Project> {
    /** Combined workflows configure typed tasks without creating single-publication tasks. */
    void apply(Project project) {
        project.tasks.register('releaseAllSigningCheck', SigningPreflightTask)
        project.tasks.register('releaseAllGuard', ReleaseSourceGuardTask)
        project.tasks.register('bundleAll', BundleAllTask)
        project.tasks.register('releaseAll', ReleaseAllTask)
        project.tasks.register('releaseAllPlan', ReleaseAllPlanTask)
        project.tasks.register('releaseAllPrepareMaven', PrepareMavenTask)
    }
}
