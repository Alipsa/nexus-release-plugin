# nexus-release-plugin
Automates the release process in Nexus (e.g. Sonatype OSSRH) after upload (publish) has completed.

The main differences compared to the [Nexus publish plugin](https://github.com/gradle-nexus/publish-plugin) are:
1. It assumes you use the maven publish plugin to upload your artifacts
2. Any subproject (module) can use it in contrast to the nexus publish plugin which must be applied on the root project only

To use it you need to add your sonatype username and password to
~/.gradle/gradle.properties, e.g:

```properties
sonatypeUsername=myUSerName
sonatypePassword=myPassw0rd
```

Then you use the plugin in your build.gradle as follows:

```groovy

plugins {
  // your other plugins...
  id 'signing'
  id 'maven-publish'
}

ext.nexusUrl = version.contains("SNAPSHOT")
    ? "https://oss.sonatype.org/content/repositories/snapshots/"
    : "https://oss.sonatype.org/service/local/staging/deploy/maven2/"

pluginManagement {
    repositories {
        maven {
            url = mavenCentral().url
        }
    }
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
            url = 'https://raw.githubusercontent.com/Alipsa/matrix/matrix-core/main/LICENSE'
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
  // This will upload to Sonatype Nexus (OSSRH) if credentials are present 
  if (project.ext.properties.sonatypeUsername) {
    repositories {
      maven {
        credentials {
          username = sonatypeUsername
          password = sonatypePassword
        }
        url = nexusUrl
      }
    }
  }
}

// Conditionally apply it if credentials are set allows 
// project members who cannot publish to use the build script smoothly
if (project.ext.properties.sonatypeUsername) {
  apply plugin: NexusReleasePlugin
  nexusReleasePlugin.nexusUrl = nexusUrl
  nexusReleasePlugin.userName = sonatypeUsername
  nexusReleasePlugin.password = sonatypePassword
}
```