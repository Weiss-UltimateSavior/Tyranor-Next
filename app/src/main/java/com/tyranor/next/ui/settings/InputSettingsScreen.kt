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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.tyranor.next.R
import com.tyranor.next.core.input.InputRemapRepository
import com.tyranor.next.theme.AppComponentCornerRadius
import com.tyranor.next.theme.MiuixSettingsTheme
import com.tyranor.next.theme.glassBorder
import com.tyranor.next.theme.glassShadow
import com.tyranor.next.ui.common.AppAlertDialog
import com.tyranor.next.ui.common.AppScreenActivity
import com.tyranor.next.ui.common.AppSearchField
import com.tyranor.next.ui.common.AppTopBar
import com.tyranor.next.ui.common.BottomInsetSpacer
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
 * 本页只消费 `core/input` 门面（[InputRemapRepository] / [PadEditorHost]），
 * 不直接依赖 engine 类型（三层架构：ui 不得 import `com.core.*`）。
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

@Composable
internal fun InputSettingsScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var padEnabled by remember { mutableStateOf(InputRemapRepository.globalPadEnabled(ctx)) }
    var gamepadEnabled by remember { mutableStateOf(InputRemapRepository.globalGamepadEnabled(ctx)) }
    var profileId by remember { mutableStateOf(InputRemapRepository.globalProfileId(ctx)) }

    // 方案列表读盘：组合期一次 + 每次回到前台重读（编辑页/游戏内可能已改）
    var profiles by remember { mutableStateOf<List<InputRemapRepository.ProfileSummary>>(emptyList()) }
    suspend fun reloadProfiles() {
        profiles = withContext(Dispatchers.IO) { InputRemapRepository.listProfileSummaries(ctx) }
        profileId = InputRemapRepository.globalProfileId(ctx)
    }
    androidx.compose.runtime.LaunchedEffect(Unit) { reloadProfiles() }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                scope.launch { reloadProfiles() }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    var renameTarget by rememberSaveable { mutableStateOf<String?>(null) }
    var deleteTarget by rememberSaveable { mutableStateOf<String?>(null) }
    var newProfileDialog by rememberSaveable { mutableStateOf(false) }
    // 导出目标存 id（可 rememberSaveable）：系统文件选择器是独立进程页，
    // 低内存重建后 Lambda 内的对象引用会丢，按 id 重读更稳
    var exportTargetId by rememberSaveable { mutableStateOf<String?>(null) }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val raw = withContext(Dispatchers.IO) {
                runCatching {
                    ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                }.getOrNull()
            }
            val importedName = InputRemapRepository.importedProfileName(raw)
            if (importedName == null) {
                toast(ctx, R.string.input_settings_profile_import_failed)
                return@launch
            }
            val id = InputRemapRepository.newProfileId(ctx)
            val name = InputRemapRepository.uniqueProfileName(ctx, importedName)
            val created = withContext(Dispatchers.IO) {
                InputRemapRepository.importProfileAsNew(ctx, raw, id, name)
            }
            if (created != null) {
                InputRemapRepository.setGlobalProfileId(ctx, id)
                reloadProfiles()
                toast(ctx, R.string.input_settings_profile_import_done)
            } else {
                toast(ctx, R.string.input_settings_profile_import_failed)
            }
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        val targetId = exportTargetId
        exportTargetId = null
        if (uri == null || targetId == null) return@rememberLauncherForActivityResult
        scope.launch {
            val payload = withContext(Dispatchers.IO) { InputRemapRepository.exportProfile(ctx, targetId) }
            if (payload == null) {
                toast(ctx, R.string.input_settings_profile_export_failed)
                return@launch
            }
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    ctx.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(payload) }
                }.isSuccess
            }
            toast(ctx, if (ok) R.string.input_settings_profile_export_done else R.string.input_settings_profile_export_failed)
        }
    }

    fun requestExport(target: InputRemapRepository.ProfileSummary) {
        exportTargetId = target.id
        val suggested = (target.name.ifBlank { target.id }) + ".json"
        runCatching { exportLauncher.launch(suggested) }
            .onFailure {
                exportTargetId = null
                toast(ctx, R.string.input_settings_profile_export_failed)
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
                                    onClick = { importLauncher.launch("*/*") },
                                )
                            }
                        }
                        profiles.forEach { profile ->
                            ProfileRow(
                                profile = profile,
                                selected = profile.id == profileId,
                                onUse = {
                                    profileId = profile.id
                                    InputRemapRepository.setGlobalProfileId(ctx, profile.id)
                                },
                                onEdit = {
                                    startActivityWithPageTransition(ctx, PadLayoutEditActivity.createIntent(ctx, profile.id))
                                },
                                // 默认方案的展示名固定在字符串资源里，重命名不会生效——
                                // 与「删除」一致：不给入口，而不是点了没反应
                                onRename = if (profile.isDefault) null else ({ renameTarget = profile.id }),
                                onCopy = {
                                    scope.launch {
                                        val id = InputRemapRepository.newProfileId(ctx)
                                        val baseName = if (profile.isDefault) {
                                            ctx.getString(R.string.input_settings_profile_default)
                                        } else {
                                            profile.name
                                        }
                                        val name = InputRemapRepository.uniqueProfileName(
                                            ctx,
                                            baseName + " " + ctx.getString(R.string.input_settings_profile_copy),
                                        )
                                        withContext(Dispatchers.IO) {
                                            InputRemapRepository.duplicateProfile(ctx, profile.id, id, name)
                                        }
                                        reloadProfiles()
                                    }
                                },
                                onDelete = if (profile.isDefault) null else ({ deleteTarget = profile.id }),
                                onExport = { requestExport(profile) },
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
                item { BottomInsetSpacer() }
            }
        }
    }

    renameTarget?.let { target ->
        val current = profiles.firstOrNull { it.id == target }?.name.orEmpty()
        ProfileNameDialog(
            title = stringResource(R.string.input_settings_profile_rename),
            initial = current,
            onConfirm = { newName ->
                renameTarget = null
                if (newName.isNotBlank()) {
                    scope.launch {
                        withContext(Dispatchers.IO) { InputRemapRepository.renameProfile(ctx, target, newName) }
                        reloadProfiles()
                    }
                }
            },
            onDismiss = { renameTarget = null },
        )
    }

    if (newProfileDialog) {
        ProfileNameDialog(
            title = stringResource(R.string.input_settings_profile_new_name),
            initial = "",
            onConfirm = { name ->
                newProfileDialog = false
                val trimmed = name.trim()
                if (trimmed.isBlank()) return@ProfileNameDialog
                scope.launch {
                    val id = InputRemapRepository.newProfileId(ctx)
                    val unique = InputRemapRepository.uniqueProfileName(ctx, trimmed)
                    val created = withContext(Dispatchers.IO) {
                        InputRemapRepository.createProfile(ctx, id, unique)
                    }
                    if (created != null) {
                        InputRemapRepository.setGlobalProfileId(ctx, created.id)
                        reloadProfiles()
                    }
                }
            },
            onDismiss = { newProfileDialog = false },
        )
    }

    deleteTarget?.let { target ->
        AppAlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = {
                Text(
                    text = stringResource(R.string.input_settings_profile_delete_title),
                    style = MaterialTheme.typography.titleMedium,
                )
            },
            text = {
                Text(
                    text = stringResource(
                        R.string.input_settings_profile_delete_message,
                        profiles.firstOrNull { it.id == target }?.name.orEmpty(),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                DialogTextButton(
                    text = stringResource(R.string.common_delete),
                    onClick = {
                        deleteTarget = null
                        scope.launch {
                            withContext(Dispatchers.IO) { InputRemapRepository.deleteProfile(ctx, target) }
                            if (profileId == target) {
                                profileId = InputRemapRepository.defaultProfileId()
                                InputRemapRepository.setGlobalProfileId(ctx, profileId)
                            }
                            reloadProfiles()
                        }
                    },
                )
            },
            dismissButton = {
                DialogTextButton(text = stringResource(R.string.common_cancel), onClick = { deleteTarget = null })
            },
        )
    }
}

