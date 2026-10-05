package com.tyranor.next.ui.game

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.tyranor.next.R
import com.tyranor.next.theme.DialogItemSurface
import com.tyranor.next.ui.common.AppAlertDialog
import com.tyranor.next.ui.common.AppNavItem
import com.tyranor.next.ui.common.DialogTextButton

/**
 * 「添加游戏」分支弹窗（游戏页顶栏入口）：选择添加 PC 游戏（SAF 目录 + exe）或
 * 安卓游戏（已安装应用列表）。选择后关闭本弹窗，由调用方挂载对应二级弹窗。
 */
@Composable
internal fun AddGameDialog(
    onDismiss: () -> Unit,
    onAddPc: () -> Unit,
    onAddAndroid: () -> Unit,
) {
    AppAlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(R.string.game_add_dialog_title),
                style = MaterialTheme.typography.titleMedium,
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                AppNavItem(
                    title = stringResource(R.string.game_add_pc),
                    summary = stringResource(R.string.game_add_pc_summary),
                    leadingIcon = R.drawable.ic_sheet_pc,
                    containerColor = DialogItemSurface,
                    indication = null,
                    onClick = onAddPc,
                )
                AppNavItem(
                    title = stringResource(R.string.game_add_android),
                    summary = stringResource(R.string.game_add_android_summary),
                    leadingIcon = R.drawable.ic_sheet_phone,
                    containerColor = DialogItemSurface,
                    indication = null,
                    onClick = onAddAndroid,
                )
            }
        },
        confirmButton = {},
        dismissButton = {
            DialogTextButton(
                text = stringResource(R.string.common_cancel),
                onClick = onDismiss,
            )
        },
    )
}
