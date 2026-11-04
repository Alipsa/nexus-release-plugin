package se.alipsa.gradle.plugin.release

import groovy.json.JsonSlurper
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import org.gradle.work.DisableCachingByDefault

/** Plans configured publications without compiling or resolving Maven candidates. */
@DisableCachingByDefault(because = 'Queries current publication availability')
abstract class ReleaseAllPlanTask extends DefaultTask {
    @Input abstract Property<String> getCandidatesJson()
    @Input abstract Property<String> getConfiguredRevision()
    @Input abstract org.gradle.api.provider.ListProperty<String> getReleaseNamespaces()
    @Input abstract org.gradle.api.provider.ListProperty<String> getModulePaths()
    @Input abstract org.gradle.api.provider.ListProperty<String> getUnpublishedProjectPaths()
    @Internal abstract DirectoryProperty getSourceDirectory()
    @InputFile @Optional @PathSensitive(PathSensitivity.RELATIVE) abstract RegularFileProperty getBomPom()
    @InputFile @Optional @PathSensitive(PathSensitivity.RELATIVE) abstract RegularFileProperty getAllPom()
    @Input abstract Property<String> getRepositoryUrl()
    @Input abstract Property<String> getPortalUrl()
    @Internal abstract Property<String> getUserName()
    @Internal abstract Property<String> getPassword()
    @Internal abstract RegularFileProperty getPortalFixture()
    @InputFiles @PathSensitive(PathSensitivity.RELATIVE) abstract org.gradle.api.file.ConfigurableFileCollection getCandidatePoms()
    @OutputFile abstract RegularFileProperty getManifestFile()
    @OutputFile @Optional abstract RegularFileProperty getReportFile()

    ReleaseAllPlanTask() {
        modulePaths.convention([])
        unpublishedProjectPaths.convention([])
        outputs.upToDateWhen { false }
    }

