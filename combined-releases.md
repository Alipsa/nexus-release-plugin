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

## Select Gradle modules (2.5.0+)

In the root build, list the project paths whose publications you want to consider:

```groovy
plugins { id 'se.alipsa.nexus-release-plugin' version '2.5.1' }

nexusReleasePlugin {
    userName = providers.gradleProperty('sonatypeUsername')
    password = providers.gradleProperty('sonatypePassword')
    combinedRelease {
        modules = [':core', ':api'] // 'core' is also accepted; nested paths use ':tools:cli'
        portalFixture = layout.projectDirectory.file('gradle/central-portal-fixture.json')
    }
}
```

Each named project must configure `maven-publish` and a Maven publication. A single Maven
publication is selected automatically. For multiple publications, explicitly choose one with
that project's `nexusReleasePlugin.mavenPublication`; ambiguity fails with an explanation.
Coordinates and artifact names come from that publication, including custom artifact IDs.
Publication inspection is deferred until combined-release tasks are configured. Normal JAR
publications must include sources and Javadoc JARs, for example using
`java { withSourcesJar(); withJavadocJar() }` before `from components.java`. POM-only
publications are supported. Gradle's metadata redirection marker in the generated POM
determines whether Module Metadata is included with its signature; no internal publication
component API is used. Metadata-enabled, disabled, and POM-only publications are tested on
Gradle 8.13 and 9.8.1.

Configure Gradle signing credentials in each selected module using the usual signing
properties or `signing { useInMemoryPgpKeys(...) }`. When combined staging is scheduled, the
plugin applies `signing` and adds publication signing if needed. Setting `modules` does not
add signing to ordinary `publishToMavenLocal` or snapshot publishing invocations. Signing
explicitly configured by the consumer retains its existing requirements. Credentials and the confirmed Portal fixture remain
release prerequisites; the fixture format and availability policy are described below.

```sh
./gradlew releaseAllPlan  # Generate publication POMs and inspect eligibility; no compilation or signing
./gradlew bundleAll       # Check, sign, stage, and bundle the saved selection
./gradlew releaseAll      # Recheck/build the bundle as needed, then upload one deployment
```

Review `build/releaseAll/manifest.json` and the printed SELECT/SKIP report before bundling.
Only eligible named modules are staged and included. Already-published and SNAPSHOT versions
are excluded. Dependencies on omitted modules must already be published at their exact
configured coordinates; dependencies in a different namespace and `compileOnly` project
dependencies are checked too. Tests and system dependencies do not affect eligibility.
An internal project dependency without a Maven publication, or with ambiguous publications,
excludes the dependent candidate during planning with its project path and configuration in
the reason, before attempting to generate its POM. These release checks do not run in ordinary
`help`, compilation, or local publication builds; Gradle's own publication validation still applies.

All production project edges, including `compileOnly` and `compileOnlyApi`, are checked by
default. For internal projects whose code is embedded in the artifact or needed only during
compilation, explicitly opt out of the unpublished-project check:

```groovy
nexusReleasePlugin {
    combinedRelease {
        modules = [':api']
        unpublishedProjectDependencies = [':internalAnnotations', ':embeddedCode']
    }
}
```

This list defaults to empty and applies only to targets without a Maven publication. It
does not bypass checks for published siblings or ambiguous publications. Entries accept
the same project-path syntax as `modules`, and unknown paths fail release configuration.
The plugin does not embed code or rewrite publication metadata: configure your shading or
publication component so consumers have no dependency on the internal project. A remaining
reference in the POM excludes the module during planning; a remaining reference in Gradle
Module Metadata blocks bundling. A compile-only dependency omitted from both publications
needs no metadata adjustment. The planner does not traverse an opted-out project's own
dependencies. If embedded code needs additional runtime libraries, either embed them too or
declare them in the released module's POM and Gradle Module Metadata (when published).
Changing the opt-out list requires a new plan, even when
the source revision is unchanged. Kotlin uses
`unpublishedProjectDependencies.set(listOf(":internalAnnotations", ":embeddedCode"))`.

Ordinary project dependencies may still build an omitted sibling's JAR for compilation;
that sibling's publication is not signed, staged, or bundled. Changing the named subset
requires a new plan. The source repository must remain clean and committed, with generated
build outputs ignored by Git.

The plugin wires source and signing checks before preparation, the selected modules' `check`
tasks when available, publication signing, and publishing to a local file repository. It uses
neither Maven local nor an external staging service. `combinedReleaseStaging` is a reserved
repository name in selected modules. No BOM, aggregate POM, candidate JSON, or custom staging
script is required. Do not run `releaseAllPrepareMaven` for a Gradle-only release; it does no
work when the plan contains no selected static Maven components.

