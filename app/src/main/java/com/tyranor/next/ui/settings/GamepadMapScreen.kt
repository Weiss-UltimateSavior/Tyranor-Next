package com.tyranor.next.ui.settings

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.LocalIndication
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color as ComposeColor
import com.tyranor.next.R
import com.tyranor.next.core.input.InputRemapRepository
import com.tyranor.next.theme.MiuixSettingsTheme
import com.tyranor.next.ui.common.AppAlertDialog
import com.tyranor.next.ui.common.AppScreenActivity
import com.tyranor.next.ui.common.AppTopBar
import com.tyranor.next.ui.common.BottomInsetSpacer
import com.tyranor.next.ui.common.DialogTextButton
import com.tyranor.next.ui.common.NoIndication
import com.tyranor.next.ui.common.NoRippleButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Scaffold as MiuixScaffold
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 手柄映射编辑页：逐逻辑按键与摇杆方向编辑输出键位。 */
class GamepadMapActivity : AppScreenActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setAppScreenContent { GamepadMapScreen() }
    }

    companion object {
        fun createIntent(context: Context): Intent = Intent(context, GamepadMapActivity::class.java)
    }
}

@Composable
internal fun GamepadMapScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var entries by remember { mutableStateOf<List<InputRemapRepository.GamepadEntry>>(emptyList()) }
    var sticks by remember { mutableStateOf<List<InputRemapRepository.StickEntry>>(emptyList()) }

    suspend fun reload() {
        withContext(Dispatchers.IO) {
            entries = InputRemapRepository.gamepadEntries(ctx)
            sticks = InputRemapRepository.stickEntries(ctx)
        }
    }
    androidx.compose.runtime.LaunchedEffect(Unit) { reload() }

    // 编辑目标：逻辑按键 id，或 "left.up" 形式的摇杆方向
    var editingButton by remember { mutableStateOf<String?>(null) }
    var editingStick by remember { mutableStateOf<Pair<String, String>?>(null) }

    MiuixSettingsTheme {
        MiuixScaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = ComposeColor.Transparent,
            contentWindowInsets = WindowInsets(0.dp),
            topBar = {
                AppTopBar(
                    title = stringResource(R.string.input_settings_gamepad_title),
                    background = ComposeColor.Transparent,
                    contentColor = MiuixTheme.colorScheme.onBackground,
                )
            },
        ) { innerPadding ->
            LazyColumn(
                modifier = Modifier.fillMaxSize()
                    .padding(horizontal = 12.dp)
                    .padding(top = innerPadding.calculateTopPadding()),
                contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    EngineCard(stringResource(R.string.input_settings_gamepad_buttons_label)) {
                        // 说明并入卡片内：AGENT.md 禁止在顶部栏下方放整页描述文案
                        Text(
                            text = stringResource(R.string.input_settings_gamepad_summary),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                        )
                        entries.forEach { entry ->
                            ArrowPreference(
                                title = gamepadKeyLabel(entry.id),
                                summary = bindingSummary(ctx, entry.keys, entry.autoKeep),
                                onClick = { editingButton = entry.id },
                            )
                        }
                    }
                }
                item {
                    EngineCard(stringResource(R.string.input_settings_gamepad_sticks_label)) {
                        listOf(
                            InputRemapRepository.STICK_LEFT to R.string.input_settings_stick_left,
                            InputRemapRepository.STICK_RIGHT to R.string.input_settings_stick_right,
                        ).forEach { (stick, titleRes) ->
                            StickRow(
                                title = stringResource(titleRes),
                                entries = sticks.filter { it.stick == stick },
                                summaryOf = { keys -> bindingSummary(ctx, keys, autoKeep = false) },
                                onEdit = { direction -> editingStick = stick to direction },
                            )
                        }
                    }
                }
                item { BottomInsetSpacer() }
            }
        }
    }

    editingButton?.let { id ->
        val entry = entries.firstOrNull { it.id == id }
        if (entry != null) {
            KeyBindingDialog(
                context = ctx,
                selectedKeys = entry.keys,
                autoKeep = entry.autoKeep,
                allowAutoKeep = true,
                onDismiss = { editingButton = null },
                onSave = { keys, autoKeep ->
                    editingButton = null
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) {
                            InputRemapRepository.setGamepadBinding(ctx, id, keys, autoKeep)
                        }
                        // 写盘失败必须提示：否则弹窗关闭、行值未变，用户以为没反应
                        if (!ok) toastBindingFailed(ctx)
                        reload()
                    }
                },
            )
        }
    }

    editingStick?.let { (stick, direction) ->
        val entry = sticks.firstOrNull { it.stick == stick && it.direction == direction }
        if (entry != null) {
            KeyBindingDialog(
                context = ctx,
                selectedKeys = entry.keys,
                autoKeep = false,
                // 摇杆方向是「持续按住即持续输出」，没有按下保持语义，隐藏该开关避免假开关
                allowAutoKeep = false,
                onDismiss = { editingStick = null },
                onSave = { keys, _ ->
                    editingStick = null
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) {
                            InputRemapRepository.setStickBinding(ctx, stick, direction, keys)
                        }
                        if (!ok) toastBindingFailed(ctx)
                        reload()
                    }
                },
            )
        }
    }
}

/** 手柄映射写盘失败提示（两处保存回调共用）。 */
private fun toastBindingFailed(context: android.content.Context) {
    android.widget.Toast.makeText(
        context,
        context.getString(R.string.input_save_failed),
        android.widget.Toast.LENGTH_SHORT,
    ).show()
}

