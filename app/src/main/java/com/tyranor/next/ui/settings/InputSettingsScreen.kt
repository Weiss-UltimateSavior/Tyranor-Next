package com.tyranor.next.ui.settings

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import com.core.input.GamepadBinding
import com.core.input.GamepadButtons
import com.core.input.GamepadMap
import com.core.input.InputConfigStore
import com.core.input.InputKeyCatalog
import com.core.input.PadProfile
import com.core.input.StickBinding
import com.tyranor.next.R
import com.tyranor.next.core.input.InputRemapRepository
import com.tyranor.next.theme.AppComponentCornerRadius
import com.tyranor.next.theme.MiuixSettingsTheme
import com.tyranor.next.theme.glassBorder
import com.tyranor.next.theme.glassShadow
import com.tyranor.next.ui.common.AppAlertDialog
import com.tyranor.next.ui.common.AppScreenActivity
import com.tyranor.next.ui.common.AppTopBar
import com.tyranor.next.ui.common.DialogTextButton
import com.tyranor.next.ui.game.startActivityWithPageTransition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card as MiuixCard
import top.yukonga.miuix.kmp.basic.Scaffold as MiuixScaffold
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 输入与手柄设置页：全局虚拟按键/手柄映射开关、按键方案管理与手柄映射入口。
 *
 * 入口在设置页「引擎设置」下方，与 EngineSettingsMenuActivity 平级（不隶属单一引擎）。
 */
