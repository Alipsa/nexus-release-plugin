package se.alipsa.gradle.plugin.release

import groovy.transform.CompileStatic
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Task that creates a release bundle for publishing to Maven Central.
 * This task is configuration cache compatible.
 */
@CompileStatic
@DisableCachingByDefault(because = "Bundle creation depends on external publication state")
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

    @Internal
    abstract MapProperty<String, File> getArtifactEntries()

    @Input
    List<String> getArtifactEntryNames() { artifactEntries.get().keySet().toList().sort() }

    @InputFiles
    @PathSensitive(PathSensitivity.NONE)
    abstract ConfigurableFileCollection getArtifactInputFiles()

    @Internal
    abstract RegularFileProperty getModuleMetadataFile()

    @InputFiles
    @Optional
    @PathSensitive(PathSensitivity.NONE)
    abstract ConfigurableFileCollection getModuleMetadataInput()

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
        Map<String, File> artifacts = artifactEntries.get()

        File publicationDir = publicationDirectory.get().asFile
        File pomFile = new File(publicationDir, "pom-default.xml")
        if (!pomFile.exists()) {
            throw new GradleException("Expected POM file at ${pomFile.absolutePath} but it was not found. Has 'publishToMavenLocal' completed successfully?")
        }

        File metadata = moduleMetadataFile.orNull?.asFile
        boolean marker = pomFile.getText('UTF-8').contains('published-with-gradle-metadata')
        if (metadata?.exists() && !marker) {
            throw new GradleException('Module metadata state is inconsistent with the POM')
        }
        if (marker && !metadata?.exists()) {
            throw new GradleException('The POM advertises Gradle metadata, but no metadata was produced in this build. Disable the metadata task (enabled = false) instead of skipping it, or let it run.')
        }
        if (metadata?.exists()) {
            artifacts = new LinkedHashMap<String, File>(artifacts)
            String metadataName = "${artifactIdValue}-${versionValue}.module"
            if (artifacts.put(metadataName, metadata) != null) throw new GradleException("Duplicate bundle entry: ${metadataName}")
        }

        // Create the bundle zip
        createBundleZip(zipFile, groupIdValue, artifactIdValue, versionValue, artifacts, publicationDir)

        if (!zipFile.exists()) {
            throw new GradleException("Failed to create release bundle for publication ${publicationNameValue}")
        }
    }

    private void createBundleZip(File zipFile, String groupIdValue, String artifactIdValue,
                                  String versionValue, Map<String, File> artifacts, File publicationDir) {
        String mavenPathPrefix = "${groupIdValue.replace('.', '/')}/${artifactIdValue}/${versionValue}/"

        if (!publicationDir.exists()) {
            logger.lifecycle("No publication directory found at $publicationDir")
            return
        }

        boolean missingFilesDetected = false
        try (ZipOutputStream zipOut = new ZipOutputStream(new FileOutputStream(zipFile))) {

            // 1. Add all publication artifacts (e.g. JARs and their signatures)
            artifacts.each { String name, File file ->
                if (!file.exists()) {
                    logger.warn("Artifact file does not exist: $file")
                    return
                }


                String zipEntryName = mavenPathPrefix + name
                logger.debug("Adding file: $zipEntryName")
                zipOut.putNextEntry(new ZipEntry(zipEntryName))
                zipOut << file.bytes
                zipOut.closeEntry()

                missingFilesDetected = addFileToZip(new File(file.absolutePath + ".asc"), zipEntryName + ".asc", zipOut) || missingFilesDetected
                CHECKSUM_ALGOS.each { String algo ->
                    addChecksumToZip(file, algo, "${zipEntryName}.${checksumExtension(algo)}", zipOut)
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
                missingFilesDetected = addFileToZip(new File(publicationDir, "pom-default.xml.asc"), pomEntryName + ".asc", zipOut) || missingFilesDetected
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
        ReleaseChecksums.checksum(file, algo)
    }

    private static String checksumExtension(String algo) {
        algo.toLowerCase().replace('-', '')
    }
}
