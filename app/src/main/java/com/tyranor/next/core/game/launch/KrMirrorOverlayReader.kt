package com.tyranor.next.core.game.launch

import bridge.KrSafMirror
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.concurrent.CancellationException

/** Bounded source reads for bootstrap overlays; unknown sources must never be shadowed. */
internal class KrMirrorOverlayReader(
    private val sources: Map<String, Source>,
    private val openSource: (String) -> InputStream?,
) {
    data class Source(val uri: String?, val size: Long = -1L, val confirmedAbsent: Boolean = false)

    sealed interface Result {
        data class Content(val bytes: ByteArray) : Result
        data object Absent : Result
        data object Unavailable : Result
    }

    fun read(name: String, maxBytes: Int): Result {
        require(maxBytes >= 0)
        val source = sources[name] ?: return Result.Unavailable
        if (source.confirmedAbsent) return Result.Absent
        val uri = source.uri ?: return Result.Unavailable
        if (source.size > maxBytes) return Result.Unavailable
        return try {
            openSource(uri)?.use { input ->
                val output = ByteArrayOutputStream()
                val chunk = ByteArray(8192)
                while (true) {
                    val count = input.read(chunk)
                    if (count < 0) break
                    if (count > maxBytes - output.size()) return Result.Unavailable
                    output.write(chunk, 0, count)
                }
                // A known-size source that delivered fewer bytes (provider EOF, source
                // shrank after prepare) must not pass as content: an empty short read
                // would be mistaken for "no user patch" and shadow the real file.
                if (source.size >= 0 && output.size().toLong() != source.size) return Result.Unavailable
                Result.Content(output.toByteArray())
            } ?: Result.Unavailable
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            Result.Unavailable
        }
    }

    companion object {
        /** Production assembly shared by the launcher and the cross-module bootstrap regression. */
        fun fromPrepared(prepared: KrSafMirror.Prepared, openSource: (String) -> InputStream?): KrMirrorOverlayReader {
            val sources = KrSafMirror.BOOTSTRAP_FILE_NAMES.associateWith { name ->
                val bootstrap = prepared.bootstrapFiles[name]
                val local = File(prepared.mirrorRoot, name)
                val localOverride = bootstrap?.localOverride == true
                Source(
                    uri = if (localOverride) local.toURI().toString() else bootstrap?.uri,
                    size = if (localOverride) local.length() else bootstrap?.size ?: -1L,
                    confirmedAbsent = !localOverride && bootstrap?.state == KrSafMirror.BootstrapFileState.ABSENT,
                )
            }
            return KrMirrorOverlayReader(sources, openSource)
        }
    }

}
