package se.alipsa.gradle.plugin.release

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.*
import org.gradle.work.DisableCachingByDefault

/** Builds Maven publications locally, preserving user signing settings and isolating downloads. */
@DisableCachingByDefault(because = 'Signs local release artifacts')
abstract class PrepareMavenTask extends DefaultTask {
    @Internal abstract DirectoryProperty getSourceDirectory()
    @InputFile @PathSensitive(PathSensitivity.NONE) abstract RegularFileProperty getManifestFile()
    @Internal abstract DirectoryProperty getStagingDirectory()
    @Internal abstract DirectoryProperty getLocalRepository()
    @Internal abstract DirectoryProperty getMavenWorkDirectory()
    @InputFile @PathSensitive(PathSensitivity.RELATIVE) abstract RegularFileProperty getVerifierFile()
    @InputFile @PathSensitive(PathSensitivity.RELATIVE) abstract RegularFileProperty getSettingsHelperFile()
    @Internal abstract RegularFileProperty getUserSettingsFile()

    /** Runs only install, never deploy; validates flattened dependencies before staging. */
    @TaskAction
    void prepare() {
        File root = sourceDirectory.get().asFile
        Map manifest = ReleaseManifest.read(manifestFile.get().asFile)
        ReleaseManifest.guard(root, manifest)
        File stage = stagingDirectory.get().asFile
        File cache = localRepository.get().asFile
        cache.parentFile.mkdirs()
        File settingsFile = java.nio.file.Files.createTempFile(cache.parentFile.toPath(), 'signing-settings-', '.xml',
            java.nio.file.attribute.PosixFilePermissions.asFileAttribute(java.nio.file.attribute.PosixFilePermissions.fromString('rw-------'))).toFile()
        File original = userSettingsFile.present ? userSettingsFile.get().asFile : new File(System.getProperty('user.home'), '.m2/settings.xml')
        try {
            PrepareMavenTask.run(root, ['groovy', settingsHelperFile.get().asFile.path, original.path, stage.path, settingsFile.path, 'matrix-release-staging'], logger)
            boolean mavenSelected = manifest.selected.any { !it.projectPath }
            if (!mavenSelected) return
            // Run existing verifier against staged artifacts, without publishing or cleaning staging.
            PrepareMavenTask.run(root, ['bash', verifierFile.get().asFile.path, '--manifest', manifestFile.get().asFile.path, '--staging', stage.path, '--local', cache.path], logger)
            manifest.selected.findAll { !it.projectPath }.sort { it.role == 'bom' ? 0 : 1 }.each { Map component ->
                PrepareMavenTask.run(mavenWorkDirectory.get().asFile, ['mvn', '-s', settingsFile.path, "-Dmaven.repo.local=${cache}", '-Prelease', '-f', new File(root, component.pomFile).path, 'install'], logger)
                def coordinate = new ReleaseCoordinate(component.groupId, component.artifactId, component.version)
                File source = new File(cache, coordinate.path())
                File destination = new File(stage, coordinate.path())
                destination.mkdirs()
                component.files.each { String name ->
                    [name, name + '.asc'].each { String fileName ->
                        File artifact = new File(source, fileName)
                        if (!artifact.isFile()) throw new IllegalStateException("Maven release output missing: ${fileName}")
                        java.nio.file.Files.copy(artifact.toPath(), new File(destination, fileName).toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                    }
                }
                ReleasePom.compare(new File(destination, "${component.artifactId}-${component.version}.pom"), component)
            }
        } finally { settingsFile.delete() }
    }

    private static void run(File directory, List<String> command, org.gradle.api.logging.Logger taskLogger) {
        Process process = new ProcessBuilder(command.collect { it.toString() }).directory(directory).redirectErrorStream(true).start()
        process.inputStream.withReader('UTF-8') { reader -> reader.eachLine { line -> taskLogger.lifecycle(line) } }
        if (process.waitFor() != 0) throw new IllegalStateException('Maven preparation/API verification failed; no upload attempted. If a Maven mirror intercepts the staging file remote, exclude matrix-release-staging from mirrorOf.')
    }
}
