package se.alipsa.gradle.plugin.release

import groovy.json.JsonOutput
import groovy.json.JsonSlurper

/** Source guards and atomic, credential-free release records. */
class ReleaseManifest {
    /** Runs git without shell interpretation. */
    static String git(File root, String... arguments) { gitOutput(root, arguments).trim() }

    static String gitOutput(File root, String... arguments) {
        Process process = new ProcessBuilder(['git', '-C', root.absolutePath] + arguments.toList()).start()
        StringBuffer output = new StringBuffer()
        StringBuffer errors = new StringBuffer()
        process.waitForProcessOutput(output, errors)
        if (process.exitValue() != 0) throw new IllegalStateException('Git source validation failed: ' + errors.toString().trim())
        output.toString()
    }

    /** Rejects index flags that can hide working-tree changes, preserving arbitrary filenames. */
    static void checkTrackedFlags(File root) {
        gitOutput(root, 'ls-files', '-v', '-z').split('\u0000').each { String entry ->
            if (entry && (Character.isLowerCase(entry.charAt(0)) || entry.charAt(0) == ('S' as char))) {
                throw new IllegalStateException("Source guard requires clearing assume-unchanged/skip-worktree: ${entry.substring(2)}")
            }
        }
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
        checkTrackedFlags(root)
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
        File temporary = java.nio.file.Files.createTempFile(file.absoluteFile.parentFile.toPath(), file.name + '.', '.tmp').toFile()
        try {
            temporary.setText(JsonOutput.prettyPrint(JsonOutput.toJson(value)), 'UTF-8')
            java.nio.file.Files.move(temporary.toPath(), file.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        } finally { temporary.delete() }
    }
}
