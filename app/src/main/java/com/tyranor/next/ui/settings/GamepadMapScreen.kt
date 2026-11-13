package com.tyranor.next.ui.settings

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color as ComposeColor
import com.tyranor.next.R
import com.tyranor.next.core.input.InputRemapRepository
import com.tyranor.next.theme.PageTextColor
import com.tyranor.next.theme.MiuixSettingsTheme
import com.tyranor.next.ui.common.AppAlertDialog
import com.tyranor.next.ui.common.AppScreenActivity
import com.tyranor.next.ui.common.AppSecondaryTopBar
import com.tyranor.next.ui.common.BottomInsetSpacer
import com.tyranor.next.ui.common.DialogTextButton
import com.tyranor.next.ui.common.NoIndication
import com.tyranor.next.ui.common.NoRippleButton
import com.tyranor.next.ui.common.motion.BlurCrossfade
import com.tyranor.next.ui.common.motion.ExpandVisibility
import com.tyranor.next.ui.common.motion.MotionMode
import com.tyranor.next.ui.common.motion.PressSquishDefaults
import com.tyranor.next.ui.common.motion.ProvideEntrance
import com.tyranor.next.ui.common.motion.bounceOnChange
import com.tyranor.next.ui.common.motion.skipEntranceOnDrag
import com.tyranor.next.ui.common.motion.pressSquish
import com.tyranor.next.ui.common.motion.pressSquishGroup
import com.tyranor.next.ui.common.motion.staggeredEntrance
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Scaffold as MiuixScaffold
import com.tyranor.next.ui.common.ArrowPreference
import com.tyranor.next.ui.common.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import com.tyranor.next.ui.common.notice.AppNotice

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

/** 编辑手柄按键和摇杆映射；通过逻辑按键及摇杆方向定位编辑目标，按卡片分组执行入场。 */
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
    // 首次读盘完成：「增强」档下映射行等到真实值到达再展开进场，不先闪一下「未绑定」再跳成实际键位
    var loaded by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        reload()
        loaded = true
    }

    // 编辑目标：逻辑按键 id，或 "left.up" 形式的摇杆方向
    var editingButton by rememberSaveable { mutableStateOf<String?>(null) }
    var editingStick by rememberSaveable { mutableStateOf<Pair<String, String>?>(null) }
    // 映射写盘成功的落点（编辑目标键 → 第几次）：只让刚改过的那一行弹一下
    var landedBinding by remember { mutableStateOf<Pair<String, Int>?>(null) }
    fun markLanded(target: String) {
        landedBinding = target to ((landedBinding?.second ?: 0) + 1)
    }
    // 「普通」档不等读盘，与原版一致（读盘前即显示各行）
    val rowsVisible = loaded || !MotionMode.enhanced

    MiuixSettingsTheme {
        ProvideEntrance {
        MiuixScaffold(
            modifier = Modifier.fillMaxSize().skipEntranceOnDrag(),
            containerColor = ComposeColor.Transparent,
            contentWindowInsets = WindowInsets(0.dp),
            topBar = {
                AppSecondaryTopBar(
                    title = stringResource(R.string.input_settings_gamepad_title),
                    background = ComposeColor.Transparent,
                    contentColor = PageTextColor,
                )
            },
        ) { innerPadding ->
            LazyColumn(
                // 顶栏透明：列表整体垫在顶栏下方（持久 padding），避免滚动时内容穿过顶栏
                modifier = Modifier.fillMaxSize()
                    .padding(horizontal = 12.dp)
                    .padding(top = innerPadding.calculateTopPadding()),
                contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    Box(Modifier.pressSquishGroup().staggeredEntrance(0)) {
                    EngineCard(stringResource(R.string.input_settings_gamepad_buttons_label)) {
                        // 说明并入卡片内：AGENTS.md 禁止在顶部栏下方放整页描述文案
                        Text(
                            text = stringResource(R.string.input_settings_gamepad_summary),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                        )
                        // 读盘到达：整组映射行从说明文字下方弹簧展开、由糊变清（大块内容自动不做模糊）
                        ExpandVisibility(visible = rowsVisible, label = "gamepadRows") {
                        Column(Modifier.fillMaxWidth()) {
                        entries.forEach { entry ->
                            BindingRowMotion(
                                summary = bindingSummary(ctx, entry.keys, entry.autoKeep),
                                landed = landedBinding?.takeIf { it.first == entry.id },
                            ) { summary ->
                            ArrowPreference(
                                title = gamepadKeyLabel(entry.id),
                                summary = summary,
                                onClick = { editingButton = entry.id },
                            )
                            }
                        }
                        }
                        }
                    }
                    }
                }
                item {
                    Box(Modifier.pressSquishGroup().staggeredEntrance(1)) {
                    EngineCard(stringResource(R.string.input_settings_gamepad_sticks_label)) {
                        ExpandVisibility(visible = rowsVisible, label = "stickRows") {
                        Column(Modifier.fillMaxWidth()) {
                        listOf(
                            InputRemapRepository.STICK_LEFT to R.string.input_settings_stick_left,
                            InputRemapRepository.STICK_RIGHT to R.string.input_settings_stick_right,
                        ).forEach { (stick, titleRes) ->
                            StickRow(
                                title = stringResource(titleRes),
                                entries = sticks.filter { it.stick == stick },
                                summaryOf = { keys -> bindingSummary(ctx, keys, autoKeep = false) },
                                onEdit = { direction -> editingStick = stick to direction },
                                landed = { direction ->
                                    landedBinding?.takeIf { it.first == stickLandingKey(stick, direction) }
                                },
                            )
                        }
                        }
                        }
                    }
                    }
                }
                item { BottomInsetSpacer() }
            }
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
                        // 落点反馈：新值到达后该行轻弹一下（摘要同时模糊替换为新键位）
                        if (ok) markLanded(id)
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
                        if (ok) markLanded(stickLandingKey(stick, direction))
                    }
                },
            )
        }
    }
}

