package com.tyranor.next.ui.auth

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import com.tyranor.next.R
import com.tyranor.next.core.auth.BangumiAuthError
import com.tyranor.next.core.auth.BangumiAuthService
import com.tyranor.next.core.i18n.AppLocaleController
import com.tyranor.next.core.privacy.AppLockController
import com.tyranor.next.ui.common.notice.AppNotice

class BangumiOAuthCallbackActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) {
            finish()
            return
        }
        // 与 Hikarinagi 回调一致：应用锁生效时丢弃浏览器回跳，不把令牌写进即将被锁门
        // 覆盖的应用。回调可能早于 ON_START，必须用「进入时将会上锁」的只读判定。
        if (AppLockController.willRequireUnlockOnEntering()) {
            finish()
            return
        }
        val appContext = applicationContext
        // core 只携带类型化错误码；文案经 BangumiAuthMessages 映射（错误协议 core → ui）
        BangumiAuthService.handleAuthorizationResponse(this, intent) { success, error ->
            val message = if (success) {
                AppLocaleController.wrap(appContext).getString(R.string.auth_bangumi_success)
            } else {
                (error ?: BangumiAuthError.FAILED).authErrorMessage(appContext)
            }
            val kind = when {
                success -> AppNotice.Kind.Success
                error == BangumiAuthError.CANCELLED -> AppNotice.Kind.Info
                else -> AppNotice.Kind.Failure
            }
            AppNotice.makeText(appContext, message, Toast.LENGTH_SHORT, kind).show()
            if (!isFinishing && !isDestroyed) {
                finish()
            }
        }
    }
}
