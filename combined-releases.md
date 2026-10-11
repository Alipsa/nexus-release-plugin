# Combined release support

Combined releases are an opt-in feature of `se.alipsa.nexus-release-plugin` starting
with 2.4.0. Calling `nexusReleasePlugin { combinedRelease { } }` registers the six
combined tasks. Single-publication `bundle` and `release` keep their existing defaults.

For a combined-only root, add this to the consumer's `gradle.properties` before
applying the plugin:

```properties
nexusReleasePlugin.rootSinglePublicationTasks=false
```

This root-only switch suppresses the plugin's root `bundle`, `release` and
`clearModuleMetadata`, preserving unqualified subproject task selectors and any
custom root tasks with those names. Subprojects ignore the switch. Discovery tasks
remain available, and the plugin still applies `signing` and `maven-publish`.
Parent `bundleTask` and `releaseTask` references are null when suppressed. Setting a
root `mavenPublication` does not re-enable those tasks; ordinary publishing and
discovery can still use it. With the switch false and no combined block, neither
release workflow is registered. Leave it true (the default) to use both workflows
in the same project.

Matrix provides the complete orchestration, prerequisites and command examples in
[its combined-release guide](https://github.com/Alipsa/matrix/blob/main/docs/releaseAll.md).
During development it resolves this checkout through a Gradle composite build; publishing
this plugin is a separate operation.

## Task contract

All file locations are configured explicitly through shared extension or typed task properties.
Credentials can inherit from the parent extension; no paths have defaults.
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
plugins { id 'se.alipsa.nexus-release-plugin' }

nexusReleasePlugin {
    userName = providers.gradleProperty('sonatypeUsername')
    password = providers.gradleProperty('sonatypePassword')
    combinedRelease {
        sourceDirectory = layout.projectDirectory
        manifestFile = layout.buildDirectory.file('releaseAll/manifest.json')
        stagingDirectory = layout.buildDirectory.dir('releaseAll/staging')
        bundleFile = layout.buildDirectory.file('releaseAll/bundle.zip')
        receiptFile = layout.buildDirectory.file('releaseAll/bundle-receipt.json')
        recordFile = layout.buildDirectory.file('releaseAll/deployment.json')
        bundleAllTask.configure { /* task-specific configuration */ }
    }
}

tasks.named('releaseAllPlan') {
    // Configure revision, candidate POMs/descriptors and Portal fixture.
}
```

Run discovery in a separate invocation before configuring staging dependencies from the
saved manifest. Shared path conventions do not add task dependencies. Scheduling
`releaseAllPlan` alongside a guard, preparation or signing-check task that reads its
manifest without explicit ordering/dependencies can produce Gradle's implicit/missing
task-dependency error. Keep discovery separate; resume must not acquire a planner dependency.
Run source guards before staging, the caller's scoped verification before
preparation, then `bundleAll`, then `releaseAll`. Do not upload if any preceding phase fails.
The combined tasks support strict configuration-cache reuse; legacy verification tasks can
run with `--no-configuration-cache` independently.

## Shared conventions

Direct task settings override combined extension conventions; combined settings override
parent conventions. Credentials inherit from parent `userName`/`password`, and `portalUrl`
inherits parent `nexusUrl`. Unconfigured credentials remain unset. `repositoryUrl` defaults
to `https://repo.maven.apache.org/maven2`; it does not inherit `metadataBaseUrl`, because
metadata lookup and exact-publication availability probes are separate endpoints and a
proxy for one may not support the other. Configure both explicitly when using a shared proxy.
All shared paths remain unset until configured.

| Shared property | Tasks receiving its convention |
| --- | --- |
| `sourceDirectory` | `releaseAllPlan`, `releaseAllGuard`, `releaseAllPrepareMaven`, `bundleAll`, `releaseAll` |
| `manifestFile` | All six combined tasks |
| `stagingDirectory` | `releaseAllPrepareMaven`, `bundleAll` |
| `bundleFile`, `receiptFile` | `bundleAll`, `releaseAll` |
| `recordFile` | `releaseAll` |
| `repositoryUrl`, `portalUrl`, `userName`, `password` | `releaseAllPlan`, `releaseAll` |

Inside the block, read-only typed task providers are `planTask`, `signingCheckTask`,
`guardTask`, `prepareMavenTask`, `bundleAllTask` and `releaseAllTask`. They support
`.configure { ... }` but cannot be reassigned. Repeated blocks configure the same instance
and task providers. The parent exposes no `combinedRelease` property getter; configure
it through the block, which registers tasks before executing its action.

## Migration from the combined plugin

Replace the retired `se.alipsa.nexus-release-all` ID with the original plugin and opt-in
block. No 2.4.0 marker for the retired ID is published. Class-based
`apply plugin: ReleaseAllSupportPlugin` also requires migration because that class was
deleted. Existing released versions remain available. Combined-only roots should set the
root opt-out above; applying the original plugin also applies signing and Maven publishing.

Groovy named lookup (`tasks.named('releaseAllPlan')`) and `bundleAll { }` must appear
after opt-in. Convention scripts evaluated earlier should use
`tasks.withType(ReleaseAllPlanTask).configureEach { ... }`, with the appropriate task type.
Eager consumer rules are supported, but `configureEach` avoids realizing tasks unnecessarily.
Kotlin builds use typed named lookup after opt-in; combined task accessors such as
`tasks.releaseAllPlan` are not generated during plugin application:

```kotlin
import se.alipsa.gradle.plugin.release.*

plugins { id("se.alipsa.nexus-release-plugin") }

tasks.withType<ReleaseAllPlanTask>().configureEach {
    // Task-specific configuration; may precede opt-in.
}
nexusReleasePlugin {
    combinedRelease {
        sourceDirectory.set(layout.projectDirectory)
        // Set the remaining shared paths explicitly.
        bundleAllTask.configure { /* task-specific configuration */ }
    }
}
tasks.named<ReleaseAllPlanTask>("releaseAllPlan") {
    // Named lookup follows opt-in.
}
```

Matrix's external build and release guide need this migration independently of the plugin
release; migrate composite-build consumers before using this checkout.

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
Connect failures also leave an active `UPLOAD_ERROR` record that must be manually reconciled
before another upload. An initial connection failure usually means no upload was sent, but a
307/308 redirect can send the first POST before the connection to its target times out.
The client does not distinguish these cases, so it conservatively retains the record.
Never automatically retry an unknown upload outcome. Resume only the saved ID, without
source checks, builds or signing. Polling defaults to 60 attempts at 10-second intervals;
Transient I/O errors are recorded and polling continues within that bound. Availability probes use HEAD.
Central propagation remains separate from Portal publication. A failed record reports all
component coordinates. Archive an `AVAILABLE` record before a new deployment; manually
reconcile every unresolved record rather than deleting it to retry.

Parent `httpConnectTimeoutSeconds` and `httpRequestTimeoutSeconds` are not inherited
by combined tasks. CentralPortalClient retains 30 s connect, 1800 s upload and 60 s
small-call defaults. PublicationStatus availability probes in `releaseAllPlan` and
`releaseAll` retain 15 s connect and 30 s per-request timeouts, separate from that client's
overall caller deadline. `releaseAll` repository HEAD probes retain 15 s connect/read
timeouts. Shared credential and URL inheritance does not imply timeout inheritance.
