package com.tyranor.next.ui.game

import android.app.AlertDialog
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
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/** Transparent trampoline that resolves a pinned shortcut against the current game library. */
class GameShortcutActivity : ComponentActivity() {
    /** Resolves the shortcut ID, confirms Artemis patch policy, and launches the game. */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) {
            finish()
            return
        }

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
                    awaitArtemisPatchChoice(game) ?: return@launch
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

    /** Suspends until the user chooses an Artemis patch policy or dismisses the dialog. */
    private suspend fun awaitArtemisPatchChoice(
        game: com.tyranor.next.core.game.model.ScanGame,
    ): EngineLauncher.ArtemisPatchChoice? = suspendCancellableCoroutine { continuation ->
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.game_auto_patch_title)
            .setMessage(getString(R.string.game_auto_patch_message, game.title))
            .setPositiveButton(R.string.game_patch_always) { _, _ ->
                if (continuation.isActive) continuation.resume(EngineLauncher.ArtemisPatchChoice.ALWAYS)
            }
            .setNegativeButton(R.string.game_patch_never) { _, _ ->
                if (continuation.isActive) continuation.resume(EngineLauncher.ArtemisPatchChoice.NEVER)
            }
            .setNeutralButton(R.string.game_patch_once) { _, _ ->
                if (continuation.isActive) continuation.resume(EngineLauncher.ArtemisPatchChoice.ONCE)
            }
            .setOnCancelListener {
                if (continuation.isActive) continuation.resume(null)
            }
            .create()

        continuation.invokeOnCancellation { dialog.dismiss() }
        if (continuation.isActive) {
            dialog.show()
            if (!continuation.isActive && dialog.isShowing) dialog.dismiss()
        }
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
