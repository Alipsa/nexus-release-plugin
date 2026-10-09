package se.alipsa.gradle.plugin.release

import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Builds one validated allow-listed Maven-layout archive. */
class CombinedBundle {
    static final long MAX_BYTES = 1_000_000_000L

    /** Validates all components before writing any ZIP entry. */
    static void create(File staging, File target, List<Map> publications, long limit = MAX_BYTES) {
        if (publications.empty) throw new IllegalArgumentException('Cannot bundle an empty selection')
        Map<String, File> entries = [:]
        Map<String, Map<String, String>> checksums = [:]
        publications.each { Map publication ->
            def coordinate = new ReleaseCoordinate(publication.groupId, publication.artifactId, publication.version)
            coordinate.validate()
            String prefix = "${coordinate.artifactId}-${coordinate.version}"
            List<String> required = ["${prefix}.pom".toString()]
            if (publication.packaging != 'pom') {
                required.add("${prefix}.jar".toString())
                if (publication.sourcesAndJavadocRequired != false) required.addAll(["${prefix}-sources.jar".toString(), "${prefix}-javadoc.jar".toString()])
            }
            if (!(publication.files as Set).containsAll(required)) throw new IllegalArgumentException('Manifest omits required publication artifacts')
            publication.files.each { String name ->
                if (!(name ==~ /[A-Za-z0-9_.+-]+/) || !name.startsWith(prefix) || !(name.endsWith('.jar') || name.endsWith('.pom'))) {
                    throw new IllegalArgumentException('Unsafe or unsupported artifact filename')
                }
                String path = "${coordinate.path()}/${name}"
                [path, path + '.asc'].each { String entry ->
                    File source = new File(staging, entry)
                    if (!source.isFile() || source.length() == 0 || java.nio.file.Files.isSymbolicLink(source.toPath()) || !source.canonicalPath.startsWith(staging.canonicalPath + File.separator)) {
                        throw new IllegalArgumentException("Missing or unsafe release artifact: ${entry}")
                    }
                    if (entries.put(entry, source) != null) throw new IllegalArgumentException("Duplicate release path: ${entry}")
                    checksums[entry] = [:]
                    ['MD5', 'SHA-1'].each { String algorithm ->
                        File checksum = new File(source.path + '.' + algorithm.toLowerCase().replace('-', ''))
                        String hash = ReleaseChecksums.checksum(source, algorithm)
                        checksums[entry][algorithm] = hash
                        if (checksum.exists() && checksum.text.trim() != hash) throw new IllegalArgumentException("Incorrect checksum for ${entry}")
                    }
                }
            }
            File pomFile = new File(staging, "${coordinate.path()}/${prefix}.pom")
            ReleasePom.compare(pomFile, publication)
            List<String> metadataErrors = []
            BundleValidator.validatePomMetadata(pomFile.getText('UTF-8'), coordinate.key(), metadataErrors)
            if (!metadataErrors.empty) throw new IllegalArgumentException(metadataErrors.join('; '))
        }
        target.parentFile.mkdirs()
        File temporary = new File(target.path + '.tmp')
        try {
            temporary.withOutputStream { output ->
                OutputStream bounded = new FilterOutputStream(output) {
                    long bytesWritten = 0
                    @Override void write(int value) {
                        if (++bytesWritten > limit) throw new IllegalArgumentException('ZIP exceeds bundle limit')
                        output.write(value)
                    }
                    @Override void write(byte[] bytes, int offset, int length) {
                        bytesWritten += length
                        if (bytesWritten > limit) throw new IllegalArgumentException('ZIP exceeds bundle limit')
                        output.write(bytes, offset, length)
                    }
                }
                new ZipOutputStream(bounded).withCloseable { zip ->
                    entries.sort().each { String path, File source ->
                        zip.putNextEntry(new ZipEntry(path)); source.withInputStream { it.transferTo(zip) }; zip.closeEntry()
                        ['MD5', 'SHA-1'].each { String algorithm ->
                            zip.putNextEntry(new ZipEntry(path + '.' + algorithm.toLowerCase().replace('-', '')))
                            zip.write(checksums[path][algorithm].getBytes('UTF-8')); zip.closeEntry()
                        }
                    }
                }
            }
            if (temporary.length() > limit) throw new IllegalArgumentException('ZIP exceeds bundle limit')
            java.nio.file.Files.move(temporary.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        } finally { temporary.delete() }
    }
}
