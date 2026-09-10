package com.tyranor.next.ui.settings

import android.content.Context
import android.content.Intent
import android.os.Bundle
import com.tyranor.next.ui.common.AppScreenActivity

class EngineSettingsActivity : AppScreenActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val kind = intent.readKind()
        if (kind == null) {
            finish()
            return
        }

        setAppScreenContent {
            EngineSettingsDetailScreen(kind = kind)
        }
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