class InputSettingsActivity : AppScreenActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setAppScreenContent { InputSettingsScreen() }
    }

    companion object {
        fun createIntent(context: Context): Intent = Intent(context, InputSettingsActivity::class.java)
    }
}

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
internal fun InputSettingsScreen() {
    val ctx = LocalContext.current
    var padEnabled by remember { mutableStateOf(InputRemapRepository.globalPadEnabled(ctx)) }
    var gamepadEnabled by remember { mutableStateOf(InputRemapRepository.globalGamepadEnabled(ctx)) }
    var profileId by remember { mutableStateOf(InputRemapRepository.globalProfileId(ctx)) }
    var profiles by remember { mutableStateOf(InputRemapRepository.listProfiles(ctx)) }
    var renameTarget by remember { mutableStateOf<String?>(null) }
    var deleteTarget by remember { mutableStateOf<String?>(null) }
    var newProfileDialog by remember { mutableStateOf(false) }
    var exportTarget by remember { mutableStateOf<PadProfile?>(null) }
    val scope = rememberCoroutineScope()

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val raw = withContext(Dispatchers.IO) {
                runCatching {
                    ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                }.getOrNull()
            }
            val parsed = InputRemapRepository.importProfile(raw)
            if (parsed == null) {
                toast(ctx, R.string.input_settings_profile_import_failed)
                return@launch
            }
            val id = InputRemapRepository.newProfileId(ctx)
            val name = InputRemapRepository.uniqueProfileName(ctx, parsed.name)
            if (InputRemapRepository.duplicateProfile(ctx, parsed, id, name) != null) {
                profileId = id
                InputRemapRepository.setGlobalProfileId(ctx, id)
                profiles = InputRemapRepository.listProfiles(ctx)
                toast(ctx, R.string.input_settings_profile_import_done)
            }
        }
    }

    // 从编辑页返回时重读方案与当前选择（编辑页可能在游戏外改了布局）
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                profiles = InputRemapRepository.listProfiles(ctx)
                profileId = InputRemapRepository.globalProfileId(ctx)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val target = exportTarget
        exportTarget = null
        if (uri == null || target == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    ctx.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use {
                        it.write(InputRemapRepository.exportProfile(target))
                    }
                }.isSuccess
            }
            toast(ctx, if (ok) R.string.input_settings_profile_export_done else R.string.input_settings_profile_export_failed)
        }
    }

    MiuixSettingsTheme {
        MiuixScaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = ComposeColor.Transparent,
            contentWindowInsets = WindowInsets(0.dp),
            topBar = {
                AppTopBar(
                    title = stringResource(R.string.input_settings_title),
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
                    EngineCard(stringResource(R.string.engine_settings_input_title)) {
                        SwitchPreference(
                            title = stringResource(R.string.engine_settings_input_pad_title),
                            summary = stringResource(R.string.engine_settings_input_pad_summary),
                            checked = padEnabled,
                            onCheckedChange = {
                                padEnabled = it
                                InputRemapRepository.setGlobalPadEnabled(ctx, it)
                            },
                        )
                        SwitchPreference(
                            title = stringResource(R.string.engine_settings_input_gamepad_title),
                            summary = stringResource(R.string.engine_settings_input_gamepad_summary),
                            checked = gamepadEnabled,
                            onCheckedChange = {
                                gamepadEnabled = it
                                InputRemapRepository.setGlobalGamepadEnabled(ctx, it)
                            },
                        )
                    }
                }

                item {
                    EngineCard(stringResource(R.string.input_settings_profiles_title)) {
                        Text(
                            stringResource(R.string.input_settings_profiles_summary),
                            style = MaterialTheme.typography.bodySmall,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                        // 新建/导入置于方案列表上方（列表条目随方案数量增长，操作入口保持固定位置）
                        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
                            Box(Modifier.weight(1f)) {
                                DialogTextButton(
                                    text = stringResource(R.string.input_settings_profile_new),
                                    onClick = { newProfileDialog = true },
                                )
                            }
                            Box(Modifier.weight(1f)) {
                                DialogTextButton(
                                    text = stringResource(R.string.input_settings_profile_import),
                                    onClick = { importLauncher.launch("application/json") },
                                )
                            }
                        }
                        profiles.forEach { profile ->
                            ProfileRow(
                                profile = profile,
                                selected = profile.id == profileId,
                                onSelect = {
                                    profileId = profile.id
                                    InputRemapRepository.setGlobalProfileId(ctx, profile.id)
                                },
                                onEdit = {
                                    startActivityWithPageTransition(ctx, PadLayoutEditActivity.createIntent(ctx, profile.id))
                                },
                                onRename = { renameTarget = profile.id },
                                onCopy = {
                                    val id = InputRemapRepository.newProfileId(ctx)
                                    val name = InputRemapRepository.uniqueProfileName(ctx, profile.name + " " + ctx.getString(R.string.input_settings_profile_copy))
                                    InputRemapRepository.duplicateProfile(ctx, profile, id, name)?.let {
                                        profiles = InputRemapRepository.listProfiles(ctx)
                                    }
                                },
                                onDelete = { deleteTarget = profile.id },
                                onExport = { exportTarget = profile },
                            )
                        }
                    }
                }

                item {
                    // 卡片不设标题：条目自身已含标题与说明，避免与卡片标题重复
                    MiuixCard(
                        modifier = Modifier.fillMaxWidth().glassShadow().glassBorder(),
                        cornerRadius = AppComponentCornerRadius,
                    ) {
                        Column(Modifier.padding(vertical = 4.dp)) {
                            ArrowPreference(
                                title = stringResource(R.string.engine_settings_input_gamepad_entry),
                                summary = stringResource(R.string.engine_settings_input_gamepad_entry_summary),
                                onClick = { startActivityWithPageTransition(ctx, GamepadMapActivity.createIntent(ctx)) },
                            )
                            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
                                Box(Modifier.weight(1f)) {
                                    DialogTextButton(
                                        text = stringResource(R.string.input_settings_reset_gamepad),
                                        onClick = {
                                            InputRemapRepository.resetGamepadMap(ctx)
                                            toast(ctx, R.string.input_settings_reset_gamepad_done)
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    renameTarget?.let { target ->
        val current = profiles.firstOrNull { it.id == target }?.name.orEmpty()
        TextInputDialog(
            title = stringResource(R.string.input_settings_profile_rename),
            initial = current,
            onConfirm = { newName ->
                renameTarget = null
                if (newName.isNotBlank()) {
                    InputRemapRepository.readProfile(ctx, target)?.let { profile ->
                        InputRemapRepository.writeProfile(ctx, profile.copy(name = newName.trim().take(24)))
                        profiles = InputRemapRepository.listProfiles(ctx)
                    }
                }
            },
            onDismiss = { renameTarget = null },
        )
    }

    if (newProfileDialog) {
        TextInputDialog(
            title = stringResource(R.string.input_settings_profile_new_name),
            initial = "",
            onConfirm = { name ->
                newProfileDialog = false
                val trimmed = name.trim()
                if (trimmed.isBlank()) return@TextInputDialog
                val id = InputRemapRepository.newProfileId(ctx)
                val unique = InputRemapRepository.uniqueProfileName(ctx, trimmed)
                InputRemapRepository.duplicateProfile(ctx, PadProfile.defaultProfile(id, unique), id, unique)?.let {
                    profileId = id
                    InputRemapRepository.setGlobalProfileId(ctx, id)
                    profiles = InputRemapRepository.listProfiles(ctx)
                }
            },
            onDismiss = { newProfileDialog = false },
        )
    }

    deleteTarget?.let { target ->
        val isDefault = target == InputConfigStore.DEFAULT_PROFILE_ID
        AppAlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.input_settings_profile_delete)) },
            text = {
                Text(
                    stringResource(
                        if (isDefault) R.string.input_settings_profile_delete_default_blocked
                        else R.string.input_settings_profile_delete,
                    ),
                )
            },
            confirmButton = {
                if (!isDefault) {
                    DialogTextButton(
                        text = stringResource(R.string.common_delete),
                        onClick = {
                            deleteTarget = null
                            InputRemapRepository.deleteProfile(ctx, target)
                            if (profileId == target) {
                                profileId = InputConfigStore.DEFAULT_PROFILE_ID
                                InputRemapRepository.setGlobalProfileId(ctx, profileId)
                            }
                            profiles = InputRemapRepository.listProfiles(ctx)
                        },
                    )
                }
            },
            dismissButton = {
                DialogTextButton(text = stringResource(R.string.common_cancel), onClick = { deleteTarget = null })
            },
        )
    }
}

@Composable
private fun ProfileRow(
    profile: PadProfile,
    selected: Boolean,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    onRename: () -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
    onExport: () -> Unit,
) {
    // 方案名是随方案持久化的用户数据（默认方案存的是语言中立占位名），此处按 id 本地化展示
    val displayName = if (profile.id == InputConfigStore.DEFAULT_PROFILE_ID) {
        stringResource(R.string.input_settings_profile_default)
    } else {
        profile.name
    }
    Column(Modifier.fillMaxWidth()) {
        // 整行（含行尾箭头）进入布局编辑；激活状态用左侧 ✓ 与下方「使用中」区分
        ArrowPreference(
            title = (if (selected) "✓ " else "") + displayName,
            summary = null,
            onClick = onEdit,
        )
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
            Box(Modifier.weight(1f)) {
                if (selected) {
                    Text(
                        text = stringResource(R.string.input_settings_profile_in_use),
                        style = MaterialTheme.typography.labelLarge,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    )
                } else {
                    DialogTextButton(text = stringResource(R.string.input_settings_profile_use), onClick = onSelect)
                }
            }
            Box(Modifier.weight(1f)) {
                DialogTextButton(text = stringResource(R.string.input_settings_profile_rename), onClick = onRename)
            }
            Box(Modifier.weight(1f)) {
                DialogTextButton(text = stringResource(R.string.input_settings_profile_copy), onClick = onCopy)
            }
            Box(Modifier.weight(1f)) {
                DialogTextButton(text = stringResource(R.string.input_settings_profile_export), onClick = onExport)
            }
            Box(Modifier.weight(1f)) {
                DialogTextButton(text = stringResource(R.string.input_settings_profile_delete), onClick = onDelete)
            }
        }
    }
}

@Composable
private fun TextInputDialog(
    title: String,
    initial: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var value by remember { mutableStateOf(initial) }
    AppAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            androidx.compose.material3.OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
            )
        },
        confirmButton = {
            DialogTextButton(text = stringResource(R.string.common_confirm), onClick = { onConfirm(value) })
        },
        dismissButton = {
            DialogTextButton(text = stringResource(R.string.common_cancel), onClick = onDismiss)
        },
    )
}

