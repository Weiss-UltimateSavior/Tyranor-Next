package com.tyranor.next.ui.auth

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tyranor.next.core.auth.BangumiAuthService
import com.tyranor.next.core.auth.BangumiAuthStore
import com.tyranor.next.core.auth.HikarinagiAuthService
import com.tyranor.next.core.auth.HikarinagiAuthStore
import com.tyranor.next.core.auth.SecureAuthStore
import java.util.concurrent.ConcurrentHashMap
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.ResponseTypeValues
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 真机 OAuth 回调 Activity 生命周期回归：
 * 1. `BangumiOAuthCallbackActivity` 处理取消、缺失响应与 state 不匹配并清除授权中时间戳、自动 finish；
 * 2. `HikarinagiOAuthCallbackActivity` 处理取消、缺失响应与 state 不匹配并安全结束。
 */
@RunWith(AndroidJUnit4::class)
class OAuthCallbackActivityDeviceTest {

    private val memoryTokens = ConcurrentHashMap<String, String>()
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        memoryTokens.clear()
        SecureAuthStore.testCipherDelegate = { field, plain ->
            if (plain != null) {
                if (plain.isEmpty()) memoryTokens.remove(field) else memoryTokens[field] = plain
                null
            } else {
                memoryTokens[field]
            }
        }
        BangumiAuthStore.clear(context)
        HikarinagiAuthStore.clear(context)
    }

    @After
    fun tearDown() {
        try {
            runCatching { BangumiAuthService.resetForTest() }
            runCatching { HikarinagiAuthService.resetForTest() }
            runCatching { BangumiAuthStore.clear(context) }
            runCatching { HikarinagiAuthStore.clear(context) }
        } finally {
            SecureAuthStore.testCipherDelegate = null
            SecureAuthStore.clearKeyCacheForTest()
            memoryTokens.clear()
        }
    }

    @Test
    fun bangumiCallbackHandlesCancelInvalidAndStateMismatchAndFinishesCleanly() {
        BangumiAuthStore.setAuthInProgress(context, System.currentTimeMillis())
        val cancelIntent = Intent(context, BangumiOAuthCallbackActivity::class.java)
            .putExtra(BangumiAuthService.EXTRA_AUTH_CANCELED, true)
        ActivityScenario.launch<BangumiOAuthCallbackActivity>(cancelIntent).use { scenario ->
            waitUntilDestroyed(scenario)
            assertEquals(0L, BangumiAuthStore.getStatus(context).authInProgressAtMillis)
        }

        val invalidIntent = Intent(context, BangumiOAuthCallbackActivity::class.java)
        ActivityScenario.launch<BangumiOAuthCallbackActivity>(invalidIntent).use { scenario ->
            waitUntilDestroyed(scenario)
        }

        val future = System.currentTimeMillis() + 3_600_000L
        assertTrue(BangumiAuthStore.saveTokens(context, "kept_tok", "kept_ref", future))
        val config = AuthorizationServiceConfiguration(
            Uri.parse("https://bgm.tv/oauth/authorize"),
            Uri.parse("https://bgm.tv/oauth/access_token"),
        )
        val req = AuthorizationRequest.Builder(
            config,
            "bgm_client",
            ResponseTypeValues.CODE,
            Uri.parse("tyranornext://oauth/bangumi"),
        ).setState("expected_state").build()
        val mismatchResp = AuthorizationResponse.Builder(req)
            .setAuthorizationCode("code_1")
            .setState("tampered_state")
            .build()
        val mismatchIntent = mismatchResp.toIntent().setClass(context, BangumiOAuthCallbackActivity::class.java)
        ActivityScenario.launch<BangumiOAuthCallbackActivity>(mismatchIntent).use { scenario ->
            waitUntilDestroyed(scenario)
            assertEquals("kept_tok", BangumiAuthStore.getAccessToken(context))
        }
    }

    @Test
    fun hikarinagiCallbackHandlesCancelInvalidAndStateMismatchAndFinishesCleanly() {
        val cancelIntent = Intent(context, HikarinagiOAuthCallbackActivity::class.java)
            .putExtra(HikarinagiAuthService.EXTRA_AUTH_CANCELED, true)
        ActivityScenario.launch<HikarinagiOAuthCallbackActivity>(cancelIntent).use { scenario ->
            waitUntilDestroyed(scenario)
        }

        val invalidIntent = Intent(context, HikarinagiOAuthCallbackActivity::class.java)
        ActivityScenario.launch<HikarinagiOAuthCallbackActivity>(invalidIntent).use { scenario ->
            waitUntilDestroyed(scenario)
        }

        val future = System.currentTimeMillis() + 3_600_000L
        assertTrue(HikarinagiAuthStore.saveTokens(context, "kept_hika", "kept_hika_ref", future))
        val config = AuthorizationServiceConfiguration(
            Uri.parse("https://id.hikarinagi.org/oidc/auth"),
            Uri.parse("https://id.hikarinagi.org/oidc/token"),
        )
        val req = AuthorizationRequest.Builder(
            config,
            "hika_client",
            ResponseTypeValues.CODE,
            Uri.parse("tyranornext://oauth/hikarinagi"),
        ).setState("expected_state").build()
        val mismatchResp = AuthorizationResponse.Builder(req)
            .setAuthorizationCode("code_2")
            .setState("tampered_state")
            .build()
        val mismatchIntent = mismatchResp.toIntent().setClass(context, HikarinagiOAuthCallbackActivity::class.java)
        ActivityScenario.launch<HikarinagiOAuthCallbackActivity>(mismatchIntent).use { scenario ->
            waitUntilDestroyed(scenario)
            assertEquals("kept_hika", HikarinagiAuthStore.getAccessToken(context))
        }
    }

    private fun <A : android.app.Activity> waitUntilDestroyed(scenario: ActivityScenario<A>) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (scenario.state != Lifecycle.State.DESTROYED && System.nanoTime() < deadline) {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            if (scenario.state != Lifecycle.State.DESTROYED) {
                Thread.sleep(20)
            }
        }
        assertEquals(Lifecycle.State.DESTROYED, scenario.state)
    }
}
