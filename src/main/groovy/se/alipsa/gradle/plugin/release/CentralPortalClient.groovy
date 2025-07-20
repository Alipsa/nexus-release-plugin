package se.alipsa.gradle.plugin.release

import groovy.json.JsonSlurper
import groovy.transform.CompileDynamic
import groovy.transform.CompileStatic
import org.gradle.api.Project
import org.gradle.api.logging.Logger
import org.gradle.api.publish.maven.MavenPublication

import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@CompileStatic
class CentralPortalClient extends WsClient {

  static final String CENTRAL_PORTAL_URL = 'https://central.sonatype.com/api/v1'
  String publishUrl
  Logger log
  Project project
  String userName
  String password
  MavenPublication mavenPublication

  CentralPortalClient(Project project, NexusReleasePluginExtension extension) {
    this.log = project.logger
    publishUrl = extension.nexusUrl.getOrElse(CentralPortalClient.CENTRAL_PORTAL_URL)
    this.userName = extension.userName.getOrNull()
    this.password = extension.password.getOrNull()
    this.mavenPublication = extension.mavenPublication.getOrNull()
    this.project = project
  }

  Map<String, Object> get(String endpoint, String username, String password) throws IOException {
    String urlString = "${publishUrl}/${endpoint}"
    return super.get(urlString, username, password)
  }

  String auth(String username, String password) {
    return "Bearer " + Base64.getEncoder().encodeToString((username + ":" + password).getBytes())
  }

  void createBundle(File zipFile) throws IOException {
    String groupId = mavenPublication.groupId
    String artifactId = mavenPublication.artifactId
    String version = mavenPublication.version

    String mavenPathPrefix = "${groupId.replace('.', '/')}/${artifactId}/${version}/"

    File publicationDir = project.layout.buildDirectory.dir("publications/${mavenPublication.name}").get().asFile
    if (!publicationDir.exists()) {
      project.logger.lifecycle("No publication directory found at $publicationDir")
      return
    }
    boolean missingFilesDetected = false
    try (ZipOutputStream zipOut = new ZipOutputStream(new FileOutputStream(zipFile))) {

      // 1. Add all publication artifacts (e.g. JARs and their signatures)
      mavenPublication.artifacts.each { artifact ->
        File file = artifact.file
        if (!file.exists()) {
          project.logger.warn("Artifact file does not exist: $file")
          return
        }

        String name = file.name
        if (!name.matches(/.*\.(jar)(\.asc|\.md5|\.sha1)?$/)) return

        String zipEntryName = mavenPathPrefix + name
        project.logger.debug("Adding file: $zipEntryName")
        zipOut.putNextEntry(new ZipEntry(zipEntryName))
        zipOut << file.bytes
        zipOut.closeEntry()

        missingFilesDetected = missingFilesDetected || addFileToZip(file, ".asc", mavenPathPrefix, zipOut)
        missingFilesDetected = missingFilesDetected || addFileToZip(file, ".md5", mavenPathPrefix, zipOut)
        missingFilesDetected = missingFilesDetected || addFileToZip(file, ".sha1", mavenPathPrefix, zipOut)
      }

      // 2. Add the POM manually (it may not be in artifacts)
      def pomName = "${artifactId}-${version}.pom"
      File pomFile = new File(publicationDir, "pom-default.xml")
      if (pomFile.exists()) {
        String pomEntryName = "${groupId.replace('.', '/')}/${artifactId}/${version}/${pomName}"
        project.logger.debug("Adding POM: $pomEntryName")
        zipOut.putNextEntry(new ZipEntry(pomEntryName))
        zipOut << pomFile.bytes
        zipOut.closeEntry()
        missingFilesDetected = missingFilesDetected || addFileToZip(new File(publicationDir, "pom-default.xml.asc"), pomEntryName + ".asc", zipOut)
        missingFilesDetected = missingFilesDetected || addFileToZip(new File(publicationDir, "pom-default.xml.md5"), pomEntryName + ".md5", zipOut)
        missingFilesDetected = missingFilesDetected || addFileToZip(new File(publicationDir, "pom-default.xml.sha1"), pomEntryName + ".sha1", zipOut)
      } else {
        missingFilesDetected = true
      }
    }
    project.logger.lifecycle("Bundle created at $zipFile")
    if (missingFilesDetected) {
      project.logger.warn("Bundle is not complete, some files were missing, this bundle is probably not publishable!")
    }
  }

  private boolean addFileToZip(File sourceFile, String targetPath, ZipOutputStream zipOut) throws IOException {
    if (sourceFile.exists()) {
      project.logger.debug("Adding: $targetPath")
      zipOut.putNextEntry(new ZipEntry(targetPath))
      zipOut << sourceFile.bytes
      zipOut.closeEntry()
      return false
    } else {
      project.logger.warn("Cannot add file: $sourceFile to zip, it does not exist")
      return true
    }
  }
  private boolean addFileToZip(File baseFile, String suffix, String mavenPathPrefix, ZipOutputStream zipOut) {
    File sourceFile = new File(baseFile.absolutePath + suffix)
    String targetPath = mavenPathPrefix + baseFile.name + suffix
    addFileToZip(sourceFile, targetPath, zipOut)
  }

  /**
   * Uploads the given file to Central portal.
   * $ curl --request POST \
   * --verbose \
   * --header 'Authorization: Bearer ZXhhbXBsZV91c2VybmFtZTpleGFtcGxlX3Bhc3N3b3Jk' \
   * --form bundle=@central-bundle.zip \
   * https://central.sonatype.com/api/v1/publisher/upload?publishingType=AUTOMATIC
   *
   * @param file The file to upload.
   * @return The deployment ID or null if the upload failed.
   */
  String upload(File file) {
    String urlString = "${publishUrl}/publisher/upload?publishingType=AUTOMATIC"
    log.debug("Post multipart to $urlString")
    Map<String, Object> result = postMultipart(urlString, file, userName, password)
    project.logger.debug("Upload result: $result")
    return result.get(BODY)
  }

  /**
   * Gets the status of a deployment by its ID.
   * $ curl --request POST \
   * --verbose \
   * --header 'Authorization: Bearer ZXhhbXBsZV91c2VybmFtZTpleGFtcGxlX3Bhca3N3b3JkCg==' \
   * 'https://central.sonatype.com/api/v1/publisher/status?id=28570f16-da32-4c14-bd2e-c1acc0782365'
   *
   * Response:
   * {
   * "deploymentId": "28570f16-da32-4c14-bd2e-c1acc0782365",
   * "deploymentName": "central-bundle.zip",
   * "deploymentState": "PUBLISHED",
   * "purls": [
   *  "pkg:maven/com.sonatype.central.example/example_java_project@0.0.7"
   * ]
   * }
   * @param deploymentId The ID of the deployment.
   * @return The status of the deployment or null if not found.
   */
  String getStatus(String deploymentId) {
    String endPoint = "$publishUrl/publisher/status?id=${deploymentId}"
    Map<String, Object> result = post(endPoint, null, userName, password)
    //project.logger.lifecycle("getStatus result: $result")
    def body
    try {
      body = new JsonSlurper().parseText(result.get(BODY) as String) as Map
    } catch (Exception e) {
      project.logger.error("Failed to parse JSON response for deployment ID ${deploymentId}: ${e.message}")
      return null
    }
    body?.deploymentState ?: null
  }
}