@Composable
internal fun GamepadMapScreen() {
    val ctx = LocalContext.current
    var map by remember { mutableStateOf(InputRemapRepository.readGamepadMap(ctx)) }
    var editing by remember { mutableStateOf<String?>(null) }

    fun persist(updated: GamepadMap) {
        map = updated
        InputRemapRepository.writeGamepadMap(ctx, updated)
    }

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
                    Text(
                        stringResource(R.string.input_settings_gamepad_summary),
                        style = MaterialTheme.typography.bodySmall,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
                item {
                    EngineCard(stringResource(R.string.input_settings_gamepad_buttons_label)) {
                        GamepadButtons.ALL.forEach { id ->
                            val binding = map.binding(id)
                            ArrowPreference(
                                title = gamepadKeyLabel(id),
                                summary = bindingSummary(ctx, binding),
                                onClick = { editing = id },
                            )
                        }
                    }
                }
                item {
                    EngineCard(stringResource(R.string.input_settings_gamepad_sticks_label)) {
                        StickRow(
                            title = stringResource(R.string.input_settings_stick_left),
                            stick = map.leftStick,
                            onEdit = { dir -> editing = "left.$dir" },
                        )
                        StickRow(
                            title = stringResource(R.string.input_settings_stick_right),
                            stick = map.rightStick,
                            onEdit = { dir -> editing = "right.$dir" },
                        )
                    }
                }
            }
        }
    }

    editing?.let { id ->
        BindingEditorDialog(
            current = when {
                id.startsWith(STICK_LEFT_PREFIX) -> map.leftStick.bindingFor(id.removePrefix(STICK_LEFT_PREFIX))
                id.startsWith(STICK_RIGHT_PREFIX) -> map.rightStick.bindingFor(id.removePrefix(STICK_RIGHT_PREFIX))
                else -> map.binding(id)
            },
            onDismiss = { editing = null },
            onSave = { binding ->
                persist(
                    when {
                        id.startsWith(STICK_LEFT_PREFIX) ->
                            map.copy(leftStick = map.leftStick.with(id.removePrefix(STICK_LEFT_PREFIX), binding))
                        id.startsWith(STICK_RIGHT_PREFIX) ->
                            map.copy(rightStick = map.rightStick.with(id.removePrefix(STICK_RIGHT_PREFIX), binding))
                        else -> map.withBinding(id, binding)
                    },
                )
                editing = null
            },
        )
    }
}

