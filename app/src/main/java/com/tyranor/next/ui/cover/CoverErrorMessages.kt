package com.tyranor.next.ui.cover

import android.content.Context
import androidx.annotation.StringRes
import com.tyranor.next.R
import com.tyranor.next.core.cover.CoverErrorCode
import com.tyranor.next.core.i18n.AppLocaleController

@StringRes
internal fun CoverErrorCode.coverErrorResource(): Int = when (this) {
    CoverErrorCode.INVALID_SOURCE -> R.string.cover_error_invalid_source
    CoverErrorCode.SOURCE_DISABLED -> R.string.cover_error_source_disabled
    CoverErrorCode.UNKNOWN -> R.string.cover_error_unknown
    CoverErrorCode.HIKARINAGI_AUTH_MISSING -> R.string.cover_error_hikarinagi_auth_missing
    CoverErrorCode.HIKARINAGI_AUTH_EXPIRED -> R.string.auth_hikarinagi_expired
    CoverErrorCode.HIKARINAGI_NETWORK -> R.string.cover_error_hikarinagi_network
    CoverErrorCode.HIKARINAGI_RETRY -> R.string.cover_error_hikarinagi_retry
    CoverErrorCode.BANGUMI_NETWORK -> R.string.cover_error_bangumi_network
    CoverErrorCode.STEAM_NETWORK -> R.string.cover_error_steam_network
    CoverErrorCode.VNDB_NETWORK -> R.string.cover_error_vndb_network
    CoverErrorCode.NEXTMOE_NETWORK -> R.string.cover_error_nextmoe_network
    CoverErrorCode.NEXTMOE_RATE_LIMITED -> R.string.cover_error_nextmoe_rate_limited
    CoverErrorCode.CANCELLED -> R.string.cover_scrape_cancelled
}

/**
 * 封面失败错误码 → 用户文案（AGENTS.md 错误处理协议：core 只返回错误码，文案在 UI 组装）。
 * 与 `ui/common/LaunchErrorMessages.kt` 同构：非 Composable，页面与 ViewModel 均可调用。
 * 命名刻意区别于 `LaunchResult.userMessage`，避免同一文件同时导入两个同名扩展。
 */
internal fun CoverErrorCode.coverErrorMessage(context: Context): String =
    AppLocaleController.wrap(context).getString(coverErrorResource())
