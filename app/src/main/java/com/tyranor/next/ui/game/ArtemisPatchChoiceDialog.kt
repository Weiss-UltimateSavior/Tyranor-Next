package com.tyranor.next.ui.game

import android.app.AlertDialog
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.tyranor.next.R
import com.tyranor.next.core.game.launch.EngineLauncher
import com.tyranor.next.core.game.model.ScanGame
import com.tyranor.next.ui.common.AppAlertDialog
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Artemis 补丁确认三选弹窗的统一入口：GameActionsSheet（Compose）与
 * 桌面快捷方式蹦床 GameShortcutActivity（原生 Activity）共用同一份
 * 「按钮 → 策略」映射，避免两处语义漂移。
 *
 * 语义：总是 → [EngineLauncher.ArtemisPatchChoice.ALWAYS]（持久化 auto）、
 * 不再 → [EngineLauncher.ArtemisPatchChoice.NEVER]（持久化 off）、
 * 本次 → [EngineLauncher.ArtemisPatchChoice.ONCE]（不落盘）、取消 → null（不启动）。
 */
private enum class PatchChoiceOption(val labelRes: Int) {
    ALWAYS(R.string.game_patch_always),
    NEVER(R.string.game_patch_never),
    ONCE(R.string.game_patch_once),
}

private fun PatchChoiceOption.toArtemisChoice(): EngineLauncher.ArtemisPatchChoice = when (this) {
    PatchChoiceOption.ALWAYS -> EngineLauncher.ArtemisPatchChoice.ALWAYS
    PatchChoiceOption.NEVER -> EngineLauncher.ArtemisPatchChoice.NEVER
    PatchChoiceOption.ONCE -> EngineLauncher.ArtemisPatchChoice.ONCE
}

/** Compose 版本：供 GameActionsSheet 在抽屉内弹出（遵循 AppAlertDialog 弹窗规范）。 */
@Composable
internal fun ArtemisPatchChoiceDialog(
    game: ScanGame,
    onChoice: (EngineLauncher.ArtemisPatchChoice?) -> Unit,
) {
    AppAlertDialog(
        onDismissRequest = { onChoice(null) },
        title = {
            Text(stringResource(R.string.game_auto_patch_title), style = MaterialTheme.typography.titleMedium)
        },
        text = {
            Text(
                stringResource(R.string.game_auto_patch_message, game.title),
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        confirmButton = {
            TextButton(onClick = { onChoice(PatchChoiceOption.ALWAYS.toArtemisChoice()) }) {
                Text(stringResource(PatchChoiceOption.ALWAYS.labelRes))
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { onChoice(PatchChoiceOption.NEVER.toArtemisChoice()) }) {
                    Text(stringResource(PatchChoiceOption.NEVER.labelRes))
                }
                TextButton(onClick = { onChoice(PatchChoiceOption.ONCE.toArtemisChoice()) }) {
                    Text(stringResource(PatchChoiceOption.ONCE.labelRes))
                }
            }
        },
    )
}

/** 原生版本：供非 Compose 的透明蹦床（快捷方式启动）使用，行为与 Compose 版本一致。 */
suspend fun Context.confirmArtemisPatchChoice(game: ScanGame): EngineLauncher.ArtemisPatchChoice? =
    suspendCancellableCoroutine { continuation ->
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.game_auto_patch_title)
            .setMessage(getString(R.string.game_auto_patch_message, game.title))
            .setPositiveButton(PatchChoiceOption.ALWAYS.labelRes) { _, _ ->
                if (continuation.isActive) continuation.resume(PatchChoiceOption.ALWAYS.toArtemisChoice())
            }
            .setNegativeButton(PatchChoiceOption.NEVER.labelRes) { _, _ ->
                if (continuation.isActive) continuation.resume(PatchChoiceOption.NEVER.toArtemisChoice())
            }
            .setNeutralButton(PatchChoiceOption.ONCE.labelRes) { _, _ ->
                if (continuation.isActive) continuation.resume(PatchChoiceOption.ONCE.toArtemisChoice())
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