/**
 * 方案行：整行（含行尾箭头）进入布局编辑。
 *
 * `onDelete` 为 null 表示不可删除（内置默认方案）：入口整体隐藏而不是点击后才报错。
 */
@Composable
private fun ProfileRow(
    profile: InputRemapRepository.ProfileSummary,
    selected: Boolean,
    onUse: () -> Unit,
    onEdit: () -> Unit,
    onRename: (() -> Unit)?,
    onCopy: () -> Unit,
    onDelete: (() -> Unit)?,
    onExport: () -> Unit,
) {
    // 方案名是随方案持久化的用户数据（默认方案存的是语言中立占位名），此处按 id 本地化展示
    val displayName = if (profile.isDefault) {
        stringResource(R.string.input_settings_profile_default)
    } else {
        profile.name
    }
    Column(Modifier.fillMaxWidth()) {
        ArrowPreference(
            title = displayName,
            summary = if (selected) stringResource(R.string.input_settings_profile_in_use) else null,
            onClick = onEdit,
        )
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
            if (!selected) {
                Box(Modifier.weight(1f)) {
                    DialogTextButton(text = stringResource(R.string.input_settings_profile_use), onClick = onUse)
                }
            }
            if (onRename != null) {
                Box(Modifier.weight(1f)) {
                    DialogTextButton(text = stringResource(R.string.input_settings_profile_rename), onClick = onRename)
                }
            }
            Box(Modifier.weight(1f)) {
                DialogTextButton(text = stringResource(R.string.input_settings_profile_copy), onClick = onCopy)
            }
            Box(Modifier.weight(1f)) {
                DialogTextButton(text = stringResource(R.string.input_settings_profile_export), onClick = onExport)
            }
            if (onDelete != null) {
                Box(Modifier.weight(1f)) {
                    DialogTextButton(text = stringResource(R.string.input_settings_profile_delete), onClick = onDelete)
                }
            }
        }
    }
}

/** 方案名输入弹窗（统一 AppSearchField + DialogTextButton + titleMedium 标题样式）。 */
@Composable
private fun ProfileNameDialog(
    title: String,
    initial: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var value by rememberSaveable(initial) { mutableStateOf(initial) }
    AppAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = title, style = MaterialTheme.typography.titleMedium) },
        text = {
            Column {
                AppSearchField(query = value, onQueryChange = { value = it })
                Text(
                    text = stringResource(R.string.input_settings_profile_name_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            DialogTextButton(text = stringResource(R.string.common_confirm), onClick = { onConfirm(value) })
        },
        dismissButton = {
            DialogTextButton(text = stringResource(R.string.common_cancel), onClick = onDismiss)
        },
    )
}

private fun toast(context: Context, messageRes: Int) {
    Toast.makeText(context, context.getString(messageRes), Toast.LENGTH_SHORT).show()
}
