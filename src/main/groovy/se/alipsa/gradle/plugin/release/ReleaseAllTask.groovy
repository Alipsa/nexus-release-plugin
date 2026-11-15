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
    /** Upload the saved ZIP without bundling; ignored when resumeId is set. */
    @Input @Optional abstract Property<Boolean> getUseExistingBundle()
    /** Reviewed SHA-256 anchor, required in existing-bundle mode; ignored during resume. */
    @Input @Optional abstract Property<String> getExpectedBundleSha256()

    /** Reviewed manifest anchor, required in existing-bundle mode; ignored during resume. */
    @Input @Optional abstract Property<String> getExpectedManifestSha256()

    /** Validates reviewed evidence inputs before any build tasks execute. */
    void validateDigestInputs() {
        if (resumeId.getOrElse('')) return
        boolean existing = useExistingBundle.getOrElse(false)
        [expectedBundleSha256: expectedBundleSha256.getOrElse(''),
         expectedManifestSha256: expectedManifestSha256.getOrElse('')].each { String name, String value ->
            if (value && !(value ==~ /[0-9a-fA-F]{64}/)) {
                throw new IllegalStateException("${name} must be a 64-character hexadecimal SHA-256")
            }
            if (existing && !value) {
                throw new IllegalStateException("Existing-bundle mode requires ${name} from reviewed evidence")
            }
            if (!existing && value) {
                throw new IllegalStateException("${name} is supported only in existing-bundle mode; set useExistingBundle or remove the digest")
            }
        }
    }

    /** Checks source and availability before submitting, or resumes without building. */
    @TaskAction
    void release() {
        def client = new CentralPortalClient(logger, portalUrl.get(), userName.getOrElse(''), password.getOrElse(''))
        String id = resumeId.getOrElse('')
        File record = recordFile.get().asFile
        if (!id) {
            validateDigestInputs()
            String expected = expectedBundleSha256.getOrElse('').toLowerCase(Locale.ROOT)
            String expectedManifest = expectedManifestSha256.getOrElse('').toLowerCase(Locale.ROOT)
            [manifestFile.get().asFile, receiptFile.get().asFile, bundleFile.get().asFile].each { File required ->
                if (!required.isFile()) {
                    String guidance = useExistingBundle.getOrElse(false) ?
                        'run releaseAllPlan and bundleAll separately before existing-bundle upload' :
                        'check that releaseAll and bundleAll use the same manifest, receipt and bundle paths'
                    throw new IllegalStateException("Prepared release file missing: ${required.name}; ${guidance}")
                }
            }
            // Hash and parse the same bytes, so the probed graph is the reviewed manifest.
            byte[] manifestBytes = manifestFile.get().asFile.bytes
            String manifestHash = ReleaseChecksums.checksum(manifestBytes, 'SHA-256')
            Map receipt = ReleaseManifest.read(receiptFile.get().asFile)
            if (expectedManifest && (receipt.manifestHash != expectedManifest || manifestHash != expectedManifest)) {
                throw new IllegalStateException('Manifest or receipt differs from expectedManifestSha256; no upload attempted')
            }
            Map manifest = new groovy.json.JsonSlurper().parse(manifestBytes) as Map
            ReleaseManifest.guard(sourceDirectory.get().asFile, manifest)
            String verifiedHash = ReleaseChecksums.checksum(bundleFile.get().asFile, 'SHA-256')
            if (expected && (receipt.bundleHash != expected || verifiedHash != expected)) {
                throw new IllegalStateException('Bundle or receipt differs from expectedBundleSha256; no upload attempted')
            }
            if (receipt.bundleHash != verifiedHash || receipt.manifestHash != manifestHash) {
                throw new IllegalStateException('Bundle or manifest changed since validation; rebuild the bundle')
            }
            def status = new PublicationStatus(repositoryUrl: repositoryUrl.get(), portalUrl: portalUrl.get(), userName: userName.getOrElse(''), password: password.getOrElse(''), requireFixture: true, fixtureFile: portalFixture.get().asFile)
            id = CombinedDeployment.submit(record, bundleFile.get().asFile, manifest, status, client, verifiedHash).deploymentId
        }
        CombinedDeployment.resume(record, id, client, { Map manifest ->
            manifest.selected.every { Map p ->
                def coordinate = new ReleaseCoordinate(p.groupId, p.artifactId, p.version)
                p.files.findAll { it.endsWith('.pom') || it == "${p.artifactId}-${p.version}.jar" }.every { String name ->
                    def connection = URI.create("${repositoryUrl.get()}/${coordinate.path()}/${name}").toURL().openConnection()
                    connection.requestMethod = 'HEAD'
                    connection.connectTimeout = 15000; connection.readTimeout = 15000
                    try { connection.responseCode == 200 } finally { connection.disconnect() }
                }
            }
        })
        logger.lifecycle("Deployment ${id} is published and available")
    }
}
