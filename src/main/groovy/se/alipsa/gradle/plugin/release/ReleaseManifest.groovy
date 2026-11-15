package se.alipsa.gradle.plugin.release

import groovy.json.JsonOutput
import groovy.json.JsonSlurper

/** Source guards and atomic, credential-free release records. */
class ReleaseManifest {
    /** Runs git without shell interpretation. */
    static String git(File root, String... arguments) { gitOutput(root, arguments).trim() }

    static String gitOutput(File root, String... arguments) {
        Process process = new ProcessBuilder(['git', '-C', root.absolutePath] + arguments.toList()).start()
        ByteArrayOutputStream output = new ByteArrayOutputStream()
        ByteArrayOutputStream errors = new ByteArrayOutputStream()
        process.waitForProcessOutput(output, errors)
        if (process.exitValue() != 0) throw new IllegalStateException('Git source validation failed: ' + errors.toString('UTF-8').trim())
        output.toString('UTF-8')
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

    /** Reads a complete snapshot, closing the file before parsing. */
    static Map read(File file) {
        // NIO opens Windows files with delete sharing, allowing an atomic replacement
        // while a reader still holds the old file. JsonSlurper.parse(File) uses a
        // FileInputStream, whose Windows handle prevents that replacement.
        byte[] snapshot = java.nio.file.Files.readAllBytes(file.toPath())
        new JsonSlurper().parse(snapshot, 'UTF-8') as Map
    }

    /** Atomically replaces a JSON file in its output directory. */
    static void write(File file, Map value) {
        file.parentFile.mkdirs()
        File temporary = java.nio.file.Files.createTempFile(file.absoluteFile.parentFile.toPath(), file.name + '.', '.tmp').toFile()
        try {
            temporary.setText(JsonOutput.prettyPrint(JsonOutput.toJson(value)), 'UTF-8')
            replaceAtomically(temporary.toPath(), file.toPath())
        } finally { temporary.delete() }
    }

    private static void replaceAtomically(java.nio.file.Path source, java.nio.file.Path target) {
        for (int attempt = 0; ; attempt++) {
            try {
                java.nio.file.Files.move(source, target, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                return
            } catch (java.nio.file.FileSystemException failure) {
                // Windows can briefly deny replacement during another rename or an
                // external read without delete sharing. Keep the old journal intact
                // and retry only the atomic move, for at most 1.27 seconds in total.
                boolean sharingViolation = failure.reason?.toLowerCase(Locale.ROOT)?.contains('being used by another process')
                if (attempt >= 7 || !System.getProperty('os.name').toLowerCase(Locale.ROOT).contains('windows') ||
                    !(failure instanceof java.nio.file.AccessDeniedException || sharingViolation)) throw failure
                try {
                    Thread.sleep(10L << attempt)
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt()
                    throw interrupted
                }
            }
        }
    }
}
