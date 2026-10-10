package se.alipsa.gradle.plugin.release

import groovy.transform.Canonical

/** An exact publication coordinate; never substitutes a previously released version. */
@Canonical
class ReleaseCoordinate implements Serializable {
    String groupId
    String artifactId
    String version

    /** Returns the Maven repository directory for this version. */
    String path() { "${groupId.replace('.', '/')}/${artifactId}/${version}" }

    /** Returns an unambiguous coordinate key. */
    String key() { "${groupId}:${artifactId}:${version}" }

    /** Rejects unresolved properties, ranges, and unsafe archive paths. */
    void validate() {
        if (version?.endsWith('+') || version in ['latest.release', 'latest.integration']) {
            throw new IllegalArgumentException('Dynamic versions cannot be released')
        }
        [groupId, artifactId, version].each { String value ->
            if (!value || !(value ==~ /[A-Za-z0-9_][A-Za-z0-9_.+-]*/) || value.contains('..')) {
                throw new IllegalArgumentException('Release coordinates must contain exact, safe versions and names')
            }
        }
    }
}
