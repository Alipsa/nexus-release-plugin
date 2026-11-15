package se.alipsa.gradle.plugin.release

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.gradle.testkit.runner.TaskOutcome
import java.util.zip.ZipFile
import static org.junit.jupiter.api.Assertions.*

class ModuleReleaseTest {
    private static final String BUNDLE = 'build/releaseAll/module-release-fixture-mega-bundle.zip'

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
                assertFalse(new File(root, BUNDLE).exists())
            } else {
                run('bundleAll')
                new ZipFile(new File(root, 'build/releaseAll/staging/example/api-artifact/1.0/api-artifact-1.0.jar')).withCloseable { jar ->
                    assertNotNull(jar.getEntry('Core.class'))
                    assertNotNull(jar.getEntry('Api.class'))
                }
                assertTrue(new File(root, BUNDLE).isFile())
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
            new ZipFile(new File(root, BUNDLE)).withCloseable { zip ->
                assertFalse(zip.entries().any { it.name.contains('.module') })
            }
        }
    }

    @Test void guardHashesPomsOnlyAfterPomRegenerationFinishes() {
        withServer { String base, List requests ->
            fixture(base, "modules = [':core']")
            // CI regenerated POMs during bundleAll; force that and widen the window in which
            // the guard intermittently saw a missing or partially written POM.
            new File(root, 'core/build.gradle') << '''
tasks.named('generatePomFileForMavenPublication') {
    outputs.upToDateWhen { false }
    doFirst { destination.delete(); Thread.sleep(1500) }
}
'''
            commit()
            run('releaseAllPlan')
            def bundle = run('bundleAll')
            assertEquals(org.gradle.testkit.runner.TaskOutcome.SUCCESS, bundle.task(':releaseAllGuard')?.outcome)
            assertEquals(org.gradle.testkit.runner.TaskOutcome.SUCCESS, bundle.task(':core:generatePomFileForMavenPublication')?.outcome)
            assertTrue(new File(root, BUNDLE).isFile())
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
            File zip = new File(root, BUNDLE)
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

    @Test
    @Timeout(120)
    void consumerConfiguredBundleFileOverridesTheModuleModeDefault() {
        withServer(Collections.synchronizedSet([] as Set), ['example:core-artifact:1.0'] as Set) { String base, List requests, List rawBodies ->
            fixture(base, "modules = [':core']; bundleFile = layout.buildDirectory.file('custom/my-bundle.zip')")
            run('releaseAllPlan')
            run(':bundleAll')
            assertTrue(new File(root, 'build/custom/my-bundle.zip').isFile())
            assertFalse(new File(root, BUNDLE).exists())
            run(':releaseAll')
            assertTrue(requests.any { it.contains('/publisher/upload') })
            assertTrue(rawBodies.any { new String(it as byte[], 'UTF-8').contains('filename="my-bundle.zip"') },
                { -> "upload should use my-bundle.zip; Content-Disposition lines: ${rawBodies.collect { new String(it as byte[], 'UTF-8') }*.readLines().flatten().findAll { it.startsWith('Content-Disposition') }}" } as java.util.function.Supplier)
        }
    }

    @Test void planAndConsumersCanShareAnInvocationAfterInitialPlanning() {
        withServer { String base, List requests ->
            fixture(base, "modules = [':core']")
            run(':releaseAllPlan')
            // Request consumers first to prove ordering is independent of CLI order.
            def result = run(':bundleAll', ':releaseAllPrepareMaven', ':releaseAllPlan')
            assertNotNull(result.task(':core:signMavenPublication'))
            assertTrue(new File(root, BUNDLE).isFile())
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

    @Test void mavenPreparationWaitsForRegeneratedPomsAndPrecedesBundling() {
        withServer { String base, List requests ->
            fixture(base, "modules = [':core']")
            run(':releaseAllPlan')
            // Preparation validates the planned POM even without a static Maven component,
            // so this exercises its real guard without requiring external Maven helpers.
            File pom = new File(root, 'core/build/publications/maven/pom-default.xml')
            2.times { int invocation ->
                assertTrue(pom.delete())
                def result = run(':bundleAll', ':releaseAllPrepareMaven', ':core:generatePomFileForMavenPublication')
                List<String> tasks = result.tasks*.path
                [':core:generatePomFileForMavenPublication', ':releaseAllPrepareMaven', ':bundleAll'].each { path ->
                    assertTrue(tasks.contains(path), 'Expected task in results: ' + path)
                }
                assertTrue(tasks.indexOf(':core:generatePomFileForMavenPublication') < tasks.indexOf(':releaseAllPrepareMaven'))
                assertTrue(tasks.indexOf(':releaseAllPrepareMaven') < tasks.indexOf(':bundleAll'))
                assertTrue(new File(root, BUNDLE).isFile())
                if (invocation == 1) assertTrue(result.output.contains('Reusing configuration cache'))
            }
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
            File zip = new File(root, BUNDLE)
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
            List<File> saved = ['manifest.json', 'bundle-receipt.json'].collect {
                new File(root, 'build/releaseAll/' + it)
            } + new File(root, BUNDLE)
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
            new ZipFile(new File(root, BUNDLE)).withCloseable { zip ->
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
            new ZipFile(new File(root, BUNDLE)).withCloseable { zip ->
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
            new File(root, 'build.gradle') << "\nreleaseAll { resumeId = 'saved-id'; useExistingBundle = true; expectedBundleSha256 = 'ignored-during-resume'; expectedManifestSha256 = 'also-ignored' }\n"
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

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    void existingBundleUploadsWithoutRebuilding(boolean uppercase) {
        withServer([] as Set, ['example:core-artifact:1.0'] as Set) { String base, List requests, List rawBodies ->
            fixture(base, "modules = [':core']")
            new File(root, 'build.gradle') << "\nreleaseAll { useExistingBundle = providers.gradleProperty('existing').isPresent(); expectedBundleSha256 = providers.gradleProperty('expected'); expectedManifestSha256 = providers.gradleProperty('expectedManifest') }\n"
            commit()
            run('releaseAllPlan')
            run('bundleAll')
            byte[] original = new File(root, BUNDLE).bytes
            String bundleHash = ReleaseChecksums.checksum(new File(root, BUNDLE), 'SHA-256')
            String manifestHash = ReleaseChecksums.checksum(new File(root, 'build/releaseAll/manifest.json'), 'SHA-256')
            def result = run('releaseAll', '-Pexisting', '-Pexpected=' + (uppercase ? bundleHash.toUpperCase(Locale.ROOT) : bundleHash),
                '-PexpectedManifest=' + (uppercase ? manifestHash.toUpperCase(Locale.ROOT) : manifestHash))
            assertNull(result.task(':bundleAll'))
            assertNull(result.task(':core:jar'))
            assertNull(result.task(':core:check'))
            assertNotNull(result.task(':releaseAllGuard'))
            assertArrayEquals(original, new File(root, BUNDLE).bytes)
            assertTrue(requests.any { it.contains('/publisher/upload') })
            assertTrue(rawBodies.findAll { it.length > 0 }.any { byte[] body -> Arrays.equals(multipartFile(body), original) })
        }
    }

    @Test void existingBundleRejectsTamperingWithoutUpload() {
        withServer { String base, List requests ->
            fixture(base, "modules = [':core']")
            new File(root, 'build.gradle') << "\nreleaseAll { useExistingBundle = providers.gradleProperty('existing').isPresent(); expectedBundleSha256 = providers.gradleProperty('expected'); expectedManifestSha256 = providers.gradleProperty('expectedManifest') }\n"
            commit()
            run('releaseAllPlan')
            run('bundleAll')
            String expected = ReleaseChecksums.checksum(new File(root, BUNDLE), 'SHA-256')
            String expectedManifest = ReleaseChecksums.checksum(new File(root, 'build/releaseAll/manifest.json'), 'SHA-256')
            new File(root, BUNDLE) << 'changed'
            def failed = runner('releaseAll', '-Pexisting', '-Pexpected=' + expected, '-PexpectedManifest=' + expectedManifest).buildAndFail()
            assertNull(failed.task(':bundleAll'))
            assertTrue(failed.output.contains('differs from expectedBundleSha256'))
            assertFalse(requests.any { it.contains('/publisher/upload') })
        }
    }

    /** The fixture server receives one file part; ISO-8859-1 preserves byte offsets. */
    private static byte[] multipartFile(byte[] body) {
        String text = new String(body, java.nio.charset.StandardCharsets.ISO_8859_1)
        int headerEnd = text.indexOf('\r\n\r\n')
        assertTrue(headerEnd >= 0)
        String boundary = text.substring(0, text.indexOf('\r\n'))
        int end = text.lastIndexOf('\r\n' + boundary + '--\r\n')
        assertTrue(end >= headerEnd + 4)
        Arrays.copyOfRange(body, headerEnd + 4, end)
    }

    @ParameterizedTest
    @ValueSource(strings = ['dirty', 'commit', 'manifest', 'replan', 'missing-receipt', 'missing-zip', 'rewritten-receipt', 'rewritten-manifest-receipt', 'guard-manifest'])
    void existingBundleRejectsDriftAndMissingFiles(String change) {
        withServer { String base, List requests ->
            existingFixture(base)
            run('releaseAllPlan')
            run('bundleAll')
            String expected = ReleaseChecksums.checksum(new File(root, BUNDLE), 'SHA-256')
            String expectedManifest = ReleaseChecksums.checksum(new File(root, 'build/releaseAll/manifest.json'), 'SHA-256')
            switch (change) {
                case 'dirty': new File(root, 'core/src/main/java/Core.java') << '\n// changed'; break
                case 'commit':
                    new File(root, 'core/src/main/java/Core.java') << '\n// changed'
                    commit(); break
                case 'manifest': new File(root, 'build/releaseAll/manifest.json') << '\n'; break
                case 'replan':
                    new File(root, 'core/src/main/java/Core.java') << '\n// changed'
                    commit()
                    run('releaseAllPlan'); break
                case 'missing-receipt': assertTrue(new File(root, 'build/releaseAll/bundle-receipt.json').delete()); break
                case 'missing-zip': assertTrue(new File(root, BUNDLE).delete()); break
                case 'guard-manifest':
                    File guardManifestFile = new File(root, 'build/releaseAll/manifest.json')
                    Map guardManifest = ReleaseManifest.read(guardManifestFile)
                    guardManifest.modulePaths = [':replaced']
                    guardManifest.revision = 'replaced'
                    ReleaseManifest.write(guardManifestFile, guardManifest)
                    break
                case 'rewritten-manifest-receipt':
                    // Bypass the mutable source/selection guard and update its mutable receipt.
                    new File(root, 'core/src/main/java/Core.java') << '\n// changed'
                    commit()
                    File manifestFile = new File(root, 'build/releaseAll/manifest.json')
                    Map manifest = ReleaseManifest.read(manifestFile)
                    manifest.revision = ReleaseManifest.git(root, 'rev-parse', 'HEAD')
                    manifest.selected = []
                    ReleaseManifest.write(manifestFile, manifest)
                    File manifestReceiptFile = new File(root, 'build/releaseAll/bundle-receipt.json')
                    Map manifestReceipt = ReleaseManifest.read(manifestReceiptFile)
                    manifestReceipt.manifestHash = ReleaseChecksums.checksum(manifestFile, 'SHA-256')
                    ReleaseManifest.write(manifestReceiptFile, manifestReceipt)
                    break
                case 'rewritten-receipt':
                    new File(root, BUNDLE) << 'changed'
                    File receiptFile = new File(root, 'build/releaseAll/bundle-receipt.json')
                    Map receipt = ReleaseManifest.read(receiptFile)
                    receipt.bundleHash = ReleaseChecksums.checksum(new File(root, BUNDLE), 'SHA-256')
                    ReleaseManifest.write(receiptFile, receipt)
            }
            def failed = runner('releaseAll', '-Pexisting', '-Pexpected=' + expected, '-PexpectedManifest=' + expectedManifest).buildAndFail()
            String message = change in ['dirty', 'commit'] ? 'Stale manifest' :
                change.startsWith('missing-') ? 'Prepared release file missing' :
                change == 'rewritten-receipt' ? 'differs from expectedBundleSha256' : 'differs from expectedManifestSha256'
            assertTrue(failed.output.contains(message), failed.output)
            assertNull(failed.task(':bundleAll'))
            assertFalse(requests.any { it.contains('/publisher/upload') })
        }
    }

    @Test void existingBundleRequiresReviewedDigestAndPriorBundle() {
        withServer { String base, List requests ->
            existingFixture(base)
            run('releaseAllPlan')
            def missingDigest = runner('releaseAll', '-Pexisting').buildAndFail()
            assertTrue(missingDigest.output.contains('requires expectedBundleSha256'))
            def missingBundle = runner('releaseAll', '-Pexisting', '-Pexpected=' + ('0' * 64),
                '-PexpectedManifest=' + ReleaseChecksums.checksum(new File(root, 'build/releaseAll/manifest.json'), 'SHA-256')).buildAndFail()
            assertTrue(missingBundle.output.contains('Prepared release file missing'))
            assertFalse(requests.any { it.contains('/publisher/upload') })
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ['bundleAll', 'releaseAll', 'dependency'])
    void existingBundleRejectsBundlingInTheUploadGraph(String first) {
        withServer { String base, List requests ->
            existingFixture(base)
            run('releaseAllPlan')
            if (first == 'dependency') {
                new File(root, 'build.gradle') << "\ntasks.register('consumer') { dependsOn bundleAll }\n"
                commit()
            }
            String second = first == 'bundleAll' ? 'releaseAll' : first == 'dependency' ? 'releaseAll' : 'bundleAll'
            if (first == 'dependency') first = 'consumer'
            def failed = runner(first, second, '-Pexisting', '-Pexpected=' + ('0' * 64), '-PexpectedManifest=' + ('0' * 64)).buildAndFail()
            assertTrue(failed.output.contains('Do not request bundleAll and releaseAll'))
            assertTrue(failed.tasks.empty)
            assertFalse(requests.any { it.contains('/publisher/upload') })
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ['bundle-malformed', 'manifest-malformed', 'manifest-missing', 'bundle-normal', 'manifest-normal', 'malformed-normal'])
    void evidenceInputErrorsFailBeforeAnyTask(String scenario) {
        withServer { String base, List requests ->
            existingFixture(base)
            String valid = 'a' * 64
            List<String> args = ['-Pexpected=' + valid, '-PexpectedManifest=' + valid]
            if (scenario.endsWith('normal')) {
                args = scenario == 'manifest-normal' ? ['-PexpectedManifest=' + valid] :
                    ['-Pexpected=' + (scenario == 'malformed-normal' ? 'oops' : valid)]
            } else {
                args.add('-Pexisting')
                if (scenario == 'manifest-missing') args.remove('-PexpectedManifest=' + valid)
                else if (scenario == 'bundle-malformed') args[0] = '-Pexpected=' + ('z' * 64)
                else args[1] = '-PexpectedManifest=123'
            }
            // No plan/bundle exists: input validation must win before preparation or source guards.
            def failed = runner('releaseAll', *args).buildAndFail()
            String message = scenario == 'manifest-missing' ? 'requires expectedManifestSha256' :
                scenario in ['bundle-normal', 'manifest-normal'] ? 'supported only in existing-bundle mode' :
                'must be a 64-character hexadecimal SHA-256'
            assertTrue(failed.output.contains(message), failed.output)
            assertTrue(failed.tasks.empty)
            assertTrue(requests.empty)
        }
    }

    @Test void normalUploadMissingFileExplainsPathMismatch() {
        withServer { String base, List requests ->
            fixture(base, "modules = [':core']")
            new File(root, 'build.gradle') << "\nreleaseAll { receiptFile = layout.buildDirectory.file('missing-receipt.json') }\n"
            commit()
            run('releaseAllPlan')
            def failed = runner('releaseAll').buildAndFail()
            assertTrue(failed.output.contains('check that releaseAll and bundleAll use the same'))
            assertFalse(failed.output.contains('before existing-bundle upload'))
            assertFalse(requests.any { it.contains('/publisher/upload') })
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ['bundleAll', 'releaseAllSigningCheck', 'releaseAllGuard'])
    void nonUploadGraphsIgnoreExportedUploadEvidence(String task) {
        withServer { String base, List requests ->
            existingFixture(base)
            run('releaseAllPlan')
            // Every case schedules the guard, so passing proves it ignored the malformed evidence.
            String[] args = ['-Pexisting', '-Pexpected=not-an-upload-digest', '-PexpectedManifest=not-a-manifest-digest', '--info']
            def first = run(task, *args)
            assertNull(first.task(':releaseAll'))
            assertEquals(TaskOutcome.SUCCESS, first.task(':releaseAllGuard')?.outcome)
            // bundleAll stages modules, whose check tasks depend on the guard.
            if (task == 'bundleAll') assertEquals(TaskOutcome.SUCCESS, first.task(':core:check')?.outcome)
            assertTrue(first.output.contains('Upload evidence ignored: releaseAll is not scheduled'), first.output)
            def cached = run(task, *args)
            assertNull(cached.task(':releaseAll'))
            assertEquals(TaskOutcome.SUCCESS, cached.task(':releaseAllGuard')?.outcome)
            assertTrue(cached.output.contains('Reusing configuration cache.'))
            assertFalse(requests.any { it.contains('/publisher/upload') })
        }
    }

    @Test void absentExistingBundleProviderDefaultsToNormalBuild() {
        withServer([] as Set, ['example:core-artifact:1.0'] as Set) { String base, List requests, List rawBodies ->
            fixture(base, "modules = [':core']")
            new File(root, 'build.gradle') << "\nreleaseAll { useExistingBundle = providers.gradleProperty('existing').map { it.toBoolean() } }\n"
            commit()
            run('releaseAllPlan')
            assertNotNull(run('releaseAll').task(':bundleAll'))
        }
    }

    private void existingFixture(String base) {
        fixture(base, "modules = [':core']")
        new File(root, 'build.gradle') << "\nreleaseAll { useExistingBundle = providers.gradleProperty('existing').isPresent(); expectedBundleSha256 = providers.gradleProperty('expected'); expectedManifestSha256 = providers.gradleProperty('expectedManifest') }\n"
        commit()
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
        withServer(published, false, [] as Set) { String base, List requests, List rawBodies -> action(base, requests) }
    }

    /** Variant for full upload flows: captures bodies, answers uploads, and marks the given coordinates
     *  published once the upload request arrives, so post-upload availability probes succeed. */
    private void withServer(Set<String> published, Set<String> deployedOnUpload, Closure action) {
        withServer(published, true, deployedOnUpload, action)
    }

    private void withServer(Set<String> published, boolean captureBodies, Set<String> deployedOnUpload, Closure action) {
        List<String> requests = Collections.synchronizedList([])
        List<byte[]> rawBodies = Collections.synchronizedList([])
        def server = HttpServer.create(new InetSocketAddress('127.0.0.1', 0), 0)
        server.createContext('/') { exchange ->
            requests.add(exchange.requestURI.toString())
            boolean portal = exchange.requestURI.path.contains('/publisher/published')
            boolean status = exchange.requestURI.path.contains('/publisher/status')
            boolean upload = exchange.requestURI.path.contains('/publisher/upload')
            boolean released = published.any { String coordinate ->
                def (group, name, version) = coordinate.split(':')
                exchange.requestURI.path.endsWith("/${group.replace('.', '/')}/${name}/${version}/${name}-${version}.pom") ||
                    exchange.requestURI.path.endsWith("/${group.replace('.', '/')}/${name}/${version}/${name}-${version}.jar")
            }
            if (captureBodies) {
                byte[] raw = exchange.requestBody.readAllBytes()
                rawBodies.add(raw)
            }
            if (upload) published.addAll(deployedOnUpload)
            byte[] body = (status ? '{"deploymentState":"PUBLISHED"}' : portal ? '{"published":false}' : upload ? 'upload-deployment-id' : '').getBytes('UTF-8')
            exchange.sendResponseHeaders(portal || status || upload || released ? 200 : 404, exchange.requestMethod == 'HEAD' ? -1 : body.length)
            exchange.responseBody.withCloseable { it.write(body) }
        }
        server.start()
        try {
            String base = "http://127.0.0.1:${server.address.port}".toString()
            action(base, requests, rawBodies)
        }
        finally { server.stop(0) }
    }

    private def run(String task, String... flags) { runner(task, *flags).build() }
    private def runner(String task, String... flags) {
        MatrixRunner.create().withProjectDir(root).withPluginClasspath()
            .withArguments([task, '--configuration-cache', '--configuration-cache-problems=fail', '--stacktrace'] + flags.toList())
    }
}
