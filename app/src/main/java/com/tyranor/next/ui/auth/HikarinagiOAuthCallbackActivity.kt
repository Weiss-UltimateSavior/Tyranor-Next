package com.tyranor.next.ui.auth

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import com.tyranor.next.core.auth.HikarinagiAuthService
import com.tyranor.next.ui.common.notice.AppNotice

class HikarinagiOAuthCallbackActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) {
            finish()
            return
        }
        HikarinagiAuthService.handleAuthorizationResponse(this, intent) { _, message ->
            if (!isFinishing && !isDestroyed) {
                AppNotice.makeText(this, message, Toast.LENGTH_SHORT, AppNotice.Kind.Info).show()
            }
            finish()
        }
    }
}