/** 摇杆方向在落点反馈里的键（与逻辑按键 id 不会重名）。 */
private fun stickLandingKey(stick: String, direction: String): String = "stick:$stick.$direction"

/**
 * 映射行的内容替换与落点：摘要（键位）变化时整行模糊替换（「普通」档瞬时替换，与原版一致），
 * [landed] 每变成一个新的非 null 值（该行写盘成功）时整行轻弹一下。
 */
@Composable
private fun BindingRowMotion(
    summary: String,
    landed: Any?,
    content: @Composable (String) -> Unit,
) {
    BlurCrossfade(
        targetState = summary,
        modifier = Modifier.fillMaxWidth().bounceOnChange(landed, depth = 0.05f),
        label = "bindingSummary",
    ) { text ->
        content(text)
    }
}

/** 手柄映射写盘失败提示（两处保存回调共用）。 */
private fun toastBindingFailed(context: Context) {
    AppNotice.makeText(
        context,
        R.string.input_save_failed,
        Toast.LENGTH_SHORT).show()
}

@Composable
private fun StickRow(
    title: String,
    entries: List<InputRemapRepository.StickEntry>,
    summaryOf: @Composable (List<Int>) -> String,
    onEdit: (String) -> Unit,
    /** 方向 → 落点触发值（该方向刚写盘成功时为新的非 null 值）。 */
    landed: (String) -> Any? = { null },
) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
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
            BindingRowMotion(summary = summaryOf(keys), landed = landed(direction)) { summary ->
            ArrowPreference(
                title = stringResource(labelRes),
                summary = summary,
                onClick = { onEdit(direction) },
            )
            }
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
@Composable
private fun bindingSummary(context: Context, keys: List<Int>, autoKeep: Boolean): String {
    val label = if (keys.isEmpty()) {
        context.getString(R.string.input_settings_binding_empty)
    } else {
        // keyDisplayLabel 是 @Composable，不能塞进 joinToString{}/map{} 这类
        // 非 Composable 形参的 lambda（编译器拒绝）：逐个解析再拼接
        val parts = ArrayList<String>(keys.size)
        for (code in keys) parts.add(keyDisplayLabel(code))
        parts.joinToString(" + ")
    }
    return if (autoKeep) {
        label + " · " + context.getString(R.string.input_settings_auto_keep)
    } else {
        label
    }
}

