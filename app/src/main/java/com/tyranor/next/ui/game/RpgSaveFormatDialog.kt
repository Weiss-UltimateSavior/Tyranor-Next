package com.tyranor.next.ui.game

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.tyranor.next.R
import com.tyranor.next.core.game.save.RpgSaveFormat
import com.tyranor.next.ui.common.AppAlertDialog
import com.tyranor.next.ui.common.AppNavItem
import com.tyranor.next.ui.common.ProvideAppLocale
import com.tyranor.next.theme.PageGrey
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * RPG Maker MV/MZ 启动前存档格式确认弹窗：检测到 JoiPlay/PC 标准格式存档时询问是否转化。
 *
 * 选项统一使用 [AppNavItem]（弹窗内传 [PageGrey] 反色 + 图标），不使用文字按钮。
 * 「转换为 Tyranor 格式」→ 转化后启动；「保持原样启动」→ 不转化直接启动（下次再问）；
 * 点遮罩/返回（onDismissRequest）等价于「保持原样启动」。
 *
 * Compose 版供 GameScreen / HomeScreen 使用；[awaitRpgSaveFormatChoice] 为透明蹦床
 * GameShortcutActivity 提供挂起版，语义一致。
 */
@Composable
internal fun RpgSaveFormatDialog(
    standardCount: Int,
    hashedCount: Int,
    onChoice: (convert: Boolean) -> Unit,
) {
    AppAlertDialog(
        onDismissRequest = { onChoice(false) },
        title = {
            Text(stringResource(R.string.save_format_convert_title), style = MaterialTheme.typography.titleMedium)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    stringResource(R.string.save_format_convert_message, standardCount),
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (hashedCount > 0) {
                    Text(
                        stringResource(R.string.save_format_convert_hashed_note, hashedCount),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                AppNavItem(
                    title = stringResource(R.string.save_format_convert_confirm),
                    leadingIcon = R.drawable.ic_sheet_saves,
                    containerColor = PageGrey,
                    showArrow = false,
                    leadingIconTint = MaterialTheme.colorScheme.primary,
                    onClick = { onChoice(true) },
                )
                AppNavItem(
                    title = stringResource(R.string.save_format_convert_keep),
                    leadingIcon = R.drawable.ic_sheet_launch,
                    containerColor = PageGrey,
                    showArrow = false,
                    onClick = { onChoice(false) },
                )
            }
        },
        // 选项已用 AppNavItem 承载，confirmButton 槽位必填故传空
        confirmButton = {},
    )
}

/**
 * 挂起版确认入口：供非 Compose 的透明蹦床（GameShortcutActivity）使用，行为与 Compose 版一致，
 * 遵循 AppAlertDialog 弹窗规范（白底 / 8dp 圆角 / 统一排版）。
 */
suspend fun ComponentActivity.awaitRpgSaveFormatChoice(
    standardCount: Int,
    hashedCount: Int,
): Boolean = suspendCancellableCoroutine { continuation ->
    setContent {
        ProvideAppLocale {
            var showDialog by remember { mutableStateOf(true) }
            if (showDialog) {
                RpgSaveFormatDialog(
                    standardCount = standardCount,
                    hashedCount = hashedCount,
                    onChoice = { convert ->
                        showDialog = false
                        if (continuation.isActive) continuation.resume(convert)
                    },
                )
            }
        }
    }
}

/**
 * 便捷入口：把待转化检测结果转成弹窗参数；`standardCount` 为可转化标准存档数。
 * 与 [RpgSaveFormat.Detection] 解耦，避免 UI 直接依赖 core 的检测类型。
 */
internal fun RpgSaveFormat.Detection.dialogArgs(): Pair<Int, Int> =
    convertibleCount to hashedCount
