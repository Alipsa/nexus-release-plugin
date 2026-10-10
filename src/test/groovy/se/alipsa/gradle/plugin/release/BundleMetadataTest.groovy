package se.alipsa.gradle.plugin.release

import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.zip.ZipFile
import static org.junit.jupiter.api.Assertions.*

@Timeout(180)
class BundleMetadataTest {
    static def run(File dir, String... arguments) {
        MatrixRunner.create().withProjectDir(dir).withPluginClasspath().withArguments(arguments as List).forwardOutput().build()
    }
    static def fail(File dir, String... arguments) {
        MatrixRunner.create().withProjectDir(dir).withPluginClasspath().withArguments(arguments as List).forwardOutput().buildAndFail()
    }
    static byte[] entry(File dir, String suffix) {
        new ZipFile(new File(dir, 'build/zips/test-project-1.0.0-bundle.zip')).withCloseable { zip ->
            zip.getInputStream(zip.getEntry('com/example/test-project/1.0.0/test-project-1.0.0' + suffix)).bytes
        }
    }

    @Test void metadataRebuiltWithWatchingParallelAndConfigurationCache() {
        File dir = TestFixtures.createTestProject()
        try {
            def args = ['bundle', '--configuration-cache', '--configuration-cache-problems=fail', '--parallel', '--watch-fs', '--no-build-cache'] as String[]
            run(dir, *args)
            File metadata = new File(dir, 'build/publications/maven/module.json')
            File signature = new File(metadata.path + '.asc')
            assertTrue(metadata.isFile()); assertTrue(signature.isFile())
            assertTrue(new File(dir, 'build/publications/maven/pom-default.xml').text.contains('published-with-gradle-metadata'))
            assertArrayEquals(metadata.bytes, entry(dir, '.module'))
            def repeated = run(dir, *args)
            assertTrue(repeated.output.contains('Reusing configuration cache'))
            assertEquals(TaskOutcome.SUCCESS, repeated.task(':generateMetadataFileForMavenPublication').outcome)
            assertArrayEquals(metadata.bytes, entry(dir, '.module'))
            def cached = run(dir, 'bundle', '--build-cache', '--info')
            assertTrue(cached.task(':generateMetadataFileForMavenPublication').outcome in [TaskOutcome.SUCCESS, TaskOutcome.FROM_CACHE])
            assertArrayEquals(metadata.bytes, entry(dir, '.module'))
            def cachedAgain = run(dir, 'bundle', '--build-cache', '--info')
            assertTrue(cachedAgain.task(':generateMetadataFileForMavenPublication').outcome in [TaskOutcome.SUCCESS, TaskOutcome.FROM_CACHE])
            assertArrayEquals(metadata.bytes, entry(dir, '.module'))
            assertArrayEquals(signature.bytes, entry(dir, '.module.asc'))
            println "METADATA_CACHE outcome=${cachedAgain.task(':generateMetadataFileForMavenPublication').outcome}"
            new File(dir, 'build.gradle') << "\ntasks.named('generateMetadataFileForMavenPublication') { onlyIf { false } }\n"
            def skipped = fail(dir, 'bundle')
            assertEquals(TaskOutcome.SKIPPED, skipped.task(':generateMetadataFileForMavenPublication').outcome)
            assertFalse(metadata.exists()); assertFalse(signature.exists())
        } finally { dir.parentFile.deleteDir() }
    }

    @Test void disabledMetadataRemovesStaleOutputAndMarkerThenCanBeEnabled() {
        File dir = TestFixtures.createTestProject()
        try {
            run(dir, 'bundle')
            File build = new File(dir, 'build.gradle')
            String original = build.text
            build << "\ntasks.named('generateMetadataFileForMavenPublication') { enabled = false }\n"
            run(dir, 'bundle')
            assertFalse(new File(dir, 'build/publications/maven/module.json').exists())
            assertFalse(new File(dir, 'build/publications/maven/module.json.asc').exists())
            assertFalse(new File(dir, 'build/publications/maven/pom-default.xml').text.contains('published-with-gradle-metadata'))
            new ZipFile(new File(dir, 'build/zips/test-project-1.0.0-bundle.zip')).withCloseable { zip ->
                assertNull(zip.getEntry('com/example/test-project/1.0.0/test-project-1.0.0.module'))
            }
            build.text = original
            run(dir, 'bundle')
            assertArrayEquals(new File(dir, 'build/publications/maven/module.json').bytes, entry(dir, '.module'))
        } finally { dir.parentFile.deleteDir() }
    }

