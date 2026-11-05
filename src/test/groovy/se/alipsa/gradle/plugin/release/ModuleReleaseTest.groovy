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

    @Test void helpDoesNotRealizeReleaseAll() {
        withServer { String base, List requests ->
            fixture(base, "modules = [':api']", false, false)
            new File(root, 'build.gradle') << "\ntasks.named('releaseAll').configure { throw new GradleException('Unexpected releaseAll realization') }\n"
            run('help')
            assertTrue(requests.empty)
        }
    }

    @Test void configureOnDemandConfiguresOnlyTheSelectedModulesAndTheirDependencies() {
        withServer(['example:core-artifact:1.0'] as Set) { String base, List requests ->
            fixture(base, "modules = [':api']")
            new File(root, 'unused/build.gradle').text = "throw new GradleException('Unrelated project was configured')"
            commit()
            run(':releaseAllPlan', '--configure-on-demand')
            assertTrue(run(':releaseAllPlan', '--configure-on-demand').output.contains('Reusing configuration cache'))
            assertEquals([':api'], ReleaseManifest.read(new File(root, 'build/releaseAll/manifest.json')).selected*.projectPath)
            def bundle = run(':bundleAll', '--configure-on-demand')
            assertNotNull(bundle.task(':api:signMavenPublication'))
            assertNull(bundle.task(':core:signMavenPublication'))
            assertTrue(run(':bundleAll', '--configure-on-demand').output.contains('Reusing configuration cache'))
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    void unpublishedCompileOnlyProjectsRequireAnExplicitOptOut(boolean optOut) {
        withServer { String base, List requests ->
            fixture(base, "modules = [':api']${optOut ? "; unpublishedProjectDependencies = ['core']" : ''}", true)
            makeCoreInternal()
            commit()
            run('releaseAllPlan')
            Map manifest = ReleaseManifest.read(new File(root, 'build/releaseAll/manifest.json'))
            if (!optOut) {
                assertEquals([], manifest.selected)
                assertTrue(manifest.excluded.first().reasons.any { it.contains("Project dependency ':core' has no Maven publication") })
                return
            }
            assertEquals([':api'], manifest.selected*.projectPath)
            assertEquals([':core'], manifest.selected.first().unpublishedProjectDependencies*.projectPath)
            assertEquals([':core'], manifest.unpublishedProjectPaths)
            assertTrue(run('releaseAllPlan').output.contains('Reusing configuration cache'))
            run('bundleAll')
            assertTrue(run('bundleAll').output.contains('Reusing configuration cache'))
            assertFalse(requests.any { it.contains('internal') })
        }
    }

    @Test void optOutCannotHideAnUnpublishedDependencyInThePom() {
        withServer { String base, List requests ->
            fixture(base, "modules = [':api']; unpublishedProjectDependencies = [':core']")
            makeCoreInternal()
            commit()
            run('releaseAllPlan')
            Map manifest = ReleaseManifest.read(new File(root, 'build/releaseAll/manifest.json'))
            assertEquals([], manifest.selected)
            assertTrue(manifest.excluded.first().reasons.any { it.contains("Unpublished project dependency ':core' is still referenced by the published POM") })
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    void embeddedProjectsMustAlsoBeRemovedFromGradleMetadata(boolean disableMetadata) {
        withServer { String base, List requests ->
            fixture(base, "modules = [':api']; unpublishedProjectDependencies = [':core']")
            makeCoreInternal()
            new File(root, 'api/build.gradle') << '''
tasks.named('jar') { from(project(':core').sourceSets.main.output) }
publishing.publications.maven.pom.withXml {
    asNode().dependencies.dependency.findAll {
        it.groupId.text() == 'internal' && it.artifactId.text() == 'core'
    }.each { it.parent().remove(it) }
}
'''
            if (disableMetadata) new File(root, 'api/build.gradle') << '\ntasks.withType(org.gradle.api.publish.tasks.GenerateModuleMetadata).configureEach { enabled = false }\n'
            commit()
            run('releaseAllPlan')
            assertEquals([':api'], ReleaseManifest.read(new File(root, 'build/releaseAll/manifest.json')).selected*.projectPath)
            if (!disableMetadata) {
                def failed = runner('bundleAll').buildAndFail()
                assertTrue(failed.output.contains("Unpublished project dependency ':core' is still referenced by Gradle Module Metadata"))
                assertFalse(new File(root, 'build/releaseAll/bundle.zip').exists())
            } else {
                run('bundleAll')
                new ZipFile(new File(root, 'build/releaseAll/staging/example/api-artifact/1.0/api-artifact-1.0.jar')).withCloseable { jar ->
                    assertNotNull(jar.getEntry('Core.class'))
                    assertNotNull(jar.getEntry('Api.class'))
                }
                assertTrue(new File(root, 'build/releaseAll/bundle.zip').isFile())
            }
        }
    }

    @Test void changingOptOutsRequiresANewPlanEvenWithoutSourceChanges() {
        withServer { String base, List requests ->
            fixture(base, "modules = [':api']; unpublishedProjectDependencies = providers.gradleProperty('embedded').map { [it] }.orElse([])", true)
            makeCoreInternal()
            commit()
            run('releaseAllPlan', '-Pembedded=core')
            def failed = runner('bundleAll').buildAndFail()
            assertTrue(failed.output.contains('Unpublished project dependency opt-outs changed; run releaseAllPlan again'))
            assertNull(failed.task(':api:signMavenPublication'))
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ['1.0-SNAPSHOT', '1.0'])
    void ordinaryLocalPublishingDoesNotRequireOrAddSigning(String version) {
        withServer { String base, List requests ->
            fixture(base, "modules = [':api']", false, false)
            ['core', 'api'].each { String name ->
                File build = new File(root, "${name}/build.gradle")
                build.text = build.text.replace("version = '1.0'", "version = '${version}'")
            }
            def arguments = [':api:publishToMavenLocal', "-Dmaven.repo.local=${new File(root, 'build/maven-local').absolutePath}".toString(),
                '--configuration-cache', '--configuration-cache-problems=fail', '--stacktrace']
            def result = runner(':api:publishToMavenLocal').withArguments(arguments).build()
            assertNull(result.task(':api:signMavenPublication'))
            assertNull(result.task(':releaseAllGuard'))
            assertTrue(new File(root, "build/maven-local/example/api-artifact/${version}/api-artifact-${version}.pom").isFile())
            assertFalse(new File(root, "build/maven-local/example/api-artifact/${version}/api-artifact-${version}.pom.asc").exists())
            assertTrue(runner(':api:publishToMavenLocal').withArguments(arguments).build().output.contains('Reusing configuration cache'))
            assertTrue(requests.empty)
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ['none', 'multiple', 'conflicting'])
    void internalPublicationProblemsOnlyExcludeCandidatesDuringPlanning(String publications) {
        withServer { String base, List requests ->
            fixture(base, "modules = [':api']")
            File internal = new File(root, 'core/build.gradle')
            if (publications == 'none') {
                internal.text = "plugins { id 'java-library' }\ngroup = 'example'; version = '1.0'\n"
            } else {
                internal << "\npublishing { publications { extra(MavenPublication) { artifactId = '${publications == 'conflicting' ? 'other-core-artifact' : 'core-artifact'}'; from components.java } } }\n"
                new File(root, 'build.gradle') << "\nnexusReleasePlugin { combinedRelease { unpublishedProjectDependencies = [':core'] } }\n"
            }
            new File(root, 'build.gradle') << '''
if (gradle.startParameter.taskNames == ['help']) {
    subprojects {
        tasks.withType(org.gradle.api.publish.maven.tasks.GenerateMavenPom).configureEach {
            throw new GradleException('Ordinary help realized a release POM task')
        }
    }
}
'''
            commit()
            run('help')
            def result = run(':api:assemble')
            assertNull(result.task(':releaseAllPlan'))
            if (publications != 'conflicting') {
                // Gradle itself rejects POM generation for conflicting publication coordinates.
                def local = runner(':api:publishToMavenLocal').withArguments(':api:publishToMavenLocal',
                    "-Dmaven.repo.local=${new File(root, 'build/maven-local').absolutePath}".toString(),
                    '--configuration-cache', '--configuration-cache-problems=fail', '--stacktrace').build()
                assertNull(local.task(':api:signMavenPublication'))
            }
            assertTrue(requests.empty)
            def plan = run('releaseAllPlan')
            assertNull(plan.task(':api:generatePomFileForMavenPublication'))
            Map manifest = ReleaseManifest.read(new File(root, 'build/releaseAll/manifest.json'))
            assertEquals([], manifest.selected)
            assertTrue(manifest.excluded.first().reasons.any {
                it.contains("Project dependency ':core'") && it.contains(publications == 'none' ? 'no Maven publication' : 'multiple Maven publications')
            })
        }
    }

    @Test void combinedStagingStillRequiresSigningKeys() {
        withServer { String base, List requests ->
            fixture(base, "modules = [':api', ':core']", false, false)
            run('releaseAllPlan')
            def failed = runner('bundleAll').buildAndFail()
            assertTrue(failed.output.contains('Signing is not configured for:'))
            assertNull(failed.task(':api:publishMavenPublicationToCombinedReleaseStagingRepository'))
        }
    }

    @Test void consumerConfiguredSigningRequirementsArePreserved() {
        withServer { String base, List requests ->
            fixture(base, "modules = [':api']", false, false)
            new File(root, 'api/build.gradle') << "\napply plugin: 'signing'\nsigning { required = true; sign publishing.publications.maven }\n"
            def failed = runner(':api:publishToMavenLocal').withArguments(':api:publishToMavenLocal',
                "-Dmaven.repo.local=${new File(root, 'build/maven-local').absolutePath}".toString(),
                '--configuration-cache', '--configuration-cache-problems=fail', '--stacktrace').buildAndFail()
            assertTrue(failed.output.contains('has no configured signatory'))
            assertNull(failed.task(':releaseAllGuard'))
            assertTrue(requests.empty)
        }
    }

    @Test void disabledGradleMetadataIsNotPlannedOrBundled() {
        withServer { String base, List requests ->
            fixture(base, "modules = [':core']")
            new File(root, 'core/build.gradle') << '\ntasks.withType(org.gradle.api.publish.tasks.GenerateModuleMetadata).configureEach { enabled = false }\n'
            commit()
            run('releaseAllPlan')
            Map publication = ReleaseManifest.read(new File(root, 'build/releaseAll/manifest.json')).selected.first()
            assertFalse(publication.files.any { it.endsWith('.module') })
            run('bundleAll')
            new ZipFile(new File(root, 'build/releaseAll/bundle.zip')).withCloseable { zip ->
                assertFalse(zip.entries().any { it.name.contains('.module') })
            }
        }
    }

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

    @Test void planAndConsumersCanShareAnInvocationAfterInitialPlanning() {
        withServer { String base, List requests ->
            fixture(base, "modules = [':core']")
            run(':releaseAllPlan')
            // Request consumers first to prove ordering is independent of CLI order.
            def result = run(':bundleAll', ':releaseAllPrepareMaven', ':releaseAllPlan')
            assertNotNull(result.task(':core:signMavenPublication'))
            assertTrue(new File(root, 'build/releaseAll/bundle.zip').isFile())
            List<String> tasks = result.tasks*.path
            [':releaseAllSigningCheck', ':releaseAllGuard', ':releaseAllPrepareMaven', ':bundleAll'].each {
                assertTrue(tasks.indexOf(':releaseAllPlan') < tasks.indexOf(it))
            }
            // Replanning updates the manifest configuration input; standalone bundles
            // retain cache reuse once that saved manifest is stable.
            run(':bundleAll')
            assertTrue(run(':bundleAll').output.contains('Reusing configuration cache'))
        }
    }

    @Test void changedSelectionCannotReuseStaleStagedArtifactsAfterReplanning() {
        withServer { String base, List requests ->
            fixture(base, "modules = [':api', ':core']")
            run(':releaseAllPlan')
            run(':bundleAll')
            File stagedApi = new File(root, 'build/releaseAll/staging/example/api-artifact/1.0/api-artifact-1.0.jar')
            byte[] staleBytes = stagedApi.bytes
            File build = new File(root, 'build.gradle')
            build.text = build.text.replace("modules = [':api', ':core']", "modules = [':core']")
            commit()
            run(':releaseAllPlan')
            build.text = build.text.replace("modules = [':core']", "modules = [':api', ':core']")
            new File(root, 'api/src/main/java/Api.java').text = '/** Updated. */ public class Api { public int value() { return 42; } }'
            commit()
            File zip = new File(root, 'build/releaseAll/bundle.zip')
            File receipt = new File(root, 'build/releaseAll/bundle-receipt.json')
            assertTrue(zip.delete())
            assertTrue(receipt.delete())
            def failed = runner(':releaseAllPlan', ':bundleAll').buildAndFail()
            assertTrue(failed.output.contains('Manifest selection changed after staging was configured'))
            assertTrue(failed.output.contains('configured modules/publications do not match the saved plan'))
            assertNull(failed.task(':api:signMavenPublication'))
            assertNull(failed.task(':api:check'))
            assertArrayEquals(staleBytes, stagedApi.bytes)
            assertFalse(zip.exists())
            assertFalse(receipt.exists())
            assertFalse(requests.any { it.contains('/publisher/upload') })
            // A separate invocation configures staging from the new plan and repairs it.
            run(':bundleAll')
            assertFalse(Arrays.equals(staleBytes, stagedApi.bytes))
            assertTrue(zip.isFile())
        }
    }

    @Test void savedPublicationMismatchReportsTheSavedPlan() {
        withServer { String base, List requests ->
            fixture(base, "modules = [':core']")
            run(':releaseAllPlan')
            run(':bundleAll')
            File manifestFile = new File(root, 'build/releaseAll/manifest.json')
            Map manifest = ReleaseManifest.read(manifestFile)
            manifest.selected.first().publication = 'previousPublication'
            ReleaseManifest.write(manifestFile, manifest)
            def failed = runner(':bundleAll').buildAndFail()
            assertTrue(failed.output.contains('configured modules/publications do not match the saved plan'))
            assertNull(failed.task(':core:signMavenPublication'))
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ['releaseAllPlan', ':releaseAllPlan', 'releaseAllPl', 'fullRelease'])
    void planningAndUploadingTogetherFailsBeforeAnyTaskRuns(String request) {
        withServer { String base, List requests ->
            fixture(base, "modules = [':core']")
            new File(root, 'build.gradle') << "\ntasks.register('fullRelease') { dependsOn 'releaseAllPlan', 'releaseAll' }\n"
            commit()
            run(':releaseAllPlan')
            run(':bundleAll')
            List<File> saved = ['manifest.json', 'bundle.zip', 'bundle-receipt.json'].collect {
                new File(root, 'build/releaseAll/' + it)
            }
            List<byte[]> original = saved.collect { it.bytes }
            requests.clear()
            String[] flags = request == 'fullRelease' ? [] as String[] : [':releaseAll'] as String[]
            // A rejected graph must not be reused to bypass validation on a retry.
            2.times {
                def failed = runner(request, *flags).buildAndFail()
                assertTrue(failed.output.contains('Do not request releaseAllPlan and releaseAll in the same invocation; review the saved manifest'))
                assertTrue(failed.tasks.empty, 'No planning, checking, signing, staging, or bundling task may run')
                saved.eachWithIndex { File file, int index -> assertArrayEquals(original[index], file.bytes) }
                assertFalse(new File(root, 'build/releaseAll/deployment.json').exists())
                assertTrue(requests.empty, 'No Portal or repository request may run')
                assertFalse(failed.output.contains('Reusing configuration cache'), failed.output)
            }
        }
    }

    @Test void omittedUnpublishedSiblingBlocksConsumerAcrossNamespacesAndCompileOnlyEdges() {
        withServer { String base, List requests ->
            fixture(base, "modules = [':api']; unpublishedProjectDependencies = [':core']", true)
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
        unpublishedProjectDependencies.set(emptyList())
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

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    void resumeOnlyUsesSavedDeploymentWithoutModulePreparation(boolean configureOnDemand) {
        withServer(['example:api-artifact:1.0'] as Set) { String base, List requests ->
            fixture(base, "modules = [':missing']")
            new File(root, 'build.gradle') << "\nreleaseAll { resumeId = 'saved-id' }\n"
            new File(root, 'build.gradle') << '\ntasks.all { }\n'
            if (configureOnDemand) new File(root, 'unused/build.gradle').text = "throw new GradleException('Resume configured an unrelated project')"
            ReleaseManifest.write(new File(root, 'build/releaseAll/deployment.json'), [deploymentId: 'saved-id', state: 'UPLOADED',
                manifest: [selected: [[groupId: 'example', artifactId: 'api-artifact', version: '1.0', files: ['api-artifact-1.0.pom']]]]])
            String task = configureOnDemand ? ':releaseAll' : 'releaseAll'
            String[] flags = configureOnDemand ? ['--configure-on-demand'] as String[] : new String[0]
            def result = run(task, *flags)
            assertEquals([':releaseAll'], result.tasks*.path)
            assertTrue(run(task, *flags).output.contains('Reusing configuration cache'))
        }
    }

    private void makeCoreInternal() {
        new File(root, 'core/build.gradle').text = "plugins { id 'java-library' }\ngroup = 'internal'; version = '1.0'\n"
    }

    private void fixture(String base, String selection, boolean compileOnly = false, boolean signingKeys = true) {
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
        String key = signingKeys ? TestFixtures.generateTestPrivateKey('test@example.com', new char[0]) : ''
        ['core', 'api', 'unused'].each { String name ->
            File dir = new File(root, name); dir.mkdirs()
            new File(dir, 'build.gradle').text = """
plugins { id 'java-library'; id 'maven-publish'${signingKeys ? "; id 'signing'" : ''} }
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
${signingKeys ? "signing { useInMemoryPgpKeys('''${key}''', '') }" : ''}
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

    private def run(String task, String... flags) { runner(task, *flags).build() }
    private def runner(String task, String... flags) {
        MatrixRunner.create().withProjectDir(root).withPluginClasspath()
            .withArguments([task, '--configuration-cache', '--configuration-cache-problems=fail', '--stacktrace'] + flags.toList())
    }
}
