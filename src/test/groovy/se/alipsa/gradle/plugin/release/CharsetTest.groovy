package se.alipsa.gradle.plugin.release

import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.gradle.api.logging.Logging
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import static org.junit.jupiter.api.Assertions.*

class CharsetTest {
    @TempDir File directory

    @Test @Tag('non-utf8-charset')
    void gitPathsAndErrorsUseUtf8() {
        assertNotEquals(StandardCharsets.UTF_8, Charset.defaultCharset())
        String name = '\u00e5.txt'
        new File(directory, name).setText('original', 'UTF-8')
        ReleaseManifest.git(directory, 'init')
        ReleaseManifest.git(directory, 'add', '.')
        assertTrue(ReleaseManifest.gitOutput(directory, 'ls-files', '-z').contains(name))
        // Feed UTF-8 bytes directly: JDK 17 on Unix also uses the default charset
        // when encoding process arguments, independently of output decoding.
        def flag = new ProcessBuilder('git', '-C', directory.absolutePath, 'update-index',
            '--assume-unchanged', '-z', '--stdin').start()
        flag.outputStream.withCloseable { it.write((name + '\u0000').getBytes(StandardCharsets.UTF_8)) }
        flag.waitForProcessOutput(new ByteArrayOutputStream(), new ByteArrayOutputStream())
        assertEquals(0, flag.exitValue())
        assertTrue(assertThrows(IllegalStateException) {
            ReleaseManifest.checkTrackedFlags(directory)
        }.message.contains(name))
        def failure = assertThrows(IllegalStateException) {
            ReleaseManifest.git(directory, '-c', 'alias.charset-error=!printf "\\303\\245\\n" >&2; exit 1', 'charset-error')
        }
        assertTrue(failure.message.contains('\u00e5'))
    }

    @Test @Tag('non-utf8-charset')
    void credentialsUseUtf8() {
        assertNotEquals(StandardCharsets.UTF_8, Charset.defaultCharset(), 'charsetTest must run with a non-UTF-8 default charset')
        def client = new CentralPortalClient(Logging.getLogger(CharsetTest), '', '', '')
        assertEquals('Bearer ' + Base64.encoder.encodeToString('usér:pässword'.getBytes(StandardCharsets.UTF_8)), client.auth('usér', 'pässword'))
    }
}
