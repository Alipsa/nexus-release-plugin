package se.alipsa.gradle.plugin.release

import groovy.transform.CompileStatic
import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * This plugin is an alternative to the nexus publish plugin which for some of
 * my more complex projects did not do what I wanted it to do.
 */
@CompileStatic
class NexusReleasePlugin implements Plugin<Project> {

  void apply(Project project) {

    def extension = project.extensions.create('nexusReleasePlugin', NexusReleasePluginExtension)

    println("NexusReleasePlugin: PublishingType = ${extension.publishingType.get()}")
    if (extension.publishingType.get() == PublishingType.NEXUS) {
      NexusPublishing nexusPublishing = new NexusPublishing(extension)
      nexusPublishing.apply(project)
    } else {
      CentralPublishing centralPublishing = new CentralPublishing(extension)
      centralPublishing.apply(project)
    }
  }
}
