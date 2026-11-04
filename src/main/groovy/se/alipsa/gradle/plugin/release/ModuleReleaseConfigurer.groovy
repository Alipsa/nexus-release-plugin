package se.alipsa.gradle.plugin.release

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.transform.PackageScope
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.publish.maven.tasks.GenerateMavenPom
import org.gradle.api.publish.maven.tasks.PublishToMavenRepository
import org.gradle.plugins.signing.Sign
import org.gradle.plugins.signing.SigningExtension

/** Adapts configured Gradle publications to the combined-release task contract. */
@PackageScope
final class ModuleReleaseConfigurer {
    static void configure(Project project, CombinedReleaseExtension extension) {
        extension.modules.finalizeValue()
        List<String> requested = extension.modules.get()
        if (requested.empty) return
        if (project != project.rootProject) {
            throw new GradleException('Configure combinedRelease.modules in the root project')
        }
        List<String> paths = requested.collect { String path ->
            if (!path?.trim() || path != path.trim()) throw new GradleException('combinedRelease.modules requires nonempty project paths')
            path.startsWith(':') ? path : ':' + path
        }.unique().sort()
        extension.sourceDirectory.convention(project.layout.projectDirectory)
        extension.manifestFile.convention(project.layout.buildDirectory.file('releaseAll/manifest.json'))
        extension.stagingDirectory.convention(project.layout.buildDirectory.dir('releaseAll/staging'))
        extension.bundleFile.convention(project.layout.buildDirectory.file('releaseAll/bundle.zip'))
        extension.receiptFile.convention(project.layout.buildDirectory.file('releaseAll/bundle-receipt.json'))
        extension.recordFile.convention(project.layout.buildDirectory.file('releaseAll/deployment.json'))
        if (extension.releaseAllTask.get().resumeId.getOrElse('')) return

        // Configure publication details only when release tasks are realized. This closure is
        // consumed by configuration actions; tasks receive detached values, never Project models.
        Closure model = { describe(project, extension, paths) }.memoize()
        extension.planTask.configure { task ->
            Map description = model.call()
            task.candidatesJson.convention(description.candidatesJson)
            task.modulePaths.set(paths)
            File source = extension.sourceDirectory.get().asFile
            task.configuredRevision.convention(project.providers.exec {
                commandLine('git', '-C', source.absolutePath, 'rev-parse', 'HEAD')
            }.standardOutput.asText.map { it.trim() })
            task.candidatePoms.from(description.pomTasks)
        }
        extension.signingCheckTask.configure { task ->
            task.configuredSigningJson.convention(model.call().signingJson)
            task.strict.convention(true)
            task.dependsOn(extension.guardTask)
        }
        extension.guardTask.configure { task ->
            task.versionsJson.convention(model.call().versionsJson)
            task.modulePaths.set(paths)
        }

        // Resolve only when bundling is scheduled, even if a consumer eagerly realizes tasks.
        // Finalization prevents repeated dependency queries from registering the repository twice.
        def staging = project.objects.listProperty(Object)
        staging.set(project.providers.provider { stagingTasks(project, extension, paths) })
        staging.finalizeValueOnRead()
        extension.bundleAllTask.configure { task ->
            task.dependsOn(extension.guardTask)
            task.dependsOn(extension.signingCheckTask)
            task.dependsOn(staging)
        }
        extension.releaseAllTask.configure { task ->
            if (!task.resumeId.getOrElse('')) task.dependsOn(extension.bundleAllTask)
        }
    }

    private static Map describe(Project project, CombinedReleaseExtension extension, List<String> paths) {
        File source = extension.sourceDirectory.get().asFile
        List<Map> descriptors = []
        Map<String, Boolean> signing = [:]
        List pomTasks = []
        paths.each { String path ->
            Project module = project.findProject(path)
            if (module == null) throw new GradleException("Unknown combined release module '${path}'")
            MavenPublication publication = publicationFor(module)
            String capitalized = publication.name.capitalize()
            def pomTask = module.tasks.named("generatePomFileFor${capitalized}Publication", GenerateMavenPom)
            File pom = pomTask.get().destination
            if (!pom.canonicalPath.startsWith(source.canonicalPath + File.separator)) {
                throw new GradleException("Publication POM for ${path} must be inside combinedRelease.sourceDirectory")
            }
            String prefix = "${publication.artifactId}-${publication.version}"
            Map projectDependencies = projectEdges(module)
            Map descriptor = [projectPath: path, publication: publication.name,
                groupId: publication.groupId, artifactId: publication.artifactId, version: publication.version,
                pomFile: source.toPath().toAbsolutePath().normalize().relativize(pom.toPath().toAbsolutePath().normalize()).toString(),
                files: ["${prefix}.pom".toString()], detectModuleMetadata: true,
                dependencies: projectDependencies.edges, exclusionReasons: projectDependencies.reasons,
                skipPomInspection: !projectDependencies.reasons.empty]
            publication.artifacts.each { artifact ->
                String classifier = artifact.classifier ? '-' + artifact.classifier : ''
                descriptor.files.add("${prefix}${classifier}.${artifact.extension}".toString())
            }
            if (descriptor.files.unique(false).size() != descriptor.files.size()) {
                throw new GradleException("Duplicate publication artifact filenames for ${path}")
            }
            descriptors.add(descriptor)
            if (projectDependencies.reasons.empty) pomTasks.add(pomTask)
            module.pluginManager.apply('signing')
            SigningExtension signingExtension = module.extensions.getByType(SigningExtension)
            signing[path] = signingExtension.signatory != null
        }
        [candidatesJson: JsonOutput.toJson(descriptors), signingJson: JsonOutput.toJson(signing),
            versionsJson: JsonOutput.toJson(descriptors.collectEntries { [(it.projectPath): it.version] }), pomTasks: pomTasks]
    }

