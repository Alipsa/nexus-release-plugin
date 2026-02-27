# nexus-release-plugin
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
   id("se.alipsa.nexus-release-plugin") version '2.1.0'
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

If you want to publish to another url that behaves just like the Central Publishing API, you 
can set the property `nexusUrl` in the nexusReleasePlugin e.g:

```groovy
nexusReleasePlugin {
   nexusUrl = "https://some.host/api/v1"
   userName = sonatypeUsername
   password = sonatypePassword
   mavenPublication = publishing.publications.maven
}
```
