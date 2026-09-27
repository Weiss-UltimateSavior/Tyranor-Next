package com.tyranor.next.ui.common

import android.content.Context
import android.widget.Toast
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.tyranor.next.R
import com.tyranor.next.core.engine.external.EmulatorTarget
import com.tyranor.next.core.engine.external.ExternalEmulatorLauncher
import com.tyranor.next.core.engine.external.ExternalEngineLauncher
import com.tyranor.next.core.game.launch.LaunchResult

/**
 * 启动失败展示态：文案 + 可选的外置目标安装引导。
 *
 * 外置模拟器/运行时未安装（或版本不支持外置启动）时携带 [installTarget]，
 * UI 用 [LaunchErrorDialog] 渲染出「去下载」动作；其余失败只有确认按钮。
 */
data class LaunchErrorState(
    val message: String,
    val installTarget: EmulatorTarget? = null,
)

/** 启动结果 → 展示态；成功返回 null。 */
fun LaunchResult.toErrorState(context: Context): LaunchErrorState? {
    val message = userMessage(context) ?: return null
    val installTarget = (this as? LaunchResult.Failure.ExternalEmulatorFailed)
        ?.takeIf {
            when (it.result.code) {
                ExternalEmulatorLauncher.CODE_PACKAGE_NOT_INSTALLED,
                ExternalEmulatorLauncher.CODE_EXTERNAL_LAUNCH_UNSUPPORTED,
                ExternalEmulatorLauncher.CODE_ACTIVITY_NOT_FOUND,
                -> true

                else -> false
            }
        }
        ?.result?.target
    return LaunchErrorState(message, installTarget)
}

/** 统一启动失败弹窗：可在外置运行时缺失时一键前往下载/更新。 */
@Composable
fun LaunchErrorDialog(state: LaunchErrorState, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val openDownloadFailedMessage = stringResource(R.string.engine_open_download_failed)
    AppAlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(R.string.game_launch_failed),
                style = MaterialTheme.typography.titleMedium,
            )
        },
        text = { Text(state.message, style = MaterialTheme.typography.bodyMedium) },
        confirmButton = {
            val target = state.installTarget
            if (target != null) {
                TextButton(onClick = {
                    onDismiss()
                    if (!ExternalEngineLauncher.openInstallPage(context, target.installUrl)) {
                        Toast.makeText(context, openDownloadFailedMessage, Toast.LENGTH_SHORT).show()
                    }
                }) { Text(stringResource(R.string.engine_module_download)) }
            } else {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_confirm)) }
            }
        },
        dismissButton = if (state.installTarget != null) {
            {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
            }
        } else {
            null
        },
    )
}
