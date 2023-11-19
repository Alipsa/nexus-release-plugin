package se.alipsa.groovy

import org.gradle.api.provider.Property

interface NexusReleasePluginExtension {
  Property<String> getNexusUrl()
  Property<String> getUserName()
  Property<String> getPassword()
}
