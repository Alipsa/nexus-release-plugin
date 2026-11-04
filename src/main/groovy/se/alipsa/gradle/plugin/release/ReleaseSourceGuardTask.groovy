package se.alipsa.gradle.plugin.release

import groovy.json.JsonSlurper
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import org.gradle.work.DisableCachingByDefault

/** Rechecks source in the execution phase, including when the configuration cache is reused. */
@DisableCachingByDefault(because = 'Git state must be checked every invocation')
abstract class ReleaseSourceGuardTask extends DefaultTask {
    @InputFile @PathSensitive(PathSensitivity.NONE) abstract RegularFileProperty getManifestFile()
    @Internal abstract DirectoryProperty getSourceDirectory()
    @Input abstract Property<String> getVersionsJson()
    @Input abstract org.gradle.api.provider.ListProperty<String> getModulePaths()
    @Input abstract org.gradle.api.provider.ListProperty<String> getUnpublishedProjectPaths()

    ReleaseSourceGuardTask() {
        modulePaths.convention([])
        unpublishedProjectPaths.convention([])
        outputs.upToDateWhen { false }
    }

    /** Rejects stale manifests before any preparation task can run. */
    @TaskAction
    void guard() {
        Map manifest = ReleaseManifest.read(manifestFile.get().asFile)
        if (!modulePaths.get().empty && manifest.modulePaths != modulePaths.get()) {
            throw new IllegalStateException('Configured modules changed; run releaseAllPlan again before bundling')
        }
        if (!modulePaths.get().empty && (manifest.unpublishedProjectPaths ?: []) != unpublishedProjectPaths.get()) {
            throw new IllegalStateException('Unpublished project dependency opt-outs changed; run releaseAllPlan again before bundling')
        }
        ReleaseManifest.guard(sourceDirectory.get().asFile, manifest, new JsonSlurper().parseText(versionsJson.get()) as Map<String, String>)
    }
}