    private static List stagingTasks(Project project, CombinedReleaseExtension extension, List<String> paths) {
        // Only bundling reads the saved plan. Discovery does not consume its own output.
        // The provider invalidates the configuration cache when the selected subset changes.
        String manifestText = project.providers.fileContents(extension.manifestFile).asText.orNull
        Map manifest = manifestText ? new JsonSlurper().parseText(manifestText) as Map : [:]
        List stagedTasks = []
        (manifest.selected ?: []).each { Map selected ->
            if (!(selected.projectPath in paths)) return
            Project module = project.project(selected.projectPath)
            MavenPublication publication = publicationFor(module)
            if (publication.name != selected.publication) return
            // Signing a publication attaches signatures to every publish of that publication.
            // Add it only to the combined staging graph, preserving ordinary local/snapshot builds
            // and any signing requirements explicitly configured by the consumer.
            module.pluginManager.apply('signing')
            if (module.tasks.findByName("sign${publication.name.capitalize()}Publication") == null) {
                module.extensions.getByType(SigningExtension).sign(publication)
            }
            def repositories = module.extensions.getByType(PublishingExtension).repositories
            if (repositories.findByName('combinedReleaseStaging') != null) {
                throw new GradleException("Repository name 'combinedReleaseStaging' in ${module.path} is reserved for combined releases")
            }
            def repository = repositories.maven {
                name = 'combinedReleaseStaging'
                url = extension.stagingDirectory.get().asFile.toURI()
            }
            String taskName = "publish${publication.name.capitalize()}PublicationTo${repository.name.capitalize()}Repository"
            def publish = module.tasks.named(taskName, PublishToMavenRepository)
            publish.configure { it.dependsOn(extension.signingCheckTask) }
            def check = module.tasks.findByName('check')
            if (check != null) {
                check.dependsOn(extension.guardTask)
                publish.configure { it.dependsOn(check) }
            }
            module.tasks.withType(Sign).configureEach { Sign sign ->
                if (sign.name == "sign${publication.name.capitalize()}Publication") {
                    sign.dependsOn(extension.signingCheckTask)
                    if (check != null) sign.dependsOn(check)
                }
            }
            stagedTasks.add(publish)
        }
        stagedTasks
    }

    private static MavenPublication publicationFor(Project module, String label = 'Combined release module') {
        def chosen = module.extensions.findByType(NexusReleasePluginExtension)?.mavenPublication
        if (chosen?.present) return chosen.get()
        def publications = module.extensions.findByType(PublishingExtension)?.publications?.withType(MavenPublication)
        if (publications == null || publications.empty) {
            throw new GradleException("${label} '${module.path}' has no Maven publication; configure maven-publish first")
        }
        if (publications.size() != 1) {
            throw new GradleException("${label} '${module.path}' has multiple Maven publications; select one with nexusReleasePlugin.mavenPublication")
        }
        publications.first()
    }

    private static Map projectEdges(Project module) {
        List<Map> edges = []
        List<String> reasons = []
        module.configurations.findAll { configuration ->
            configuration.name in ['api', 'implementation', 'compileOnly', 'compileOnlyApi', 'runtime', 'runtimeOnly', 'compileClasspath', 'runtimeClasspath']
        }.each { configuration ->
            configuration.allDependencies.withType(ProjectDependency).each { ProjectDependency dependency ->
                Project target = module.rootProject.project(dependency.path)
                Map edge = [projectPath: target.path, scope: configuration.name.startsWith('runtime') ? 'runtime' : 'compile',
                    origin: "${module.path}:${configuration.name}".toString()]
                try {
                    MavenPublication publication = publicationFor(target, 'Project dependency')
                    edge.putAll(groupId: publication.groupId, artifactId: publication.artifactId, version: publication.version)
                } catch (GradleException unavailable) {
                    // Retain provenance and exclude during planning instead of making help/build
                    // fail for unpublished internal projects or ambiguous dependency publications.
                    edge.putAll(groupId: target.group.toString(), artifactId: target.name, version: target.version.toString())
                    reasons.add("${unavailable.message} (${edge.origin})".toString())
                }
                edges.add(edge)
            }
        }
        [edges: edges.unique(), reasons: reasons.unique()]
    }
}
