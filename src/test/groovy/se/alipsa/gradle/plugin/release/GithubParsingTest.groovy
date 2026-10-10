package se.alipsa.gradle.plugin.release

import org.junit.jupiter.api.Test
import static org.junit.jupiter.api.Assertions.*

class GithubParsingTest {
    @Test void parsesCredentialsAndTrailingSlashesWithoutLoggingTokens() {
        ['https://user@github.com/o/r.git', 'https://x-access-token:abc@github.com/o/r.git',
         'https://github.com/o/r/', 'ssh://git@github.com:22/o/r.git', 'git@github.com:o/r.git'].each {
            assertEquals('o/r', NexusReleasePlugin.parseGithubCoordinates(it, 'https://api.github.com'))
        }
        assertNull(NexusReleasePlugin.parseGithubCoordinates('https://gitlab.com/o/r', 'https://api.github.com'))
        ['url=', '\turl = '].each {
            assertEquals('https://github.com/o/r.git', NexusReleasePlugin.parseOriginUrlFromConfig("[REMOTE \"ORIGIN\"]\n${it}https://github.com/o/r.git\n[remote \"other\"]\nurl=x"))
        }
    }
}