Defaults in module mode are the root project as `sourceDirectory` and these paths under
the root build directory: `releaseAll/manifest.json`, `releaseAll/staging`,
`releaseAll/bundle.zip`, `releaseAll/bundle-receipt.json`, and `releaseAll/deployment.json`.
The existing shared properties override these conventions. Repository/Portal URLs and
credentials use the shared conventions below. Configure module selection and opt-outs in
the root build script. Both lists are finalized after all projects are evaluated in default
mode, or at the end of root-project evaluation with configure-on-demand.
Run `./gradlew :releaseAllPlan` in a separate invocation before the first bundle, and
again whenever the selected publications change. Staging dependencies are configured
from that saved manifest before tasks execute. Once the saved selection is established,
`./gradlew :releaseAllPlan :bundleAll` is supported: consumers run after the planner.
A same-invocation replan cannot add staging tasks for newly selected publications;
if selection changes, run `:bundleAll` again in a separate invocation.
The manifest is tracked as a configuration-cache input when wiring the staging task graph.
With `--configure-on-demand`, the plugin explicitly configures named modules and their
production project dependencies during root evaluation, before inspecting publications.
This mode checks `resumeId` early and may configure those projects for non-release invocations;
status-only recovery skips that work. Default configuration keeps `releaseAll` lazy until
needed. Use qualified root task
paths (`:releaseAllPlan`, `:bundleAll`, `:releaseAll`) to avoid Gradle's recursive search for
unqualified task selectors configuring unrelated projects. Planning and bundling with
configure-on-demand and strict configuration-cache reuse are tested on both supported
Gradle versions.
With `releaseAll.resumeId` set, module preparation is skipped and only the saved deployment
is resumed; the default deployment record is still `build/releaseAll/deployment.json`.

Kotlin builds use the same typed list property:

```kotlin
nexusReleasePlugin {
    combinedRelease {
        modules.set(listOf(":core", ":api"))
        portalFixture.set(layout.projectDirectory.file("gradle/central-portal-fixture.json"))
    }
}
```

The remaining sections describe the advanced task API. Leaving `modules` empty preserves
explicit configuration and caller-managed orchestration for mixed Gradle/Maven builds.

## Task contract

Without module selection, file locations are configured explicitly through shared extension or typed task properties.
Credentials can inherit from the parent extension; no paths have defaults.
The source repository must be clean and committed. Generated files belong in ignored outputs.

| Task | Inputs and behavior |
| --- | --- |
| `releaseAllPlan` | `sourceDirectory`, `configuredRevision`, `candidatesJson`, generated `candidatePoms`, independently optional static `bomPom` and `allPom` (configure either, both, or neither), `repositoryUrl`, `portalUrl`, `userName`, `password`, `portalFixture`; writes `manifestFile` and optional `reportFile`. Never invokes Maven or resolves Maven candidates. |
| `releaseAllSigningCheck` | `manifestFile`, `configuredSigningJson` mapping project paths to signatory availability, `strict`; reports unavailable Gradle signing or fails when strict. No signing operation. |
| `releaseAllGuard` | `sourceDirectory`, `manifestFile`, `versionsJson`; checks revision, clean tree, publication-POM hashes and Git index flags and effective publication versions before staging tasks run. |
| `releaseAllPrepareMaven` | `sourceDirectory`, `manifestFile`; returns after source validation when no static Maven component is selected. Otherwise requires `stagingDirectory`, separate `localRepository`, `mavenWorkDirectory`, `verifierFile`, `settingsHelperFile`, `stagingRepositoryId` (default `release-staging`), optional `userSettingsFile` (default `~/.m2/settings.xml`); runs the caller's verifier and Maven `install -Prelease`, never deploy. The settings helper takes source user-settings, staged repository, output-settings and repository ID; it must preserve signing settings without logging secrets. |
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
saved manifest. All five manifest consumers declare `mustRunAfter(releaseAllPlan)`, so
requesting the planner and consumers together respects that order and passes Gradle's
task-dependency validation. This ordering does not schedule planning automatically:
standalone consumers use the reviewed saved manifest, and resume does not run the planner.
Run source guards before staging, the caller's scoped verification before
preparation, then `bundleAll`, then `releaseAll`. Do not upload if any preceding phase fails.
The combined tasks support strict configuration-cache reuse; legacy verification tasks can
run with `--no-configuration-cache` independently.

Combined ZIP entries, including signatures and checksums, use the fixed local ZIP time
`1980-02-01 00:00:00`. Identical staged bytes produce an identical ZIP regardless of
file modification times or the system timezone. The receipt's `bundleHash` identifies
the exact ZIP bytes for that run and upload; it is not a stable content address for a
release. Regenerating GPG signatures can change their embedded creation times and thus
the staged bytes and bundle hash. `bundleAll` runs each time it is requested, including
as a dependency of `releaseAll`.

## Shared conventions

Direct task settings override combined extension conventions; combined settings override
parent conventions. Credentials inherit from parent `userName`/`password`, and `portalUrl`
inherits parent `nexusUrl`. Unconfigured credentials remain unset. `repositoryUrl` defaults
to `https://repo.maven.apache.org/maven2`; it does not inherit `metadataBaseUrl`, because
metadata lookup and exact-publication availability probes are separate endpoints and a
proxy for one may not support the other. Configure both explicitly when using a shared proxy.
Shared paths remain unset until configured or module-mode conventions are applied.

| Shared property | Tasks receiving its convention |
| --- | --- |
| `sourceDirectory` | `releaseAllPlan`, `releaseAllGuard`, `releaseAllPrepareMaven`, `bundleAll`, `releaseAll` |
| `manifestFile` | All six combined tasks |
| `stagingDirectory` | `releaseAllPrepareMaven`, `bundleAll` |
| `bundleFile`, `receiptFile` | `bundleAll`, `releaseAll` |
| `recordFile` | `releaseAll` |
| `repositoryUrl`, `portalUrl`, `userName`, `password` | `releaseAllPlan`, `releaseAll` |
| `portalFixture` | `releaseAllPlan`, `releaseAll` |

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
Eligibility edges include candidate group/artifact pairs, all candidate group IDs and their
subgroups, production project edges, and groups covered by the optional
`releaseAllPlan.releaseNamespaces` list (exact namespaces and their subgroups). This always
checks dependencies on omitted siblings within candidate namespaces, even when the explicit
namespace list is empty. Third-party
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
