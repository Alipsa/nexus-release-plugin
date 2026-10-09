package se.alipsa.gradle.plugin.release

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.*
import org.gradle.work.DisableCachingByDefault

/** Configuration-cache compatible aggregation without implicit build or upload dependencies. */
@DisableCachingByDefault(because = 'Release requires source guards')
abstract class BundleAllTask extends DefaultTask {
    @InputFile @PathSensitive(PathSensitivity.NONE)
    abstract RegularFileProperty getManifestFile()
    @InputDirectory @PathSensitive(PathSensitivity.RELATIVE)
    abstract DirectoryProperty getStagingDirectory()
    @Internal
    abstract DirectoryProperty getSourceDirectory()
    @OutputFile
    abstract RegularFileProperty getBundleFile()

    @OutputFile
    abstract RegularFileProperty getReceiptFile()

    BundleAllTask() { outputs.upToDateWhen { false } }

    /** Validates the source and all manifest artifacts and creates the combined ZIP. */
    @TaskAction
    void bundle() {
        Map manifest = ReleaseManifest.read(manifestFile.get().asFile)
        ReleaseManifest.guard(sourceDirectory.get().asFile, manifest)
        CombinedBundle.create(stagingDirectory.get().asFile, bundleFile.get().asFile, manifest.selected as List<Map>)
        ReleaseManifest.write(receiptFile.get().asFile, [bundleHash: ReleaseChecksums.checksum(bundleFile.get().asFile, 'SHA-256'), manifestHash: ReleaseChecksums.checksum(manifestFile.get().asFile, 'SHA-256')])
    }
}
