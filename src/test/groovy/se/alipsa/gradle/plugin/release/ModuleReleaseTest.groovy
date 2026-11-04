package se.alipsa.gradle.plugin.release

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.util.zip.ZipFile
import static org.junit.jupiter.api.Assertions.*

class ModuleReleaseTest {
    @TempDir File root

    @Test void namedSubsetPlansBuildsSignsAndBundlesRealPublications() {
        withServer { String base, List requests ->
            fixture(base, "modules = [':api', 'core']")
            new File(root, 'build.gradle') << '\ntasks.all { }\n'
            commit()
            def plan = run('releaseAllPlan')
            assertNull(plan.task(':api:jar'))
            assertNull(plan.task(':core:signMavenPublication'))
            Map manifest = ReleaseManifest.read(new File(root, 'build/releaseAll/manifest.json'))
            assertEquals([':api', ':core'], manifest.modulePaths)
            assertEquals([':api', ':core'], manifest.selected*.projectPath)
            assertEquals([], manifest.excluded)
            assertTrue(manifest.selected.find { it.projectPath == ':api' }.dependencies.any { it.artifactId == 'core-artifact' })
            assertTrue(run('releaseAllPlan').output.contains('Reusing configuration cache'))
            def bundle = run('bundleAll')
            assertNotNull(bundle.task(':core:signMavenPublication'))
            assertNotNull(bundle.task(':api:signMavenPublication'))
            assertNotNull(bundle.task(':api:check'))
            assertNotNull(bundle.task(':core:check'))
            assertNull(bundle.task(':unused:jar'))
            assertNull(bundle.task(':unused:signMavenPublication'))
            assertNull(bundle.task(':unused:check'))
            assertNull(bundle.task(':releaseAllPrepareMaven'))
            File zip = new File(root, 'build/releaseAll/bundle.zip')
            Set<String> entries = new ZipFile(zip).withCloseable { it.entries().collect { it.name } as Set }
            Set<String> expected = [] as Set
            ['api-artifact', 'core-artifact'].each { String name ->
                ['.pom', '.jar', '-sources.jar', '-javadoc.jar', '.module'].each { String suffix ->
                    String path = "example/${name}/1.0/${name}-1.0${suffix}"
                    ['', '.asc'].each { String signature ->
                        ['', '.md5', '.sha1'].each { String checksum -> expected.add(path + signature + checksum) }
                    }
                }
            }
            assertEquals(expected, entries)
            assertTrue(run('bundleAll').output.contains('Reusing configuration cache'))
            // A Gradle-only build needs no Maven settings helper or verifier, even if invoked.
            run('releaseAllPrepareMaven')
        }
    }

    @Test void omittedUnpublishedSiblingBlocksConsumerAcrossNamespacesAndCompileOnlyEdges() {
        withServer { String base, List requests ->
            fixture(base, "modules = [':api']", true)
            run('releaseAllPlan')
            Map manifest = ReleaseManifest.read(new File(root, 'build/releaseAll/manifest.json'))
            assertEquals([], manifest.selected)
            assertTrue(manifest.excluded.first().reasons.any { it.contains('Unavailable dependency: other:core-artifact:1.0') })
            assertTrue(requests.any { it.contains('other/core-artifact/1.0') })
        }
    }

    @Test void pomDependencyOnOmittedSiblingIsCheckedWithoutExplicitNamespaces() {
        withServer { String base, List requests ->
            fixture(base, "modules = [':api']")
            new File(root, 'api/build.gradle').text = new File(root, 'api/build.gradle').text.replace("api project(':core')", "api 'example:core-artifact:1.0'")
            new File(root, 'build.gradle') << '\nreleaseAllPlan { releaseNamespaces = [] }\n'
            commit()
            run('releaseAllPlan')
            Map manifest = ReleaseManifest.read(new File(root, 'build/releaseAll/manifest.json'))
            assertEquals([], manifest.selected)
            assertTrue(manifest.excluded.first().reasons.any { it.contains('Unavailable dependency: example:core-artifact:1.0') })
            assertTrue(requests.any { it.contains('example/core-artifact/1.0') })
        }
    }

    @Test void omittedPublishedSiblingCanBeUsedWithoutPublishingItAgain() {
        withServer(['example:core-artifact:1.0'] as Set) { String base, List requests ->
            fixture(base, "modules = [':api']")
            run('releaseAllPlan')
            Map manifest = ReleaseManifest.read(new File(root, 'build/releaseAll/manifest.json'))
            assertEquals([':api'], manifest.selected*.projectPath)
            def result = run('bundleAll')
            assertNull(result.task(':core:signMavenPublication'))
            assertNull(result.task(':core:publishMavenPublicationToCombinedReleaseStagingRepository'))
            new ZipFile(new File(root, 'build/releaseAll/bundle.zip')).withCloseable { zip ->
                assertTrue(zip.entries().every { it.name.startsWith('example/api-artifact/') })
            }
        }
    }

