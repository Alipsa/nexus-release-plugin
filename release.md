# Release history

## Ver 2.3.0, 2026-10-11
- Update build plugins: Ben Manes Versions 0.54.0 → 0.65.0 and Gradle Plugin Publish 2.1.1 → 2.2.1.
- Update MockWebServer 5.3.2 → 5.5.0, Groovy 4.0.31 → 4.0.33, and JUnit Jupiter / Platform Launcher 6.0.3 → 6.1.3.
- Update the Gradle wrapper 9.5.0 → 9.8.1.
- Fix: POST redirects that become GETs now fail with a redirect-specific error; GET and method-preserving 307/308 redirects remain supported.
- Fix: overall upload timeouts now explain that the deployment outcome is unknown and advise checking the Portal before retrying.
  Connect timeouts instead identify the Portal URL and suggest checking network and proxy settings. Combined release connect failures
  conservatively retain an `UPLOAD_ERROR` record requiring reconciliation, since a redirected POST may already have been sent.
- Fix: status-check 401/403 errors now include the deployment ID and confirm that the upload succeeded.
- Fix: Central Portal requests through `WsClient`/`CentralPortalClient` now have connect timeouts and an overall
  caller deadline, covering uploads and deployment status checks for `release` and `releaseAll`, including responses that stall after headers.
  New properties `httpConnectTimeoutSeconds` (default 30) and `httpRequestTimeoutSeconds` (default 1800), each
  1–86400 seconds. Status and other small calls through this client use the shorter of 60 seconds and the configured
  request timeout; uploads use the full configured timeout. `releaseAll` uses the default timeouts; the new properties
  apply only to `release`. Separate `PublicationStatus` and `ReleaseAllTask` HEAD availability checks are unchanged
  and do not receive the new overall deadline.
  `WsClient` now uses `java.net.http.HttpClient`; header names in its result map are lower-case, with no null status-line key.
- Fix: `release` status polling now tolerates transient errors (5xx, 429, timeouts, malformed responses) instead of
  failing a deployment that was uploaded successfully; 401/403 still fail immediately. The number of status checks is
  unchanged: one initial check plus up to `statusCheckRetries` further checks.
- Fix: when status polling times out, the error now says the deployment may still publish and how to check it,
  instead of reporting "Failed to release". Replaced the obsolete "staging profile id" error message.
- Fix: a missing signature no longer stops later `.asc` files (including the POM signature) from being added to the bundle.
- Bundle entries are now named from the publication coordinates (`artifactId-version[-classifier].extension`),
  so `base.archivesName` no longer has to match `artifactId`.
- The bundle now includes Gradle Module Metadata (`.module` + signature + checksums) when it was generated or restored from cache
  in the current build, and all artifacts of the publication, not only `.jar` files. Skipping the
  metadata task with `onlyIf` while the POM still advertises it is now an error. The combined release accepts
  `.module` files in the manifest. Metadata and its signature are cleared before generation to prevent stale
  output from being bundled; generation reruns or restores from cache whenever its task is scheduled. This affects
  all publishing builds, including snapshots, and forces the publication signing task to re-sign every artifact;
  gpg-agent/pinentry may prompt for a passphrase on every publish.
- `release` retains support for JAR-producing packaging, including `bundle`, `maven-plugin`, and `ejb`.
  Other packaging without a main JAR receives a clear error instead of misleading "missing .jar" errors.
- `release` validation now requires a signature and checksums for every artifact in the bundle, not only for a fixed
  set of JAR/POM files.
- Ordinary `publish` and `publishToMavenLocal` builds do not require `mavenPublication`; metadata cleanup only attaches when a publication is selected.
- Behaviour change: `bundle` now depends only on the configured publication's
  `publish<Name>PublicationToMavenLocal` task instead of `publishToMavenLocal`, so other publications are no
  longer installed to `~/.m2` as a side effect.
- Fix: `latestGithubRelease` now detects `origin` remotes that contain user info or tokens
  (e.g. `https://x-access-token:...@github.com/...`), and `url=` entries written without spaces.
- Fix: Central Portal credentials are encoded as UTF-8 regardless of the platform charset.
- `publish.sh` validates Plugin Portal publication before the Central `release`, then publishes to the Portal,
  stopping on the first failure. A Portal failure after Central release requires retrying only Portal publication.

## Ver 2.2.0, 2026-05-01
- fixed groovy doc generation
- add latestMavenVersions task to report the latest versions of the project (and sub modules) published to maven central.
- Add configurable configuration properties: statusCheckRetries, statusCheckIntervalSeconds, initialStatusCheckDelaySeconds

## Ver 2.1.2, 2026-03-18
- Enable configuration cache and parallel execution.
- Declare the plugin to support CC
- Add github ci/cd workflow for testing against the compatibility matrix (latest 8.x and 9.x versions) on every commit and PR.
- Publish the plugin to Maven Central on every release in additional to Plugin Portal.

## Ver 2.1.1, 2026-02-27
- com.github.ben-manes.versions:com.github.ben-manes.versions.gradle.plugin [0.52.0 -> 0.53.0]
- com.squareup.okhttp3:mockwebserver [5.1.0 -> 5.3.2]
- org.apache.groovy:groovy [5.0.0 -> 4.0.30]
- org.junit.jupiter:junit-jupiter [5.13.4 -> 6.0.3]
- org.junit.platform:junit-platform-launcher [1.13.4 -> 6.0.3]
- org.testcontainers:testcontainers [1.21.3 -> 2.0.3]
- org.testcontainers:testcontainers-junit-jupiter [1.21.3 (junit-jupiter) -> 2.0.3]
- Fix: `release` now uses `mavenPublication.version` (not `project.version`) for snapshot gating and bundle validation, so differing project/publication versions work correctly.
- Fix: checksums are now regenerated on every bundle build to prevent stale `.md5`/`.sha1` files from being reused.
- Added compatibility matrix test coverage for Gradle 8.13 and 9.3.1.

## Ver 2.1.0, 2026-02-27
- Added upfront bundle validation before upload:
  - credentials must be configured
  - required `.jar`/`.pom` and `.asc` entries must exist in the bundle
  - artifact filename consistency is validated against `artifactId` and `version`
  - POM metadata required by Maven Central is validated
- Improved Central Portal failure reporting by parsing full deployment status and logging validation errors when deployment state is `FAILED`.
- Improved HTTP handling in API client calls by checking non-2xx responses and surfacing status/body in explicit exceptions.
- Added tests for bundle validation rules, upload error handling (401/403/422), and pre-upload release failures.

## Ver 2.0.1, 2026-01-30
- Updated the plugin to use the provider APIs to be fully configuration cache compatible.

## Ver 2.0.0, 2025-09-07
- Change to the new Central Portal Publish API

## Ver 1.1.0, 2025-02-23
- Bugfix: Reverse search logic for projects in staged repos to choose the latest one instead of the first one.

## Ver 1.0.0, 2025-02-14
- Initial release
