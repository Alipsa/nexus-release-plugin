package se.alipsa.gradle.plugin.release

import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.gradle.api.logging.Logging
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import static org.junit.jupiter.api.Assertions.*

class CharsetTest {
    @Test @Tag('non-utf8-charset')
    void credentialsUseUtf8() {
        assertNotEquals(StandardCharsets.UTF_8, Charset.defaultCharset(), 'charsetTest must run with a non-UTF-8 default charset')
        def client = new CentralPortalClient(Logging.getLogger(CharsetTest), '', '', '')
        assertEquals('Bearer ' + Base64.encoder.encodeToString('usér:pässword'.getBytes(StandardCharsets.UTF_8)), client.auth('usér', 'pässword'))
    }
}
