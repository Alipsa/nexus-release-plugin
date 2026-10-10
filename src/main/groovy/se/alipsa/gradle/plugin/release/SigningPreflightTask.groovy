package se.alipsa.gradle.plugin.release

import groovy.json.JsonSlurper
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import org.gradle.work.DisableCachingByDefault

/** Checks configured signing before long verification builds, without signing anything. */
@DisableCachingByDefault(because = 'Preflight reports current signing configuration')
abstract class SigningPreflightTask extends DefaultTask {
    @Input abstract Property<String> getConfiguredSigningJson()
    @InputFile @PathSensitive(PathSensitivity.NONE) abstract RegularFileProperty getManifestFile()
    @Input abstract Property<Boolean> getStrict()

    SigningPreflightTask() { strict.convention(false) }

    /** Reports missing setup in dry-run; blocks preparation without signing configuration. */
    @TaskAction
    void checkSigning() {
        Map configured = new JsonSlurper().parseText(configuredSigningJson.get()) as Map
        Map manifest = ReleaseManifest.read(manifestFile.get().asFile)
        List<String> missing = manifest.selected.findAll { it.projectPath && configured[it.projectPath] != true }.collect { it.projectPath }
        if (!missing.empty) {
            String message = 'Signing is not configured for: ' + missing.join(', ') + '. Configure the existing Gradle signing properties before preparation.'
            if (strict.get()) throw new IllegalStateException(message)
            logger.warn(message)
        }
    }
}
