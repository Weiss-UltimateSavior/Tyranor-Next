package com.tyranor.next.ui.auth

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import com.tyranor.next.R
import com.tyranor.next.core.auth.HikarinagiAuthError
import com.tyranor.next.core.auth.HikarinagiAuthService
import com.tyranor.next.core.i18n.AppLocaleController
import com.tyranor.next.ui.common.notice.AppNotice

class HikarinagiOAuthCallbackActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) {
            finish()
            return
        }
        val appContext = applicationContext
        // core 只携带类型化错误码与可选 detail；文案经 HikarinagiAuthMessages 映射（错误协议 core → ui）
        HikarinagiAuthService.handleAuthorizationResponse(this, intent) { success, error, detail ->
            val message = if (success) {
                AppLocaleController.wrap(appContext).getString(R.string.auth_hikarinagi_success)
            } else {
                (error ?: HikarinagiAuthError.FAILED).authErrorMessage(appContext, detail)
            }
            val kind = when {
                success -> AppNotice.Kind.Success
                error == HikarinagiAuthError.CANCELLED -> AppNotice.Kind.Info
                else -> AppNotice.Kind.Failure
            }
            AppNotice.makeText(appContext, message, Toast.LENGTH_SHORT, kind).show()
            if (!isFinishing && !isDestroyed) {
                finish()
            }
        }
    }
}
