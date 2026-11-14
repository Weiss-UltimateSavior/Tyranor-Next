package com.tyranor.next.ui.cover

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * VNDB 授权条目点击行为决策：已授权且未失效的「退出」=直接登出
 * （与 Bangumi / Hikarinagi 同文案按钮行为对齐，不再实开令牌粘贴弹窗）；
 * 未授权或授权已失效 → 打开令牌粘贴弹窗重新授权。
 */
class VndbAuthRowActionTest {

    @Test
    fun authorizedAndHealthyRowLogsOutDirectly() {
        assertEquals(VndbAuthRowAction.LOG_OUT, vndbAuthRowAction(authorized = true, needsReauth = false))
    }

    @Test
    fun needsReauthOrGuestRowOpensTokenDialog() {
        assertEquals(VndbAuthRowAction.SHOW_TOKEN_DIALOG, vndbAuthRowAction(authorized = true, needsReauth = true))
        assertEquals(VndbAuthRowAction.SHOW_TOKEN_DIALOG, vndbAuthRowAction(authorized = false, needsReauth = false))
        assertEquals(VndbAuthRowAction.SHOW_TOKEN_DIALOG, vndbAuthRowAction(authorized = false, needsReauth = true))
    }

    @Test
    fun nextMoeClearRemainsAvailableWhenCredentialsExistDuringNeedsReauth() {
        val guest = nextMoeAuthRowActions(authorized = false, needsReauth = false)
        assertTrue(guest.showAuthorize)
        assertFalse(guest.showClear)

        val storageErrorWithoutCreds = nextMoeAuthRowActions(authorized = false, needsReauth = true)
        assertTrue(storageErrorWithoutCreds.showAuthorize)
        assertFalse(storageErrorWithoutCreds.showClear)

        val healthy = nextMoeAuthRowActions(authorized = true, needsReauth = false)
        assertFalse(healthy.showAuthorize)
        assertTrue(healthy.showClear)

        val expiredWithStoredCreds = nextMoeAuthRowActions(authorized = true, needsReauth = true)
        assertTrue(expiredWithStoredCreds.showAuthorize)
        assertTrue(expiredWithStoredCreds.showClear)
    }
}
