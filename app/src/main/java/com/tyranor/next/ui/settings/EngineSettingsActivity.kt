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
import com.tyranor.next.core.settings.AppSettingsStore
import com.tyranor.next.ui.common.ProvideAppLocale
import com.tyranor.next.theme.TyranorNextTheme
import com.tyranor.next.ui.common.WithoutPressIndication

class EngineSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val darkMode = AppSettingsStore.isDarkEffective(this)
        enableEdgeToEdge(
            statusBarStyle = if (darkMode) androidx.activity.SystemBarStyle.dark(Color.TRANSPARENT) else androidx.activity.SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = if (darkMode) androidx.activity.SystemBarStyle.dark(Color.TRANSPARENT) else androidx.activity.SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        @Suppress("DEPRECATION")
        window.statusBarColor = Color.TRANSPARENT
        @Suppress("DEPRECATION")
        window.navigationBarColor = Color.TRANSPARENT

        val kind = intent.readKind()
        if (kind == null) {
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
                            EngineSettingsDetailScreen(kind = kind)
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
        private const val EXTRA_KIND = "extra_kind"

        fun createIntent(context: Context, kind: EngineSettingsKind): Intent {
            return Intent(context, EngineSettingsActivity::class.java).apply {
                putExtra(EXTRA_KIND, kind.name)
            }
        }

        private fun Intent.readKind(): EngineSettingsKind? {
            val name = getStringExtra(EXTRA_KIND) ?: return null
            return runCatching { EngineSettingsKind.valueOf(name) }.getOrNull()
        }
    }
}