    @Test void customOutputInputsTrackContentAndSignatureAfterCleanCacheStore() {
        String script = TestFixtures.createBuildScript() + '''
 def revision = providers.gradleProperty('metadataRevision').getOrElse('')
 tasks.named('generateMetadataFileForMavenPublication') {
   outputFile = layout.buildDirectory.file('custom/module.json')
   doLast { outputFile.get().asFile.append('\\n' + revision) }
 }
 tasks.named('bundle') {
   doFirst {
     assert moduleMetadataInput.files*.name.toSet() == ['module.json', 'module.json.asc'].toSet()
   }
 }
'''
        File dir = TestFixtures.createTestProject(script)
        try {
            def args = ['bundle', '--configuration-cache', '--configuration-cache-problems=fail', '--no-build-cache']
            run(dir, *(['clean'] + args + ['-PmetadataRevision=one']) as String[])
            byte[] first = entry(dir, '.module')
            byte[] signature = entry(dir, '.module.asc')
            def changed = run(dir, *(args + ['-PmetadataRevision=two']) as String[])
            assertNotEquals(TaskOutcome.UP_TO_DATE, changed.task(':bundle').outcome)
            assertFalse(Arrays.equals(first, entry(dir, '.module')))
            assertFalse(Arrays.equals(signature, entry(dir, '.module.asc')))
            assertArrayEquals(new File(dir, 'build/custom/module.json').bytes, entry(dir, '.module'))
            def reused = run(dir, *(args + ['-PmetadataRevision=two']) as String[])
            assertTrue(reused.output.contains('Reusing configuration cache'))
            def isolated = args + ['-PmetadataRevision=two', '-x', 'publishMavenPublicationToMavenLocal',
                '-x', 'generateMetadataFileForMavenPublication', '-x', 'clearModuleMetadata', '-x', 'signMavenPublication']
            run(dir, *isolated as String[])
            File custom = new File(dir, 'build/custom/module.json')
            File customSignature = new File(custom.path + '.asc')
            custom.append('\n ')
            def contentChanged = run(dir, *isolated as String[])
            assertTrue(contentChanged.output.contains('Reusing configuration cache'))
            assertEquals(TaskOutcome.SUCCESS, contentChanged.task(':bundle').outcome)
            assertArrayEquals(custom.bytes, entry(dir, '.module'))
            customSignature.append('\n')
            def signatureChanged = run(dir, *isolated as String[])
            assertEquals(TaskOutcome.SUCCESS, signatureChanged.task(':bundle').outcome)
            assertArrayEquals(customSignature.bytes, entry(dir, '.module.asc'))
        } finally { dir.parentFile.deleteDir() }
    }

    @Test void selectedPublicationOnlyAndAdditionalArtifactBuiltLazily() {
        String script = TestFixtures.createBuildScript() + '''
 tasks.register('distZip', Zip) { archiveClassifier = 'dist'; from('src') }
 publishing.publications.maven.artifact(tasks.named('distZip'))
 publishing.publications.create('other', MavenPublication) { from components.java; artifactId = 'other' }
 signing { sign publishing.publications.other }
'''
        File dir = TestFixtures.createTestProject(script)
        try {
            def result = run(dir, 'bundle', '--parallel')
            assertNull(result.task(':publishOtherPublicationToMavenLocal'))
            assertEquals(TaskOutcome.SUCCESS, result.task(':distZip').outcome)
            assertTrue(entry(dir, '-dist.zip').length > 0)
            assertTrue(entry(dir, '-dist.zip.asc').length > 0)
            run(dir, 'publishOtherPublicationToMavenLocal')
            File otherMetadata = new File(dir, 'build/publications/other/module.json')
            byte[] other = otherMetadata.bytes
            run(dir, 'bundle')
            assertArrayEquals(other, otherMetadata.bytes)
        } finally { dir.parentFile.deleteDir() }
    }

    @Test void missingPublicationHasClearMessage() {
        File dir = TestFixtures.createTestProject("plugins { id 'se.alipsa.nexus-release-plugin' }")
        try { assertTrue(fail(dir, 'bundle').output.contains('nexusReleasePlugin.mavenPublication must be set')) }
        finally { dir.parentFile.deleteDir() }
    }
}
