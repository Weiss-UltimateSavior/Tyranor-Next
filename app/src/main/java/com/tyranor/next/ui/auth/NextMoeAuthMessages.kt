package com.tyranor.next.ui.auth

import android.content.Context
import androidx.annotation.StringRes
import com.tyranor.next.R
import com.tyranor.next.core.auth.NextMoeAuthError
import com.tyranor.next.core.i18n.AppLocaleController

@StringRes
internal fun NextMoeAuthError.authErrorResource(): Int = when (this) {
    NextMoeAuthError.CLIENT_ID_MISSING -> R.string.auth_nextmoe_client_id_missing
    NextMoeAuthError.NO_BROWSER -> R.string.auth_nextmoe_no_browser
    NextMoeAuthError.LAUNCH_FAILED -> R.string.auth_nextmoe_launch_failed
    NextMoeAuthError.CANCELLED -> R.string.auth_nextmoe_cancelled
    NextMoeAuthError.FAILED -> R.string.auth_nextmoe_failed
    NextMoeAuthError.INVALID_RESPONSE -> R.string.auth_nextmoe_invalid_response
    NextMoeAuthError.STATE_MISMATCH -> R.string.auth_nextmoe_state_mismatch
    NextMoeAuthError.TOKEN_EXCHANGE_FAILED -> R.string.auth_nextmoe_token_exchange_failed
    NextMoeAuthError.TOKEN_MISSING_FIELDS -> R.string.auth_nextmoe_token_missing_fields
    NextMoeAuthError.ID_TOKEN_INVALID_FORMAT -> R.string.auth_nextmoe_id_token_invalid_format
    NextMoeAuthError.ID_TOKEN_NONCE_FAILED -> R.string.auth_nextmoe_id_token_nonce_failed
    NextMoeAuthError.ID_TOKEN_PARSE_FAILED -> R.string.auth_nextmoe_id_token_parse_failed
    NextMoeAuthError.EXPIRED -> R.string.auth_nextmoe_expired
    NextMoeAuthError.PERMISSION -> R.string.play_error_nextmoe_token_permission
}

internal fun NextMoeAuthError.authErrorMessage(context: Context, detail: String? = null): String {
    val customDetail = detail?.trim()?.takeIf { it.isNotEmpty() }
    if (customDetail != null) return customDetail
    return AppLocaleController.wrap(context).getString(authErrorResource())
}
