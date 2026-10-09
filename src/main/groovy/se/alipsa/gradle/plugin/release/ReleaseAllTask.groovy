package se.alipsa.gradle.plugin.release

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import org.gradle.work.DisableCachingByDefault

/** Uploads a prepared combined bundle or resumes a persisted deployment. */
@DisableCachingByDefault(because = 'External deployment state')
abstract class ReleaseAllTask extends DefaultTask {
    @Internal abstract DirectoryProperty getSourceDirectory()
    @Internal abstract RegularFileProperty getManifestFile()
    @Internal abstract RegularFileProperty getBundleFile()
    @Internal abstract RegularFileProperty getRecordFile()
    @Internal abstract RegularFileProperty getReceiptFile()
    @Internal abstract RegularFileProperty getPortalFixture()
    @Internal abstract Property<String> getUserName()
    @Internal abstract Property<String> getPassword()
    @Input abstract Property<String> getPortalUrl()
    @Input abstract Property<String> getRepositoryUrl()
    @Input abstract Property<String> getResumeId()

    /** Checks source and availability before submitting, or resumes without building. */
    @TaskAction
    void release() {
        def client = new CentralPortalClient(logger, portalUrl.get(), userName.getOrElse(''), password.getOrElse(''))
        String id = resumeId.getOrElse('')
        File record = recordFile.get().asFile
        if (!id) {
            Map manifest = ReleaseManifest.read(manifestFile.get().asFile)
            ReleaseManifest.guard(sourceDirectory.get().asFile, manifest)
            Map receipt = ReleaseManifest.read(receiptFile.get().asFile)
            if (receipt.bundleHash != ReleaseChecksums.checksum(bundleFile.get().asFile, 'SHA-256') || receipt.manifestHash != ReleaseChecksums.checksum(manifestFile.get().asFile, 'SHA-256')) {
                throw new IllegalStateException('Bundle or manifest changed since validation; rebuild the bundle')
            }
            def status = new PublicationStatus(repositoryUrl: repositoryUrl.get(), portalUrl: portalUrl.get(), userName: userName.getOrElse(''), password: password.getOrElse(''), requireFixture: true, fixtureFile: portalFixture.get().asFile)
            id = CombinedDeployment.submit(record, bundleFile.get().asFile, manifest, status, client).deploymentId
        }
        CombinedDeployment.resume(record, id, client, { Map manifest ->
            manifest.selected.every { Map p ->
                def coordinate = new ReleaseCoordinate(p.groupId, p.artifactId, p.version)
                p.files.findAll { it.endsWith('.pom') || it == "${p.artifactId}-${p.version}.jar" }.every { String name ->
                    def connection = URI.create("${repositoryUrl.get()}/${coordinate.path()}/${name}").toURL().openConnection()
                    connection.connectTimeout = 15000; connection.readTimeout = 15000
                    try { connection.responseCode == 200 } finally { connection.disconnect() }
                }
            }
        })
        logger.lifecycle("Deployment ${id} is published and available")
    }
}
