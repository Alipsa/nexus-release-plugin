package se.alipsa.gradle.plugin.release

import groovy.transform.CompileStatic
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import org.gradle.api.publish.maven.MavenPublication

import javax.inject.Inject

@CompileStatic
class NexusReleasePluginExtension {

  final Property<String> nexusUrl
  final Property<String> userName
  final Property<String> password
  final Property<MavenPublication> mavenPublication

  @Inject
  NexusReleasePluginExtension(ObjectFactory objects) {
    nexusUrl = objects.property(String)
    userName = objects.property(String)
    password = objects.property(String)
    mavenPublication = objects.property(MavenPublication)
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
}
