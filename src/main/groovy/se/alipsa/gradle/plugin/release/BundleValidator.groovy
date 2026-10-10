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
        List<String> requiredEntries = ['.pom', '.jar', '-sources.jar', '-javadoc.jar'].collect {
            "${basePath}${artifactPrefix}${it}".toString()
        }

        try (ZipFile zip = new ZipFile(zipFile)) {
            ZipEntry pomEntry = zip.getEntry("${basePath}${artifactPrefix}.pom")
            if (pomEntry != null) {
                GPathResult pom
                try {
                    pom = new XmlSlurper(false, false).parseText(zip.getInputStream(pomEntry).getText('UTF-8'))
                } catch (Exception failure) {
                    errors.add("Failed to parse POM ${pomEntry.name}: ${failure.message}")
                    return
                }
                String packaging = pom.packaging.text() ?: 'jar'
                if (packaging != 'jar') {
                    errors.add("Packaging '${packaging}' is not supported by the release task in this version; only jar-packaged publications (optionally with additional artifacts) can be released" +
                        (packaging == 'pom' ? '. For pom-packaged publications such as BOMs, use the combined release (releaseAll)' : ''))
                    return
                }
            }
            Set<String> names = zip.entries().collect { ZipEntry e -> e.name } as Set<String>
            requiredEntries.each { String requiredEntry ->
                if (!names.contains(requiredEntry)) {
                    errors.add("Missing required bundle entry: ${requiredEntry}")
                }
            }

            names.findAll { String name ->
                !name.endsWith('/') && !['.asc', '.md5', '.sha1', '.sha256', '.sha512'].any { name.endsWith(it) }
            }.each { String name ->
                ['.asc', '.md5', '.sha1'].each { String suffix ->
                    ZipEntry companion = zip.getEntry(name + suffix)
                    String kind = suffix == '.asc' ? 'signature' : 'checksum'
                    if (companion == null) errors.add("Missing ${kind} for bundle entry: ${name}")
                    else if (zip.getInputStream(companion).withCloseable { it.read() } == -1) errors.add("Empty ${kind} for bundle entry: ${name}")
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
  -> Check that the publication and artifact coordinates match '${artifactId}'""".stripIndent()
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
        if (pomText.contains('published-with-gradle-metadata')) {
            String modulePath = expectedPomPath.substring(0, expectedPomPath.length() - '.pom'.length()) + '.module'
            [modulePath, modulePath + '.asc'].each { String path ->
                // A present module's signature is already checked by the generic rule.
                if (path.endsWith('.asc') && zip.getEntry(modulePath) != null) return
                ZipEntry entry = zip.getEntry(path)
                if (entry == null || entry.isDirectory()) {
                    errors.add("Missing required bundle entry advertised by POM Gradle metadata marker: ${path}")
                } else if (zip.getInputStream(entry).withCloseable { it.read() } == -1) {
                    errors.add("Empty required bundle entry advertised by POM Gradle metadata marker: ${path}")
                }
            }
        }
        validatePomMetadata(pomText, expectedPomPath, errors)
    }

    /** Checks Central metadata for both single-publication and combined archives. */
    static void validatePomMetadata(String pomText, String expectedPomPath, List<String> errors) {
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
