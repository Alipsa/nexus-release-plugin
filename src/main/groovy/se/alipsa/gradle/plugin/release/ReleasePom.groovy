package se.alipsa.gradle.plugin.release

import groovy.xml.XmlSlurper

/** Static POM inspection: does not resolve artifacts or run Maven. */
class ReleasePom {
    /** Reads exact dependencies with inherited BOM properties and management. */
    static Map read(File file, Map<String, String> inherited = [:], Map<String, String> managed = [:]) {
        def pom = new XmlSlurper().parse(file)
        Map<String, String> properties = new LinkedHashMap<>(inherited)
        pom.properties.children().each { properties[it.name().toString()] = it.text() }
        properties['project.version'] = pom.version.text()
        properties['project.groupId'] = pom.groupId.text()
        Closure<String> resolve = { String value ->
            String result = value
            20.times {
                result = result.replaceAll(/\$\{([^}]+)\}/) { full, key -> properties[key] ?: full }
            }
            if (result.contains('${')) throw new IllegalArgumentException("Unresolved POM expression in ${file.name}: ${value}")
            result
        }
        Closure<List<Map<String, String>>> deps = { nodes, String origin ->
            nodes.collect { node ->
                String groupId = resolve(node.groupId.text())
                String artifactId = resolve(node.artifactId.text())
                String version = resolve(node.version.text() ?: managed["${groupId}:${artifactId}"] ?: '')
                if (version || groupId.startsWith('se.alipsa.matrix')) new ReleaseCoordinate(groupId, artifactId, version).validate()
                [groupId: groupId, artifactId: artifactId, version: version,
                 scope: resolve(node.scope.text() ?: 'compile'), type: resolve(node.type.text() ?: 'jar'),
                 classifier: resolve(node.classifier.text()), origin: origin]
            }
        }
        [groupId: resolve(pom.groupId.text()), artifactId: resolve(pom.artifactId.text()),
         version: resolve(pom.version.text()), packaging: resolve(pom.packaging.text() ?: 'jar'), properties: properties,
         dependencies: deps(pom.dependencies.dependency, 'pom.dependencies'),
         management: deps(pom.dependencyManagement.dependencies.dependency, 'pom.dependencyManagement')]
    }

    /** Ensures flattened publication matches the expected dependency set. */
    static void compare(File pom, Map expected) {
        Map actual = read(pom)
        ['groupId', 'artifactId', 'version', 'packaging'].each { String key ->
            if (actual[key] != expected[key]) throw new IllegalArgumentException("Published POM ${key} differs from manifest")
        }
        def signature = { Map edge -> ['groupId', 'artifactId', 'version', 'scope', 'type', 'classifier'].collect { edge[it] ?: '' }.join(':') }
        if (actual.dependencies.collect(signature).sort() != expected.publishedDependencies.collect(signature).sort()) {
            throw new IllegalArgumentException('Published dependencies differ from manifest')
        }
        if (expected.containsKey('publishedManagement') && actual.management.collect(signature).sort() != expected.publishedManagement.collect(signature).sort()) {
            throw new IllegalArgumentException('Published dependency management differs from manifest')
        }
    }
}