private const val STICK_LEFT_PREFIX = "left."
private const val STICK_RIGHT_PREFIX = "right."

@Composable
private fun StickRow(
    title: String,
    stick: StickBinding,
    onEdit: (String) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        ArrowPreference(title = title, summary = null, onClick = { onEdit("up") })
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
            listOf(
                R.string.input_settings_stick_up to "up",
                R.string.input_settings_stick_down to "down",
                R.string.input_settings_stick_left_dir to "left",
                R.string.input_settings_stick_right_dir to "right",
            ).forEach { (labelRes, dir) ->
                Box(Modifier.weight(1f)) {
                    DialogTextButton(
                        text = stringResource(labelRes),
                        onClick = { onEdit(dir) },
                    )
                }
            }
        }
    }
}

@Composable
private fun gamepadKeyLabel(id: String): String = stringResource(
    when (id) {
        GamepadButtons.A -> R.string.input_settings_gamepad_key_a
        GamepadButtons.B -> R.string.input_settings_gamepad_key_b
        GamepadButtons.X -> R.string.input_settings_gamepad_key_x
        GamepadButtons.Y -> R.string.input_settings_gamepad_key_y
        GamepadButtons.START -> R.string.input_settings_gamepad_key_start
        GamepadButtons.SELECT -> R.string.input_settings_gamepad_key_select
        GamepadButtons.L1 -> R.string.input_settings_gamepad_key_l1
        GamepadButtons.R1 -> R.string.input_settings_gamepad_key_r1
        GamepadButtons.L2 -> R.string.input_settings_gamepad_key_l2
        GamepadButtons.R2 -> R.string.input_settings_gamepad_key_r2
        GamepadButtons.L3 -> R.string.input_settings_gamepad_key_l3
        GamepadButtons.R3 -> R.string.input_settings_gamepad_key_r3
        GamepadButtons.DPAD_UP -> R.string.input_settings_gamepad_key_dpad_up
        GamepadButtons.DPAD_DOWN -> R.string.input_settings_gamepad_key_dpad_down
        GamepadButtons.DPAD_LEFT -> R.string.input_settings_gamepad_key_dpad_left
        else -> R.string.input_settings_gamepad_key_dpad_right
    },
)

