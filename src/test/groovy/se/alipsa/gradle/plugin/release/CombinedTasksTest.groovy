package se.alipsa.gradle.plugin.release

import com.sun.net.httpserver.HttpServer
import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import static org.junit.jupiter.api.Assertions.*

class CombinedTasksTest {
    @TempDir File root

    @Test void planningAndBundlingReuseStrictConfigurationCache() {
        HttpServer server = HttpServer.create(new InetSocketAddress('127.0.0.1', 0), 0)
        server.createContext('/') { exchange ->
            boolean portal = exchange.requestURI.path.contains('/publisher/published')
            byte[] body = (portal ? '{"published":false}' : '').getBytes('UTF-8')
            exchange.sendResponseHeaders(portal ? 200 : 404, body.length)
            exchange.responseBody.withCloseable { it.write(body) }
        }
        server.start()
        try {
            String base = "http://127.0.0.1:${server.address.port}"
            new File(root, 'settings.gradle').text = "rootProject.name='combined-fixture'"
            new File(root, '.gitignore').text = 'build/\n.gradle/\n'
            new File(root, 'fixture.json').text = '{"namespacePolicy":"groupId","published":{"body":{"published":true}},"absent":{"body":{"published":false}}}'
            new File(root, 'verify.sh').text = '#!/usr/bin/env bash\nexit 0\n'
            new File(root, 'settings-helper.groovy').text = '// fixture settings helper'
            File bin = new File(root, 'bin'); bin.mkdirs()
            File groovy = new File(bin, 'groovy')
            groovy.text = '#!/usr/bin/env bash\nexit 0\n'; groovy.setExecutable(true)
            File maven = new File(bin, 'mvn')
            maven.text = '#!/usr/bin/env bash\nprintf "%s\\n" "$*" >> build/maven-commands.txt\n'; maven.setExecutable(true)
            ['matrix-bom', 'matrix-all', 'platform', 'slim'].each { name ->
                String packaging = name in ['matrix-bom', 'platform'] ? 'pom' : 'jar'
                String xml = """<project><groupId>g</groupId><artifactId>${name}</artifactId><version>1</version><packaging>${packaging}</packaging><name>Test</name><description>Test</description><url>https://example.org</url><licenses><license><name>MIT</name><url>https://example.org</url></license></licenses><developers><developer><name>Test</name></developer></developers><scm><url>https://example.org</url><connection>scm:git:example</connection></scm></project>"""
                String testOnly = name == 'matrix-all' ? '<dependencies><dependency><groupId>g</groupId><artifactId>test-only</artifactId><version>1-SNAPSHOT</version><scope>test</scope></dependency></dependencies>' : ''
                if (name == 'slim') xml = xml.replace('</project>', '<dependencies><dependency><groupId>external</groupId><artifactId>private-library</artifactId><version>[1,2)</version></dependency></dependencies></project>')
                xml = xml.replace('</project>', testOnly + '</project>')
                new File(root, name + '.xml').text = xml
                File staged = new File(root, "build/staging/g/${name}/1"); staged.mkdirs()
                new File(staged, "${name}-1.pom").text = testOnly ? xml.replace(testOnly, '') : xml
                new File(staged, "${name}-1.pom.asc").text = 'signature'
                if (packaging == 'jar') {
                    new File(staged, "${name}-1.jar").text = 'jar'
                    new File(staged, "${name}-1.jar.asc").text = 'signature'
                }
            }
            new File(root, 'build.gradle').text = """
plugins { id 'se.alipsa.nexus-release-all' }
releaseAllPlan {
  sourceDirectory = layout.projectDirectory
  configuredRevision = providers.exec { commandLine 'git', 'rev-parse', 'HEAD' }.standardOutput.asText.get().trim()
  candidatesJson = '[{"projectPath":":platform","publication":"maven","pomFile":"platform.xml","groupId":"g","artifactId":"platform","version":"1","files":["platform-1.pom"]},{"projectPath":":slim","publication":"maven","pomFile":"slim.xml","groupId":"g","artifactId":"slim","version":"1","sourcesAndJavadocRequired":false,"files":["slim-1.pom","slim-1.jar"]}]'
  bomPom = layout.projectDirectory.file('matrix-bom.xml')
  allPom = layout.projectDirectory.file('matrix-all.xml')
  portalFixture = layout.projectDirectory.file('fixture.json')
  repositoryUrl = '${base}/repo'
  portalUrl = '${base}/api/v1'
  userName = 'u'; password = 'p'
  manifestFile = layout.buildDirectory.file('manifest.json')
}
bundleAll {
  sourceDirectory = layout.projectDirectory
  manifestFile = layout.buildDirectory.file('manifest.json')
  stagingDirectory = layout.buildDirectory.dir('staging')
  bundleFile = layout.buildDirectory.file('bundle.zip')
  receiptFile = layout.buildDirectory.file('receipt.json')
}
releaseAllGuard {
  sourceDirectory = layout.projectDirectory
  manifestFile = layout.buildDirectory.file('manifest.json')
  versionsJson = '{}'
}
releaseAllPrepareMaven {
  sourceDirectory = layout.projectDirectory
  manifestFile = layout.buildDirectory.file('manifest.json')
  stagingDirectory = layout.buildDirectory.dir('staging')
  localRepository = layout.buildDirectory.dir('maven-local')
  mavenWorkDirectory = layout.projectDirectory
  verifierFile = layout.projectDirectory.file('verify.sh')
  settingsHelperFile = layout.projectDirectory.file('settings-helper.groovy')
}
"""
            ReleaseManifest.git(root, 'init'); ReleaseManifest.git(root, 'add', '.')
            ReleaseManifest.git(root, '-c', 'user.name=Test', '-c', 'user.email=test@example.org', 'commit', '-m', 'Fixture')
            run('releaseAllPlan')
            Map planned = ReleaseManifest.read(new File(root, 'build/manifest.json'))
            assertEquals('pom', planned.selected.find { it.artifactId == 'platform' }.packaging)
            assertFalse(planned.selected.find { it.artifactId == 'slim' }.sourcesAndJavadocRequired)
            assertEquals(4, planned.hashes.size())
            assertTrue(run('releaseAllPlan').output.contains('Reusing configuration cache'))
            assertEquals(4, ReleaseManifest.read(new File(root, 'build/manifest.json')).selected.size())
            assertEquals(['platform.xml', 'slim.xml', 'matrix-bom.xml', 'matrix-all.xml'], ReleaseManifest.read(new File(root, 'build/manifest.json')).selected*.pomFile)
            assertFalse(ReleaseManifest.read(new File(root, 'build/manifest.json')).selected.find { it.artifactId == 'matrix-all' }.dependencies.any { it.scope == 'test' })
            run('bundleAll')
            assertTrue(run('bundleAll').output.contains('Reusing configuration cache'))
            assertTrue(new File(root, 'build/bundle.zip').isFile())
            run('releaseAllGuard')
            assertTrue(run('releaseAllGuard').output.contains('Reusing configuration cache'))
            new File(root, 'build/staging').eachFileRecurse { File artifact ->
                if (artifact.isFile()) {
                    File installed = new File(root, 'build/maven-local/' + new File(root, 'build/staging').toPath().relativize(artifact.toPath()).toString())
                    installed.parentFile.mkdirs(); installed.bytes = artifact.bytes
                }
            }
            run('releaseAllPrepareMaven')
            assertTrue(run('releaseAllPrepareMaven').output.contains('Reusing configuration cache'))
            assertEquals(4, new File(root, 'build/maven-commands.txt').readLines().size())
            assertTrue(new File(root, 'build/maven-commands.txt').readLines().every { it.contains('-Prelease') && it.endsWith(' install') && !it.contains('deploy') })
            new File(root, 'matrix-bom.xml').append('changed')
            def result = runner('bundleAll').buildAndFail()
            assertTrue(result.output.contains('Stale manifest'))
        } finally { server.stop(0) }
    }

