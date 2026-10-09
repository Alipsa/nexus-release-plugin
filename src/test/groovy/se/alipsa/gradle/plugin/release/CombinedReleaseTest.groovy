package se.alipsa.gradle.plugin.release

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.util.zip.ZipFile
import static org.junit.jupiter.api.Assertions.*

class CombinedReleaseTest {
    @TempDir File directory

    @Test void compileOnlySnapshotAndCascadingExclusions() {
        def core = candidate('core', '3.10.0-SNAPSHOT')
        def stats = candidate('stats', '2.5.4')
        stats.dependencies = [[groupId: 'g', artifactId: 'core', version: core.coordinate.version, origin: 'compileOnly']]
        def plot = candidate('plot', '1.0')
        plot.dependencies = [[groupId: 'g', artifactId: 'stats', version: '2.5.4', origin: 'compileClasspath']]
        assertTrue(ReleasePlanner.select([core, stats, plot], { false }).empty)
        assertTrue(stats.reasons.first().contains('compileOnly'))
        assertTrue(plot.reasons.first().contains('Unavailable'))
    }

    @Test void releasedOldBomPinDoesNotBlockUnselectedSnapshot() {
        def arff = candidate('matrix-arff', '0.3.1-SNAPSHOT')
        def bom = candidate('matrix-bom', '2.7.0')
        bom.dependencies = [[groupId: 'g', artifactId: 'matrix-arff', version: '0.3.0', origin: 'management']]
        List<String> warnings = []
        assertEquals([bom], ReleasePlanner.select([arff, bom], { it.version == '0.3.0' }, warnings))
        assertEquals(1, warnings.size())
    }

    @Test void bomDriftIsEvaluatedAfterDependencyCascades() {
        def arff = candidate('matrix-arff', '0.3.2')
        arff.dependencies = [[groupId: 'g', artifactId: 'core', version: '1-SNAPSHOT', origin: 'compileOnly']]
        def bom = candidate('matrix-bom', '2.7.0')
        bom.dependencies = [[groupId: 'g', artifactId: 'matrix-arff', version: '0.3.0', origin: 'management']]
        assertEquals([bom], ReleasePlanner.select([bom, arff], { it.version == '0.3.0' }))
    }

    @Test void selectedModulePinMismatchBlocksBom() {
        def arff = candidate('matrix-arff', '0.3.2')
        def bom = candidate('matrix-bom', '2.7.0')
        bom.dependencies = [[groupId: 'g', artifactId: 'matrix-arff', version: '0.3.0', origin: 'management']]
        assertEquals([arff], ReleasePlanner.select([arff, bom], { it.version == '0.3.0' }))
    }

    @Test void portalOverridesRepository404AndErrorsFailClosed() {
        HttpServer server = HttpServer.create(new InetSocketAddress('127.0.0.1', 0), 0)
        int responseStatus = 200
        String body = '{"published":true}'
        List<String> queries = []
        server.createContext('/') { exchange ->
            boolean portal = exchange.requestURI.path.contains('/publisher/published')
            if (portal) {
                queries.add(exchange.requestURI.rawQuery)
                assertTrue(exchange.requestHeaders.getFirst('Authorization').startsWith('Bearer '))
            }
            byte[] data = (portal ? body : '').getBytes('UTF-8')
            exchange.sendResponseHeaders(portal ? responseStatus : 404, data.length)
            exchange.responseBody.withCloseable { it.write(data) }
        }
        server.start()
        try {
            def status = new PublicationStatus(repositoryUrl: "http://127.0.0.1:${server.address.port}/repo", portalUrl: "http://127.0.0.1:${server.address.port}/api/v1", userName: 'u', password: 'p', attempts: 2)
            def coordinate = new ReleaseCoordinate('g', 'a', '1.0+test')
            assertTrue(status.published(coordinate))
            assertTrue(queries.last().contains('version=1.0%2Btest'))
            body = '{"published":false}'
            assertFalse(status.published(coordinate))
            body = '{"published":"false"}'
            assertThrows(IOException) { status.published(coordinate) }
            [400, 401, 403, 500].each { code ->
                responseStatus = code
                assertThrows(IOException) { status.published(coordinate) }
            }
        } finally { server.stop(0) }
    }

