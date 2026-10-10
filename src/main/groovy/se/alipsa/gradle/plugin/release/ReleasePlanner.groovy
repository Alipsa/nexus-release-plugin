package se.alipsa.gradle.plugin.release

/** Fixed-point eligibility over published and compile-time dependencies. */
class ReleasePlanner {
    /** Selects exact unreleased coordinates, retaining direct and cascading exclusions. */
    static List<PublicationDescriptor> select(List<PublicationDescriptor> candidates, Closure<Boolean> released,
                                              List<String> warnings = []) {
        Map<String, Boolean> availability = [:]
        Closure<Boolean> lookup = { ReleaseCoordinate c ->
            availability.computeIfAbsent(c.key()) { released(c) }
        }
        candidates.each { PublicationDescriptor p ->
            // Published edges remain complete even when eligibility edges are filtered by namespace.
            (p.publishedDependencies + p.publishedManagement).findAll { !(it.scope in ['test', 'system']) && it.version }.each { Map edge ->
                def dependency = new ReleaseCoordinate(edge.groupId, edge.artifactId, edge.version)
                try { dependency.validate() }
                catch (IllegalArgumentException invalid) {
                    p.reasons.add("Invalid published dependency: ${dependency.key()}: ${invalid.message}")
                    return
                }
                if (dependency.version.endsWith('-SNAPSHOT')) {
                    p.reasons.add("SNAPSHOT dependency: ${dependency.key()} (${edge.origin})")
                }
            }
            p.coordinate.validate()
            if (p.coordinate.version.endsWith('-SNAPSHOT')) p.reasons.add('SNAPSHOT version')
            else if (lookup(p.coordinate)) p.reasons.add('Already released')
        }
        boolean changed = true
        while (changed) {
            changed = false
            def selected = candidates.findAll { it.reasons.empty }
            Set<String> keys = selected.collect { it.coordinate.key() } as Set<String>
            selected.each { PublicationDescriptor p ->
                p.dependencies.each { Map<String, String> edge ->
                    def dependency = new ReleaseCoordinate(edge.groupId, edge.artifactId, edge.version)
                    try { dependency.validate() }
                    catch (IllegalArgumentException invalid) {
                        p.reasons.add("Invalid release dependency: ${dependency.key()} (${edge.origin}): ${invalid.message}")
                        changed = true
                        return
                    }
                    String reason
                    if (dependency.version.endsWith('-SNAPSHOT')) reason = 'SNAPSHOT dependency'
                    else if (!keys.contains(dependency.key()) && !lookup(dependency)) reason = 'Unavailable dependency'
                    if (reason) {
                        p.reasons.add("${reason}: ${dependency.key()} (${edge.origin})")
                        changed = true
                    }

                }
            }
        }
        // First stabilize dependency exclusions. A module excluded in this phase must not
        // cause a false BOM mismatch against an otherwise valid released pin.
        def selectedBeforeDrift = candidates.findAll { it.reasons.empty }
        selectedBeforeDrift.findAll { it.role == 'bom' }.each { PublicationDescriptor bom ->
            bom.dependencies.each { Map<String, String> edge ->
                def module = selectedBeforeDrift.find { it.coordinate.groupId == edge.groupId && it.coordinate.artifactId == edge.artifactId }
                if (module && module.coordinate.version != edge.version) {
                    bom.reasons.add("Selected module/BOM pin mismatch: ${edge.artifactId}:${edge.version} versus ${module.coordinate.version}")
                }
            }
        }
        // Cascade any BOM/all exclusion without reviving a previously blocked component.
        changed = true
        while (changed) {
            changed = false
            Set<String> keys = candidates.findAll { it.reasons.empty }.collect { it.coordinate.key() } as Set<String>
            candidates.findAll { it.reasons.empty }.each { PublicationDescriptor p ->
                p.dependencies.each { Map<String, String> edge ->
                    def dependency = new ReleaseCoordinate(edge.groupId, edge.artifactId, edge.version)
                    if (!keys.contains(dependency.key()) && !lookup(dependency)) {
                        p.reasons.add("Unavailable dependency after BOM exclusion: ${dependency.key()} (${edge.origin})")
                        changed = true
                    }
                }
            }
        }
        candidates.findAll { it.role == 'bom' }.each { PublicationDescriptor bom ->
            bom.dependencies.each { Map<String, String> edge ->
                def module = candidates.find { it.coordinate.artifactId == edge.artifactId && it.coordinate.groupId == edge.groupId }
                if (module && module.coordinate.version != edge.version && !module.reasons.empty) {
                    warnings.add("BOM pin ${edge.artifactId}:${edge.version} differs from unselected module ${module.coordinate.version}")
                }
            }
        }
        candidates.findAll { it.reasons.empty }
    }
}
