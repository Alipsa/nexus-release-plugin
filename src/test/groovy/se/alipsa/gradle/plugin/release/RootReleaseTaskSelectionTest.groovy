package se.alipsa.gradle.plugin.release

import com.sun.net.httpserver.HttpServer
import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import static org.junit.jupiter.api.Assertions.*

class RootReleaseTaskSelectionTest {
    @TempDir File root

    private GradleRunner runner(String... tasks) {
        MatrixRunner.create().withProjectDir(root).withPluginClasspath()
            .withArguments((tasks as List) + ['--configuration-cache', '--configuration-cache-problems=fail', '--stacktrace'])
    }

    private void setup(String rootScript, boolean single = false) {
        new File(root, 'settings.gradle').text = "rootProject.name='root-fixture'\ninclude 'sub'"
        new File(root, 'gradle.properties').text = "nexusReleasePlugin.rootSinglePublicationTasks=${single}\n"
        new File(root, 'build.gradle').text = "plugins { id 'se.alipsa.nexus-release-plugin' }\n" + rootScript
        new File(root, 'sub').mkdirs()
        new File(root, 'sub/build.gradle').text = "plugins { id 'se.alipsa.nexus-release-plugin' }"
        MatrixRunner.instrument(root)
    }

    @Test void unqualifiedSelectorsRunSubprojectReleasesAndReuseConfigurationCache() {
        HttpServer server = HttpServer.create(new InetSocketAddress('127.0.0.1', 0), 0)
        int uploads = 0
        server.createContext('/') { exchange ->
            boolean upload = exchange.requestURI.path.contains('/publisher/upload')
            if (upload) { uploads++; exchange.requestBody.bytes }
            byte[] response = (upload ? 'test-deployment' : '{"deploymentState":"PUBLISHED"}').getBytes('UTF-8')
            exchange.sendResponseHeaders(200, response.length)
            exchange.responseBody.withCloseable { it.write(response) }
        }
        server.start()
        try {
            setup('''
nexusReleasePlugin { combinedRelease { } }
assert tasks.findByName('bundle') == null
assert tasks.findByName('release') == null
assert tasks.findByName('clearModuleMetadata') == null
assert nexusReleasePlugin.bundleTask == null
assert nexusReleasePlugin.releaseTask == null
assert tasks.findByName('latestMavenVersions') != null
assert tasks.findByName('latestGithubRelease') != null
''')
            new File(root, 'sub/build.gradle').text = TestFixtures.createBuildScript("http://127.0.0.1:${server.address.port}/api/v1") + '''
nexusReleasePlugin {
    initialStatusCheckDelaySeconds = 0
    statusCheckIntervalSeconds = 0
}
assert tasks.findByName('bundle') != null
assert tasks.findByName('release') != null
'''
            def bundle = runner('bundle').build()
            assertNotNull(bundle.task(':sub:bundle'))
            assertNull(bundle.task(':bundle'))
            assertTrue(runner('bundle').build().output.contains('Reusing configuration cache'))
            def release = runner('release').build()
            assertNotNull(release.task(':sub:release'))
            assertNull(release.task(':release'))
            assertTrue(runner('release').build().output.contains('Reusing configuration cache'))
            assertEquals(2, uploads)
        } finally { server.stop(0) }
    }

    @Test void optOutPreservesCustomRootTasksAndOrdinaryPublishing() {
        setup('''
tasks.register('bundle')
tasks.register('release')
publishing { publications { maven(MavenPublication) { groupId='g'; artifactId='a'; version='1' } } }
nexusReleasePlugin {
    mavenPublication = publishing.publications.maven
    combinedRelease { }
}
assert nexusReleasePlugin.bundleTask == null
assert nexusReleasePlugin.releaseTask == null
''')
        def result = runner(':bundle', ':release', ':publish').build()
        assertNotNull(result.task(':bundle'))
        assertNotNull(result.task(':release'))
        assertTrue(runner(':bundle', ':release', ':publish').build().output.contains('Reusing configuration cache'))
    }

    @Test void propertyChangesInvalidateCacheAndDefaultStillRequiresPublication() {
        setup('')
        def first = runner('tasks', '--all').build()
        assertNotNull(first.task(':tasks'))
        assertFalse(first.output.readLines().any { it.startsWith('bundle -') || it.startsWith('release -') || it == 'clearModuleMetadata' })
        assertTrue(runner('tasks', '--all').build().output.contains('Reusing configuration cache'))
        new File(root, 'gradle.properties').text = 'nexusReleasePlugin.rootSinglePublicationTasks=true   \n'
        def changed = runner('tasks', '--all').build()
        assertFalse(changed.output.contains('Reusing configuration cache'))
        assertTrue(changed.output.readLines().any { it.startsWith('bundle -') })
        assertTrue(changed.output.readLines().any { it.startsWith('release -') })
        assertTrue(changed.output.readLines().any { it == 'clearModuleMetadata' })
        def failed = runner(':bundle').buildAndFail()
        assertTrue(failed.output.contains('nexusReleasePlugin.mavenPublication must be set'))
        new File(root, 'gradle.properties').text = 'nexusReleasePlugin.rootSinglePublicationTasks=invalid\n'
        assertTrue(runner('help').buildAndFail().output.contains("must be true or false, was 'invalid'"))
    }

    @Test void optOutWithoutCombinedBlockRegistersNeitherWorkflow() {
        setup('''
assert tasks.findByName('bundle') == null
assert tasks.findByName('release') == null
assert tasks.findByName('bundleAll') == null
assert tasks.findByName('releaseAll') == null
''')
        new File(root, 'gradle.properties').text = 'nexusReleasePlugin.rootSinglePublicationTasks=false   \n'
        assertTrue(runner('help', ':publish').build().output.contains('BUILD SUCCESSFUL'))
    }

    @Test void defaultCanUseSingleAndCombinedWorkflowsTogether() {
        setup('''
publishing { publications { maven(MavenPublication) { groupId='g'; artifactId='a'; version='1' } } }
nexusReleasePlugin {
    mavenPublication = publishing.publications.maven
    combinedRelease { }
}
assert nexusReleasePlugin.bundleTask != null
assert nexusReleasePlugin.releaseTask != null
assert tasks.findByName('bundleAll') != null
assert tasks.findByName('releaseAll') != null
''', true)
        assertTrue(runner('help').build().output.contains('BUILD SUCCESSFUL'))
    }
}
