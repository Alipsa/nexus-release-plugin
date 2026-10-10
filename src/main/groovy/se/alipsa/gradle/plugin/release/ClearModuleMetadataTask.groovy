package se.alipsa.gradle.plugin.release

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

@DisableCachingByDefault(because = 'Deletes stale publication metadata before generation')
abstract class ClearModuleMetadataTask extends DefaultTask {
    @Internal abstract RegularFileProperty getMetadataFile()

    ClearModuleMetadataTask() {
        destroyables.register(metadataFile.map { it.asFile }, metadataFile.map { new File(it.asFile.path + '.asc') })
    }

    @TaskAction void clear() {
        if (!metadataFile.present) return
        File metadata = metadataFile.get().asFile
        [metadata, new File(metadata.path + '.asc')].each { File file ->
            if (file.exists() && !file.delete()) throw new GradleException("Cannot delete stale metadata: ${file}")
        }
    }
}
