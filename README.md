# nexus-release-plugin

[![CI](https://github.com/Alipsa/nexus-release-plugin/actions/workflows/ci.yml/badge.svg)](https://github.com/Alipsa/nexus-release-plugin/actions/workflows/ci.yml)
[![Java](https://img.shields.io/badge/Java-17%20%26%2021-blue)](https://docs.oracle.com/en/java/javase/17/)
[![Groovy](https://img.shields.io/badge/Groovy-4.0.33-blue)](https://groovy-lang.org/)
[![Javadoc](https://javadoc.io/badge2/se.alipsa.nexus-release-plugin/se.alipsa.nexus-release-plugin.gradle.plugin/javadoc.svg)](https://javadoc.io/doc/se.alipsa.nexus-release-plugin/se.alipsa.nexus-release-plugin.gradle.plugin)

Automates the release process to Sonatype Central.

Assumptions are:
1. It assumes you use the maven publish plugin to define your artifacts
2. Any subproject (module) can use it in contrast to the nexus publish plugin which must be applied on the root project, allowing you to publish modules independently.

To use it you need to add your sonatype username token and password token to
~/.gradle/gradle.properties, e.g:

```properties
sonatypeUsername=myUserToken
sonatypePassword=myPassw0rdToken
```
Alternatively, you can set them as system properties or as environment variables.

Then you use the plugin in your build.gradle as follows:
```groovy

plugins {
  // your other plugins...
   id 'signing'
   id 'maven-publish'
   id 'se.alipsa.nexus-release-plugin' version '2.4.0'
}

publishing {
  publications {
    maven(MavenPublication) {
      from components.java

      artifact(javadocJar)
      artifact(sourcesJar)
      pom {
        name = 'Your project name'
        description = "${project.description}"
        url = "https://github.com/yourGroup/yourProject"
        licenses {
          license {
            name = 'MIT License'
            url = 'https://raw.githubusercontent.com/yourGroup/yourProject/main/LICENSE'
          }
        }
        developers {
          developer {
            id = 'nn'
            name = 'Full Name'
          }
        }
        scm {
          url = 'https://github.com/yourGroup/yourProject/tree/main'
          connection = 'scm:git:https://github.com/yourGroup/yourProject.git'
          developerConnection = 'scm:git:https://github.com/yourGroup/yourProject.git'
        }
      }
    }
  }
}

nexusReleasePlugin {
   userName = sonatypeUsername
   password = sonatypePassword
   mavenPublication = publishing.publications.maven
}
```

By default, the plugin adds these tasks:

- `bundle`: creates the Maven Central bundle from the configured publication.
- `release`: validates and uploads the bundle to Central Portal.
- `latestMavenVersions`: lists the latest published version for the current project, and when run on the root project also for published subprojects that use this plugin.
- `latestGithubRelease`: displays the latest GitHub release tag for this repository (auto-detected from the `origin` remote).

If you want to publish to another url that behaves just like the Central Publishing API, you 
can set the property `nexusUrl` in the nexusReleasePlugin e.g:

```groovy
nexusReleasePlugin {
   nexusUrl = "https://central.sonatype.com/api/v1"
   userName = sonatypeUsername
   password = sonatypePassword
   mavenPublication = publishing.publications.maven
}
```

The `release` task waits for Central Portal to process the uploaded deployment before it checks status.
The defaults are one initial check plus 10 additional checks, a 10 second interval between checks, and a 10 second initial delay.
You can tune those values if Central Portal is taking longer to validate or publish your bundle:

```groovy
nexusReleasePlugin {
   statusCheckRetries = 30
   statusCheckIntervalSeconds = 20
   initialStatusCheckDelaySeconds = 20
   httpConnectTimeoutSeconds = 30
   httpRequestTimeoutSeconds = 1800
}
```
## The latestMavenVersions task
The latestMavenVersions task can be used to get a report of the latest versions of the project (and sub modules) published to maven central. This can be useful to:
- check that the version you are about to release is not already published to maven central.
- check if a release has been published and is now available in maven central
- check the latest version of a dependency that you have published.

Example build.gradle:
```groovy
plugins {
  id 'groovy'
  id 'maven-publish'
  id 'se.alipsa.nexus-release-plugin' version '2.4.0'
}

group = 'se.alipsa'
version = '1.0.0'

publishing {
  publications {
    maven(MavenPublication) {
      from components.java
    }
  }
}

nexusReleasePlugin {
  mavenPublication = publishing.publications.maven
}
```

Run:
```
./gradlew latestMavenVersions
```

Example output:
```
my-project: se.alipsa:my-project:1.2.3
```

For a multi-module root project, running it from the root lists the root project and subprojects using this plugin:

```
my-root-project: se.alipsa:my-root-project:1.2.3
:core: se.alipsa:core:1.4.0
:csv: se.alipsa:csv:2.0.1
```

If you need `latestMavenVersions` to query another Maven-compatible repository, set `metadataBaseUrl`:

```groovy
nexusReleasePlugin {
   metadataBaseUrl = "https://repo1.maven.org/maven2"
   mavenPublication = publishing.publications.maven
}
```

When the artifact has not yet been published, the output includes the coordinates that were searched so you can verify the configuration is correct:
```
my-project (se.alipsa:my-project): not found in metadata repository
```

## The latestGithubRelease task

The `latestGithubRelease` task displays the latest GitHub release tag for this repository.
The repository is auto-detected from the `origin` remote in `.git/config` — no configuration needed for public repos.

Run:
```
./gradlew latestGithubRelease
```

Example output:
```
Alipsa/my-project: v1.2.3
```

If no releases exist yet:
```
Alipsa/my-project: no releases found
```

For private repositories, or to avoid GitHub API rate limits, set a token:

```groovy
nexusReleasePlugin {
   githubToken = githubAccessToken  // from gradle.properties
}
```

For GitHub Enterprise, the repository is auto-detected from the Enterprise remote URL (e.g. `https://github.example.com/owner/repo.git`). You only need to set the API base URL:

```groovy
nexusReleasePlugin {
   githubApiBaseUrl = "https://github.example.com/api/v3"
}
```

If auto-detection does not work (e.g. non-standard hosting or no remote configured), set the repository slug explicitly:

```groovy
nexusReleasePlugin {
   githubRepo = "owner/repo"
}
```

An explicit `githubRepo` always takes priority over auto-detection.

## Combined releases

Starting with 2.4.0, the original plugin provides manifest planning, guarded preparation,
combined bundling and one-upload deployment recovery through
`nexusReleasePlugin { combinedRelease { } }`. Combined-only roots should set
`nexusReleasePlugin.rootSinglePublicationTasks=false` in `gradle.properties` before applying
the plugin, preserving subproject `bundle`/`release` selectors and custom root task names.
Single-module behavior remains the default. See [configuration, shared-property precedence
and migration instructions](combined-releases.md). The separate combined plugin ID and
its implementation class are retired in 2.4.0.

Enable combined tasks in the root build:

```groovy
plugins {
    id 'se.alipsa.nexus-release-plugin' version '2.4.0'
}

nexusReleasePlugin {
    combinedRelease {
        // Configure shared paths, credentials and task-specific inputs as described in the guide.
    }
}
```

This registers `releaseAllPlan`, `releaseAllSigningCheck`, `releaseAllGuard`,
`releaseAllPrepareMaven`, `bundleAll` and `releaseAll`. Configure named tasks after
the block, or use typed `configureEach` before it. Combined-only roots also need
the opt-out property above; the block alone retains the default single-publication tasks.

Combined credentials and Portal URL inherit parent conventions; parent HTTP timeout settings
do not. Combined CentralPortalClient defaults remain 30 s connect, 1800 s upload and 60 s
small calls. PublicationStatus probes use 15 s connect and 30 s per-request timeouts;
repository HEAD probes use 15 s connect/read timeouts. Combined `repositoryUrl` does not
inherit `metadataBaseUrl`: metadata lookup and exact-publication probes can require different
proxy endpoints. Configure both explicitly when needed.

## Building the Plugin

The build uses the Gradle 9.8.1 wrapper and compiles for Java 17.

```bash
./gradlew build                  # Build and test
./gradlew publishToMavenLocal    # Publish to local Maven repo
```

## License

MIT License - see [LICENSE](LICENSE) for details.

### Release deadlines and bundle contents

`httpConnectTimeoutSeconds` defaults to 30 and `httpRequestTimeoutSeconds` to 1800. Both accept 1–86400 seconds. Set them in `nexusReleasePlugin`; overrides apply to `release`. Uploads use the full request deadline. Deployment status checks and other small calls through `CentralPortalClient` use the shorter of 60 seconds and the configured deadline. The caller stops waiting even if an upload or response body stalls. Cancellation closed stalled connections in the verified runtime matrix; no stronger guarantee is made for arbitrary network implementations.

`releaseAll` uses the default client timeouts. Its separate `PublicationStatus` and HEAD availability checks do not receive this overall deadline. Runtime testing covers JDK 17 and 21 with Gradle 8.13 and 9.8.1; compilation targets Java 17. Other daemon JDKs are outside the verified support matrix.

`statusCheckRetries` is the number of additional checks after the first one. Transient errors consume a check and are retried within that budget. Authentication and permission errors fail immediately and identify the deployment whose status check was rejected. Exhaustion reports an unknown or pending deployment that may still publish, with a Portal link.

GET redirects remain supported, as do POST redirects that preserve the method (307/308). A POST redirected into a GET fails with a redirect-specific error instead of treating the target response as a deployment ID. Overall upload timeouts report an unknown outcome and advise checking [Portal deployments](https://central.sonatype.com/publishing/deployments) before retrying. Connect timeouts identify the Portal URL and suggest checking network and proxy settings. For `releaseAll`, connect failures retain an `UPLOAD_ERROR` record that must be reconciled before another upload; see [interrupted deployments](combined-releases.md#interrupted-deployments).

Bundles use Maven coordinate names and include all artifacts of the selected publication with a main JAR, including classified ZIPs and generated Gradle Module Metadata. Every primary entry needs a nonempty signature and MD5/SHA-1 checksums. JAR-producing packaging such as `bundle`, `maven-plugin`, and `ejb` remains supported. Publications without a main JAR are rejected by `release`; use `releaseAll` for pom-packaged publications. `bundle` installs only the selected publication to Maven local. Ordinary `publish` and `publishToMavenLocal` builds work without setting `mavenPublication`; cleanup attaches only when a publication is selected.

The selected publication's module metadata and adjacent signature are cleared before metadata generation, including plain `publishToMavenLocal`, publishing to other repositories, and snapshots. Metadata regenerates or restores from cache. Deleting its signature forces the publication signing task to re-sign every artifact; gpg-agent/pinentry may prompt on every publish even when signing previously stayed up to date. Disable generation with `enabled = false` to omit metadata. Skipping it with `onlyIf` while the POM advertises metadata fails before upload.

The publishing-script harness runs as part of `check` when Bash is available. Local builds without Bash skip it with a reason; CI requires it with `-PrequireShellHarness=true`.
