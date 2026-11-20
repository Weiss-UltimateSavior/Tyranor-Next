package com.tyranor.next.ui.auth

import android.content.Context
import androidx.annotation.StringRes
import com.tyranor.next.R
import com.tyranor.next.core.auth.BangumiAuthError
import com.tyranor.next.core.i18n.AppLocaleController

@StringRes
internal fun BangumiAuthError.authErrorResource(): Int = when (this) {
    BangumiAuthError.CLIENT_ID_MISSING -> R.string.auth_bangumi_client_id_missing
    BangumiAuthError.NO_BROWSER -> R.string.auth_bangumi_no_browser
    BangumiAuthError.LAUNCH_FAILED -> R.string.auth_bangumi_launch_failed
    BangumiAuthError.CANCELLED -> R.string.auth_bangumi_cancelled
    BangumiAuthError.FAILED -> R.string.auth_bangumi_failed
    BangumiAuthError.INVALID_RESPONSE -> R.string.auth_bangumi_invalid_response
    BangumiAuthError.STATE_MISMATCH -> R.string.auth_bangumi_state_mismatch
    BangumiAuthError.TOKEN_EXCHANGE_FAILED -> R.string.auth_bangumi_token_exchange_failed
    BangumiAuthError.TOKEN_MISSING_FIELDS -> R.string.auth_bangumi_token_missing_fields
    BangumiAuthError.EXPIRED -> R.string.auth_bangumi_expired
    // 读路径 403 权限缺失（独立 permission_error 位）：与 Hikarinagi/NextMoe 同款借用
    // play_error_* 词表（BK-37，不为授权域另设同义键）
    BangumiAuthError.PERMISSION -> R.string.play_error_bangumi_token_permission
    BangumiAuthError.TOKEN_INVALID -> R.string.auth_bangumi_token_invalid
    BangumiAuthError.NETWORK -> R.string.auth_bangumi_network
}

/** BangumiAuthError → 文案（core 只携带错误码；见 ui/cover/CoverErrorMessages 同款映射）。 */
internal fun BangumiAuthError.authErrorMessage(context: Context): String =
    AppLocaleController.wrap(context).getString(authErrorResource())
