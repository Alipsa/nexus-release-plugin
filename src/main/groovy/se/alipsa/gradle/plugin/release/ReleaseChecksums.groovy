package se.alipsa.gradle.plugin.release

import java.security.MessageDigest

/** Shared checksums for single and combined release archives and records. */
class ReleaseChecksums {
    /** Computes a Maven checksum without loading the artifact into memory. */
    static String checksum(File file, String algorithm) {
        MessageDigest digest = MessageDigest.getInstance(algorithm)
        file.withInputStream { input ->
            byte[] buffer = new byte[65536]
            int count
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count)
        }
        digest.digest().encodeHex().toString()
    }
    /** Computes a checksum of already-read evidence bytes in the same lowercase hex format. */
    static String checksum(byte[] bytes, String algorithm) {
        MessageDigest.getInstance(algorithm).digest(bytes).encodeHex().toString()
    }
}