/**
 * 键位展示名（UI 层组装：ASCII 名优先，其次本地化资源，未知回退数字避免空白项）。
 *
 * core 只给 code 与资源 id（见 `InputRemapRepository.keyOption`），文案组装不上移之外的
 * 地方做——AGENTS.md 错误协议：core 不返回本地化文案。
 */
@Composable
private fun keyDisplayLabel(code: Int): String {
    val option = InputRemapRepository.keyOption(code)
    return option?.label
        ?: option?.labelRes?.takeIf { it != 0 }?.let { stringResource(it) }
        ?: code.toString()
}

private val SelectedKeysSaver = Saver<Set<Int>, IntArray>(
    save = { it.toIntArray() },
    restore = { it.toSet() },
)

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
    var selected by rememberSaveable(stateSaver = SelectedKeysSaver) { mutableStateOf(selectedKeys.toSet()) }
    var keep by rememberSaveable { mutableStateOf(autoKeep) }
    val groups = remember { InputRemapRepository.keyGroups() }

    // 弹窗在独立窗口里组合，拿不到调用点的 MiuixSettingsTheme 作用域：
    // 不显式包一层的话 SwitchPreference 与 onSurfaceVariantSummary 会落到库默认浅色板
    // （默认蓝、深色模式下黑字），与页面观感不一致。仓库既有弹窗同此处理。
    MiuixSettingsTheme {
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
                    // 弹窗编排：开关、计数、各组标题与键位行按屏幕次序依次轻轻升起（弹窗入场节拍内；
                    // 之后滚动进来的行直接显示）。只改图层，停稳后与原样一致
                    var order = 0
                    if (allowAutoKeep) {
                        val switchOrder = order++
                        item {
                            SwitchPreference(
                                modifier = Modifier.pressSquish(scale = PressSquishDefaults.RowScale)
                                    .staggeredEntrance(switchOrder, distance = 10.dp, stepMillis = 30L),
                                title = stringResource(R.string.input_settings_auto_keep_toggle),
                                checked = keep,
                                onCheckedChange = { keep = it },
                            )
                        }
                    }
                    val countOrder = order++
                    item {
                        BlurCrossfade(
                            targetState = selected.size,
                            modifier = Modifier
                                .staggeredEntrance(countOrder, distance = 10.dp, stepMillis = 30L)
                                .padding(horizontal = 16.dp, vertical = 4.dp),
                        ) { count ->
                            Text(
                                text = stringResource(R.string.input_settings_binding_keys, count),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                    }
                    groups.forEach { group ->
                        val titleOrder = order++
                        item {
                            Text(
                                text = stringResource(group.titleRes),
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier
                                    .staggeredEntrance(titleOrder, distance = 10.dp, stepMillis = 30L)
                                    .padding(horizontal = 16.dp, vertical = 6.dp),
                            )
                        }
                        group.keys.chunked(4).forEach { chunk ->
                            val rowOrder = order++
                            item {
                                Row(
                                    Modifier
                                        .staggeredEntrance(rowOrder, distance = 10.dp, stepMillis = 30L)
                                        .fillMaxWidth()
                                        .padding(horizontal = 8.dp),
                                ) {
                                    chunk.forEach { option ->
                                        val on = option.code in selected
                                        // 选中态切换：实底 ↔ 浅底整体模糊替换（颜色不再瞬间跳变，新态轻轻弹入）；
                                        // 「普通」档瞬时替换，与原版一致。宽度分配挪到替换容器上，按钮仍等分
                                        BlurCrossfade(
                                            targetState = on,
                                            modifier = Modifier.weight(1f),
                                            label = "keyToggle",
                                        ) { isOn ->
                                        // 选中态用主题色实底 + 白字，与游戏内编辑面板的键位高亮一致；
                                        // 未选中为静默文本，一眼可辨「选了哪些」而不是只看计数
                                        NoRippleButton(
                                            text = keyDisplayLabel(option.code),
                                            tonal = !isOn,
                                            onClick = {
                                                // 按点击时的真实选中态切换：替换过渡中点到即将淡出的旧按钮也不会反向
                                                selected = if (option.code in selected) {
                                                    selected - option.code
                                                } else {
                                                    selected + option.code
                                                }
                                            },
                                        )
                                        }
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
}
