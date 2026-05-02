# nexus-release-plugin

[![CI](https://github.com/Alipsa/nexus-release-plugin/actions/workflows/ci.yml/badge.svg)](https://github.com/Alipsa/nexus-release-plugin/actions/workflows/ci.yml)
[![Java](https://img.shields.io/badge/Java-17+-blue)](https://docs.oracle.com/en/java/javase/17/)
[![Groovy](https://img.shields.io/badge/Groovy-4.0.30-blue)](https://groovy-lang.org/)
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
   id 'se.alipsa.nexus-release-plugin' version '2.1.2'
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

The plugin adds these tasks:

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
The defaults are 10 retries, a 10 second interval between checks, and a 10 second initial delay.
You can tune those values if Central Portal is taking longer to validate or publish your bundle:

```groovy
nexusReleasePlugin {
   statusCheckRetries = 30
   statusCheckIntervalSeconds = 20
   initialStatusCheckDelaySeconds = 20
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
  id 'se.alipsa.nexus-release-plugin' version '2.2.0'
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

For GitHub Enterprise, override the API base URL:

```groovy
nexusReleasePlugin {
   githubApiBaseUrl = "https://github.example.com/api/v3"
}
```

## Building the Plugin

```bash
./gradlew build                  # Build and test
./gradlew publishToMavenLocal    # Publish to local Maven repo
```

## License

MIT License - see [LICENSE](LICENSE) for details.
