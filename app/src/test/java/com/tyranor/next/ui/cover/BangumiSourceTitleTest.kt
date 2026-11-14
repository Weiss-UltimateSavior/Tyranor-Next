package com.tyranor.next.ui.cover

import com.tyranor.next.core.auth.BangumiAuthStatus
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Bangumi 来源行标题拼装（纯映射）：授权且已解析用户名时追加「 (用户名)」；
 * 未授权或用户名空白（含纯空格）时按输入返回基础标题。数字 uid 兜底串视为
 * 用户名参与拼装（store 侧缺名回退行为由 AuthStatusPersistenceTest 固化）。
 *
 * 自 AuthStatusPersistenceTest 拆出：core/auth 测试不反向 import ui 层。
 */
class BangumiSourceTitleTest {

    @Test
    fun authorizedWithResolvedUsernameAppendsUser() {
        assertEquals(
            "Bangumi (bgm_user)",
            bangumiSourceTitle("Bangumi", status(username = "bgm_user")),
        )
    }

    @Test
    fun numericUserIdFallbackIsUsedAsUsername() {
        assertEquals(
            "Bangumi (987654)",
            bangumiSourceTitle("Bangumi", status(username = "987654")),
        )
    }

    @Test
    fun unauthorizedOrBlankUsernameReturnsBaseTitle() {
        assertEquals(
            "Bangumi",
            bangumiSourceTitle("Bangumi", status(username = "987654", authorized = false)),
        )
        assertEquals(
            "Bangumi",
            bangumiSourceTitle("Bangumi", status(username = "   ")),
        )
    }

    private fun status(
        username: String,
        authorized: Boolean = true,
    ): BangumiAuthStatus = BangumiAuthStatus(
        authorized = authorized,
        needsReauth = false,
        expiresAtMillis = 0L,
        username = username,
        lastError = "",
    )
}
