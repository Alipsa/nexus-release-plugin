# Release history

## Ver 2.1.1, in progress
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
