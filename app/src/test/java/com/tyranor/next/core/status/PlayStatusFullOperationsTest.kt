package com.tyranor.next.core.status

import com.tyranor.next.core.auth.HikarinagiAuthStore
import com.tyranor.next.core.auth.NextMoeAuthStore
import com.tyranor.next.core.auth.VndbAuthError
import com.tyranor.next.core.auth.VndbAuthStore
import com.tyranor.next.core.engine.EngineType
import com.tyranor.next.core.game.model.ScanGame
import com.tyranor.next.core.game.storage.GameLibraryFacade
import com.tyranor.next.core.net.HttpJsonResult
import com.tyranor.next.core.net.testHttpRoundTrip
import com.tyranor.next.core.settings.AppSettingsStore
import com.tyranor.next.testing.AuthAndCoverTestStubsRule
import com.tyranor.next.testing.InMemoryPreferencesContext
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * 游玩状态 HTTP 与绑定链路全量回归：
 * 1. VNDB `verifyVndbToken`、`checkVndbPermissions`、`readVndbStatus`、`writeVndbStatus`（含 401/403/500/会话变更）；
 * 2. NextMoe `readNextMoeStatus`（200 `completion` / 404 `NOT_FOUND` 未标记 / 404 `ENTITY_DELETED` / 410 / 429）；
 * 3. Hikarinagi `writeHikarinagiStatus`（200 `success=false`）；
 * 4. `bindSubject` / `unbindSubject` 幂等与游戏不在库分支。
 */
class PlayStatusFullOperationsTest {

    @get:Rule
    val stubsRule = AuthAndCoverTestStubsRule(installInMemoryCipher = true)

    private lateinit var context: InMemoryPreferencesContext

    @Before
    fun setUp() {
        context = InMemoryPreferencesContext()
        GameLibraryFacade.skipPersistForTest = true
        GameLibraryFacade.seedGamesForTest(emptyList())
    }

    @Test
    fun vndbVerifyPermissionsReadAndWriteCoverAllErrorBranches() {
        testHttpRoundTrip = { url, method, _, _ ->
            assertEquals("GET", method)
            assertTrue(url.endsWith("/authinfo"))
            HttpJsonResult(
                200,
                JSONObject()
                    .put("id", "u123")
                    .put("username", "alice")
                    .put("permissions", JSONArray().put("listread").put("listwrite")),
            )
        }
        val info = PlayStatusService.verifyVndbToken("vndb_pat")
        assertEquals("u123", info.userId)
        assertEquals("alice", info.username)
        assertEquals(listOf("listread", "listwrite"), info.permissions)

        assertTrue(VndbAuthStore.saveToken(context, "vndb_pat", "u123", "alice", listOf("listread")))
        val updatedPermissions = PlayStatusService.checkVndbPermissions(context)
        assertEquals(listOf("listread", "listwrite"), updatedPermissions)
        assertEquals(listOf("listread", "listwrite"), VndbAuthStore.getStatus(context).permissions)

        // readVndbStatus 200 -> 解析预置标签并清错
        VndbAuthStore.markLastError(context, VndbAuthError.PERMISSION.name)
        testHttpRoundTrip = { url, method, body, _ ->
            assertEquals("POST", method)
            assertTrue(url.endsWith("/ulist"))
            assertTrue(body!!.contains("\"v17\""))
            HttpJsonResult(
                200,
                JSONObject().put(
                    "results",
                    JSONArray().put(
                        JSONObject()
                            .put("id", "v17")
                            .put("labels", JSONArray().put(JSONObject().put("id", 1)).put(JSONObject().put("id", 77))),
                    ),
                ),
            )
        }
        val readStatus = PlayStatusService.readVndbStatus(context, "v17")
        assertEquals(setOf(1, 77), readStatus.labelIds)
        assertFalse(VndbAuthStore.getStatus(context).needsReauth)

        // readVndbStatus 403 -> 置位 PERMISSION 但保留 token
        testHttpRoundTrip = { _, _, _, _ -> HttpJsonResult(403, null) }
        try {
            PlayStatusService.readVndbStatus(context, "v17")
            fail("expected VNDB_TOKEN_PERMISSION")
        } catch (e: PlayStatusException) {
            assertEquals(PlayStatusError.VNDB_TOKEN_PERMISSION, e.error)
        }
        assertEquals(VndbAuthError.PERMISSION.name, VndbAuthStore.getStatus(context).lastError)

        // PERMISSION 允许直接写入重试（不前置拦截）
        testHttpRoundTrip = { url, method, body, _ ->
            assertEquals("PATCH", method)
            assertTrue(url.endsWith("/ulist/v17"))
            val obj = JSONObject(body!!)
            assertEquals(listOf(2), obj.getJSONArray("labels_set").let { a -> (0 until a.length()).map { a.getInt(it) } })
            HttpJsonResult(204, null)
        }
        PlayStatusService.writeVndbStatus(context, "v17", VndbLabels.FINISHED)

        // writeVndbStatus 500 -> VNDB_NETWORK，不回写授权态
        testHttpRoundTrip = { _, _, _, _ -> HttpJsonResult(500, null) }
        try {
            PlayStatusService.writeVndbStatus(context, "v17", VndbLabels.PLAYING)
            fail("expected VNDB_NETWORK")
        } catch (e: PlayStatusException) {
            assertEquals(PlayStatusError.VNDB_NETWORK, e.error)
        }

        // writeVndbStatus 会话变更 -> AUTH_SESSION_CHANGED
        val staleSession = VndbAuthStore.sessionEpoch.value
        assertTrue(VndbAuthStore.saveToken(context, "vndb_pat_2", "u999", "bob", listOf("listwrite")))
        try {
            PlayStatusService.writeVndbStatus(context, "v17", VndbLabels.PLAYING, expectedSessionEpoch = staleSession)
            fail("expected AUTH_SESSION_CHANGED")
        } catch (e: PlayStatusException) {
            assertEquals(PlayStatusError.AUTH_SESSION_CHANGED, e.error)
        }
    }

