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
| `releaseAllGuard` | `sourceDirectory`, `manifestFile`, `versionsJson`; checks revision, clean tree, publication-POM hashes and Git index flags and effective publication versions before staging tasks run. |
| `releaseAllPrepareMaven` | `sourceDirectory`, `manifestFile`, `stagingDirectory`, separate `localRepository`, `mavenWorkDirectory`, `verifierFile`, `settingsHelperFile`, `stagingRepositoryId` (default `release-staging`), optional `userSettingsFile` (default `~/.m2/settings.xml`); runs the caller's verifier and Maven `install -Prelease`, never deploy. The settings helper takes source user-settings, staged repository, output-settings and repository ID; it must preserve signing settings without logging secrets. |
| `bundleAll` | `sourceDirectory`, `manifestFile`, `stagingDirectory`; writes `bundleFile` and `receiptFile`. Rechecks source, dependency POMs, metadata, artifact policy, nonempty signature files and staged checksums (signature cryptography is not verified). Generates MD5/SHA-1 for artifacts/signatures. |
| `releaseAll` | `sourceDirectory`, `manifestFile`, `bundleFile`, `receiptFile`, `recordFile`, `portalFixture`, repository/Portal URLs and credentials; `resumeId` defaults to an empty string for a new upload, or a saved deployment ID for status-only recovery. Rechecks availability and uploads once. |

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
Eligibility edges include candidate group/artifact pairs and groups covered by the optional
`releaseAllPlan.releaseNamespaces` list (exact namespaces and their subgroups). Third-party
dependencies remain in POM expectations but are not availability probes; test/system edges
are also excluded from eligibility. Candidate JSON requires a nonempty `files` list; omitted
`dependencies` defaults to an empty list.
All explicit non-test/system versions in published dependencies and dependency management,
including third-party dependencies and imported BOMs, must be concrete release versions.
SNAPSHOTs, version ranges, dynamic versions and unresolved expressions exclude the candidate;
these exclusions cascade to dependents. Third-party availability is still not probed.
Rejecting third-party ranges and dynamic versions is a deliberate combined-release policy
change; use concrete pins even if the repository accepts ranges in published POMs.
Dependencies whose versions come from an imported BOM can remain unspecified in the source
POM, but their flattened published versions must be concrete non-SNAPSHOT versions.
Invalid eligibility-edge versions also exclude the dependent candidate rather than aborting
planning. Unresolved source POM expressions fail static inspection before selection.
Only exact non-SNAPSHOT versions whose dependencies are released or in the final bundle are
selected. Exclusions cascade; a selected module's mismatched BOM pin blocks the new BOM.
An unselected module can retain an already-released BOM pin with a warning.

Normal JAR components require main, sources and Javadoc JARs plus POM. POM components require
only a POM. An explicitly configured `sourcesAndJavadocRequired: false` policy preserves an
aggregate JAR's existing main-JAR/POM publication set. All expected files need nonempty
signatures. Explicitly listed `.module` metadata and its signature are accepted. Repository bookkeeping and unrelated components are not copied.
Source guards hash publication POMs and require a clean, unchanged Git revision with no
assume-unchanged or skip-worktree index flags. Maven preparation requires a POSIX filesystem
and bash, groovy and mvn; Windows users must use WSL. Repository and Portal URLs default to
Maven Central and the Central Portal API.
The ZIP size limit defaults to 1,000,000,000 bytes.

Publication checks use exact repository POMs and authenticated Portal
`GET /publisher/published?namespace=groupId&name=artifactId&version=version` under the configured
Portal API base. Errors never mean absence. Confirmed, sanitized namespace/absence fixtures
are required for production absence-based discovery and new uploads; mock fixtures are only
used in tests. Availability checks retry transient failures at most three times by default.

## Interrupted deployments

The record is written as `SUBMITTING` before HTTP and stores the ID immediately on success.
Definite HTTP 401/403/422 rejections are archived beside the active record as
`*.rejected-<uuid>.json`, allowing a corrected new invocation. Other upload exceptions persist
`UPLOAD_ERROR` with diagnostics; invalid ID responses retain the raw response in `SUBMITTING`.
Never automatically retry an unknown upload outcome. Resume only the saved ID, without
source checks, builds or signing. Polling defaults to 60 attempts at 10-second intervals;
Transient I/O errors are recorded and polling continues within that bound. Availability probes use HEAD.
Central propagation remains separate from Portal publication. A failed record reports all
component coordinates. Archive an `AVAILABLE` record before a new deployment; manually
reconcile every unresolved record rather than deleting it to retry.

CentralPortalClient uploads and deployment status checks use default connect (30 s) and caller deadlines (1800 s uploads, 60 s small calls). The single-publication extension timeout overrides do not apply here. Separate PublicationStatus and HEAD availability checks retain their existing behavior without this overall deadline.
