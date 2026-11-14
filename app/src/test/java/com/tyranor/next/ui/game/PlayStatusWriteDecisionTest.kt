package com.tyranor.next.ui.game

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.tyranor.next.core.auth.BangumiAuthService
import com.tyranor.next.core.auth.BangumiAuthStore
import com.tyranor.next.core.auth.HikarinagiAuthService
import com.tyranor.next.core.auth.HikarinagiAuthStore
import com.tyranor.next.core.auth.NextMoeAuthError
import com.tyranor.next.core.auth.NextMoeAuthService
import com.tyranor.next.core.auth.NextMoeAuthStore
import com.tyranor.next.core.auth.SecureAuthStore
import com.tyranor.next.core.auth.VndbAuthStore
import com.tyranor.next.core.engine.EngineType
import com.tyranor.next.core.game.model.ScanGame
import com.tyranor.next.core.settings.AppSettingsStore
import com.tyranor.next.core.status.BangumiCollectTypes
import com.tyranor.next.core.status.HikarinagiStatuses
import com.tyranor.next.core.status.NextMoeWorkStates
import com.tyranor.next.core.status.PlayStatusError
import com.tyranor.next.core.status.PlayStatusException
import com.tyranor.next.core.status.PlayStatusService
import com.tyranor.next.core.status.VndbLabels
import org.json.JSONObject
import java.lang.reflect.Proxy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 游玩状态写后 UI 决策回归：
 * 1. 同账号刷新令牌仅递增 credentialEpoch，不改变 sessionEpoch —— Bangumi / Hikarinagi 写入成功后
 *    仍应应用乐观结果并关闭选择弹窗；
 * 2. Hikarinagi 写入期间若发生后台刷新（可能伴随中间回读返回旧状态），写后应额外触发确认回读；
 * 3. 若发生显式重登、登出或换绑（sessionEpoch / boundId 变化），写入结果不得污染新状态。
 */
class PlayStatusWriteDecisionTest {

    private val storage = mutableMapOf<String, MutableMap<String, Any>>()
    private val memoryTokens = mutableMapOf<String, String>()