    /** Performs fail-closed selection and persists all exclusions and source guards. */
    @TaskAction
    void plan() {
        File root = sourceDirectory.get().asFile
        if (ReleaseManifest.git(root, 'rev-parse', 'HEAD') != configuredRevision.get()) throw new IllegalStateException('HEAD changed since publication configuration; restart planning')
        if (ReleaseManifest.git(root, 'status', '--porcelain')) throw new IllegalStateException('Release planning requires a clean Git working tree')
        List<PublicationDescriptor> candidates = []
        Set<String> inspectedPoms = [] as Set
        new JsonSlurper().parseText(candidatesJson.get()).each { Map registered ->
            if (!(registered.files instanceof List) || registered.files.empty) {
                throw new IllegalArgumentException("Candidate ${registered.projectPath ?: registered.artifactId} must declare a nonempty files list")
            }
            File candidatePom = new File(root, registered.pomFile)
            // A known project-publication problem can also make Gradle's POM generation fail
            // (e.g. conflicting dependency publications). Report its exclusion without generation.
            boolean inspectPom = !(registered.skipPomInspection && registered.exclusionReasons)
            Map parsed = inspectPom ? ReleasePom.read(candidatePom) : [packaging: 'jar', dependencies: [], management: []]
            if (inspectPom) inspectedPoms.add(registered.pomFile)
            List<String> files = new ArrayList<String>(registered.files)
            List<Map> unpublished = registered.unpublishedProjectDependencies ?: []
            List<String> reasons = new ArrayList<String>(registered.exclusionReasons ?: [])
            unpublished.each { Map internal ->
                if ((parsed.dependencies + parsed.management).any { it.groupId == internal.groupId && it.artifactId == internal.artifactId }) {
                    reasons.add("Unpublished project dependency '${internal.projectPath}' is still referenced by the published POM; remove that dependency from the publication before opting out".toString())
                }
            }
            // Gradle advertises module metadata in the generated POM using this public
            // redirection marker. This avoids consulting internal MavenPublication components.
            if (inspectPom && registered.detectModuleMetadata && candidatePom.getText('UTF-8').contains('<!-- do_not_remove: published-with-gradle-metadata -->')) {
                String metadataName = "${registered.artifactId}-${registered.version}.module".toString()
                if (!files.contains(metadataName)) files.add(metadataName)
            }
            def publication = new PublicationDescriptor(projectPath: registered.projectPath, publication: registered.publication,
                pomFile: registered.pomFile, coordinate: new ReleaseCoordinate(registered.groupId, registered.artifactId, registered.version),
                packaging: registered.packaging ?: parsed.packaging,
                sourcesAndJavadocRequired: registered.sourcesAndJavadocRequired != false,
                dependencies: (registered.dependencies ?: []) + (parsed.dependencies + parsed.management),
                publishedDependencies: parsed.dependencies, publishedManagement: parsed.management, files: files,
                unpublishedProjectDependencies: unpublished, reasons: reasons)
            candidates.add(publication)
        }
        candidates.groupBy { it.projectPath }.each { String path, List publications ->
            if (path && publications.size() > 1) throw new IllegalArgumentException("Multiple publications for project ${path} are not supported by the version guard")
        }
        // Static Maven candidates are optional: consumers without BOM/aggregate components plan
        // only the registered Gradle candidates.
        Map bom = bomPom.present ? ReleasePom.read(bomPom.get().asFile) : null
        Map<String, String> managed = bom ? bom.management.collectEntries { ["${it.groupId}:${it.artifactId}".toString(), it.version] } : [:]
        Map all = allPom.present ? ReleasePom.read(allPom.get().asFile, bom ? bom.properties : [:], managed) : null
        [bom, all].findAll { it != null }.each { Map parsed ->
            String prefix = "${parsed.artifactId}-${parsed.version}"
            File inputPom = parsed.is(bom) ? bomPom.get().asFile : allPom.get().asFile
            if (!inputPom.canonicalPath.startsWith(root.canonicalPath + File.separator)) throw new IllegalArgumentException('Maven candidate POM must be inside the source repository')
            String pomPath = root.toPath().toAbsolutePath().normalize().relativize(inputPom.toPath().toAbsolutePath().normalize()).toString()
            def publication = new PublicationDescriptor(pomFile: pomPath,
                coordinate: new ReleaseCoordinate(parsed.groupId, parsed.artifactId, parsed.version), packaging: parsed.packaging,
                role: parsed.is(bom) ? 'bom' : 'aggregate', sourcesAndJavadocRequired: false,
                dependencies: (parsed.dependencies.findAll { it.scope != 'test' } + parsed.management),
                publishedDependencies: parsed.dependencies.findAll { it.scope != 'test' }, publishedManagement: parsed.is(bom) ? parsed.management : [], files: ["${prefix}.pom".toString()])
            if (parsed.packaging == 'jar') publication.files.add("${prefix}.jar".toString())
            candidates.add(publication)
            inspectedPoms.add(pomPath)
        }
        Set<String> candidateNames = candidates.collect { "${it.coordinate.groupId}:${it.coordinate.artifactId}".toString() } as Set
        // A subset must still check omitted siblings in each candidate's namespace.
        List<String> namespaces = (releaseNamespaces.get() + candidates.collect { it.coordinate.groupId }).unique()
        candidates.each { PublicationDescriptor publication ->
            publication.dependencies = publication.dependencies.findAll { Map edge ->
                !(edge.scope in ['test', 'system']) && (edge.projectPath || candidateNames.contains("${edge.groupId}:${edge.artifactId}".toString()) ||
                    namespaces.any { edge.groupId == it || edge.groupId?.startsWith(it + '.') })
            }
        }
        def status = new PublicationStatus(repositoryUrl: repositoryUrl.get(), portalUrl: portalUrl.get(), userName: userName.getOrElse(''), password: password.getOrElse(''), requireFixture: true, fixtureFile: portalFixture.get().asFile)
        List<String> warnings = []
        List<PublicationDescriptor> selected = ReleasePlanner.select(candidates, { ReleaseCoordinate c -> status.published(c) }, warnings)
        Set<String> scope = selected.findAll { it.projectPath }.collect { it.projectPath } as Set<String>
        boolean changed = true
        while (changed) {
            int previous = scope.size()
            candidates.findAll { it.projectPath }.each { PublicationDescriptor p ->
                if (p.dependencies.any { Map dep -> candidates.any { it.projectPath in scope && it.coordinate.groupId == dep.groupId && it.coordinate.artifactId == dep.artifactId } }) scope.add(p.projectPath)
            }
            changed = scope.size() != previous
        }
        Map<String, String> hashes = [:]
        ReleaseManifest.checkTrackedFlags(root)
        inspectedPoms.each { String path ->
            File input = new File(root, path)
            if (!input.isFile()) throw new IllegalStateException("Missing publication input: ${path}")
            hashes[path] = ReleaseChecksums.checksum(input, 'SHA-256')
        }
        Map manifest = [schemaVersion: 1, revision: configuredRevision.get(), hashes: hashes, modulePaths: modulePaths.get(),
            unpublishedProjectPaths: unpublishedProjectPaths.get(),
            versions: candidates.findAll { it.projectPath }.collectEntries { [it.projectPath, it.coordinate.version] },
            selected: selected.collect { it.toMap() }, excluded: candidates.findAll { !it.reasons.empty }.collect { it.toMap() },
            verificationScope: scope.sort(), warnings: warnings, created: java.time.Instant.now().toString()]
        ReleaseManifest.guard(root, manifest)
        ReleaseManifest.write(manifestFile.get().asFile, manifest)
        List<String> report = ["Release plan at ${manifest.revision}"]
        (manifest.selected + manifest.excluded).each { Map component ->
            report.add("${component.reasons.empty ? 'SELECT' : 'SKIP'} ${component.groupId}:${component.artifactId}:${component.version}".toString())
            component.reasons.each { report.add("  ${it}".toString()) }
            component.dependencies.each { Map edge ->
                report.add("  depends ${edge.groupId}:${edge.artifactId}:${edge.version} (${edge.origin})".toString())
            }
        }
        warnings.each { report.add("WARNING ${it}".toString()) }
        report.add("Verification scope: ${scope.sort().join(', ')}".toString())
        String readable = report.join('\n') + '\n'
        if (reportFile.present) {
            reportFile.get().asFile.parentFile.mkdirs()
            reportFile.get().asFile.setText(readable, 'UTF-8')
        }
        logger.lifecycle(readable)
    }
}
