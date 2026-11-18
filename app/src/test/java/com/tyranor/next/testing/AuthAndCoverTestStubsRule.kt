package com.tyranor.next.testing

import com.tyranor.next.core.auth.BangumiAuthService
import com.tyranor.next.core.auth.HikarinagiAuthService
import com.tyranor.next.core.auth.HikarinagiAuthStore
import com.tyranor.next.core.auth.NextMoeAuthService
import com.tyranor.next.core.auth.SecureAuthStore
import com.tyranor.next.core.cover.CoverImageCache
import com.tyranor.next.core.cover.CoverScrapeTaskManager
import com.tyranor.next.core.cover.NextMoeCoverService
import com.tyranor.next.core.game.storage.GameLibraryFacade
import com.tyranor.next.core.net.testHttpRoundTrip
import com.tyranor.next.ui.game.PlayStatusSummaryCache
import java.util.concurrent.ConcurrentHashMap
import org.junit.rules.ExternalResource

/**
 * JUnit Rule：在每个用例前后统一复位 auth / cover / status / facade 的进程级测试桩与缓存，
 * 避免依赖手写 `@After` 遗漏或在异常中止时污染跨用例状态。
 */
class AuthAndCoverTestStubsRule(
    private val installInMemoryCipher: Boolean = false,
) : ExternalResource() {

    private val memoryTokens = ConcurrentHashMap<String, String>()

    override fun before() {
        resetAll()
        if (installInMemoryCipher) {
            SecureAuthStore.testCipherDelegate = { field, plain ->
                if (plain != null) {
                    if (plain.isEmpty()) memoryTokens.remove(field)
                    else memoryTokens[field] = plain
                    null
                } else {
                    memoryTokens[field]
                }
            }
        }
    }

    override fun after() {
        resetAll()
    }

    fun clearMemoryTokens() {
        memoryTokens.clear()
    }

    private fun resetAll() {
        memoryTokens.clear()
        SecureAuthStore.testCipherDelegate = null
        SecureAuthStore.testForceEncryptFail = false
        SecureAuthStore.clearKeyCacheForTest()
        testHttpRoundTrip = null
        NextMoeCoverService.testHttpRoundTrip = null
        CoverImageCache.testDownloadResult = null
        BangumiAuthService.testRefreshHttpRoundTrip = null
        BangumiAuthService.testClientCredentials = null
        BangumiAuthService.resetForTest()
        HikarinagiAuthService.testRefreshHttpRoundTrip = null
        HikarinagiAuthService.testClientId = null
        HikarinagiAuthService.resetForTest()
        HikarinagiAuthStore.resetForTest()
        NextMoeAuthService.testRefreshHttpRoundTrip = null
        NextMoeAuthService.testTokenExchangeHttpRoundTrip = null
        NextMoeAuthService.testClientId = null
        NextMoeAuthService.resetForTest()
        CoverScrapeTaskManager.resetForTest()
        GameLibraryFacade.skipPersistForTest = false
        GameLibraryFacade.seedGamesForTest(null)
        GameLibraryFacade.resetCachesForTest()
        PlayStatusSummaryCache.clearForTest()
    }
}
