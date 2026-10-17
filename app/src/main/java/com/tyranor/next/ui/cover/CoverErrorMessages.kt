package com.tyranor.next.ui.cover

import android.content.Context
import com.tyranor.next.R
import com.tyranor.next.core.cover.CoverErrorCode
import com.tyranor.next.core.i18n.AppLocaleController

/**
 * 封面失败错误码 → 用户文案（AGENTS.md 错误处理协议：core 只返回错误码，文案在 UI 组装）。
 * 与 `ui/common/LaunchErrorMessages.kt` 同构：非 Composable，页面与 ViewModel 均可调用。
 * 命名刻意区别于 `LaunchResult.userMessage`，避免同一文件同时导入两个同名扩展。
 */
internal fun CoverErrorCode.coverErrorMessage(context: Context): String {
    val localized = AppLocaleController.wrap(context)
    return when (this) {
        CoverErrorCode.INVALID_SOURCE -> localized.getString(R.string.cover_error_invalid_source)
        CoverErrorCode.SOURCE_DISABLED -> localized.getString(R.string.cover_error_source_disabled)
        CoverErrorCode.UNKNOWN -> localized.getString(R.string.cover_error_unknown)
        CoverErrorCode.HIKARINAGI_AUTH_EXPIRED -> localized.getString(R.string.auth_hikarinagi_expired)
        CoverErrorCode.HIKARINAGI_NETWORK -> localized.getString(R.string.cover_error_hikarinagi_network)
        CoverErrorCode.HIKARINAGI_RETRY -> localized.getString(R.string.cover_error_hikarinagi_retry)
        CoverErrorCode.BANGUMI_NETWORK -> localized.getString(R.string.cover_error_bangumi_network)
        CoverErrorCode.STEAM_NETWORK -> localized.getString(R.string.cover_error_steam_network)
        CoverErrorCode.VNDB_NETWORK -> localized.getString(R.string.cover_error_vndb_network)
        CoverErrorCode.NEXTMOE_NETWORK -> localized.getString(R.string.cover_error_nextmoe_network)
        CoverErrorCode.NEXTMOE_RATE_LIMITED -> localized.getString(R.string.cover_error_nextmoe_rate_limited)
        CoverErrorCode.CANCELLED -> localized.getString(R.string.cover_scrape_cancelled)
    }
}
