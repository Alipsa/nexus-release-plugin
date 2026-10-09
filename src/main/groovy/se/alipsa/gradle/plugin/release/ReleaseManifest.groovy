package se.alipsa.gradle.plugin.release

import groovy.json.JsonOutput
import groovy.json.JsonSlurper

/** Source guards and atomic, credential-free release records. */
class ReleaseManifest {
    /** Runs git without shell interpretation. */
    static String git(File root, String... arguments) {
        Process process = new ProcessBuilder(['git', '-C', root.absolutePath] + arguments.toList()).redirectErrorStream(true).start()
        String output = process.inputStream.getText('UTF-8').trim()
        if (process.waitFor() != 0) throw new IllegalStateException('Git source validation failed')
        output
    }

    /** Checks revision, cleanliness, input hashes and effective publication versions. */
    static void guard(File root, Map manifest, Map<String, String> versions = [:]) {
        (manifest.selected ?: []).each { Map component ->
            new ReleaseCoordinate(component.groupId, component.artifactId, component.version).validate()
            if (component.version.endsWith('-SNAPSHOT')) throw new IllegalStateException('Manifest contains a SNAPSHOT publication')
        }
        if (git(root, 'rev-parse', 'HEAD') != manifest.revision || git(root, 'status', '--porcelain')) {
            throw new IllegalStateException('Stale manifest: HEAD changed or working tree is dirty')
        }
        manifest.hashes.each { String path, String hash ->
            File source = new File(root, path)
            if (!source.isFile() || ReleaseChecksums.checksum(source, 'SHA-256') != hash) throw new IllegalStateException("Stale manifest input: ${path}")
        }
        versions.each { String path, String version ->
            if (manifest.versions[path] != version) throw new IllegalStateException("Stale publication version: ${path}")
        }
    }

    /** Reads the persisted JSON manifest. */
    static Map read(File file) { new JsonSlurper().parse(file) as Map }

    /** Atomically replaces a JSON file in its output directory. */
    static void write(File file, Map value) {
        file.parentFile.mkdirs()
        File temporary = new File(file.path + '.tmp')
        temporary.setText(JsonOutput.prettyPrint(JsonOutput.toJson(value)), 'UTF-8')
        java.nio.file.Files.move(temporary.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    }
}