    private val context: Context = object : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val backing = storage.getOrPut(name) { mutableMapOf() }
            return Proxy.newProxyInstance(
                SharedPreferences::class.java.classLoader,
                arrayOf(SharedPreferences::class.java),
            ) { _, method, args ->
                when (method.name) {
                    "contains" -> backing.containsKey(args[0])
                    "getString" -> backing[args[0]] as? String ?: args.getOrNull(1) as? String
                    "getLong" -> backing[args[0]] as? Long ?: args.getOrNull(1) as? Long ?: 0L
                    "getInt" -> backing[args[0]] as? Int ?: args.getOrNull(1) as? Int ?: 0
                    "getBoolean" -> backing[args[0]] as? Boolean ?: args.getOrNull(1) as? Boolean ?: false
                    "getAll" -> backing.toMap()
                    "edit" -> createMemoryEditor(backing)
                    else -> null
                }
            } as SharedPreferences
        }
    }

    private val baseGame = ScanGame(
        title = "Demo",
        uri = "content://games/demo",
        engine = EngineType.KIRIKIRI,
        launchTarget = "/games/demo",
        bangumiId = "4154",
        hikarinagiId = "hkn-1",
    )

    @Before
    fun setUp() {
        storage.clear()
        memoryTokens.clear()
        SecureAuthStore.testCipherDelegate = { field, plain ->
            if (plain != null) {
                if (plain.isEmpty()) memoryTokens.remove(field) else memoryTokens[field] = plain
                null
            } else {
                memoryTokens[field]
            }
        }
    }

    @After
    fun tearDown() {
        BangumiAuthService.testRefreshHttpRoundTrip = null
        BangumiAuthService.testClientCredentials = null
        HikarinagiAuthService.testRefreshHttpRoundTrip = null
        HikarinagiAuthService.testClientId = null
        NextMoeAuthService.testRefreshHttpRoundTrip = null
        NextMoeAuthService.testClientId = null
        SecureAuthStore.testCipherDelegate = null
        storage.clear()
        memoryTokens.clear()
    }

    @Test
    fun bangumiSameSessionRefreshKeepsSessionEpochAndAppliesWriteResult() {
        assertTrue(BangumiAuthStore.saveTokens(context, "bgm_a1", "bgm_r1", 100L))
        val (refreshToken, startCredEpoch) = BangumiAuthStore.readRefreshSnapshot(context)
        val startSessionEpoch = BangumiAuthStore.sessionEpoch.value

        // 模拟写入在途期间 Bangumi 同账号刷新令牌：credentialEpoch 递增，sessionEpoch 保持不变
        assertTrue(
            BangumiAuthStore.saveTokensIfMatching(
                context,
                startCredEpoch,
                refreshToken,
                "bgm_a2",
                "bgm_r2",
                999_999L,
            ),
        )
        assertTrue(BangumiAuthStore.credentialEpoch.value > startCredEpoch)
        assertEquals(startSessionEpoch, BangumiAuthStore.sessionEpoch.value)

        val decision = evaluatePlayStatusWriteDecision(
            source = AppSettingsStore.COVER_SOURCE_BANGUMI,
            targetUri = baseGame.uri,
            boundId = "4154",
            sourceSessionEpoch = startSessionEpoch,
            sourceCredentialEpoch = startCredEpoch,
            latestGame = baseGame,
        )
        assertTrue(decision.applyOptimisticResult)
        assertFalse(decision.triggerHikarinagiReread)

        // 若期间完成了显式重新登录或登出（sessionEpoch 变更），必须拒绝提交旧写入结果
        BangumiAuthStore.clear(context)
        assertTrue(BangumiAuthStore.sessionEpoch.value > startSessionEpoch)
        val superseded = evaluatePlayStatusWriteDecision(
            source = AppSettingsStore.COVER_SOURCE_BANGUMI,
            targetUri = baseGame.uri,
            boundId = "4154",
            sourceSessionEpoch = startSessionEpoch,
            sourceCredentialEpoch = startCredEpoch,
            latestGame = baseGame,
        )
        assertFalse(superseded.applyOptimisticResult)
    }

    @Test
    fun hikarinagiRefreshFollowedByStaleReadAllowsWriteUpdateAndRequestsConfirmationReread() {
        assertTrue(HikarinagiAuthStore.saveTokens(context, "hika_a1", "hika_r1", 100L))
        val (refreshToken, startCredEpoch) = HikarinagiAuthStore.readRefreshSnapshot(context)
        val startSessionEpoch = HikarinagiAuthStore.sessionEpoch.value

        // 步骤 1：写入期间 Hikarinagi 令牌刷新（credentialEpoch 递增、sessionEpoch 不变），
        // 可触发一次回读先返回旧状态
        assertTrue(
            HikarinagiAuthStore.saveTokensIfMatching(
                context,
                startCredEpoch,
                refreshToken,
                "hika_a2",
                "hika_r2",
                999_999L,
            ),
        )
        assertTrue(HikarinagiAuthStore.credentialEpoch.value > startCredEpoch)
        assertEquals(startSessionEpoch, HikarinagiAuthStore.sessionEpoch.value)

        // 步骤 2：写入完成后判定——同一会话下仍提交刚写入的状态，并触发写后确认回读覆盖在途旧读
        val decision = evaluatePlayStatusWriteDecision(
            source = AppSettingsStore.COVER_SOURCE_HIKARINAGI,
            targetUri = baseGame.uri,
            boundId = "hkn-1",
            sourceSessionEpoch = startSessionEpoch,
            sourceCredentialEpoch = startCredEpoch,
            latestGame = baseGame,
        )
        assertTrue(decision.applyOptimisticResult)
        assertTrue(decision.triggerHikarinagiReread)

        // 步骤 3：若期间已切换账号或解绑，既不提交乐观结果也不触发多余回读
        val unboundDecision = evaluatePlayStatusWriteDecision(
            source = AppSettingsStore.COVER_SOURCE_HIKARINAGI,
            targetUri = baseGame.uri,
            boundId = "hkn-1",
            sourceSessionEpoch = startSessionEpoch,
            sourceCredentialEpoch = startCredEpoch,
            latestGame = baseGame.copy(hikarinagiId = null),
        )
        assertFalse(unboundDecision.applyOptimisticResult)
        assertFalse(unboundDecision.triggerHikarinagiReread)
    }

    @Test
    fun writeBangumiHikarinagiAndVndbRejectSupersededSessionEpochBeforeHttp() {
        assertTrue(BangumiAuthStore.saveTokens(context, "bgm_a1", "bgm_r1", 3600L))
        val bgmEpoch = BangumiAuthStore.sessionEpoch.value
        assertTrue(BangumiAuthStore.saveTokens(context, "bgm_a2", "bgm_r2", 3600L))
        val bgmEx = assertThrows(PlayStatusException::class.java) {
            PlayStatusService.writeBangumiStatus(
                context,
                "4154",
                BangumiCollectTypes.DONE,
                expectedSessionEpoch = bgmEpoch,
            )
        }
        assertEquals(PlayStatusError.AUTH_SESSION_CHANGED, bgmEx.error)

        assertTrue(HikarinagiAuthStore.saveTokens(context, "hika_a1", "hika_r1", 3600L))
        val hikaEpoch = HikarinagiAuthStore.sessionEpoch.value
        HikarinagiAuthStore.clear(context)
        val hikaEx = assertThrows(PlayStatusException::class.java) {
            PlayStatusService.writeHikarinagiStatus(
                context,
                "hkn-1",
                HikarinagiStatuses.GOING,
                expectedSessionEpoch = hikaEpoch,
            )
        }
        assertEquals(PlayStatusError.AUTH_SESSION_CHANGED, hikaEx.error)

        assertTrue(VndbAuthStore.saveToken(context, "vndb_t1", "u1", "user1", listOf("listwrite")))
        val vndbEpoch = VndbAuthStore.sessionEpoch.value
        assertTrue(VndbAuthStore.saveToken(context, "vndb_t2", "u2", "user2", listOf("listwrite")))
        val vndbEx = assertThrows(PlayStatusException::class.java) {
            PlayStatusService.writeVndbStatus(
                context,
                "v17",
                VndbLabels.PLAYING,
                expectedSessionEpoch = vndbEpoch,
            )
        }
        assertEquals(PlayStatusError.AUTH_SESSION_CHANGED, vndbEx.error)
    }

    @Test
    fun writeBangumiAndHikarinagiRejectMidRefreshAccountSwitch() {
        assertTrue(BangumiAuthStore.saveTokens(context, "bgm_old_a", "bgm_old_r", 1L))
        val bgmStartEpoch = BangumiAuthStore.sessionEpoch.value
        BangumiAuthService.testClientCredentials = "cid" to "csecret"
        BangumiAuthService.testRefreshHttpRoundTrip = { _, _ ->
            // 模拟刷新往返期间用户完成新账号登录，sessionEpoch 递增
            BangumiAuthStore.saveTokens(context, "bgm_new_a", "bgm_new_r", 3600L)
            200 to """{"access_token":"stale_refreshed","refresh_token":"stale_r2","expires_in":3600}"""
        }
        val bgmEx = assertThrows(PlayStatusException::class.java) {
            PlayStatusService.writeBangumiStatus(
                context,
                "4154",
                BangumiCollectTypes.DONE,
                expectedSessionEpoch = bgmStartEpoch,
            )
        }
        assertEquals(PlayStatusError.AUTH_SESSION_CHANGED, bgmEx.error)

        assertTrue(HikarinagiAuthStore.saveTokens(context, "hika_old_a", "hika_old_r", 1L))
        val hikaStartEpoch = HikarinagiAuthStore.sessionEpoch.value
        HikarinagiAuthService.testClientId = "cid"
        HikarinagiAuthService.testRefreshHttpRoundTrip = { _, _ ->
            HikarinagiAuthStore.saveTokens(context, "hika_new_a", "hika_new_r", 3600L)
            200 to """{"access_token":"stale_refreshed","refresh_token":"stale_r2","expires_in":3600}"""
        }
        val hikaEx = assertThrows(PlayStatusException::class.java) {
            PlayStatusService.writeHikarinagiStatus(
                context,
                "hkn-1",
                HikarinagiStatuses.GOING,
                expectedSessionEpoch = hikaStartEpoch,
            )
        }
        assertEquals(PlayStatusError.AUTH_SESSION_CHANGED, hikaEx.error)
    }

    @Test
    fun readAccessTokenIfSessionMatchesAllowsSameSessionRefreshAndRejectsAccountSwitch() {
        assertTrue(BangumiAuthStore.saveTokens(context, "bgm_a1", "bgm_r1", 100L))
        val (bgmRefresh, bgmCredEpoch) = BangumiAuthStore.readRefreshSnapshot(context)
        val bgmSession = BangumiAuthStore.sessionEpoch.value
        assertTrue(
            BangumiAuthStore.saveTokensIfMatching(
                context,
                bgmCredEpoch,
                bgmRefresh,
                "bgm_a2",
                "bgm_r2",
                3600L,
            ),
        )
        assertEquals("bgm_a2", BangumiAuthStore.readAccessTokenIfSessionMatches(context, bgmSession))
        assertTrue(BangumiAuthStore.saveTokens(context, "bgm_other", "bgm_r3", 3600L))
        assertNull(BangumiAuthStore.readAccessTokenIfSessionMatches(context, bgmSession))

        assertTrue(HikarinagiAuthStore.saveTokens(context, "hika_a1", "hika_r1", 100L))
        val (hikaRefresh, hikaCredEpoch) = HikarinagiAuthStore.readRefreshSnapshot(context)
        val hikaSession = HikarinagiAuthStore.sessionEpoch.value
        assertTrue(
            HikarinagiAuthStore.saveTokensIfMatching(
                context,
                hikaCredEpoch,
                hikaRefresh,
                "hika_a2",
                "hika_r2",
                3600L,
            ),
        )
        assertEquals("hika_a2", HikarinagiAuthStore.readAccessTokenIfSessionMatches(context, hikaSession))
        HikarinagiAuthStore.clear(context)
        assertNull(HikarinagiAuthStore.readAccessTokenIfSessionMatches(context, hikaSession))

        assertTrue(VndbAuthStore.saveToken(context, "vndb_t1", "u1", "user1", listOf("listwrite")))
        val vndbSession = VndbAuthStore.sessionEpoch.value
        assertEquals("vndb_t1", VndbAuthStore.readTokenIfSessionMatches(context, vndbSession))
        VndbAuthStore.clear(context)
        assertNull(VndbAuthStore.readTokenIfSessionMatches(context, vndbSession))

        assertTrue(NextMoeAuthStore.saveTokens(context, "nm_a1", "nm_r1", 100L))
        val (nmRefresh, nmCredEpoch) = NextMoeAuthStore.readRefreshSnapshot(context)
        val nmSession = NextMoeAuthStore.sessionEpoch.value
        assertTrue(
            NextMoeAuthStore.saveTokensIfMatching(
                context,
                nmCredEpoch,
                nmRefresh,
                "nm_a2",
                "nm_r2",
                3600L,
            ),
        )
        assertEquals("nm_a2", NextMoeAuthStore.readAccessTokenIfSessionMatches(context, nmSession))
        val decision = evaluatePlayStatusWriteDecision(
            source = AppSettingsStore.COVER_SOURCE_NEXTMOE,
            targetUri = baseGame.uri,
            boundId = "207379",
            sourceSessionEpoch = nmSession,
            sourceCredentialEpoch = nmCredEpoch,
            latestGame = baseGame.copy(nextmoeId = "207379"),
        )
        assertTrue(decision.applyOptimisticResult)
        assertTrue(decision.triggerNextMoeReread)

        NextMoeAuthStore.clear(context)
        assertNull(NextMoeAuthStore.readAccessTokenIfSessionMatches(context, nmSession))
        val staleDecision = evaluatePlayStatusWriteDecision(
            source = AppSettingsStore.COVER_SOURCE_NEXTMOE,
            targetUri = baseGame.uri,
            boundId = "207379",
            sourceSessionEpoch = nmSession,
            sourceCredentialEpoch = nmCredEpoch,
            latestGame = baseGame.copy(nextmoeId = "207379"),
        )
        assertFalse(staleDecision.applyOptimisticResult)
        assertFalse(staleDecision.triggerNextMoeReread)
    }

    @Test
    fun writeNextMoeStatusRejectsSupersededSessionEpochBeforeHttpAndMidRefresh() {
        assertTrue(NextMoeAuthStore.saveTokens(context, "nm_a1", "nm_r1", 3600L))
        val nmEpoch = NextMoeAuthStore.sessionEpoch.value
        assertTrue(NextMoeAuthStore.saveTokens(context, "nm_a2", "nm_r2", 3600L))
        val preHttpEx = assertThrows(PlayStatusException::class.java) {
            PlayStatusService.writeNextMoeStatus(
                context,
                "207379",
                NextMoeWorkStates.DOING,
                expectedSessionEpoch = nmEpoch,
            )
        }
        assertEquals(PlayStatusError.AUTH_SESSION_CHANGED, preHttpEx.error)

        assertTrue(NextMoeAuthStore.saveTokens(context, "nm_old_a", "nm_old_r", 1L))
        val startEpoch = NextMoeAuthStore.sessionEpoch.value
        NextMoeAuthService.testClientId = "nm_cid"
        NextMoeAuthService.testRefreshHttpRoundTrip = { _, _ ->
            NextMoeAuthStore.saveTokens(context, "nm_new_a", "nm_new_r", 3600L)
            200 to """{"access_token":"stale_refreshed","refresh_token":"stale_r2","expires_in":900}"""
        }
        val midRefreshEx = assertThrows(PlayStatusException::class.java) {
            PlayStatusService.writeNextMoeStatus(
                context,
                "207379",
                NextMoeWorkStates.DONE,
                preservedCompletion = "main",
                expectedSessionEpoch = startEpoch,
            )
        }
        assertEquals(PlayStatusError.AUTH_SESSION_CHANGED, midRefreshEx.error)
    }

    @Test
    fun finishNextMoeStatusReadGuardsErrorClearsByAccessToken() {
        assertTrue(NextMoeAuthStore.saveTokens(context, "nm_tok_new", "nm_ref_new", System.currentTimeMillis() + 3_600_000L))
        NextMoeAuthStore.markNeedsReauth(context, NextMoeAuthError.EXPIRED.name)
        NextMoeAuthStore.markPermissionDenied(context, NextMoeAuthError.PERMISSION.name)

        val okJson = JSONObject().put("state", NextMoeWorkStates.DOING).put("completion", "one_route")
        PlayStatusService.finishNextMoeStatusRead(context, "nm_tok_old", okJson)
        assertTrue(NextMoeAuthStore.getStatus(context).needsReauth)
        assertTrue(NextMoeAuthStore.getStatus(context).permissionDenied)

        PlayStatusService.finishNextMoeStatusRead(context, "nm_tok_old", null)
        assertTrue(NextMoeAuthStore.getStatus(context).needsReauth)
        assertTrue(NextMoeAuthStore.getStatus(context).permissionDenied)

        val notFound = PlayStatusService.finishNextMoeStatusRead(context, "nm_tok_new", null)
        assertFalse(notFound.exists)
        assertFalse(NextMoeAuthStore.getStatus(context).needsReauth)
        assertFalse(NextMoeAuthStore.getStatus(context).permissionDenied)

        NextMoeAuthStore.markNeedsReauth(context, NextMoeAuthError.EXPIRED.name)
        NextMoeAuthStore.markPermissionDenied(context, NextMoeAuthError.PERMISSION.name)
        val loaded = PlayStatusService.finishNextMoeStatusRead(context, "nm_tok_new", okJson)
        assertTrue(loaded.exists)
        assertEquals(NextMoeWorkStates.DOING, loaded.state)
        assertEquals("one_route", loaded.completion)
        assertFalse(NextMoeAuthStore.getStatus(context).needsReauth)
        assertFalse(NextMoeAuthStore.getStatus(context).permissionDenied)
    }

    private fun createMemoryEditor(backing: MutableMap<String, Any>): SharedPreferences.Editor {
        val pending = mutableMapOf<String, Any?>()
        var clear = false
        lateinit var editor: SharedPreferences.Editor
        editor = Proxy.newProxyInstance(
            SharedPreferences.Editor::class.java.classLoader,
            arrayOf(SharedPreferences.Editor::class.java),
        ) { _, method, args ->
            when (method.name) {
                "putString" -> { pending[args[0] as String] = args[1]; editor }
                "putLong" -> { pending[args[0] as String] = args[1]; editor }
                "putInt" -> { pending[args[0] as String] = args[1]; editor }
                "putBoolean" -> { pending[args[0] as String] = args[1]; editor }
                "remove" -> { pending[args[0] as String] = null; editor }
                "clear" -> { clear = true; pending.clear(); editor }
                "apply", "commit" -> {
                    if (clear) backing.clear()
                    for ((k, v) in pending) {
                        if (v == null) backing.remove(k) else backing[k] = v
                    }
                    if (method.name == "commit") true else null
                }
                else -> null
            }
        } as SharedPreferences.Editor
        return editor
    }
}