    @Test void pomAndJarPoliciesAllowlistAndChecksums() {
        def pom = publication('matrix-bom', 'pom', ['matrix-bom-1.pom'])
        def all = publication('matrix-all', 'jar', ['matrix-all-1.pom', 'matrix-all-1.jar'])
        [pom, all].each { p ->
            p.files.each { name ->
                def file = new File(directory, "g/${p.artifactId}/1/${name}")
                file.parentFile.mkdirs()
                file.text = name.endsWith('.pom') ? pomText(p.artifactId, p.packaging) : 'jar fixture'
                new File(file.path + '.asc').text = 'signature fixture'
            }
        }
        new File(directory, 'download.pom').text = 'unrelated'
        File zip = new File(directory, 'bundle.zip')
        CombinedBundle.create(directory, zip, [pom, all])
        new ZipFile(zip).withCloseable { archive ->
            assertEquals(18, archive.size())
            assertNull(archive.getEntry('download.pom'))
            assertEquals(ReleaseChecksums.checksum(new File(directory, 'g/matrix-all/1/matrix-all-1.jar'), 'SHA-1'), archive.getInputStream(archive.getEntry('g/matrix-all/1/matrix-all-1.jar.sha1')).text)
        }
        assertThrows(IllegalArgumentException) { CombinedBundle.create(directory, zip, [pom, pom]) }
        assertThrows(IllegalArgumentException) { CombinedBundle.create(directory, zip, [pom, all], 1) }
        new File(directory, 'g/matrix-all/1/matrix-all-1.jar.asc').delete()
        assertThrows(IllegalArgumentException) { CombinedBundle.create(directory, zip, [all]) }
    }

    @Test void staticMavenPropertiesAndUnresolvedExpressions() {
        File pom = new File(directory, 'pom.xml')
        pom.text = pomText('a', 'pom').replace('</project>', '<dependencies><dependency><groupId>g</groupId><artifactId>b</artifactId><version>${bVersion}</version></dependency></dependencies></project>')
        assertThrows(IllegalArgumentException) { ReleasePom.read(pom) }
        assertEquals('2', ReleasePom.read(pom, [bVersion: '2']).dependencies.first().version)
    }

    @Test void sourceGuardsRejectRevisionDirtyTreeAndChangedInput() {
        File root = new File(directory, 'repo'); root.mkdirs()
        new File(root, 'input.txt').text = 'original'
        git(root, 'init'); git(root, 'add', '.')
        git(root, '-c', 'user.name=Test', '-c', 'user.email=test@example.org', 'commit', '-m', 'Fixture')
        Map manifest = [revision: ReleaseManifest.git(root, 'rev-parse', 'HEAD'), hashes: ['input.txt': ReleaseChecksums.checksum(new File(root, 'input.txt'), 'SHA-256')], versions: [':a': '1']]
        ReleaseManifest.guard(root, manifest, [':a': '1'])
        assertThrows(IllegalStateException) { ReleaseManifest.guard(root, manifest, [':a': '2']) }
        new File(root, 'input.txt').text = 'changed'
        assertThrows(IllegalStateException) { ReleaseManifest.guard(root, manifest) }
        git(root, 'add', '.'); git(root, '-c', 'user.name=Test', '-c', 'user.email=test@example.org', 'commit', '-m', 'Changed')
        assertThrows(IllegalStateException) { ReleaseManifest.guard(root, manifest) }
    }

    @Test void uploadIsOnceAndUnknownOutcomeCannotBeRetried() {
        File bundle = new File(directory, 'bundle.zip'); bundle.text = 'bundle'
        File record = new File(directory, 'deployment.json')
        Map manifest = [selected: [[groupId: 'g', artifactId: 'a', version: '1']]]
        def availability = new PublicationStatus() {
            @Override boolean published(ReleaseCoordinate coordinate, boolean checkBoth) { false }
        }
        int uploads = 0
        def client = new CentralPortalClient(org.gradle.api.logging.Logging.getLogger(CombinedReleaseTest), null, 'u', 'p') {
            @Override String upload(File file) { uploads++; throw new IOException('Disconnected after server accepted upload') }
        }
        assertThrows(IOException) { CombinedDeployment.submit(record, bundle, manifest, availability, client) }
        assertEquals('SUBMITTING', ReleaseManifest.read(record).state)
        assertThrows(IllegalStateException) { CombinedDeployment.submit(record, bundle, manifest, availability, client) }
        assertEquals(1, uploads)
    }

    @Test void resumePollsAndPropagationTimeoutDoesNotUpload() {
        File record = new File(directory, 'deployment.json')
        ReleaseManifest.write(record, [deploymentId: 'id', state: 'UPLOADED', manifest: [selected: []]])
        int polls = 0
        def client = new CentralPortalClient(org.gradle.api.logging.Logging.getLogger(CombinedReleaseTest), null, 'u', 'p') {
            @Override Map<String, Object> getDeploymentStatus(String id) { polls++; [deploymentState: 'PUBLISHED'] }
            @Override String upload(File file) { throw new AssertionError('Resume must never upload') }
        }
        assertThrows(IllegalStateException) { CombinedDeployment.resume(record, 'id', client, { false }, 2, 0) }
        assertEquals(2, polls)
        CombinedDeployment.resume(record, 'id', client, { true }, 1, 0)
        assertEquals('AVAILABLE', ReleaseManifest.read(record).state)
    }

