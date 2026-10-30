package com.tyranor.next.core.game.save.sync

import java.io.IOException
import java.text.Normalizer
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import java.util.Locale

enum class SaveSyncDirection(val pulls: Boolean, val uploads: Boolean) {
    PULL(true, false), UPLOAD(false, true), BOTH(true, true),
}

enum class SaveSyncMode(val pulls: Boolean, val uploads: Boolean) {
    MANUAL(false, false), PULL_ONLY(true, false), UPLOAD_ONLY(false, true), BIDIRECTIONAL(true, true),
}

data class WebDavConfig(
    val serverUrl: String = "",
    val username: String = "",
    val password: String = "",
    val mode: SaveSyncMode = SaveSyncMode.MANUAL,
    /** Available choices are ten, twenty, and unlimited (zero). */
    val remoteLimit: Int = 10,
    val showSyncFeedback: Boolean = true,
    val enabled: Boolean = false,
    /** Read with the encrypted configuration; never supplied by the settings UI. */
    internal val revision: Long = 0L,
) {
    val configured: Boolean get() = serverUrl.isNotBlank()
    val active: Boolean get() = enabled && configured

    override fun toString(): String =
        "WebDavConfig(mode=$mode, remoteLimit=$remoteLimit, showSyncFeedback=$showSyncFeedback, enabled=$enabled, configured=$configured)"
}

enum class SaveSyncError {
    NOT_CONFIGURED, INVALID_CONFIG, CREDENTIALS_UNAVAILABLE, AUTHENTICATION, NETWORK,
    SERVER, INVALID_RESPONSE, CORRUPT_ARCHIVE, LOCAL_IO, SAVE_UNAVAILABLE, GAME_RUNNING,
    IDENTITY_CONFLICT, DISABLED, SELECTION_INCOMPLETE, CLOUD_REPAIR_REQUIRED, STORAGE_FULL, SAVE_LOCATION_CHANGED,
    LOCAL_STATE_CHANGED,
}

class SaveSyncException(
    val code: SaveSyncError,
    val status: Int? = null,
    cause: Throwable? = null,
) : IOException(code.name, cause)

enum class SaveVersionSource { LOCAL, CLOUD }

enum class SaveActivationResult { UNCHANGED, APPLIED, METADATA_PENDING, PREVIOUS_PENDING }

data class SaveVersion(
    val id: String,
    val contentHash: String,
    val createdAt: Long,
    val size: Long,
    val source: SaveVersionSource = SaveVersionSource.LOCAL,
    val restored: Boolean = false,
    val previousCreatedAt: Long? = null,
)

data class RemoteSaveVersion(
    val name: String,
    val contentHash: String,
    val createdAt: Long,
    val size: Long,
    val etag: String? = null,
    val legacy: Boolean = false,
    val restored: Boolean = false,
    val previousCreatedAt: Long? = null,
)

/** Stable readable folders; neither a renamed library title nor an absolute device path is used. */
data class RemoteSaveGame(val engine: String, val identity: String) {
    val key: String get() = SaveContentHash.text(engine + "\n" + identity)
    val folderName: String get() {
        val normalized = Normalizer.normalize(identity, Normalizer.Form.NFC)
            .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_")
            .trim().trim('.')
        // Keep the existing UTF-16 budget/layout without splitting a supplementary character.
        val end = if (normalized.length > 80 && normalized[79].isHighSurrogate() &&
            normalized[80].isLowSurrogate()) 79 else minOf(80, normalized.length)
        val readable = normalized.take(end).ifBlank { "game" }
        return readable + "--" + key.take(12)
    }
}

object SaveSyncRetention {
    val choices: List<Int> = listOf(10, 20, 0)
    /** Upgrade old custom limits without tightening retention and unexpectedly deleting history. */
    fun fromLegacy(value: Int): Int = when {
        value < 0 -> throw SaveSyncException(SaveSyncError.INVALID_CONFIG)
        value == 0 -> 0
        value <= 10 -> 10
        value <= 20 -> 20
        else -> 0
    }
}

