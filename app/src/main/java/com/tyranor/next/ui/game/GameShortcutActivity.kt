package com.tyranor.next.ui.game

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.tyranor.next.R
import com.tyranor.next.core.game.launch.EngineLauncher
import com.tyranor.next.core.game.scan.EngineScanner
import com.tyranor.next.core.game.shortcut.GameShortcutManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 桌面快捷方式回跳蹦床。
 *
 * 该 Activity 是 `ShortcutInfo.setIntent` 的目标，由桌面 Launcher 以显式 Intent 跨进程启动，
 * 因此 `exported=true` 是**必需**的（改为 false 会触发 Permission Denial），请勿回退。
 * 入参 `EXTRA_SHORTCUT_ID` 为库内游戏 uri 的 SHA-256，可通过 [GameShortcutActivity.createIntent]
 * 重新解析库内游戏并启动，必要时先做 Artemis 补丁确认。
 */
class GameShortcutActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 配置变更/进程重建后继续（弹窗会重新出现），不要静默 finish，否则用户点快捷方式无反馈
        val shortcutId = intent.getStringExtra(EXTRA_SHORTCUT_ID)
        if (shortcutId.isNullOrBlank()) {
            showUnavailableAndFinish()
            return
        }

        lifecycleScope.launch {
            try {
                val game = withContext(Dispatchers.IO) {
                    EngineScanner.loadGames(applicationContext)
                        .firstOrNull { GameShortcutManager.shortcutId(it.uri) == shortcutId }
                }
                if (game == null) {
                    showUnavailable()
                    return@launch
                }

                val needsArtemisPatchConfirm = withContext(Dispatchers.IO) {
                    EngineLauncher.needsArtemisPatchConfirm(applicationContext, game)
                }
                val patchChoice = if (needsArtemisPatchConfirm) {
                    confirmArtemisPatchChoice(game) ?: return@launch
                } else {
                    null
                }

                val error = EngineLauncher.launch(this@GameShortcutActivity, game, patchChoice)
                if (error != null) {
                    Toast.makeText(this@GameShortcutActivity, error, Toast.LENGTH_LONG).show()
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                showLaunchFailure(error)
            } finally {
                finish()
            }
        }
    }

    /** Shows the localized missing-game message without changing launcher state. */
    private fun showUnavailable() {
        Toast.makeText(this, R.string.game_desktop_shortcut_unavailable, Toast.LENGTH_LONG).show()
    }

    /** Shows the missing-game message and closes the transparent trampoline. */
    private fun showUnavailableAndFinish() {
        showUnavailable()
        finish()
    }

    /** Converts an unexpected launch exception into a user-visible localized toast. */
    private fun showLaunchFailure(error: Throwable) {
        Log.e(TAG, "Shortcut game launch failed", error)
        Toast.makeText(this, R.string.launch_failed, Toast.LENGTH_LONG).show()
    }

    companion object {
        private const val TAG = "GameShortcutActivity"
        private const val EXTRA_SHORTCUT_ID = "extra_shortcut_id"

        /** Builds the explicit trampoline intent carried by a pinned shortcut. */
        fun createIntent(context: Context, gameUri: String): Intent =
            Intent(context, GameShortcutActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                putExtra(EXTRA_SHORTCUT_ID, GameShortcutManager.shortcutId(gameUri))
            }
    }
}
