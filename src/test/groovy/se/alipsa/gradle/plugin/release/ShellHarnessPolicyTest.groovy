package se.alipsa.gradle.plugin.release

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Test
import static org.junit.jupiter.api.Assertions.*

class ShellHarnessPolicyTest {
    @Test void missingBashSkipsLocallyAndFailsWhenRequired() {
        String source = System.getProperty('shellHarnessPolicyScript')
        assertNotNull(source, 'shellHarnessPolicyScript must be supplied by PolicyScriptArg')
        assertTrue(new File(source).isFile())
        File fixture = File.createTempDir('shell-policy', '')
        try {
            new File(fixture, 'settings.gradle').text = "rootProject.name = 'shell-policy'"
            new File(fixture, 'policy.gradle').bytes = new File(source).bytes
            new File(fixture, 'build.gradle').text = "tasks.register('shellHarness')\napply from: 'policy.gradle'\n"
            def args = ['shellHarness', '--info', "-PshellHarnessBash=${new File(fixture, 'absent').absolutePath}".toString()]
            def runner = GradleRunner.create().withProjectDir(fixture)
            def skipped = runner.withArguments(args + ['-PrequireShellHarness=false']).build()
            assertEquals(TaskOutcome.SKIPPED, skipped.task(':shellHarness').outcome)
            assertTrue(skipped.output.contains('Bash is available, or required by CI'))
            def failed = runner.withArguments(args + ['-PrequireShellHarness=true']).buildAndFail()
            assertTrue(failed.output.contains('Bash is unavailable but requireShellHarness=true'))
        } finally { fixture.deleteDir() }
    }
}