private fun bindingSummary(context: Context, binding: GamepadBinding): String {
    val label = if (binding.keys.isEmpty()) {
        context.getString(R.string.input_settings_binding_empty)
    } else {
        binding.keys.joinToString(" + ") {
            InputKeyCatalog.label(context, it).ifBlank { it.toString() }
        }
    }
    return if (binding.autoKeep) {
        label + " · " + context.getString(R.string.input_settings_auto_keep)
    } else {
        label
    }
}

/** 单条绑定编辑器：多选输出键 + 保持开关。 */
@Composable
private fun BindingEditorDialog(
    current: GamepadBinding,
    onDismiss: () -> Unit,
    onSave: (GamepadBinding) -> Unit,
) {
    var selected by remember { mutableStateOf(current.keys.toSet()) }
    var autoKeep by remember { mutableStateOf(current.autoKeep) }
    AppAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.input_settings_choose_keys)) },
        text = {
            LazyColumn(Modifier.fillMaxWidth()) {
                item {
                    SwitchPreference(
                        title = stringResource(R.string.input_settings_auto_keep_toggle),
                        checked = autoKeep,
                        onCheckedChange = { autoKeep = it },
                    )
                    Text(
                        stringResource(R.string.input_settings_binding_keys, selected.size),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
                InputKeyCatalog.groups().forEach { group ->
                    item {
                        Text(
                            stringResource(group.titleRes),
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                        )
                    }
                    group.keys.chunked(4).forEach { chunk ->
                        item {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                                chunk.forEach { entry ->
                                    val on = entry.code in selected
                                    Box(Modifier.weight(1f)) {
                                        TextButton(
                                            onClick = {
                                                selected = if (on) selected - entry.code else selected + entry.code
                                            },
                                        ) {
                                            Text(
                                                (entry.label ?: stringResource(entry.labelRes)),
                                                style = MaterialTheme.typography.labelMedium,
                                                color = if (on) MaterialTheme.colorScheme.primary
                                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
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
                onClick = { onSave(GamepadBinding(keys = selected.toList(), autoKeep = autoKeep)) },
            )
        },
        dismissButton = {
            DialogTextButton(
                text = stringResource(R.string.input_settings_clear_binding),
                onClick = { onSave(GamepadBinding()) },
            )
        },
    )
}

private fun toast(context: Context, messageRes: Int) {
    Toast.makeText(context, context.getString(messageRes), Toast.LENGTH_SHORT).show()
}
