package se.alipsa.gradle.plugin.release

import java.security.MessageDigest

/** Shared streaming checksums for single and combined release archives. */
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
}
