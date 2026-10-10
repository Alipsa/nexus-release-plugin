package se.alipsa.gradle.plugin.release

/** Serializable manifest publication and its eligibility edges. */
class PublicationDescriptor implements Serializable {
    String projectPath
    String publication
    String pomFile
    String packaging = 'jar'
    String role = 'module'
    boolean sourcesAndJavadocRequired = true
    ReleaseCoordinate coordinate
    List<Map<String, String>> dependencies = []
    List<Map<String, String>> publishedDependencies = []
    List<Map<String, String>> publishedManagement = []
    List<String> files = []
    List<String> reasons = []

    /** Serializes this publication without credentials. */
    Map<String, Serializable> toMap() {
        [projectPath: projectPath, publication: publication, pomFile: pomFile, packaging: packaging, role: role, sourcesAndJavadocRequired: sourcesAndJavadocRequired,
         groupId: coordinate.groupId, artifactId: coordinate.artifactId, version: coordinate.version,
         dependencies: dependencies, publishedDependencies: publishedDependencies, publishedManagement: publishedManagement, files: files, reasons: reasons]
    }
}
