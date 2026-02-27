package se.alipsa.gradle.plugin.release

import groovy.transform.CompileStatic
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

import java.security.DigestOutputStream
import java.security.MessageDigest
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Task that creates a release bundle for publishing to Maven Central.
 * This task is configuration cache compatible.
 */
@CompileStatic
abstract class BundleTask extends DefaultTask {

    static final List<String> CHECKSUM_ALGOS = ['MD5', 'SHA-1']

    @Input
    abstract Property<String> getGroupId()

    @Input
    abstract Property<String> getArtifactId()

    @Input
    abstract Property<String> getVersion()

    @Input
    abstract Property<String> getPublicationName()

    @InputDirectory
    @PathSensitive(PathSensitivity.RELATIVE)
    abstract DirectoryProperty getPublicationDirectory()

    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    abstract ListProperty<File> getArtifactFiles()

    @OutputFile
    abstract RegularFileProperty getBundleFile()

    BundleTask() {
        group = 'publishing'
        description = 'Create a release bundle that can be used to publish to Maven Central'
    }

    @TaskAction
    void createBundle() {
        File zipFile = bundleFile.get().asFile
        zipFile.parentFile.mkdirs()

        String groupIdValue = groupId.get()
        String artifactIdValue = artifactId.get()
        String versionValue = version.get()
        String publicationNameValue = publicationName.get()
        List<File> artifacts = artifactFiles.get()

        File publicationDir = publicationDirectory.get().asFile
        File pomFile = new File(publicationDir, "pom-default.xml")
        if (!pomFile.exists()) {
            throw new GradleException("Expected POM file at ${pomFile.absolutePath} but it was not found. Has 'publishToMavenLocal' completed successfully?")
        }

        // Create the bundle zip
        createBundleZip(zipFile, groupIdValue, artifactIdValue, versionValue, artifacts, publicationDir)

        if (!zipFile.exists()) {
            throw new GradleException("Failed to create release bundle for publication ${publicationNameValue}")
        }
    }

    private void createBundleZip(File zipFile, String groupIdValue, String artifactIdValue,
                                  String versionValue, List<File> artifacts, File publicationDir) {
        String mavenPathPrefix = "${groupIdValue.replace('.', '/')}/${artifactIdValue}/${versionValue}/"

        if (!publicationDir.exists()) {
            logger.lifecycle("No publication directory found at $publicationDir")
            return
        }

        boolean missingFilesDetected = false
        try (ZipOutputStream zipOut = new ZipOutputStream(new FileOutputStream(zipFile))) {

            // 1. Add all publication artifacts (e.g. JARs and their signatures)
            artifacts.each { File file ->
                if (!file.exists()) {
                    logger.warn("Artifact file does not exist: $file")
                    return
                }

                String name = file.name
                if (!name.endsWith('.jar')) return

                String zipEntryName = mavenPathPrefix + name
                logger.debug("Adding file: $zipEntryName")
                zipOut.putNextEntry(new ZipEntry(zipEntryName))
                zipOut << file.bytes
                zipOut.closeEntry()

                missingFilesDetected = missingFilesDetected || addFileToZip(file, ".asc", mavenPathPrefix, zipOut)
                CHECKSUM_ALGOS.each { String algo ->
                    addChecksumToZip(file, algo, "${mavenPathPrefix}${file.name}.${checksumExtension(algo)}", zipOut)
                }
            }

            // 2. Add the POM manually (it may not be in artifacts)
            def pomName = "${artifactIdValue}-${versionValue}.pom"
            File pomFile = new File(publicationDir, "pom-default.xml")
            if (pomFile.exists()) {
                String pomEntryName = "${groupIdValue.replace('.', '/')}/${artifactIdValue}/${versionValue}/${pomName}"
                logger.debug("Adding POM: $pomEntryName")
                zipOut.putNextEntry(new ZipEntry(pomEntryName))
                zipOut << pomFile.bytes
                zipOut.closeEntry()
                missingFilesDetected = missingFilesDetected || addFileToZip(new File(publicationDir, "pom-default.xml.asc"), pomEntryName + ".asc", zipOut)
                CHECKSUM_ALGOS.each { String algo ->
                    addChecksumToZip(pomFile, algo, "${pomEntryName}.${checksumExtension(algo)}", zipOut)
                }
            } else {
                missingFilesDetected = true
            }
        }
        logger.lifecycle("Bundle created at $zipFile")
        if (missingFilesDetected) {
            logger.warn("Bundle is not complete, some files were missing, this bundle is probably not publishable!")
        }
    }

    private boolean addFileToZip(File sourceFile, String targetPath, ZipOutputStream zipOut) throws IOException {
        if (sourceFile.exists()) {
            logger.debug("Adding: $targetPath")
            zipOut.putNextEntry(new ZipEntry(targetPath))
            zipOut << sourceFile.bytes
            zipOut.closeEntry()
            return false
        } else {
            logger.warn("Cannot add file: $sourceFile to zip, it does not exist")
            return true
        }
    }

    private boolean addFileToZip(File baseFile, String suffix, String mavenPathPrefix, ZipOutputStream zipOut) {
        File sourceFile = new File(baseFile.absolutePath + suffix)
        String targetPath = mavenPathPrefix + baseFile.name + suffix
        addFileToZip(sourceFile, targetPath, zipOut)
    }

    private void addChecksumToZip(File sourceFile, String algo, String targetPath, ZipOutputStream zipOut) throws IOException {
        logger.debug("Adding generated checksum: ${targetPath}")
        String hash = checksumFor(sourceFile, algo)
        zipOut.putNextEntry(new ZipEntry(targetPath))
        zipOut.write(hash.getBytes(StandardCharsets.UTF_8))
        zipOut.closeEntry()
    }

    private static String checksumFor(File file, String algo) {
        def digest = MessageDigest.getInstance(algo)
        file.withInputStream { is ->
            new DigestOutputStream(OutputStream.nullOutputStream(), digest)
                .withCloseable { dos -> is.transferTo(dos) }
        }
        digest.digest().encodeHex().toString()
    }

    private static String checksumExtension(String algo) {
        algo.toLowerCase().replace('-', '')
    }
}
