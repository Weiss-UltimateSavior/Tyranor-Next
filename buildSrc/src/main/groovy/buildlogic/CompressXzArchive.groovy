package buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.workers.WorkerExecutor

import javax.inject.Inject

/**
 * Compresses one bundled native plugin/runtime archive into a single LZMA2 (`.xz`) asset.
 *
 * The input archive stays uncompressed (STORED entries) on purpose: an XZ stream shares one
 * LZMA2 dictionary across the whole archive, so near-identical binaries inside a family
 * (two Kirikiroid2 kernels, four Artemis builds) compress far better together than each
 * shared object would in isolation.
 *
 * The actual compression runs in an isolated worker process (see [CompressXzWorkAction]) because
 * preset 9 needs several hundred MiB of encoder state. The worker writes its in-progress stream to
 * [temporaryDirectory] and only moves the finished asset into [destinationFile]'s directory, so a
 * killed build cannot leave a `.tmp` file inside the packaged asset tree.
 */
abstract class CompressXzArchive extends DefaultTask {

    /** Uncompressed (STORED) zip container produced by the matching packaging task. */
    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    abstract RegularFileProperty getSourceFile()

    /** `.xz` asset written into the APK asset tree. */
    @OutputFile
    abstract RegularFileProperty getDestinationFile()

    /**
     * Scratch directory for the in-progress `.xz` stream. Must be outside the generated asset
     * tree (normally `layout.buildDirectory.dir("tmp/xz")`); it is build-local state, not an
     * output, so it is deliberately not tracked as a task output.
     */
    @Internal
    abstract DirectoryProperty getTemporaryDirectory()

    /**
     * LZMA2 preset (0-9). Preset 9 uses a 64 MiB dictionary: lower presets lose most of the
     * cross-library redundancy this task exists for (measured 17.1 MiB vs 20.3 MiB for the
     * Kirikiroid2 pack).
     */
    @Input
    int preset = 9

    @Inject
    abstract WorkerExecutor getWorkerExecutor()

    @TaskAction
    void compress() {
        // Capture before submitting: inside the Action closure unqualified property names would
        // resolve to the parameters object itself (self-referential provider → isolation overflow).
        def source = sourceFile
        def destination = destinationFile
        def temporary = temporaryDirectory
        def level = preset
        workerExecutor.processIsolation { options ->
            options.forkOptions.maxHeapSize = '2g'
            options.forkOptions.jvmArgs('-Dfile.encoding=UTF-8')
        }.submit(CompressXzWorkAction) { parameters ->
            parameters.sourceFile.set(source)
            parameters.destinationFile.set(destination)
            parameters.temporaryDirectory.set(temporary)
            parameters.preset.set(level)
        }
    }
}
