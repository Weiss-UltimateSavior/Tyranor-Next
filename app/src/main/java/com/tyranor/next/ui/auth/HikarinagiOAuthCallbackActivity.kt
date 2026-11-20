package com.tyranor.next.ui.auth

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import com.tyranor.next.R
import com.tyranor.next.core.auth.HikarinagiAuthError
import com.tyranor.next.core.auth.HikarinagiAuthService
import com.tyranor.next.core.i18n.AppLocaleController
import com.tyranor.next.core.privacy.AppLockController
import com.tyranor.next.ui.common.notice.AppNotice

class HikarinagiOAuthCallbackActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) {
            finish()
            return
        }
        // 应用锁生效时浏览器回跳不能把令牌写进已锁定的应用、也不能在锁门之上弹提示。
        // 回调到达可能早于 ProcessLifecycleOwner 的 ON_START：用「进入时将会上锁」的只读
        // 判定，而不是当前 lockedState（此时还可能为 false）。
        // 丢弃本次回调是安全方向：授权请求可重新发起，state/nonce 保证回调无法凭空伪造。
        if (AppLockController.willRequireUnlockOnEntering()) {
            finish()
            return
        }
        val appContext = applicationContext
        // core 只携带类型化错误码；文案经 HikarinagiAuthMessages 映射（错误协议 core → ui）
        HikarinagiAuthService.handleAuthorizationResponse(this, intent) { success, error ->
            val message = if (success) {
                AppLocaleController.wrap(appContext).getString(R.string.auth_hikarinagi_success)
            } else {
                (error ?: HikarinagiAuthError.FAILED).authErrorMessage(appContext)
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
