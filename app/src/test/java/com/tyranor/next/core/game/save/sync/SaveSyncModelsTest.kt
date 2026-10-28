package com.tyranor.next.core.game.save.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.time.Instant
import java.time.ZoneId

class SaveSyncModelsTest {
    @Test fun readableRemoteNamesRoundTripTimezoneAndAcceptLegacyNames() {
        val hash = "a".repeat(64)
        val createdAt = Instant.parse("2026-10-25T12:34:56.789Z").toEpochMilli()
        val readableTime = "2026-10-25_20-34-56-789+0800"
        val name = SaveVersionNames.createRemote(
            createdAt,
            hash,
            ZoneId.of("Asia/Shanghai"),
            restored = true,
        )

        assertEquals("Restored-as-new_", SaveVersionNames.RESTORED_PREFIX)
        assertEquals("${SaveVersionNames.RESTORED_PREFIX}${readableTime}_$hash.zip", name)
        val parsed = SaveVersionNames.parse(name) ?: error("Readable remote name was rejected")
        assertEquals(createdAt, parsed.createdAt)
        assertEquals(hash, parsed.contentHash)
        assertFalse(parsed.legacy)
        assertTrue(parsed.restored)

        val legacyPlain = SaveVersionNames.parse(SaveVersionNames.create(123, hash))
            ?: error("Plain legacy remote name was rejected")
        assertFalse(legacyPlain.restored)
        val legacy = SaveVersionNames.parse(SaveVersionNames.RESTORED_PREFIX + SaveVersionNames.create(123, hash))
            ?: error("Legacy remote name was rejected")
        assertEquals(123L, legacy.createdAt)
        assertEquals(hash, legacy.contentHash)
        assertTrue(legacy.restored)
        assertNull(legacy.previousCreatedAt)
    }

    @Test fun restoredNamesCarryPreviousTimeThroughOldAndReadableFormats() {
        val hash = "b".repeat(64)
        // 毫秒位非零，换名与上传都必须逐毫秒保留两个时间，不四舍五入到秒
        val previousCreatedAt = 1_792_800_487_321L
        val createdAt = 1_793_100_896_789L
        val paddedPrevious = "Restored-as-new_" + previousCreatedAt.toString().padStart(19, '0') + "~"

        val localName = SaveVersionNames.createRestored(createdAt, hash, previousCreatedAt)
        assertTrue(localName, localName.startsWith(paddedPrevious))
        val local = SaveVersionNames.parse(localName) ?: error("Restored local name was rejected")
        assertEquals(createdAt, local.createdAt)
        assertEquals(previousCreatedAt, local.previousCreatedAt)
        assertEquals(hash, local.contentHash)
        assertTrue(local.restored)

        val remoteName = SaveVersionNames.createRemote(
            createdAt,
            hash,
            ZoneId.of("Asia/Shanghai"),
            restored = true,
            previousCreatedAt = previousCreatedAt,
        )
        assertTrue(remoteName, remoteName.startsWith(paddedPrevious))
        val remote = SaveVersionNames.parse(remoteName) ?: error("Restored readable remote name was rejected")
        assertEquals(createdAt, remote.createdAt)
        assertEquals(previousCreatedAt, remote.previousCreatedAt)
        assertEquals(hash, remote.contentHash)
        assertTrue(remote.restored)

        // 旧命名没有 previous 段：必须继续可读，且不凭空造出历史时间
        listOf(
            SaveVersionNames.RESTORED_PREFIX + SaveVersionNames.create(createdAt, hash),
            SaveVersionNames.createRemote(createdAt, hash, ZoneId.of("Asia/Shanghai"), restored = true),
        ).forEach { markerless ->
            val parsed = SaveVersionNames.parse(markerless) ?: error("Markerless restored name was rejected")
            assertTrue(parsed.restored)
            assertNull(parsed.previousCreatedAt)
            assertEquals(createdAt, parsed.createdAt)
        }
        assertNull(SaveVersionNames.parse(SaveVersionNames.create(createdAt, hash))?.previousCreatedAt)
    }

