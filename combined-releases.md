# Combined release support

The opt-in `se.alipsa.nexus-release-all` support plugin registers root tasks without
registering single-publication tasks or changing `se.alipsa.nexus-release-plugin`.
The existing `bundle` and `release` tasks remain available.

Matrix provides the complete orchestration, prerequisites and command examples in
[its combined-release guide](https://github.com/Alipsa/matrix/blob/main/docs/releaseAll.md).
During development it resolves this checkout through a Gradle composite build; publishing
this plugin is a separate operation.

## Task contract

All file locations and credentials are configured explicitly through typed task properties.
The source repository must be clean and committed. Generated files belong in ignored outputs.

| Task | Inputs and behavior |
| --- | --- |
| `releaseAllPlan` | `sourceDirectory`, `configuredRevision`, `candidatesJson`, generated `candidatePoms`, static `bomPom`/`allPom`, `repositoryUrl`, `portalUrl`, `userName`, `password`, `portalFixture`; writes `manifestFile` and optional `reportFile`. Never invokes Maven or resolves Maven candidates. |
| `releaseAllSigningCheck` | `manifestFile`, `configuredSigningJson` mapping project paths to signatory availability, `strict`; reports unavailable Gradle signing or fails when strict. No signing operation. |
| `releaseAllGuard` | `sourceDirectory`, `manifestFile`, `versionsJson`; checks revision, clean tree, source hashes and effective publication versions before staging tasks run. |
| `releaseAllPrepareMaven` | `sourceDirectory`, `manifestFile`, `stagingDirectory`, separate `localRepository`, `mavenWorkDirectory`, `verifierFile`, `settingsHelperFile`, optional `userSettingsFile` (default `~/.m2/settings.xml`); runs the caller's verifier and Maven `install -Prelease`, never deploy. The settings helper takes source user-settings, staged repository, output-settings and repository ID; it must preserve signing settings without logging secrets. |
| `bundleAll` | `sourceDirectory`, `manifestFile`, `stagingDirectory`; writes `bundleFile` and `receiptFile`. Rechecks source, dependency POMs, metadata, artifact policy, signatures and staged checksums. Generates MD5/SHA-1 for artifacts/signatures. |
| `releaseAll` | `sourceDirectory`, `manifestFile`, `bundleFile`, `receiptFile`, `recordFile`, `portalFixture`, repository/Portal URLs and credentials; `resumeId` defaults to the caller-supplied empty string for a new upload, or a saved deployment ID for status-only recovery. Rechecks availability and uploads once. |

The Matrix build configures these properties in `gradle/release-all.gradle`. For example,
a caller configures the bundle task after registering its own staging/preparation workflow:

```groovy
plugins { id 'se.alipsa.nexus-release-all' }

bundleAll {
    sourceDirectory = layout.projectDirectory
    manifestFile = layout.buildDirectory.file('releaseAll/manifest.json')
    stagingDirectory = layout.buildDirectory.dir('releaseAll/staging')
    bundleFile = layout.buildDirectory.file('releaseAll/bundle.zip')
    receiptFile = layout.buildDirectory.file('releaseAll/bundle-receipt.json')
}
```

Run discovery in a separate invocation before configuring staging dependencies from the
saved manifest. Run source guards before staging, the caller's scoped verification before
preparation, then `bundleAll`, then `releaseAll`. Do not upload if any preceding phase fails.
The combined tasks support strict configuration-cache reuse; legacy verification tasks can
run with `--no-configuration-cache` independently.

## Manifest and publication policy

Publication descriptors preserve exact configured coordinates, expected filenames, source
project/publication or Maven POM, dependency origins, and published dependency/management
expectations. Include production compile and runtime project edges even if omitted by POMs.
Only exact non-SNAPSHOT versions whose dependencies are released or in the final bundle are
selected. Exclusions cascade; a selected module's mismatched BOM pin blocks the new BOM.
An unselected module can retain an already-released BOM pin with a warning.

Normal JAR components require main, sources and Javadoc JARs plus POM. POM components require
only a POM. An explicitly configured `sourcesAndJavadocRequired: false` policy preserves an
aggregate JAR's existing main-JAR/POM publication set. All expected files need nonempty
signatures. No Gradle metadata, repository bookkeeping or unrelated components are copied.
The ZIP size limit defaults to 1,000,000,000 bytes.

Publication checks use exact repository POMs and authenticated Portal
`GET /publisher/published?namespace=groupId&name=artifactId&version=version` under the configured
Portal API base. Errors never mean absence. Confirmed, sanitized namespace/absence fixtures
are required for production absence-based discovery and new uploads; mock fixtures are only
used in tests. Availability checks retry transient failures at most three times by default.

## Interrupted deployments

The record is written as `SUBMITTING` before HTTP and stores the ID immediately on success.
Never automatically retry an unknown upload outcome. Resume only the saved ID, without
source checks, builds or signing. Polling defaults to 60 attempts at 10-second intervals;
Central propagation remains separate from Portal publication. A failed record reports all
component coordinates. Archive an `AVAILABLE` record before a new deployment; manually
reconcile every unresolved record rather than deleting it to retry.
