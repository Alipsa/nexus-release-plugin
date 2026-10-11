package se.alipsa.gradle.plugin.release

import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Test
import java.util.zip.ZipFile
import static org.junit.jupiter.api.Assertions.*

class BundleTaskTest {
    @Test void missingFirstSignatureDoesNotSuppressLaterSignatures() {
        File dir = File.createTempDir('bundle-signatures', '')
        try {
            def project = ProjectBuilder.builder().withProjectDir(dir).build()
            def task = project.tasks.create('testBundle', BundleTask)
            File publication = new File(dir, 'publication'); publication.mkdirs()
            new File(publication, 'pom-default.xml').text = '<project/>'
            new File(publication, 'pom-default.xml.asc').text = 'pom signature'
            File first = new File(dir, 'first.jar'); first.text = 'first'
            File second = new File(dir, 'second.jar'); second.text = 'second'
            new File(second.path + '.asc').text = 'second signature'
            task.groupId.set('com.example'); task.artifactId.set('demo'); task.version.set('1.0.0')
            task.publicationName.set('maven'); task.publicationDirectory.set(publication)
            task.artifactEntries.set(['demo-1.0.0.jar': first, 'demo-1.0.0-sources.jar': second])
            task.artifactInputFiles.from(first, second)
            File output = new File(dir, 'bundle.zip'); task.bundleFile.set(output)
            task.createBundle()
            new ZipFile(output).withCloseable { zip ->
                String prefix = 'com/example/demo/1.0.0/demo-1.0.0'
                assertNull(zip.getEntry(prefix + '.jar.asc'))
                assertNotNull(zip.getEntry(prefix + '-sources.jar.asc'))
                assertNotNull(zip.getEntry(prefix + '.pom.asc'))
            }
        } finally { dir.deleteDir() }
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = [true, false])
    void metadataAndPomMustAgreeBeforeWritingBundle(boolean metadataPresent) {
        File dir = File.createTempDir('metadata-state', '')
        try {
            def project = ProjectBuilder.builder().withProjectDir(dir).build()
            def task = project.tasks.create('testBundle', BundleTask)
            File publication = new File(dir, 'publication'); publication.mkdirs()
            new File(publication, 'pom-default.xml').text = metadataPresent ? '<project/>' : '<project><!-- published-with-gradle-metadata --></project>'
            File metadata = new File(dir, 'custom/module.json'); metadata.parentFile.mkdirs()
            if (metadataPresent) metadata.text = '{}'
            task.moduleMetadataFile.set(metadata)
            task.groupId.set('com.example'); task.artifactId.set('demo'); task.version.set('1.0.0')
            task.publicationName.set('maven'); task.publicationDirectory.set(publication)
            task.artifactEntries.set([:])
            File zip = new File(dir, 'bundle.zip'); task.bundleFile.set(zip)
            def failure = assertThrows(org.gradle.api.GradleException) { task.createBundle() }
            assertTrue(failure.message.contains(metadataPresent ? 'Module metadata state is inconsistent' : 'no metadata was produced in this build'))
            assertFalse(zip.exists())
        } finally { dir.deleteDir() }
    }

}
