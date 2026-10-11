package se.alipsa.gradle.plugin.release

import groovy.transform.CompileStatic
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property

/** Shared configuration created before any combined task can be realized. */
@CompileStatic
final class CombinedReleaseProperties {
    final DirectoryProperty sourceDirectory
    final RegularFileProperty manifestFile
    final DirectoryProperty stagingDirectory
    final RegularFileProperty bundleFile
    final RegularFileProperty receiptFile
    final RegularFileProperty recordFile
    final Property<String> repositoryUrl
    final Property<String> portalUrl
    final Property<String> userName
    final Property<String> password

    CombinedReleaseProperties(ObjectFactory objects, NexusReleasePluginExtension parent) {
        sourceDirectory = objects.directoryProperty()
        manifestFile = objects.fileProperty()
        stagingDirectory = objects.directoryProperty()
        bundleFile = objects.fileProperty()
        receiptFile = objects.fileProperty()
        recordFile = objects.fileProperty()
        repositoryUrl = objects.property(String).convention('https://repo.maven.apache.org/maven2')
        portalUrl = objects.property(String).convention(parent.nexusUrl)
        userName = objects.property(String).convention(parent.userName)
        password = objects.property(String).convention(parent.password)
    }
}
