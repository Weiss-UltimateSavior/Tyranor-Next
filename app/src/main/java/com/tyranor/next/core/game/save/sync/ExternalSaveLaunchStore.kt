package com.tyranor.next.core.game.save.sync

import com.tyranor.next.core.game.save.SavePathSafety
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.StandardOpenOption.READ
import java.security.MessageDigest
import java.util.UUID

internal data class ExternalSaveLaunch(
    val uri: String,
    val token: String,
    val activeKey: String?,
    val owner: String,
    val dispatchSequence: Long = 0,
)

/**
 * Durable external-host launch evidence independent of upload mode and configuration generation.
 *
 * Prepared evidence also survives owner changes: dispatch may have succeeded before its ACK was
 * persisted. Returning to Tyranor says nothing about Winlator's independent process lifetime.
 * Only failed dispatch or explicit confirmation can retire evidence. Corruption is scoped by URI.
 */
internal class ExternalSaveLaunchStore(
    private val root: File,
    private val owner: String,
) {
    init {
        require(owner.isNotBlank()) { "owner must not be blank" }
    }

    fun prepare(uri: String, token: String, activeKey: String?) = withDiskLock { disk ->
        requireIdentity(uri, token)
        val entries = replaceToken(disk, uri, ExternalSaveLaunch(uri, token, activeKey, owner))
        persist(disk, uri, entries)
    }

    fun dispatched(uri: String, token: String, activeKey: String?, sequence: Long) = withDiskLock { disk ->
        requireIdentity(uri, token)
        require(sequence >= 0L) { "dispatch sequence must not be negative" }
        val entries = replaceToken(disk, uri, ExternalSaveLaunch(uri, token, activeKey, owner, sequence))
        persist(disk, uri, entries)
    }

    fun failed(uri: String, token: String) = withDiskLock { disk ->
        requireIdentity(uri, token)
        val existing = load(disk, uri)
        val remaining = existing.filterNot { it.token == token }
        if (remaining.size != existing.size) persist(disk, uri, remaining)
    }

    fun hasActive(relatedUris: Set<String>, activeKey: String): Boolean = withDiskLock { disk ->
        val entries = ArrayList<ExternalSaveLaunch>()
        for (marker in markers(disk)) {
            val key = marker.key
            val related = activeKey.isBlank() || relatedUris.any { SaveContentHash.text(it) == key }
            if (!marker.regular) {
                if (related) throw localIo()
                continue
            }
            val decoded = try {
                decode(key, disk.states.load(key))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: SaveSyncException) {
                // Fail closed only for the affected key; one damaged marker must not block
                // unrelated games.
                if (related) throw localIo()
                continue
            }
            entries += decoded
        }
        entries.any { entry ->
            activeKey.isBlank() || entry.uri in relatedUris ||
                entry.activeKey.isNullOrBlank() ||
                saveDirectoryScopesOverlap(entry.activeKey, activeKey)
        }
    }

    /** Captured before showing the dialog. Never interprets unreadable bytes as missing evidence. */
    fun confirmation(uri: String): Confirmation = withDiskLock { disk ->
        require(uri.isNotBlank())
        val key = SaveContentHash.text(uri)
        val marker = markerFile(disk.directory, key)
        retirePreviousReceipts(disk, key, marker)
        val observed = evidence(marker)
        val identity = if (observed != null && observed.fileKey == null) {
            // Windows BasicFileAttributes legitimately omits fileKey. Hard links retain identity
            // independently of bytes/timestamps, including a copy made with COPY_ATTRIBUTES.
            if (!observed.regular && !observed.directory) throw localIo()
            val receiptName = ".exit-confirmation-$key-${UUID.randomUUID()}.receipt"
            val source = if (observed.directory) {
                SavePathSafety.requireSafeRoot(marker, trustedRoot = disk.directory)
                File(marker, receiptName).also { Files.createFile(it.toPath()) }
            } else marker
            val anchor = File(disk.directory, receiptName)
            try {
                Files.createLink(anchor.toPath(), source.toPath())
            } catch (failure: Exception) {
                if (observed.directory) Files.deleteIfExists(source.toPath())
                throw failure
            }
            MarkerIdentity(anchor, if (observed.directory) source.name else null)
        } else null
        val snapshot = Confirmation(uri, evidence(marker), identity)
        try {
            requireCurrent(disk, snapshot)
        } catch (failure: Exception) {
            identity?.let { releaseIdentity(marker, it) }
            throw failure
        }
        snapshot
    }

    /** Caller fences native hosts, sessions and in-memory launches for this URI. */
    fun confirmExit(
        confirmation: Confirmation,
        liveTokens: Set<String>,
        beforeRetire: () -> Unit,
    ) = withDiskLock { disk ->
        val key = SaveContentHash.text(confirmation.uri)
        val marker = markerFile(disk.directory, key)
        var retired = marker
        try {
            requireCurrent(disk, confirmation)
            val raw = if (confirmation.evidence?.regular == true) {
                Files.newByteChannel(marker.toPath(), setOf(READ, NOFOLLOW_LINKS)).use { channel ->
                    java.nio.channels.Channels.newInputStream(channel).readBytes().toString(Charsets.UTF_8)
                }
            } else null
            val entries = try {
                raw?.let { decode(key, JSONObject(it)) }.orEmpty()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: SaveSyncException) {
                if (failure.code != SaveSyncError.LOCAL_IO) throw failure
                emptyList()
            } catch (_: org.json.JSONException) {
                emptyList()
            }
            if (entries.any { it.token in liveTokens && it.dispatchSequence == 0L }) {
                throw SaveSyncException(SaveSyncError.GAME_RUNNING)
            }
            requireCurrent(disk, confirmation)
            beforeRetire()
            if (confirmation.evidence != null) {
                if (entries.isNotEmpty()) {
                    // Confirmed healthy launches need no recovery copy on every play session.
                    Files.delete(marker.toPath())
                } else {
                    // Preserve damaged entries by renaming them without traversing their contents.
                    val quarantine = File(disk.directory, "$key.json.confirmed-${UUID.randomUUID()}")
                    try {
                        Files.move(marker.toPath(), quarantine.toPath(), ATOMIC_MOVE)
                    } catch (_: AtomicMoveNotSupportedException) {
                        Files.move(marker.toPath(), quarantine.toPath())
                    }
                    retired = quarantine
                }
            }
        } finally {
            confirmation.identity?.let { releaseIdentity(retired, it) }
        }
    }

    internal class Confirmation internal constructor(
        internal val uri: String,
        internal val evidence: MarkerEvidence?,
        internal val identity: MarkerIdentity?,
    ) {
        val hasEvidence: Boolean get() = evidence != null
    }

    internal class MarkerIdentity(val anchor: File, val directoryReceiptName: String?)

    /** One outstanding receipt per URI; a newer dialog invalidates older dialogs across restarts. */
    private fun retirePreviousReceipts(disk: DiskContext, key: String, marker: File) {
        if (!disk.directory.exists()) return
        for (anchor in disk.directory.listFiles() ?: throw localIo()) {
            if (!anchor.name.startsWith(".exit-confirmation-$key-") || !anchor.name.endsWith(".receipt")) continue
            releaseIdentity(marker, MarkerIdentity(anchor, anchor.name))
        }
    }

    private fun releaseIdentity(marker: File, identity: MarkerIdentity) {
        try {
            if (identity.directoryReceiptName != null && Files.isDirectory(marker.toPath(), NOFOLLOW_LINKS)) {
                val receipt = File(marker, identity.directoryReceiptName)
                if (Files.isRegularFile(receipt.toPath(), NOFOLLOW_LINKS) &&
                    Files.isRegularFile(identity.anchor.toPath(), NOFOLLOW_LINKS) &&
                    Files.isSameFile(receipt.toPath(), identity.anchor.toPath())) {
                    Files.deleteIfExists(receipt.toPath())
                }
            }
        } finally {
            Files.deleteIfExists(identity.anchor.toPath())
        }
    }

    private fun requireCurrent(disk: DiskContext, confirmation: Confirmation) {
        val marker = markerFile(disk.directory, SaveContentHash.text(confirmation.uri))
        if (evidence(marker) != confirmation.evidence) throw SaveSyncException(SaveSyncError.LOCAL_STATE_CHANGED)
        confirmation.identity?.let { identity ->
            val source = identity.directoryReceiptName?.let { File(marker, it) } ?: marker
            val (attributes, anchorAttributes) = try {
                Files.readAttributes(source.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS) to
                    Files.readAttributes(identity.anchor.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS)
            } catch (_: NoSuchFileException) {
                throw SaveSyncException(SaveSyncError.LOCAL_STATE_CHANGED)
            }
            if (!attributes.isRegularFile || !anchorAttributes.isRegularFile ||
                !Files.isSameFile(source.toPath(), identity.anchor.toPath())) {
                throw SaveSyncException(SaveSyncError.LOCAL_STATE_CHANGED)
            }
        }
    }

    internal data class MarkerEvidence(
        val fileKey: String?,
        val created: String,
        val modified: String,
        val size: Long,
        val regular: Boolean,
        val directory: Boolean,
        val symlink: Boolean,
        val contentHash: String?,
    )

    private fun evidence(marker: File): MarkerEvidence? {
        fun attributes(): BasicFileAttributes = Files.readAttributes(
            marker.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        val before = try { attributes() } catch (_: NoSuchFileException) { return null }
        val hash = when {
            before.isRegularFile -> {
                val digest = MessageDigest.getInstance("SHA-256")
                Files.newByteChannel(marker.toPath(), setOf(READ, NOFOLLOW_LINKS)).use { channel ->
                    val buffer = java.nio.ByteBuffer.allocate(8192)
                    while (channel.read(buffer) != -1) {
                        buffer.flip()
                        digest.update(buffer)
                        buffer.clear()
                    }
                }
                digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
            }
            before.isSymbolicLink -> SaveContentHash.text(Files.readSymbolicLink(marker.toPath()).toString())
            else -> null
        }
        fun describe(attributes: BasicFileAttributes) = MarkerEvidence(
            attributes.fileKey()?.toString(),
            attributes.creationTime().toString(), attributes.lastModifiedTime().toString(),
            attributes.size(), attributes.isRegularFile, attributes.isDirectory, attributes.isSymbolicLink, hash)
        val result = describe(before)
        if (result != describe(attributes())) throw SaveSyncException(SaveSyncError.LOCAL_STATE_CHANGED)
        return result
    }

    private fun load(disk: DiskContext, uri: String): List<ExternalSaveLaunch> {
        val key = SaveContentHash.text(uri)
        val state = readMarker(disk, key) ?: return emptyList()
        val entries = decode(key, state)
        if (entries.any { it.uri != uri }) throw localIo()
        return entries
    }

    private fun replaceToken(
        disk: DiskContext,
        uri: String,
        replacement: ExternalSaveLaunch,
    ): List<ExternalSaveLaunch> {
        val key = SaveContentHash.text(uri)
        val existing = readMarker(disk, key)?.let { decode(key, it) } ?: emptyList()
        if (existing.any { it.uri != uri }) throw localIo()
        return existing.filterNot { it.token == replacement.token } + replacement
    }

    private fun persist(disk: DiskContext, uri: String, entries: List<ExternalSaveLaunch>) {
        persistKey(disk, SaveContentHash.text(uri), entries)
    }

    private fun persistKey(disk: DiskContext, key: String, entries: List<ExternalSaveLaunch>) {
        if (entries.isEmpty()) {
            disk.states.remove(key)
            return
        }
        val uri = entries.first().uri
        if (SaveContentHash.text(uri) != key || entries.any { it.uri != uri }) throw localIo()
        val encoded = JSONArray()
        for (entry in entries) {
            encoded.put(
                JSONObject()
                    .put(KEY_TOKEN, entry.token)
                    .put(KEY_ACTIVE_KEY, entry.activeKey ?: JSONObject.NULL)
                    .put(KEY_OWNER, entry.owner)
                    .put(KEY_DISPATCH_SEQUENCE, entry.dispatchSequence),
            )
        }
        disk.states.save(key, JSONObject().put(KEY_URI, uri).put(KEY_ENTRIES, encoded))
    }

    private fun decode(key: String, state: JSONObject): List<ExternalSaveLaunch> {
        val uri = state.requiredString(KEY_URI)
        if (uri.isBlank() || SaveContentHash.text(uri) != key) throw localIo()
        val encoded = state.opt(KEY_ENTRIES) as? JSONArray ?: throw localIo()
        if (encoded.length() == 0) throw localIo()
        val tokens = HashSet<String>()
        return buildList {
            for (index in 0 until encoded.length()) {
                val entry = encoded.opt(index) as? JSONObject ?: throw localIo()
                val token = entry.requiredString(KEY_TOKEN)
                val entryOwner = entry.requiredString(KEY_OWNER)
                val activeKey = entry.requiredNullableString(KEY_ACTIVE_KEY)
                val dispatchSequence = entry.requiredLong(KEY_DISPATCH_SEQUENCE)
                if (token.isBlank() || entryOwner.isBlank() || dispatchSequence < 0L || !tokens.add(token)) {
                    throw localIo()
                }
                add(ExternalSaveLaunch(uri, token, activeKey, entryOwner, dispatchSequence))
            }
        }
    }

    private data class Marker(val key: String, val regular: Boolean)

    private fun markers(disk: DiskContext): List<Marker> {
        if (!disk.directory.exists()) return emptyList()
        val files = disk.directory.listFiles() ?: throw localIo()
        return files.filter { it.name.endsWith(".json") }.mapNotNull { file ->
            val key = file.name.removeSuffix(".json")
            if (!key.matches(KEY_PATTERN)) return@mapNotNull null
            val regular = try {
                requireRegularMarker(disk.directory, file)
                true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: NoSuchFileException) {
                return@mapNotNull null
            } catch (_: Exception) {
                // Never follow a symlink or read a non-regular marker; isolate it instead.
                false
            }
            Marker(key, regular)
        }
    }

    private fun readMarker(disk: DiskContext, key: String): JSONObject? {
        val marker = markerFile(disk.directory, key)
        return try {
            requireRegularMarker(disk.directory, marker)
            disk.states.load(key)
        } catch (_: NoSuchFileException) {
            null
        }
    }

    private fun requireRegularMarker(directory: File, marker: File) {
        val attributes = Files.readAttributes(
            marker.toPath(),
            BasicFileAttributes::class.java,
            NOFOLLOW_LINKS,
        )
        if (!attributes.isRegularFile) throw localIo()
        SavePathSafety.requireSafeFile(directory, marker)
    }

    private fun markerFile(directory: File, key: String): File = File(directory, "$key.json")

    private fun JSONObject.requiredString(name: String): String =
        opt(name) as? String ?: throw localIo()

    private fun JSONObject.requiredNullableString(name: String): String? {
        if (!has(name)) throw localIo()
        return when (val value = opt(name)) {
            null, JSONObject.NULL -> null
            is String -> value
            else -> throw localIo()
        }
    }

    private fun JSONObject.requiredLong(name: String): Long {
        val value = opt(name) as? Number ?: throw localIo()
        return value.toString().toLongOrNull() ?: throw localIo()
    }

    private fun requireIdentity(uri: String, token: String) {
        require(uri.isNotBlank()) { "uri must not be blank" }
        require(token.isNotBlank()) { "token must not be blank" }
    }

    private fun localIo(): SaveSyncException = SaveSyncException(SaveSyncError.LOCAL_IO)

    private fun <T> withDiskLock(block: (DiskContext) -> T): T = synchronized(DISK_LOCK) {
        try {
            val directory = SavePathSafety.requireSafeRoot(
                File(File(root, "save_sync"), "external_launches"),
                trustedRoot = root,
            )
            block(DiskContext(directory, SaveSyncStateStore(directory)))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: SaveSyncException) {
            throw failure
        } catch (failure: Exception) {
            throw SaveSyncException(SaveSyncError.LOCAL_IO, cause = failure)
        }
    }

    private data class DiskContext(val directory: File, val states: SaveSyncStateStore)

    companion object {
        private val DISK_LOCK = Any()
        private val KEY_PATTERN = Regex("[a-f0-9]{64}")
        private const val KEY_URI = "uri"
        private const val KEY_ENTRIES = "entries"
        private const val KEY_TOKEN = "token"
        private const val KEY_ACTIVE_KEY = "activeKey"
        private const val KEY_OWNER = "owner"
        private const val KEY_DISPATCH_SEQUENCE = "dispatchSequence"
    }
}
