package se.alipsa.gradle.plugin.release

import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import static org.junit.jupiter.api.Assertions.*

class CombinedReleaseDslTest {
    @TempDir File root

    private GradleRunner fixture(String script, boolean kotlin = false) {
        new File(root, 'settings.gradle').text = "rootProject.name='dsl-fixture'"
        new File(root, kotlin ? 'build.gradle.kts' : 'build.gradle').text = script
        if (kotlin) {
            int expectedJava = Integer.parseInt(System.getProperty('testJavaVersion', '17'))
            String expectedGradle = System.getProperty('testGradleVersion', '')
            new File(root, 'build.gradle.kts') << """
println("TESTKIT_RUNTIME Gradle=" + gradle.gradleVersion + " JVM=" + Runtime.version().feature())
check(Runtime.version().feature() == ${expectedJava})
${expectedGradle ? 'check(gradle.gradleVersion == "' + expectedGradle + '")' : ''}
"""
        } else {
            MatrixRunner.instrument(root)
        }
        MatrixRunner.create().withProjectDir(root).withPluginClasspath()
            .withArguments('help', '--configuration-cache', '--configuration-cache-problems=fail', '--stacktrace')
    }

    @Test void sharedConventionsAndReadOnlyProvidersWorkUnderEagerRules() {
        ['tasks.withType(ReleaseAllPlanTask) { }', 'tasks.all { }', 'tasks.whenTaskAdded { }',
         'tasks.withType(ReleaseAllPlanTask).configureEach { }'].each { rule ->
            def runner = fixture("""
import se.alipsa.gradle.plugin.release.*
plugins { id 'se.alipsa.nexus-release-plugin' }
${rule}
def originalPlan
nexusReleasePlugin {
    userName = providers.provider { 'parent-user' }
    password = providers.provider { 'parent-password' }
    nexusUrl = 'https://portal.example/api'
    metadataBaseUrl = 'https://metadata.example'
    httpConnectTimeoutSeconds = 1
    httpRequestTimeoutSeconds = 2
    combinedRelease {
        assert !manifestFile.present
        assert !sourceDirectory.present
        originalPlan = planTask
        assert planTask.get().userName.get() == 'parent-user'
        sourceDirectory = layout.projectDirectory
        manifestFile = layout.buildDirectory.file('shared/manifest.json')
        stagingDirectory = layout.buildDirectory.dir('shared/staging')
        bundleFile = layout.buildDirectory.file('shared/bundle.zip')
        receiptFile = layout.buildDirectory.file('shared/receipt.json')
        recordFile = layout.buildDirectory.file('shared/record.json')
        bundleAllTask.configure { receiptFile.set(layout.buildDirectory.file('override.json')) }
    }
    combinedRelease {
        assert planTask.is(originalPlan)
        assert planTask.get().sourceDirectory.get().asFile == layout.projectDirectory.asFile
        [planTask, signingCheckTask, guardTask, prepareMavenTask, bundleAllTask, releaseAllTask].each {
            assert it.get().manifestFile.get().asFile == manifestFile.get().asFile
        }
        [planTask, guardTask, prepareMavenTask, bundleAllTask, releaseAllTask].each {
            assert it.get().sourceDirectory.get().asFile == sourceDirectory.get().asFile
        }
        [prepareMavenTask, bundleAllTask].each { assert it.get().stagingDirectory.get().asFile == stagingDirectory.get().asFile }
        [bundleAllTask, releaseAllTask].each { assert it.get().bundleFile.get().asFile == bundleFile.get().asFile }
        assert bundleAllTask.get().receiptFile.get().asFile.name == 'override.json'
        assert releaseAllTask.get().receiptFile.get().asFile == receiptFile.get().asFile
        assert releaseAllTask.get().recordFile.get().asFile == recordFile.get().asFile
        [planTask, releaseAllTask].each {
            assert it.get().userName.get() == 'parent-user'
            assert it.get().password.get() == 'parent-password'
            assert it.get().portalUrl.get() == 'https://portal.example/api'
            assert it.get().repositoryUrl.get() == 'https://repo.maven.apache.org/maven2'
        }
        nexusReleasePlugin.userName.set('updated-parent')
        assert releaseAllTask.get().userName.get() == 'updated-parent'
        userName = 'combined-user'
        assert planTask.get().userName.get() == 'combined-user'
        planTask.configure { userName.set('task-user') }
        assert planTask.get().userName.get() == 'task-user'
        manifestFile = layout.buildDirectory.file('changed.json')
        assert guardTask.get().manifestFile.get().asFile.name == 'changed.json'
        assert releaseAllTask.get().resumeId.get() == ''
        assert planTask.get().releaseNamespaces.get() == []
        assert !signingCheckTask.get().strict.get()
        assert prepareMavenTask.get().stagingRepositoryId.get() == 'release-staging'
    }
}
assert tasks.withType(ReleaseAllTask).size() == 1
""")
            assertTrue(runner.build().output.contains('BUILD SUCCESSFUL'))
            assertTrue(runner.build().output.contains('Reusing configuration cache'))
        }
    }

    @Test void kotlinUsesDecoratedPropertiesAndExplicitTaskLookup() {
        def runner = fixture('''
import se.alipsa.gradle.plugin.release.*
plugins { id("se.alipsa.nexus-release-plugin") }
tasks.withType<ReleaseAllPlanTask>().configureEach { releaseNamespaces.set(listOf("example")) }
nexusReleasePlugin {
    userName.set(providers.provider { "parent-user" })
    combinedRelease {
        sourceDirectory.set(layout.projectDirectory)
        manifestFile.set(layout.buildDirectory.file("manifest.json"))
        bundleAllTask.configure { bundleFile.set(layout.buildDirectory.file("bundle.zip")) }
        check(planTask.get().userName.get() == "parent-user")
    }
}
tasks.named<ReleaseAllPlanTask>("releaseAllPlan") {
    check(releaseNamespaces.get() == listOf("example"))
    check(manifestFile.get().asFile.name == "manifest.json")
}
tasks.named<ReleaseAllPlanTask>("releaseAllPlan").get()
''', true)
        assertTrue(runner.build().output.contains('BUILD SUCCESSFUL'))
        assertTrue(runner.build().output.contains('Reusing configuration cache'))
    }

    @Test void dslCannotReplaceRegistrarOrCombinedProviders() {
        def attempts = [
            'nexusReleasePlugin.registrar = { null }',
            'nexusReleasePlugin.setRegistrar({ null })',
            'nexusReleasePlugin.getCombinedRelease()',
            'nexusReleasePlugin.combined = null'
        ]
        ['planTask', 'signingCheckTask', 'guardTask', 'prepareMavenTask', 'bundleAllTask', 'releaseAllTask'].each { name ->
            attempts << "nexusReleasePlugin { combinedRelease { ${name} = null } }"
            attempts << "nexusReleasePlugin { combinedRelease { ${name} = tasks.named('help') } }"
        }
        attempts.each { attempt ->
            def result = fixture("plugins { id 'se.alipsa.nexus-release-plugin' }\n${attempt}\n").buildAndFail()
            assertTrue(result.output.contains('What went wrong:'), attempt)
            assertTrue(result.output.contains('read-only') || result.output.contains('Could not set unknown property') ||
                result.output.contains('Could not find method'), result.output)
            assertFalse(result.output.contains('NullPointerException'), attempt)
        }
    }
}
