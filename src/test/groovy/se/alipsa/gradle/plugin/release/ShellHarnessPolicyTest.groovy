package se.alipsa.gradle.plugin.release

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Test
import static org.junit.jupiter.api.Assertions.*

class ShellHarnessPolicyTest {
    @Test void probesBashOnlyWhenHarnessRunsIncludingConfigurationCacheReuse() {
        boolean bashPresent
        try { bashPresent = new ProcessBuilder('bash', '-c', 'exit 0').start().waitFor() == 0 }
        catch (IOException ignored) { bashPresent = false }
        org.junit.jupiter.api.Assumptions.assumeTrue(bashPresent, 'Probe fixture needs Bash')
        File fixture = File.createTempDir('shell-policy-lazy', '')
        try {
            new File(fixture, 'settings.gradle').text = "rootProject.name = 'shell-policy-lazy'"
            new File(fixture, 'policy.gradle').bytes = new File(System.getProperty('shellHarnessPolicyScript')).bytes
            new File(fixture, 'build.gradle').text = "tasks.register('shellHarness') { doLast { logger.lifecycle('harness ran') } }\ntasks.register('unrelated')\napply from: 'policy.gradle'\n"
            File marker = new File(fixture, 'probed')
            File bash = new File(fixture, 'bash-probe')
            bash.text = '#!/usr/bin/env bash\ntouch "' + marker.absolutePath + '"\nexit 0\n'
            assertTrue(bash.setExecutable(true))
            def runner = GradleRunner.create().withProjectDir(fixture)
            def args = ['--configuration-cache', "-PshellHarnessBash=${bash.absolutePath}".toString()]
            runner.withArguments(['unrelated'] + args).build()
            assertFalse(marker.exists(), 'Unrelated task must not probe Bash during configuration')
            def reused = runner.withArguments(['unrelated'] + args).build()
            assertTrue(reused.output.contains('Reusing configuration cache'))
            assertFalse(marker.exists(), 'Unrelated cache hit must not probe Bash')
            assertEquals(TaskOutcome.SUCCESS, runner.withArguments(['shellHarness'] + args).build().task(':shellHarness').outcome)
            assertTrue(marker.exists(), 'Scheduled harness must probe Bash')
        } finally { fixture.deleteDir() }
    }

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