    @Test void multiComponentUploadAndLatePublicationCheck() {
        File bundle = new File(directory, 'bundle.zip'); bundle.text = 'combined'
        File record = new File(directory, 'deployment.json')
        Map manifest = [selected: ['a', 'b'].collect { [groupId: 'g', artifactId: it, version: '1'] }]
        List<String> checked = []
        boolean latePublication = true
        def status = new PublicationStatus() {
            @Override boolean published(ReleaseCoordinate coordinate, boolean both) {
                assertTrue(both)
                checked.add(coordinate.key())
                latePublication && coordinate.artifactId == 'b'
            }
        }
        int uploads = 0
        def client = new CentralPortalClient(org.gradle.api.logging.Logging.getLogger(CombinedReleaseTest), null, 'u', 'p') {
            @Override String upload(File file) { uploads++; 'deployment-id' }
        }
        assertThrows(IllegalStateException) { CombinedDeployment.submit(record, bundle, manifest, status, client) }
        assertEquals(0, uploads)
        assertFalse(record.exists())
        latePublication = false
        Map result = CombinedDeployment.submit(record, bundle, manifest, status, client)
        assertEquals(1, uploads)
        assertEquals('deployment-id', result.deploymentId)
        assertEquals(['g:a:1', 'g:b:1', 'g:a:1', 'g:b:1'], checked)
        assertThrows(IllegalStateException) { CombinedDeployment.submit(record, bundle, manifest, status, client) }
        assertEquals(1, uploads)
    }

    @Test void failedDeploymentReportsEveryCoordinate() {
        File record = new File(directory, 'deployment.json')
        ReleaseManifest.write(record, [deploymentId: 'id', manifest: [selected: [[groupId: 'g', artifactId: 'a', version: '1']]]])
        def client = new CentralPortalClient(org.gradle.api.logging.Logging.getLogger(CombinedReleaseTest), null, 'u', 'p') {
            @Override Map<String, Object> getDeploymentStatus(String id) { [deploymentState: 'FAILED'] }
        }
        def failure = assertThrows(IllegalStateException) { CombinedDeployment.resume(record, 'id', client, { true }, 1, 0) }
        assertTrue(failure.message.contains('g:a:1'))
        assertEquals('FAILED', ReleaseManifest.read(record).state)
    }

    @Test void concurrentLocalSubmissionsReserveOneJournal() {
        File record = new File(directory, 'deployment.json')
        File bundle = new File(directory, 'bundle.zip'); bundle.text = 'bundle'
        Map manifest = [selected: [[groupId: 'g', artifactId: 'a', version: '1']]]
        def barrier = new java.util.concurrent.CyclicBarrier(2)
        def uploads = new java.util.concurrent.atomic.AtomicInteger()
        def availability = new PublicationStatus() {
            @Override boolean published(ReleaseCoordinate coordinate, boolean both) {
                barrier.await(5, java.util.concurrent.TimeUnit.SECONDS)
                false
            }
        }
        def client = new CentralPortalClient(org.gradle.api.logging.Logging.getLogger(CombinedReleaseTest), null, 'u', 'p') {
            @Override String upload(File file) { uploads.incrementAndGet(); 'saved-id' }
        }
        def executor = java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            def calls = (1..2).collect {
                executor.submit({ ->
                    try { CombinedDeployment.submit(record, bundle, manifest, availability, client); true }
                    catch (IllegalStateException ignored) { false }
                } as java.util.concurrent.Callable<Boolean>)
            }
            assertEquals(1, calls.count { it.get(10, java.util.concurrent.TimeUnit.SECONDS) })
            assertEquals(1, uploads.get())
            assertEquals('saved-id', ReleaseManifest.read(record).deploymentId)
        } finally { executor.shutdownNow() }
    }

    private static void git(File root, String... args) {
        ReleaseManifest.git(root, args)
    }

    private static PublicationDescriptor candidate(String artifact, String version) {
        new PublicationDescriptor(coordinate: new ReleaseCoordinate('g', artifact, version), role: artifact == 'matrix-bom' ? 'bom' : 'module')
    }
    private static Map publication(String artifact, String packaging, List<String> files) {
        [groupId: 'g', artifactId: artifact, version: '1', packaging: packaging, sourcesAndJavadocRequired: artifact != 'matrix-all', files: files, publishedDependencies: []]
    }
    private static String pomText(String artifact, String packaging) {
        """<project><groupId>g</groupId><artifactId>${artifact}</artifactId><version>1</version><packaging>${packaging}</packaging><name>A</name><description>A</description><url>https://example.org</url><licenses><license><name>MIT</name><url>https://example.org</url></license></licenses><developers><developer><name>A</name></developer></developers><scm><url>https://example.org</url><connection>scm:git:example</connection></scm></project>"""
    }
}
