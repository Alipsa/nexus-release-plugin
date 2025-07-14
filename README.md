# nexus-release-plugin
Automates the release process in Nexus (e.g. Sonatype OSSRH) after upload (publish) has completed.

The main differences compared to the [Nexus publish plugin](https://github.com/gradle-nexus/publish-plugin) are:
1. It assumes you use the maven publish plugin to upload your artifacts
2. Any subproject (module) can use it in contrast to the nexus publish plugin which must be applied on the 
   root project only allowing you to publish modules independently.

To use it you need to add your sonatype username token and password token to
~/.gradle/gradle.properties, e.g:

```properties
sonatypeUsername=myUSerToken
sonatypePassword=myPassw0rdToken
```

Then you use the plugin in your build.gradle as follows:
```groovy

plugins {
  // your other plugins...
   id 'signing'
   id 'maven-publish'
   id("se.alipsa.nexus-release-plugin") version '2.0.0'
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

To publish to an old style nexus (ossrh) you can instead do:
```groovy

plugins {
  // your other plugins...
   id 'signing'
   id 'maven-publish'
   id("se.alipsa.nexus-release-plugin") version '2.0.0'
}

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

nexusReleasePlugin {
   userName = sonatypeUsername
   password = sonatypePassword
   mavenPublication = publishing.publications.maven
   publishingType = PublishingType.NEXUS
}
```