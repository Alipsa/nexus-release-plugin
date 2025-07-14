package se.alipsa.gradle.plugin.release

import groovy.transform.CompileStatic
import org.gradle.api.Task
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.tasks.TaskProvider

import javax.inject.Inject

@CompileStatic
class NexusReleasePluginExtension {

  final Property<String> nexusUrl
  final Property<String> userName
  final Property<String> password
  final Property<MavenPublication> mavenPublication
  final Property<PublishingType> publishingType

  // ✅ Optional references to tasks
  TaskProvider<Task> bundleTask
  TaskProvider<Task> releaseTask

  @Inject
  NexusReleasePluginExtension(ObjectFactory objects) {
    nexusUrl = objects.property(String)
    userName = objects.property(String)
    password = objects.property(String)
    mavenPublication = objects.property(MavenPublication)
    publishingType = objects.property(PublishingType).convention(PublishingType.CENTRAL)
  }

  void setNexusUrl(String url) {
    nexusUrl.set(url)
  }

  void setUserName(String user) {
    userName.set(user)
  }

  void setPassword(String pass) {
    password.set(pass)
  }

  void setMavenPublication(MavenPublication publication) {
    mavenPublication.set(publication)
  }

  void setPublishingType(PublishingType type) {
    publishingType.set(type)
  }

}
