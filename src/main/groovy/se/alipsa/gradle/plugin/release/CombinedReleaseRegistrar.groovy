package se.alipsa.gradle.plugin.release

import groovy.transform.CompileStatic
import groovy.transform.PackageScope
import org.gradle.api.model.ObjectFactory
import org.gradle.api.tasks.TaskProvider

import org.gradle.api.tasks.TaskContainer
import org.gradle.api.Project

/** Registers tasks against existing properties, including under eager consumer rules. */
@CompileStatic
@PackageScope
final class CombinedReleaseRegistrar {
    static CombinedReleaseExtension register(Project project, NexusReleasePluginExtension parent) {
        TaskContainer tasks = project.tasks
        ObjectFactory objects = project.objects
        def shared = new CombinedReleaseProperties(objects, parent)
        TaskProvider<ReleaseAllPlanTask> planTask = tasks.register('releaseAllPlan', ReleaseAllPlanTask) { ReleaseAllPlanTask task ->
            task.sourceDirectory.convention(shared.sourceDirectory)
            task.manifestFile.convention(shared.manifestFile)
            task.repositoryUrl.convention(shared.repositoryUrl)
            task.portalUrl.convention(shared.portalUrl)
            task.userName.convention(shared.userName)
            task.password.convention(shared.password)
            task.portalFixture.convention(shared.portalFixture)
            task.releaseNamespaces.convention([])
        }
        TaskProvider<SigningPreflightTask> signingCheckTask = tasks.register('releaseAllSigningCheck', SigningPreflightTask) { SigningPreflightTask task ->
            task.manifestFile.convention(shared.manifestFile)
        }
        TaskProvider<ReleaseSourceGuardTask> guardTask = tasks.register('releaseAllGuard', ReleaseSourceGuardTask) { ReleaseSourceGuardTask task ->
            task.sourceDirectory.convention(shared.sourceDirectory)
            task.manifestFile.convention(shared.manifestFile)
        }
        TaskProvider<PrepareMavenTask> prepareMavenTask = tasks.register('releaseAllPrepareMaven', PrepareMavenTask) { PrepareMavenTask task ->
            task.sourceDirectory.convention(shared.sourceDirectory)
            task.manifestFile.convention(shared.manifestFile)
            task.stagingDirectory.convention(shared.stagingDirectory)
            task.stagingRepositoryId.convention('release-staging')
        }
        TaskProvider<BundleAllTask> bundleAllTask = tasks.register('bundleAll', BundleAllTask) { BundleAllTask task ->
            task.sourceDirectory.convention(shared.sourceDirectory)
            task.manifestFile.convention(shared.manifestFile)
            task.stagingDirectory.convention(shared.stagingDirectory)
            task.bundleFile.convention(shared.bundleFile)
            task.receiptFile.convention(shared.receiptFile)
        }
        TaskProvider<ReleaseAllTask> releaseAllTask = tasks.register('releaseAll', ReleaseAllTask) { ReleaseAllTask task ->
            task.sourceDirectory.convention(shared.sourceDirectory)
            task.manifestFile.convention(shared.manifestFile)
            task.bundleFile.convention(shared.bundleFile)
            task.receiptFile.convention(shared.receiptFile)
            task.recordFile.convention(shared.recordFile)
            task.repositoryUrl.convention(shared.repositoryUrl)
            task.portalUrl.convention(shared.portalUrl)
            task.userName.convention(shared.userName)
            task.password.convention(shared.password)
            task.portalFixture.convention(shared.portalFixture)
            task.resumeId.convention('')
        }
        def providers = new CombinedReleaseTasks(planTask, signingCheckTask, guardTask, prepareMavenTask, bundleAllTask, releaseAllTask)
        CombinedReleaseExtension extension = objects.newInstance(CombinedReleaseExtension, shared, providers)
        if (project.gradle.startParameter.configureOnDemand) {
            // With on-demand configuration, projectsEvaluated fires after task dependencies
            // have been collected. Wire the graph at the end of the owning build script.
            project.afterEvaluate {
                ModuleReleaseConfigurer.configure(project, extension)
            }
        } else {
            project.gradle.projectsEvaluated {
                ModuleReleaseConfigurer.configure(project, extension)
            }
        }
        extension
    }
}
