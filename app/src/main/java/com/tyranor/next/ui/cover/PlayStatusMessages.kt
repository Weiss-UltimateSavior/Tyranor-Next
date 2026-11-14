package com.tyranor.next.ui.cover

import android.content.Context
import com.tyranor.next.R
import com.tyranor.next.core.i18n.AppLocaleController
import com.tyranor.next.core.status.PLAY_STATUS_LOG_TAG
import com.tyranor.next.core.status.PlayStatusError
import com.tyranor.next.core.status.toPlayStatusError

/** PlayStatusError → 文案（core 只携带错误码；见 CoverErrorMessages 同款映射）。 */
internal fun PlayStatusError.playStatusErrorMessage(context: Context): String = when (this) {
    PlayStatusError.HIKARINAGI_AUTH_MISSING -> R.string.play_error_hikarinagi_auth_missing
    PlayStatusError.HIKARINAGI_TOKEN_EXPIRED -> R.string.auth_hikarinagi_expired
    PlayStatusError.HIKARINAGI_TOKEN_PERMISSION -> R.string.play_error_hikarinagi_token_permission
    PlayStatusError.HIKARINAGI_SUBJECT_NOT_FOUND -> R.string.play_error_hikarinagi_not_found
    PlayStatusError.HIKARINAGI_NETWORK -> R.string.play_error_hikarinagi_network
    PlayStatusError.BANGUMI_AUTH_MISSING -> R.string.play_error_bangumi_auth_missing
    PlayStatusError.BANGUMI_TOKEN_EXPIRED -> R.string.auth_bangumi_expired
    PlayStatusError.BANGUMI_TOKEN_PERMISSION -> R.string.play_error_bangumi_token_permission
    PlayStatusError.BANGUMI_NETWORK -> R.string.play_error_bangumi_network
    PlayStatusError.VNDB_AUTH_MISSING -> R.string.play_error_vndb_auth_missing
    PlayStatusError.VNDB_TOKEN_INVALID -> R.string.play_error_vndb_token_invalid
    PlayStatusError.VNDB_TOKEN_PERMISSION -> R.string.play_error_vndb_token_permission
    PlayStatusError.VNDB_NETWORK -> R.string.play_error_vndb_network
    PlayStatusError.NEXTMOE_AUTH_MISSING -> R.string.play_error_nextmoe_auth_missing
    PlayStatusError.NEXTMOE_TOKEN_EXPIRED -> R.string.auth_nextmoe_expired
    PlayStatusError.NEXTMOE_TOKEN_PERMISSION -> R.string.play_error_nextmoe_token_permission
    PlayStatusError.NEXTMOE_SUBJECT_NOT_FOUND -> R.string.play_error_nextmoe_not_found
    PlayStatusError.NEXTMOE_RATE_LIMITED -> R.string.play_error_nextmoe_rate_limited
    PlayStatusError.NEXTMOE_NETWORK -> R.string.play_error_nextmoe_network
    PlayStatusError.AUTH_SESSION_CHANGED -> R.string.play_error_auth_session_changed
    PlayStatusError.UNKNOWN -> R.string.play_error_unknown
}.let { AppLocaleController.wrap(context).getString(it) }

/**
 * 未预期异常收敛：委托 core 的 [toPlayStatusError] 保留 Log.w，
 * 避免非预期异常（解析/SQLite）静默变「未知错误」导致排障困难。
 */
internal fun Throwable.playStatusError(): PlayStatusError = toPlayStatusError(PLAY_STATUS_LOG_TAG)
