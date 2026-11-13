package com.tyranor.next.ui.common

import android.content.Context
import android.widget.Toast
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.tyranor.next.ui.common.motion.BlurCrossfade
import com.tyranor.next.ui.common.motion.shakeOnTrigger
import kotlinx.coroutines.delay
import androidx.compose.ui.res.stringResource
import com.tyranor.next.R
import com.tyranor.next.core.engine.external.EmulatorTarget
import com.tyranor.next.core.engine.external.ExternalEmulatorLauncher
import com.tyranor.next.core.engine.external.ExternalEngineLauncher
import com.tyranor.next.core.game.launch.LaunchResult
import com.tyranor.next.ui.common.notice.AppNotice

/**
 * 启动失败展示态：文案 + 可选的外置目标安装引导。
 *
 * 外置模拟器/运行时未安装（或版本不支持外置启动）时携带 [installTarget]；
 * 外置 APK 引擎模块缺失/入口不兼容时携带 [installUrl]。UI 用 [LaunchErrorDialog]
 * 渲染出「去下载」动作；其余失败只有确认按钮。
 */
data class LaunchErrorState(
    val message: String,
    val installTarget: EmulatorTarget? = null,
    val installUrl: String? = null,
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
    val moduleInstallUrl = (this as? LaunchResult.Failure.ExternalModuleFailed)
        ?.result?.installUrl
    return LaunchErrorState(message, installTarget, moduleInstallUrl)
}

/** 标题摇一下前的等待：让弹窗与标题入场基本落稳后再摇（这是编排时序，不是动画时长）。 */
private const val LAUNCH_ERROR_SHAKE_DELAY_MILLIS = 260L

/** 统一启动失败弹窗：可在外置运行时缺失时一键前往下载/更新。 */
@Composable
fun LaunchErrorDialog(state: LaunchErrorState, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val openDownloadFailedMessage = stringResource(R.string.engine_open_download_failed)
    val installUrl = state.installUrl ?: state.installTarget?.installUrl
    // 「启动失败」标题在弹窗落稳时轻轻摇一下（操作被拒绝的即时反馈）；弹窗未关又换了一条失败信息时再摇一次。
    // shakeOnTrigger 第一次组合不抖、「普通」档不加节点
    var rejectShake by remember { mutableIntStateOf(0) }
    LaunchedEffect(state.message) {
        delay(LAUNCH_ERROR_SHAKE_DELAY_MILLIS)
        rejectShake++
    }
    AppAlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(R.string.game_launch_failed),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.shakeOnTrigger(rejectShake, amplitude = 6.dp),
            )
        },
        // 失败信息变化（弹窗未关时再次失败）：模糊替换，不瞬间跳字
        text = {
            BlurCrossfade(targetState = state.message, label = "launchErrorMessage") { message ->
                Text(message, style = MaterialTheme.typography.bodyMedium)
            }
        },
        confirmButton = {
            if (installUrl != null) {
                DialogTextButton(
                    text = stringResource(R.string.engine_module_download),
                    onClick = {
                        onDismiss()
                        if (!ExternalEngineLauncher.openInstallPage(context, installUrl)) {
                            AppNotice.makeText(context, openDownloadFailedMessage, Toast.LENGTH_SHORT, AppNotice.Kind.Failure).show()
                        }
                    },
                )
            } else {
                DialogTextButton(text = stringResource(R.string.common_confirm), onClick = onDismiss)
            }
        },
        dismissButton = if (installUrl != null) {
            {
                DialogTextButton(text = stringResource(R.string.common_cancel), onClick = onDismiss)
            }
        } else {
            null
        },
    )
}