@Composable
private fun StickRow(
    title: String,
    entries: List<InputRemapRepository.StickEntry>,
    summaryOf: (List<Int>) -> String,
    onEdit: (String) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
            color = MiuixTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        listOf(
            "up" to R.string.input_settings_stick_up,
            "down" to R.string.input_settings_stick_down,
            "left" to R.string.input_settings_stick_left_dir,
            "right" to R.string.input_settings_stick_right_dir,
        ).forEach { (direction, labelRes) ->
            val keys = entries.firstOrNull { it.direction == direction }?.keys.orEmpty()
            ArrowPreference(
                title = stringResource(labelRes),
                summary = summaryOf(keys),
                onClick = { onEdit(direction) },
            )
        }
    }
}

@Composable
private fun gamepadKeyLabel(id: String): String = stringResource(
    when (id) {
        InputRemapRepository.GAMEPAD_A -> R.string.input_settings_gamepad_key_a
        InputRemapRepository.GAMEPAD_B -> R.string.input_settings_gamepad_key_b
        InputRemapRepository.GAMEPAD_X -> R.string.input_settings_gamepad_key_x
        InputRemapRepository.GAMEPAD_Y -> R.string.input_settings_gamepad_key_y
        InputRemapRepository.GAMEPAD_START -> R.string.input_settings_gamepad_key_start
        InputRemapRepository.GAMEPAD_SELECT -> R.string.input_settings_gamepad_key_select
        InputRemapRepository.GAMEPAD_L1 -> R.string.input_settings_gamepad_key_l1
        InputRemapRepository.GAMEPAD_R1 -> R.string.input_settings_gamepad_key_r1
        InputRemapRepository.GAMEPAD_L2 -> R.string.input_settings_gamepad_key_l2
        InputRemapRepository.GAMEPAD_R2 -> R.string.input_settings_gamepad_key_r2
        InputRemapRepository.GAMEPAD_L3 -> R.string.input_settings_gamepad_key_l3
        InputRemapRepository.GAMEPAD_R3 -> R.string.input_settings_gamepad_key_r3
        InputRemapRepository.GAMEPAD_DPAD_UP -> R.string.input_settings_gamepad_key_dpad_up
        InputRemapRepository.GAMEPAD_DPAD_DOWN -> R.string.input_settings_gamepad_key_dpad_down
        InputRemapRepository.GAMEPAD_DPAD_LEFT -> R.string.input_settings_gamepad_key_dpad_left
        InputRemapRepository.GAMEPAD_DPAD_RIGHT -> R.string.input_settings_gamepad_key_dpad_right
        // 未知 id 不得错标为具体按键（会让用户以为绑定丢了或绑错）
        else -> R.string.input_settings_gamepad_key_unknown
    },
)

/** 绑定的可读摘要：键位名连接 + 「保持」标记；空绑定显示「未绑定」。 */
private fun bindingSummary(context: Context, keys: List<Int>, autoKeep: Boolean): String {
    val label = if (keys.isEmpty()) {
        context.getString(R.string.input_settings_binding_empty)
    } else {
        keys.joinToString(" + ") { InputRemapRepository.keyLabel(context, it) }
    }
    return if (autoKeep) {
        label + " · " + context.getString(R.string.input_settings_auto_keep)
    } else {
        label
    }
}

/** 键位多选对话框：分组键位网格 + 可选「按下保持」。 */
@Composable
private fun KeyBindingDialog(
    context: Context,
    selectedKeys: List<Int>,
    autoKeep: Boolean,
    allowAutoKeep: Boolean,
    onDismiss: () -> Unit,
    onSave: (List<Int>, Boolean) -> Unit,
) {
    var selected by remember { mutableStateOf(selectedKeys.toSet()) }
    var keep by remember { mutableStateOf(autoKeep) }
    val groups = remember { InputRemapRepository.keyGroups(context) }

    AppAlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(R.string.input_settings_choose_keys),
                style = MaterialTheme.typography.titleMedium,
            )
        },
        text = {
            // 弹窗点击反馈规范：正文内所有可点击组件（开关/键位网格）禁用按压反馈
            CompositionLocalProvider(LocalIndication provides NoIndication) {
                LazyColumn(Modifier.fillMaxWidth()) {
                    if (allowAutoKeep) {
                        item {
                            SwitchPreference(
                                title = stringResource(R.string.input_settings_auto_keep_toggle),
                                checked = keep,
                                onCheckedChange = { keep = it },
                            )
                        }
                    }
                    item {
                        Text(
                            text = stringResource(R.string.input_settings_binding_keys, selected.size),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                    }
                    groups.forEach { group ->
                        item {
                            Text(
                                text = group.title,
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                            )
                        }
                        group.keys.chunked(4).forEach { chunk ->
                            item {
                                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                                    chunk.forEach { option ->
                                        val on = option.code in selected
                                        // 选中态用主题色实底 + 白字，与游戏内编辑面板的键位高亮一致；
                                        // 未选中为静默文本，一眼可辨「选了哪些」而不是只看计数
                                        NoRippleButton(
                                            text = option.label,
                                            tonal = !on,
                                            modifier = Modifier.weight(1f),
                                            onClick = {
                                                selected = if (on) selected - option.code else selected + option.code
                                            },
                                        )
                                    }
                                    repeat(4 - chunk.size) {
                                        Box(Modifier.weight(1f))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            DialogTextButton(
                text = stringResource(R.string.common_confirm),
                onClick = { onSave(selected.toList(), keep) },
            )
        },
        dismissButton = {
            DialogTextButton(
                text = stringResource(R.string.input_settings_clear_binding),
                onClick = { onSave(emptyList(), false) },
            )
        },
    )
}