    @Test void pomOnlyPublicationNeedsNoJarOrMetadata() {
        withServer { String base, List requests ->
            fixture(base, "modules = [':core']")
            File build = new File(root, 'core/build.gradle')
            build.text = build.text.replace("id 'java-library'; ", '')
                .replace('java { withSourcesJar(); withJavadocJar() }', '')
                .replace('from components.java', '')
                .replace('pom {', "pom { packaging = 'pom'")
            commit()
            run('releaseAllPlan')
            assertEquals('pom', ReleaseManifest.read(new File(root, 'build/releaseAll/manifest.json')).selected.first().packaging)
            run('bundleAll')
            new ZipFile(new File(root, 'build/releaseAll/bundle.zip')).withCloseable { zip ->
                assertEquals(6, zip.size())
                assertTrue(zip.entries().every { it.name.contains('.pom') })
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ['bomPom', 'allPom'])
    void eachStaticPomCanBeUsedIndependently(String property) {
        withServer { String base, List requests ->
            fixture(base, "modules = [':core']")
            new File(root, 'standalone.xml').text = '<project><groupId>example</groupId><artifactId>static-component</artifactId><version>1.0</version><packaging>pom</packaging></project>'
            new File(root, 'build.gradle') << "\nreleaseAllPlan { ${property} = layout.projectDirectory.file('standalone.xml') }\n"
            commit()
            run('releaseAllPlan')
            Map manifest = ReleaseManifest.read(new File(root, 'build/releaseAll/manifest.json'))
            assertEquals(['core-artifact', 'static-component'], manifest.selected*.artifactId)
            assertEquals(property == 'bomPom' ? 'bom' : 'aggregate', manifest.selected.last().role)
            assertEquals(2, manifest.hashes.size())
        }
    }

    @Test void changingModuleSelectionRequiresANewPlan() {
        withServer { String base, List requests ->
            fixture(base, "modules = [':api', ':core']")
            run('releaseAllPlan')
            new File(root, 'build.gradle').text = new File(root, 'build.gradle').text.replace("[':api', ':core']", "[':core']")
            commit()
            def failed = runner('bundleAll').buildAndFail()
            assertTrue(failed.output.contains('Configured modules changed; run releaseAllPlan again'))
            assertNull(failed.task(':core:signMavenPublication'))
        }
    }

    @Test void unknownAndAmbiguousModulesHaveActionableErrors() {
        withServer { String base, List requests ->
            fixture(base, "modules = [':missing']")
            assertTrue(runner('releaseAllPlan').buildAndFail().output.contains("Unknown combined release module ':missing'"))
            new File(root, 'build.gradle').text = new File(root, 'build.gradle').text.replace("':missing'", "':core'")
            new File(root, 'core/build.gradle') << "\npublishing { publications { extra(MavenPublication) { from components.java } } }\n"
            commit()
            assertTrue(runner('releaseAllPlan').buildAndFail().output.contains('select one with nexusReleasePlugin.mavenPublication'))
            new File(root, 'core/build.gradle') << "\napply plugin: 'se.alipsa.nexus-release-plugin'\nnexusReleasePlugin { mavenPublication = publishing.publications.maven }\n"
            commit()
            run('releaseAllPlan')
            assertEquals(['maven'], ReleaseManifest.read(new File(root, 'build/releaseAll/manifest.json')).selected*.publication)
        }
    }

    @Test void kotlinCanSelectModulesWithoutLowLevelConfiguration() {
        withServer { String base, List requests ->
            fixture(base, "modules = [':api', ':core']")
            assertTrue(new File(root, 'build.gradle').delete())
            new File(root, 'build.gradle.kts').text = """
plugins { id("se.alipsa.nexus-release-plugin") }
nexusReleasePlugin {
    userName.set("u"); password.set("p")
    combinedRelease {
        modules.set(listOf(":api", ":core"))
        portalFixture.set(layout.projectDirectory.file("fixture.json"))
        repositoryUrl.set("${base}/repo")
        portalUrl.set("${base}/api/v1")
    }
}
"""
            commit()
            run('releaseAllPlan')
            assertEquals([':api', ':core'], ReleaseManifest.read(new File(root, 'build/releaseAll/manifest.json')).selected*.projectPath)
            run('bundleAll')
            assertTrue(run('bundleAll').output.contains('Reusing configuration cache'))
        }
    }

    @Test void resumeOnlyUsesSavedDeploymentWithoutModulePreparation() {
        withServer(['example:api-artifact:1.0'] as Set) { String base, List requests ->
            fixture(base, "modules = [':missing']")
            new File(root, 'build.gradle') << "\nreleaseAll { resumeId = 'saved-id' }\n"
            ReleaseManifest.write(new File(root, 'build/releaseAll/deployment.json'), [deploymentId: 'saved-id', state: 'UPLOADED',
                manifest: [selected: [[groupId: 'example', artifactId: 'api-artifact', version: '1.0', files: ['api-artifact-1.0.pom']]]]])
            def result = run('releaseAll')
            assertEquals([':releaseAll'], result.tasks*.path)
            assertTrue(run('releaseAll').output.contains('Reusing configuration cache'))
        }
    }

    private void fixture(String base, String selection, boolean compileOnly = false) {
        new File(root, 'settings.gradle').text = "rootProject.name='module-release-fixture'\ninclude 'core', 'api', 'unused'\n"
        new File(root, '.gitignore').text = 'build/\n.gradle/\n'
        new File(root, 'fixture.json').text = '{"namespacePolicy":"groupId","published":{"body":{"published":true}},"absent":{"body":{"published":false}}}'
        new File(root, 'build.gradle').text = """
plugins { id 'se.alipsa.nexus-release-plugin' }
nexusReleasePlugin {
    userName = 'u'; password = 'p'
    combinedRelease {
        ${selection}
        portalFixture = layout.projectDirectory.file('fixture.json')
        repositoryUrl = '${base}/repo'
        portalUrl = '${base}/api/v1'
    }
}
"""
        String key = TestFixtures.generateTestPrivateKey('test@example.com', new char[0])
        ['core', 'api', 'unused'].each { String name ->
            File dir = new File(root, name); dir.mkdirs()
            new File(dir, 'build.gradle').text = """
plugins { id 'java-library'; id 'maven-publish'; id 'signing' }
group = '${compileOnly && name == 'core' ? 'other' : 'example'}'
version = '1.0'
java { withSourcesJar(); withJavadocJar() }
publishing {
    publications {
        maven(MavenPublication) {
            artifactId = '${name}-artifact'
            from components.java
            pom {
                name = 'Test'; description = 'Test'; url = 'https://example.org'
                licenses { license { name = 'MIT'; url = 'https://example.org/license' } }
                developers { developer { name = 'Test' } }
                scm { url = 'https://example.org'; connection = 'scm:git:https://example.org/repo' }
            }
        }
    }
}
signing { useInMemoryPgpKeys('''${key}''', '') }
${name == 'api' ? "dependencies { ${compileOnly ? 'compileOnly' : 'api'} project(':core') }" : ''}
"""
            File source = new File(dir, "src/main/java/${name.capitalize()}.java")
            source.parentFile.mkdirs()
            source.text = "/** Fixture. */ public class ${name.capitalize()} {}"
        }
        MatrixRunner.instrument(root)
        ReleaseManifest.git(root, 'init')
        commit()
    }

    private void commit() {
        ReleaseManifest.git(root, 'add', '.')
        ReleaseManifest.git(root, '-c', 'user.name=Test', '-c', 'user.email=test@example.org', 'commit', '-m', 'fixture')
    }

    private void withServer(Set<String> published = [] as Set, Closure action) {
        List<String> requests = Collections.synchronizedList([])
        def server = HttpServer.create(new InetSocketAddress('127.0.0.1', 0), 0)
        server.createContext('/') { exchange ->
            requests.add(exchange.requestURI.toString())
            boolean portal = exchange.requestURI.path.contains('/publisher/published')
            boolean status = exchange.requestURI.path.contains('/publisher/status')
            boolean released = published.any { String coordinate ->
                def (group, name, version) = coordinate.split(':')
                exchange.requestURI.path.endsWith("/${group.replace('.', '/')}/${name}/${version}/${name}-${version}.pom")
            }
            byte[] body = (status ? '{"deploymentState":"PUBLISHED"}' : portal ? '{"published":false}' : '').getBytes('UTF-8')
            exchange.sendResponseHeaders(portal || status || released ? 200 : 404, exchange.requestMethod == 'HEAD' ? -1 : body.length)
            exchange.responseBody.withCloseable { it.write(body) }
        }
        server.start()
        try { action("http://127.0.0.1:${server.address.port}".toString(), requests) }
        finally { server.stop(0) }
    }

    private def run(String task) { runner(task).build() }
    private def runner(String task) {
        MatrixRunner.create().withProjectDir(root).withPluginClasspath()
            .withArguments(task, '--configuration-cache', '--configuration-cache-problems=fail', '--stacktrace')
    }
}