    @Test fun unicodeIdentityIsSafeAndEngineScopesTheFolderSuffix() {
        val identity = "星/月:*?\"<>|\u0001"
        val ons = RemoteSaveGame("ONS", identity)
        val reallive = RemoteSaveGame("REALLIVE", identity)
        val forbidden = charArrayOf('/', '\\', ':', '*', '?', '"', '<', '>', '|', '\u0001')

        assertTrue(ons.folderName.startsWith("星_月"))
        assertFalse(ons.folderName.any { it in forbidden })
        assertTrue(ons.folderName.endsWith("--${ons.key.take(12)}"))
        assertEquals(ons.folderName.substringBeforeLast("--"), reallive.folderName.substringBeforeLast("--"))
        assertNotEquals(ons.folderName, reallive.folderName)
    }

    @Test fun retentionUsesStableChoicesAndLegacyMigrationNeverTightens() {
        assertEquals(SaveSyncMode.MANUAL, WebDavConfig().mode)
        assertFalse(WebDavConfig().enabled)
        assertFalse(WebDavConfig().active)
        assertTrue(WebDavConfig().showSyncFeedback)
        assertFalse(WebDavSettingsStore.validated(WebDavConfig(showSyncFeedback = false)).showSyncFeedback)
        assertEquals(listOf(10, 20, 0), SaveSyncRetention.choices)
        SaveSyncRetention.choices.forEach { limit ->
            assertEquals(limit, WebDavSettingsStore.validated(WebDavConfig(remoteLimit = limit)).remoteLimit)
        }
        listOf(-1, 1, 15, 21).forEach { invalid ->
            val failure = assertThrows(SaveSyncException::class.java) {
                WebDavSettingsStore.validated(WebDavConfig(remoteLimit = invalid))
            }
            assertEquals(SaveSyncError.INVALID_CONFIG, failure.code)
        }

        assertEquals(0, SaveSyncRetention.fromLegacy(0))
        assertEquals(10, SaveSyncRetention.fromLegacy(5))
        assertEquals(10, SaveSyncRetention.fromLegacy(10))
        assertEquals(20, SaveSyncRetention.fromLegacy(15))
        assertEquals(20, SaveSyncRetention.fromLegacy(20))
        assertEquals(0, SaveSyncRetention.fromLegacy(100))
    }

    @Test fun readableFolderTruncationPreservesWholeSupplementaryCharactersAndLayout() {
        val emoji = "\uD83D\uDE00"
        for ((identity, readable) in listOf(
            "a".repeat(79) + emoji to "a".repeat(79),
            "a".repeat(78) + emoji + "b" to "a".repeat(78) + emoji,
            "a".repeat(81) to "a".repeat(80),
            "a".repeat(78) + emoji to "a".repeat(78) + emoji,
            "short" + emoji to "short" + emoji,
        )) {
            val game = RemoteSaveGame("ONS", identity)
            assertEquals(readable + "--" + game.key.take(12), game.folderName)
            val url = "https://dav.example/".toHttpUrl().newBuilder().addPathSegment(game.folderName).build()
            assertEquals(game.folderName, url.pathSegments.last())
            assertFalse(url.encodedPath.contains("%3F"))
            if (emoji in readable) assertTrue(url.encodedPath.contains("%F0%9F%98%80"))
        }
    }

    @Test fun configToStringRedactsEndpointAndCredentials() {
        val endpoint = "https://private.example.invalid/dav/account-42"
        val username = "private-user-42"
        val password = "private-password-42"
        val rendered = WebDavConfig(
            serverUrl = endpoint,
            username = username,
            password = password,
            enabled = true,
        ).toString()

        listOf(endpoint, username, password).forEach { secret ->
            assertFalse(rendered, rendered.contains(secret))
        }
        assertTrue(rendered.contains("configured=true"))
        assertTrue(rendered.contains("enabled=true"))
    }

    @Test fun createRemoteRejectsYear10000WithTypedInvalidConfig() {
        val year10000 = Instant.parse("+10000-01-01T00:00:00Z").toEpochMilli()
        val failure = assertThrows(SaveSyncException::class.java) {
            SaveVersionNames.createRemote(year10000, "c".repeat(64), ZoneId.of("UTC"))
        }

        assertEquals(SaveSyncError.INVALID_CONFIG, failure.code)
    }
}
