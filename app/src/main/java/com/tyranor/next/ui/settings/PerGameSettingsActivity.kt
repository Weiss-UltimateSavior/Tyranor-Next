package com.tyranor.next.ui.settings

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.tyranor.next.R
import com.tyranor.next.core.game.model.ScanGame
import com.tyranor.next.core.game.model.ScanGameIntents
import com.tyranor.next.core.settings.AppSettingsStore
import com.tyranor.next.core.i18n.ProvideAppLocale
import com.tyranor.next.theme.TyranorNextTheme
import com.tyranor.next.ui.common.WithoutPressIndication

class PerGameSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val darkMode = AppSettingsStore.isDarkEffective(this)
        enableEdgeToEdge(
            statusBarStyle = if (darkMode) androidx.activity.SystemBarStyle.dark(Color.TRANSPARENT) else androidx.activity.SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = if (darkMode) androidx.activity.SystemBarStyle.dark(Color.TRANSPARENT) else androidx.activity.SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        // 状态栏/导航栏透明沉浸由 enableEdgeToEdge(transparent) 处理，无需再设置已弃用的 window.statusBarColor

        val game = intent.readScanGame()
        if (game == null) {
            finish()
            return
        }

        setContent {
            ProvideAppLocale {
                TyranorNextTheme {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background,
                    ) {
                        WithoutPressIndication {
                            PerGameSettingsScreen(game = game)
                        }
                    }
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    override fun finish() {
        super.finish()
        overridePendingTransition(R.anim.page_slide_in_from_top, R.anim.page_slide_out_to_bottom)
    }

    companion object {
        fun createIntent(context: Context, game: ScanGame): Intent =
            ScanGameIntents.putGame(Intent(context, PerGameSettingsActivity::class.java), game)

        private fun Intent.readScanGame(): ScanGame? = ScanGameIntents.getGame(this)
    }
}
