package se.alipsa.gradle.plugin.release

import groovy.xml.XmlSlurper
import groovy.xml.slurpersupport.GPathResult

import java.util.zip.ZipEntry
import java.util.zip.ZipFile

class BundleValidator {

    static List<String> validateBundle(
        File zipFile,
        String groupId,
        String artifactId,
        String version,
        String userName,
        String password
    ) {
        List<String> errors = []
        validateCredentials(userName, password, errors)
        validateZip(zipFile, groupId, artifactId, version, errors)
        return errors
    }

    private static void validateCredentials(String userName, String password, List<String> errors) {
        if (isBlank(userName) || isBlank(password)) {
            errors.add(
                """Credentials not configured. Add the following to ~/.gradle/gradle.properties:
  sonatypeUsername=<your user token>
  sonatypePassword=<your password token>""".stripIndent()
            )
        }
    }

    private static void validateZip(File zipFile, String groupId, String artifactId, String version, List<String> errors) {
        if (!zipFile.exists()) {
            errors.add("Expected bundle at ${zipFile.absolutePath} but it was not found.")
            return
        }

        String basePath = "${groupId.replace('.', '/')}/${artifactId}/${version}/"
        String artifactPrefix = "${artifactId}-${version}"
        List<String> requiredEntries = [
            "${basePath}${artifactPrefix}.pom",
            "${basePath}${artifactPrefix}.pom.asc",
            "${basePath}${artifactPrefix}.jar",
            "${basePath}${artifactPrefix}.jar.asc",
            "${basePath}${artifactPrefix}-sources.jar",
            "${basePath}${artifactPrefix}-sources.jar.asc",
            "${basePath}${artifactPrefix}-javadoc.jar",
            "${basePath}${artifactPrefix}-javadoc.jar.asc"
        ]

        try (ZipFile zip = new ZipFile(zipFile)) {
            Set<String> names = zip.entries().collect { ZipEntry e -> e.name } as Set<String>
            requiredEntries.each { String requiredEntry ->
                if (!names.contains(requiredEntry)) {
                    errors.add("Missing required bundle entry: ${requiredEntry}")
                }
            }

            validateFileNameConsistency(names, artifactPrefix, artifactId, errors)
            validatePom(zip, "${basePath}${artifactPrefix}.pom", errors)
        } catch (IOException e) {
            errors.add("Failed to inspect bundle ${zipFile.absolutePath}: ${e.message}")
        }
    }

    private static void validateFileNameConsistency(Set<String> names, String artifactPrefix, String artifactId, List<String> errors) {
        names.each { String name ->
            if (!name.endsWith('.jar') && !name.endsWith('.pom')) {
                return
            }
            String fileName = name.tokenize('/').last()
            if (!fileName.startsWith(artifactPrefix)) {
                errors.add(
                    """Filename mismatch: '${fileName}' does not start with '${artifactPrefix}'
  -> Make sure base.archivesName = '${artifactId}' matches artifactId = '${artifactId}'""".stripIndent()
                )
            }
        }
    }

    private static void validatePom(ZipFile zip, String expectedPomPath, List<String> errors) {
        ZipEntry pomEntry = zip.getEntry(expectedPomPath)
        if (pomEntry == null) {
            errors.add("POM not found in bundle at ${expectedPomPath}")
            return
        }

        String pomText = zip.getInputStream(pomEntry).getText('UTF-8')
        GPathResult pom
        try {
            pom = new XmlSlurper(false, false).parseText(pomText)
        } catch (Exception e) {
            errors.add("Failed to parse POM ${expectedPomPath}: ${e.message}")
            return
        }

        checkTextField(pom, 'name', '<name>', errors)
        checkTextField(pom, 'description', '<description>', errors)
        checkTextField(pom, 'url', '<url>', errors)

        def licenses = pom.licenses.license
        if (licenses.size() == 0) {
            errors.add("POM is missing required field: <licenses>")
        } else {
            boolean validLicenseFound = false
            licenses.each { license ->
                if (!isBlank(license.name?.text()) && !isBlank(license.url?.text())) {
                    validLicenseFound = true
                }
            }
            if (!validLicenseFound) {
                errors.add("POM <licenses> must contain at least one <license> with <name> and <url>")
            }
        }

        def developers = pom.developers.developer
        if (developers.size() == 0) {
            errors.add("POM is missing required field: <developers>")
        } else {
            boolean validDeveloperFound = false
            developers.each { developer ->
                if (!isBlank(developer.id?.text()) || !isBlank(developer.name?.text())) {
                    validDeveloperFound = true
                }
            }
            if (!validDeveloperFound) {
                errors.add("POM <developers> must contain at least one <developer> with <id> or <name>")
            }
        }

        if (isBlank(pom.scm.url?.text()) || isBlank(pom.scm.connection?.text())) {
            errors.add("POM <scm> must contain both <url> and <connection>")
        }
    }

    private static void checkTextField(GPathResult pom, String fieldName, String fieldLabel, List<String> errors) {
        String value = pom."${fieldName}"?.text()
        if (isBlank(value)) {
            errors.add("POM is missing required field: ${fieldLabel}")
        }
    }

    private static boolean isBlank(String value) {
        value == null || value.trim().isEmpty()
    }
}
