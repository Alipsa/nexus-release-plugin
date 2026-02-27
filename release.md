# Release history

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
