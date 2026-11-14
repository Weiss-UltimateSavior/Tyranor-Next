package com.tyranor.next.ui.auth

import android.content.Context
import androidx.annotation.StringRes
import com.tyranor.next.R
import com.tyranor.next.core.auth.HikarinagiAuthError
import com.tyranor.next.core.i18n.AppLocaleController

@StringRes
internal fun HikarinagiAuthError.authErrorResource(): Int = when (this) {
    HikarinagiAuthError.CLIENT_ID_MISSING -> R.string.auth_hikarinagi_client_id_missing
    HikarinagiAuthError.NO_BROWSER -> R.string.auth_hikarinagi_no_browser
    HikarinagiAuthError.LAUNCH_FAILED -> R.string.auth_hikarinagi_launch_failed
    HikarinagiAuthError.CANCELLED -> R.string.auth_hikarinagi_cancelled
    HikarinagiAuthError.FAILED -> R.string.auth_hikarinagi_failed
    HikarinagiAuthError.INVALID_RESPONSE -> R.string.auth_hikarinagi_invalid_response
    HikarinagiAuthError.STATE_MISMATCH -> R.string.auth_hikarinagi_state_mismatch
    HikarinagiAuthError.TOKEN_EXCHANGE_FAILED -> R.string.auth_hikarinagi_token_exchange_failed
    HikarinagiAuthError.TOKEN_MISSING_FIELDS -> R.string.auth_hikarinagi_token_missing_fields
    HikarinagiAuthError.ID_TOKEN_INVALID_FORMAT -> R.string.auth_hikarinagi_id_token_invalid_format
    HikarinagiAuthError.ID_TOKEN_NONCE_FAILED -> R.string.auth_hikarinagi_id_token_nonce_failed
    HikarinagiAuthError.ID_TOKEN_PARSE_FAILED -> R.string.auth_hikarinagi_id_token_parse_failed
    HikarinagiAuthError.EXPIRED -> R.string.auth_hikarinagi_expired
    HikarinagiAuthError.PERMISSION -> R.string.play_error_hikarinagi_token_permission
}

/**
 * HikarinagiAuthError → 文案（core 只携带错误码与可选 AppAuth 异常原始描述；
 * 见 BangumiAuthMessages 同款映射）。
 */
internal fun HikarinagiAuthError.authErrorMessage(context: Context, detail: String? = null): String {
    val customDetail = detail?.trim()?.takeIf { it.isNotEmpty() }
    if (customDetail != null) return customDetail
    return AppLocaleController.wrap(context).getString(authErrorResource())
}