data class SaveSyncReport(
    val uploaded: Int = 0,
    val downloaded: Int = 0,
    val unchanged: Int = 0,
    val busy: Int = 0,
    val failures: List<SaveSyncFailure> = emptyList(),
    val cleanupPending: Int = 0,
    /** Stable game URIs whose cleanup remains pending. */
    val cleanupPendingGames: Set<String> = emptySet(),
) {
    operator fun plus(other: SaveSyncReport) = SaveSyncReport(
        uploaded + other.uploaded, downloaded + other.downloaded,
        unchanged + other.unchanged, busy + other.busy, failures + other.failures,
        cleanupPending + other.cleanupPending, cleanupPendingGames + other.cleanupPendingGames,
    )
}

data class SaveSyncFailure(
    val gameTitle: String,
    val error: SaveSyncError,
    val gameUri: String = "",
)

/** A strong entity-tag uses the RFC 7232 quoted `etagc` byte grammar. */
internal fun isStrongEtag(value: String?): Boolean {
    val etag = value ?: return false
    if (etag.length < 2 || etag.first() != '"' || etag.last() != '"') return false
    for (index in 1 until etag.lastIndex) {
        val code = etag[index].code
        if (code != 0x21 && code !in 0x23..0x7e && code !in 0x80..0xff) return false
    }
    return true
}

/** Immutable names let devices publish independently without replacing a shared manifest. */
object SaveVersionNames {
    const val RESTORED_PREFIX = "Restored-as-new_"
    private val pattern = Regex("^(\\d{19})_([a-f0-9]{64})\\.zip$")
    private val readablePattern = Regex("^(\\d{4}-\\d{2}-\\d{2}_\\d{2}-\\d{2}-\\d{2}-\\d{3}[+-]\\d{4})_([a-f0-9]{64})\\.zip$")
    private val previousTimePattern = Regex("^(\\d{19})~")
    private val remoteTime = DateTimeFormatter.ofPattern("uuuu-MM-dd_HH-mm-ss-SSSxx", Locale.ROOT)
        .withResolverStyle(ResolverStyle.STRICT)
    fun create(createdAt: Long, hash: String): String {
        require(createdAt > 0 && hash.matches(Regex("[a-f0-9]{64}")))
        return createdAt.toString().padStart(19, '0') + "_" + hash + ".zip"
    }

    fun createRestored(createdAt: Long, hash: String, previousCreatedAt: Long?): String =
        restoredPrefix(previousCreatedAt) + create(createdAt, hash)

    fun createRemote(
        createdAt: Long,
        hash: String,
        zone: ZoneId = ZoneId.systemDefault(),
        restored: Boolean = false,
        previousCreatedAt: Long? = null,
    ): String {
        require(createdAt > 0 && hash.matches(Regex("[a-f0-9]{64}")))
        val date = Instant.ofEpochMilli(createdAt).atZone(zone)
        if (date.year !in 1..9999) throw SaveSyncException(SaveSyncError.INVALID_CONFIG)
        return (if (restored) restoredPrefix(previousCreatedAt) else "") +
            remoteTime.format(date) + "_" + hash + ".zip"
    }

    private fun restoredPrefix(previousCreatedAt: Long?): String {
        require(previousCreatedAt == null || previousCreatedAt > 0)
        return RESTORED_PREFIX + (previousCreatedAt?.toString()?.padStart(19, '0')?.plus("~") ?: "")
    }

    fun parse(name: String, size: Long = 0, etag: String? = null): RemoteSaveVersion? {
        val restored = name.startsWith(RESTORED_PREFIX)
        val payload = name.removePrefix(RESTORED_PREFIX)
        val previousTime = if (restored) previousTimePattern.find(payload) else null
        val previousCreatedAt = previousTime?.groupValues?.get(1)?.let {
            it.toLongOrNull()?.takeIf { time -> time in 1..253402300799999L } ?: return null
        }
        val plainName = if (previousTime == null) payload else payload.removeRange(previousTime.range)
        pattern.matchEntire(plainName)?.let { match ->
            val time = match.groupValues[1].toLongOrNull()?.takeIf { it in 1..253402300799999L } ?: return null
            return RemoteSaveVersion(name, match.groupValues[2], time, size, etag,
                restored = restored, previousCreatedAt = previousCreatedAt)
        }
        val match = readablePattern.matchEntire(plainName) ?: return null
        val time = try {
            OffsetDateTime.parse(match.groupValues[1], remoteTime).toInstant().toEpochMilli().takeIf { it > 0 }
        } catch (_: java.time.DateTimeException) { null } ?: return null
        return RemoteSaveVersion(name, match.groupValues[2], time, size, etag,
            restored = restored, previousCreatedAt = previousCreatedAt)
    }
}
