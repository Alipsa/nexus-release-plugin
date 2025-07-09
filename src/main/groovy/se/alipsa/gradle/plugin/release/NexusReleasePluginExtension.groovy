package se.alipsa.gradle.plugin.release

import org.gradle.api.provider.Property
import org.gradle.api.publish.maven.MavenPublication

interface NexusReleasePluginExtension {
  Property<String> getNexusUrl()
  Property<String> getUserName()
  Property<String> getPassword()
  Property<Object> getMavenPublication()
}
