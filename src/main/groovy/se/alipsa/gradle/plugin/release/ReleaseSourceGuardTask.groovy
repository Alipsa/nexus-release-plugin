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

    ReleaseSourceGuardTask() { outputs.upToDateWhen { false } }

    /** Rejects stale manifests before any preparation task can run. */
    @TaskAction
    void guard() {
        ReleaseManifest.guard(sourceDirectory.get().asFile, ReleaseManifest.read(manifestFile.get().asFile), new JsonSlurper().parseText(versionsJson.get()) as Map<String, String>)
    }
}