    @Test void resumeUsesHeadForRepositoryArtifacts() {
        List<String> methods = Collections.synchronizedList([])
        HttpServer server = HttpServer.create(new InetSocketAddress('127.0.0.1', 0), 0)
        server.createContext('/') { exchange ->
            if (exchange.requestURI.path.contains('/publisher/status')) {
                byte[] body = '{"deploymentState":"PUBLISHED"}'.getBytes('UTF-8')
                exchange.sendResponseHeaders(200, body.length)
                exchange.responseBody.withCloseable { it.write(body) }
            } else {
                methods.add(exchange.requestMethod)
                exchange.sendResponseHeaders(200, -1)
                exchange.close()
            }
        }
        server.start()
        try {
            String base = "http://127.0.0.1:${server.address.port}"
            new File(root, 'settings.gradle').text = "rootProject.name='resume-fixture'"
            ReleaseManifest.write(new File(root, 'record.json'), [deploymentId: 'saved-id', state: 'UPLOADED', manifest: [selected: [[groupId: 'g', artifactId: 'a', version: '1', files: ['a-1.pom', 'a-1.jar']]]]])
            new File(root, 'build.gradle').text = """
plugins { id 'se.alipsa.nexus-release-all' }
releaseAll {
  recordFile = layout.projectDirectory.file('record.json')
  resumeId = 'saved-id'
  repositoryUrl = '${base}/repo'
  portalUrl = '${base}/api/v1'
}
"""
            run('releaseAll')
            assertEquals(['HEAD', 'HEAD'], methods)
            assertEquals('AVAILABLE', ReleaseManifest.read(new File(root, 'record.json')).state)
        } finally { server.stop(0) }
    }

    private def run(String task) { runner(task).build() }
    private GradleRunner runner(String task) {
        GradleRunner.create().withProjectDir(root).withPluginClasspath()
            .withEnvironment(new HashMap<String, String>(System.getenv()) + [PATH: new File(root, 'bin').path + File.pathSeparator + System.getenv('PATH')])
            .withArguments(task, '--configuration-cache', '--configuration-cache-problems=fail', '--stacktrace')
    }
}
