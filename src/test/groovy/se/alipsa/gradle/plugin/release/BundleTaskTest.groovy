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
            File first = new File(dir, 'demo-1.0.0.jar'); first.text = 'first'
            File second = new File(dir, 'demo-1.0.0-sources.jar'); second.text = 'second'
            new File(second.path + '.asc').text = 'second signature'
            task.groupId.set('com.example'); task.artifactId.set('demo'); task.version.set('1.0.0')
            task.publicationName.set('maven'); task.publicationDirectory.set(publication)
            task.artifactFiles.set([first, second])
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
}
