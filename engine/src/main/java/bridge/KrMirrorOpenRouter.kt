package bridge

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.nio.file.Files
import java.util.concurrent.CancellationException

/** Injectable mirror-only open path; unindexed/scoped paths retain the existing bridge route. */
internal class KrMirrorOpenRouter<T>(
    private val session: KrMirrorReadSession<T>,
    private val sourceSize: (T) -> Long,
    private val sourceStream: (T) -> InputStream,
    private val sourceDescriptor: (T) -> Int,
    private val localDescriptor: (File, Intent) -> Int,
    private val closeDescriptor: (Int) -> Unit,
    private val diagnostic: (String, Exception?) -> Unit,
) {
    enum class Intent { READ_ONLY, UPDATE, WRITE, TRUNCATE }

    fun open(key: String, file: File, intent: Intent, allowCreate: Boolean = false): Int {
        val unindexedWrite = !session.shouldRoute(key) &&
            intent != Intent.READ_ONLY &&
            session.localFiles?.resourceFileForPath(key) != null &&
            (allowCreate || (!session.degraded && file.isFile && file.length() > 0L))
        if (!session.shouldRoute(key) && !unindexedWrite) return NOT_ROUTED
        val state = session.localFiles ?: return FAILED
        return try {
            if (unindexedWrite) {
                // Do not adopt a placeholder that appeared while waiting for the lock.
                // Publish ownership before exposing even an intentionally empty new file.
                state.withFileLock(file) {
                    // Another ordinary O_CREAT may have published a local file while
                    // this request waited. Recheck ownership, not the pre-lock route snapshot.
                    if (!file.exists() && file.parentFile?.isDirectory != true) {
                        NOT_ROUTED
                    } else if ((allowCreate && !file.exists()) || (file.isFile && (session.isLocal(key, file) || (!session.degraded && file.length() > 0L)))) {
                        openLocal(key, file, intent, state)
                    } else {
                        FAILED
                    }
                }
            } else if (intent == Intent.READ_ONLY) {
                // Copy-up publishes with atomic rename. Do not hold a write mutex
                // across provider I/O: an old launch must not block a new launch read.
                val source = session.sourceEntry(key)
                // A stale ownership marker with a missing local file must not fail the
                // read while the source document is still readable (review round 10, #15).
                if ((session.isLocal(key, file) && file.isFile) || file.length() > 0L || (source == null && file.isFile)) {
                    localDescriptor(file, intent).takeIf { it >= 0 } ?: FAILED
                } else if (source != null) {
                    readSource(key, source)
                } else if (allowCreate) {
                    // A tombstoned key opened read-only WITH O_CREAT would fall back to
                    // native creation of an unowned 0-byte file that the degraded gate
                    // then blocks for the rest of the launch (audit of b49080ab, N1).
                    // Fail closed for this narrow combination; plain read probes (no
                    // O_CREAT) keep the NOT_ROUTED ENOENT path below.
                    FAILED
                } else {
                    // Unlinked this session with no local file left: a pure read is a
                    // plain miss. Return NOT_ROUTED so native open reports ENOENT the
                    // way base did — games probe deleted optional files and would
                    // misread FAILED/EIO as a broken resource (review round 9, #11).
                    NOT_ROUTED
                }
            } else if (intent == Intent.TRUNCATE) {
                // Truncation discards the source content by intent: publish the empty
                // local override directly instead of streaming a possibly >64 MiB
                // source only to erase it — an oversized source must not fail an
                // open whose result is a truncation anyway (review round 10, #9).
                state.withFileLock(file) {
                    val source = session.sourceEntry(key)
                    if (file.isFile && (session.isLocal(key, file) || file.length() > 0L || source == null)) {
                        return@withFileLock openLocal(key, file, intent, state)
                    }
                    if (source == null) {
                        return@withFileLock if (allowCreate && !file.exists()) {
                            if (file.parentFile?.isDirectory != true) {
                                NOT_ROUTED
                            } else {
                                openLocal(key, file, intent, state)
                            }
                        } else {
                            FAILED
                        }
                    }
                    openLocal(key, file, intent, state)
                }
            } else {
                // Materialize the complete source before handing out a writable fd.
                // This protects copy-up preparation/publication, not later writes:
                // legitimate truncation, crashes or ENOSPC after fd handoff may leave
                // an empty/partial local override. Append opens copy up as well.
                state.withFileLock(file) {
                    // A successful unlink/rename may have completed while this open
                    // waited. Never publish source bytes captured before the lock.
                    val source = session.sourceEntry(key)
                    if (file.isFile && (session.isLocal(key, file) || file.length() > 0L || source == null)) {
                        return@withFileLock openLocal(key, file, intent, state)
                    }
                    if (source == null) {
                        // O_CREAT may intentionally make a new empty local file. A
                        // plain r+/O_RDWR or write-only open must fail after unlink.
                        return@withFileLock if (allowCreate && !file.exists()) {
                            if (file.parentFile?.isDirectory != true) {
                                NOT_ROUTED
                            } else {
                                openLocal(key, file, intent, state)
                            }
                        } else {
                            FAILED
                        }
                    }
                    try {
                        state.copyUp(file, sourceSize(source), { before, after ->
                            diagnostic("Mirror source size changed: indexed=$before actual=$after", null)
                        }) { sourceStream(source) }
                    } catch (error: InterruptedIOException) {
                        throw error
                    } catch (error: IOException) {
                        diagnostic("Mirror copy-up unavailable; mutating open fails", error)
                        return@withFileLock FAILED
                    }
                    openLocal(key, file, intent, state)
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: InterruptedIOException) {
            throw error
        } catch (error: Exception) {
            session.recordRead(key, false)
            diagnostic("Mirror open failed", error)
            FAILED
        }
    }

    private fun readSource(key: String, source: T): Int {
        val fd = sourceDescriptor(source)
        session.recordRead(key, fd >= 0)
        return if (fd >= 0) fd else FAILED
    }

    private fun openLocal(key: String, file: File, intent: Intent, state: KrMirrorLocalFiles): Int {
        val createdByOpen = !file.exists()
        val fd = try {
            localDescriptor(file, intent)
        } catch (error: Exception) {
            rollbackCreatedFile(file, createdByOpen, error)
            throw error
        }
        if (fd < 0) {
            rollbackCreatedFile(file, createdByOpen, null)
            return FAILED
        }
        var ownershipPublished = false
        try {
            state.markOverridden(file)
            ownershipPublished = true
            session.promote(key)
            return fd
        } catch (error: Exception) {
            runCatching { closeDescriptor(fd) }
            if (!ownershipPublished) {
                rollbackCreatedFile(file, createdByOpen, error)
            }
            throw error
        }
    }

    private fun rollbackCreatedFile(file: File, createdByOpen: Boolean, cause: Exception?) {
        if (!createdByOpen || !file.exists()) return
        try {
            Files.deleteIfExists(file.toPath())
        } catch (rollbackError: Exception) {
            cause?.addSuppressed(rollbackError)
        }
    }

    companion object {
        const val NOT_ROUTED = -1
        const val FAILED = -2
    }
}
