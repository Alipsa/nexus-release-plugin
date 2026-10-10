package se.alipsa.gradle.plugin.release

import org.gradle.testkit.runner.GradleRunner

class MatrixRunner {
    static GradleRunner create() {
        String version = System.getProperty('testGradleVersion', '')
        def runner = GradleRunner.create()
        if (version) runner.withGradleVersion(version)
        runner
    }

    static void instrument(File directory) {
        int expected = Integer.parseInt(System.getProperty('testJavaVersion', '17'))
        String version = System.getProperty('testGradleVersion', '')
        new File(directory, 'build.gradle') << """
println('TESTKIT_RUNTIME Gradle=' + gradle.gradleVersion + ' JVM=' + Runtime.version().feature())
assert Runtime.version().feature() == ${expected} : 'Unexpected TestKit daemon JVM'
${version ? "assert gradle.gradleVersion == '${version}'" : ''}
"""
    }
}