    @Test
    fun nextMoeReadCoversCompletionUnmarkedDeletedGoneAndRateLimit() {
        assertTrue(
            NextMoeAuthStore.saveTokens(
                context,
                "nm_access",
                "nm_refresh",
                System.currentTimeMillis() + 600_000L,
            ),
        )

        testHttpRoundTrip = { _, _, _, _ ->
            HttpJsonResult(200, JSONObject().put("state", "doing").put("completion", "main"))
        }
        val active = PlayStatusService.readNextMoeStatus(context, "207379")
        assertTrue(active.exists)
        assertEquals("doing", active.state)
        assertEquals("main", active.completion)

        testHttpRoundTrip = { _, _, _, _ ->
            HttpJsonResult(404, JSONObject().put("code", "NOT_FOUND"))
        }
        val unmarked = PlayStatusService.readNextMoeStatus(context, "207379")
        assertFalse(unmarked.exists)
        assertNull(unmarked.state)

        testHttpRoundTrip = { _, _, _, _ ->
            HttpJsonResult(404, JSONObject().put("code", "ENTITY_DELETED"))
        }
        try {
            PlayStatusService.readNextMoeStatus(context, "207379")
            fail("expected NEXTMOE_SUBJECT_NOT_FOUND")
        } catch (e: PlayStatusException) {
            assertEquals(PlayStatusError.NEXTMOE_SUBJECT_NOT_FOUND, e.error)
        }

        testHttpRoundTrip = { _, _, _, _ -> HttpJsonResult(410, null) }
        try {
            PlayStatusService.readNextMoeStatus(context, "207379")
            fail("expected NEXTMOE_SUBJECT_NOT_FOUND on 410")
        } catch (e: PlayStatusException) {
            assertEquals(PlayStatusError.NEXTMOE_SUBJECT_NOT_FOUND, e.error)
        }

        testHttpRoundTrip = { _, _, _, _ -> HttpJsonResult(429, null) }
        try {
            PlayStatusService.readNextMoeStatus(context, "207379")
            fail("expected NEXTMOE_RATE_LIMITED on 429")
        } catch (e: PlayStatusException) {
            assertEquals(PlayStatusError.NEXTMOE_RATE_LIMITED, e.error)
        }
    }

    @Test
    fun hikarinagiWriteSuccessFalseThrowsNetworkError() {
        assertTrue(
            HikarinagiAuthStore.saveTokens(
                context,
                "hkn_access",
                "hkn_refresh",
                System.currentTimeMillis() + 600_000L,
            ),
        )
        testHttpRoundTrip = { _, _, _, _ ->
            HttpJsonResult(200, JSONObject().put("success", false))
        }
        try {
            PlayStatusService.writeHikarinagiStatus(context, "hkn-1", HikarinagiStatuses.GOING)
            fail("expected HIKARINAGI_NETWORK when success=false")
        } catch (e: PlayStatusException) {
            assertEquals(PlayStatusError.HIKARINAGI_NETWORK, e.error)
        }
    }

    @Test
    fun bindAndUnbindSubjectIdempotencyAndMissingGameBranches() {
        val inLib = ScanGame(
            title = "Sakura",
            uri = "content://games/bind_test",
            engine = EngineType.KIRIKIRI,
            launchTarget = "data.xp3",
        )
        GameLibraryFacade.seedGamesForTest(listOf(inLib))

        val bound = PlayStatusService.bindSubject(context, inLib, AppSettingsStore.COVER_SOURCE_BANGUMI, "4154", "CLANNAD")
        assertNotNull(bound)
        assertEquals("4154", bound!!.bangumiId)
        assertEquals("CLANNAD", bound.metadataTitle)

        // 幂等重绑同 ID 且无新标题：不报错、仍返回当前行
        val idempotentBind = PlayStatusService.bindSubject(context, bound, AppSettingsStore.COVER_SOURCE_BANGUMI, "4154", null)
        assertEquals(bound, idempotentBind)

        val unbound = PlayStatusService.unbindSubject(context, bound, AppSettingsStore.COVER_SOURCE_BANGUMI)
        assertNotNull(unbound)
        assertNull(unbound!!.bangumiId)
        assertEquals("CLANNAD", unbound.metadataTitle)

        // 幂等解绑已为空的来源：仍返回当前行
        val idempotentUnbind = PlayStatusService.unbindSubject(context, unbound, AppSettingsStore.COVER_SOURCE_BANGUMI)
        assertEquals(unbound, idempotentUnbind)

        // 游戏不在库内：bindSubject 与 unbindSubject 均返回 null
        val missingGame = inLib.copy(uri = "content://games/missing")
        assertNull(PlayStatusService.bindSubject(context, missingGame, AppSettingsStore.COVER_SOURCE_BANGUMI, "4154", "CLANNAD"))
        assertNull(PlayStatusService.unbindSubject(context, missingGame, AppSettingsStore.COVER_SOURCE_BANGUMI))
    }
}
