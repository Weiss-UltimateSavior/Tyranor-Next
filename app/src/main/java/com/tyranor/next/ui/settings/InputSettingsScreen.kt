package com.tyranor.next.ui.settings

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.MutableTransitionState
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.tyranor.next.R
import com.tyranor.next.core.input.InputRemapRepository
import com.tyranor.next.theme.PageTextColor
import com.tyranor.next.theme.AppComponentCornerRadius
import com.tyranor.next.theme.MiuixSettingsTheme
import com.tyranor.next.theme.glassBorder
import com.tyranor.next.ui.common.AppAlertDialog
import com.tyranor.next.ui.common.AppScreenActivity
import com.tyranor.next.ui.common.AppSearchField
import com.tyranor.next.ui.common.AppSecondaryTopBar
import com.tyranor.next.ui.common.BottomInsetSpacer
import com.tyranor.next.ui.common.DialogTextButton
import com.tyranor.next.ui.common.NoIndication
import com.tyranor.next.ui.common.NoRippleTextButton
import com.tyranor.next.ui.common.motion.BlurCrossfade
import com.tyranor.next.ui.common.motion.MotionMode
import com.tyranor.next.ui.common.motion.PressSquishDefaults
import com.tyranor.next.ui.common.motion.ProvideEntrance
import com.tyranor.next.ui.common.motion.bounceOnChange
import com.tyranor.next.ui.common.motion.ExpandSectionVisibility
import com.tyranor.next.ui.common.motion.skipEntranceOnDrag
import com.tyranor.next.ui.common.motion.pressSquish
import com.tyranor.next.ui.common.motion.pressSquishGroup
import com.tyranor.next.ui.common.motion.staggeredEntrance
import com.tyranor.next.ui.game.startActivityWithPageTransition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card as MiuixCard
import top.yukonga.miuix.kmp.basic.Scaffold as MiuixScaffold
import com.tyranor.next.ui.common.ArrowPreference
import com.tyranor.next.ui.common.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import com.tyranor.next.ui.common.notice.AppNotice

/**
 * 输入与手柄设置页：全局虚拟按键/手柄映射开关、按键方案管理与手柄映射入口。
 *
 * 入口在侧边栏「妙妙工具」面板（原设置页入口已迁入），为顶层入口（不隶属单一引擎）。
 * 本页只消费 `core/input` 门面（[InputRemapRepository] / [com.tyranor.next.core.input.PadEditorHost]），
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

/**
 * 管理全局输入开关与按键方案的选择、导入导出和编辑入口，返回前台时重读共享设置。
 * 列表入场和内容变换只影响展示，方案的身份、保护与读写仍由输入仓库管理。
 */
@Composable
internal fun InputSettingsScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    // 开关展示值：未显式设置时按开显示（与 MV/MZ 默认一致，见 globalPadSwitchValue），
    // 否则用户以为按键层已关、且要连点两下才能真正关闭
    var padEnabled by remember {
        mutableStateOf(InputRemapRepository.globalPadSwitchValue(ctx))
    }
    var gamepadEnabled by remember { mutableStateOf(InputRemapRepository.globalGamepadEnabled(ctx)) }
    var profileId by remember { mutableStateOf(InputRemapRepository.globalProfileId(ctx)) }

    // 方案列表读盘：组合期一次 + 每次回到前台重读（编辑页/游戏内可能已改）
    var profiles by remember { mutableStateOf<List<InputRemapRepository.ProfileSummary>>(emptyList()) }
    suspend fun reloadProfiles() {
        profiles = withContext(Dispatchers.IO) { InputRemapRepository.listProfileSummaries(ctx) }
        // 方案选择可能刚被本页改动（导入/新建后指向新方案），一并重读
        profileId = InputRemapRepository.globalProfileId(ctx)
    }

    /** 回到前台重读全部展示值：开关也可能被单游戏设置页/其它入口改过（同一会话内保持一致）。 */
    fun reloadAll() {
        padEnabled = InputRemapRepository.globalPadSwitchValue(ctx)
        gamepadEnabled = InputRemapRepository.globalGamepadEnabled(ctx)
        profileId = InputRemapRepository.globalProfileId(ctx)
    }

    LaunchedEffect(Unit) { reloadProfiles() }

    // 方案行增删动效（仅「增强」档）：已见过的方案 id —— 首次加载与列表项滚回可见区时不重播展开，
    // 只有之后真正新增（新建 / 导入 / 复制）的方案才从 0 高度展开进场
    var knownProfileIds by remember { mutableStateOf<Set<String>?>(null) }
    LaunchedEffect(profiles) {
        if (profiles.isNotEmpty()) knownProfileIds = knownProfileIds.orEmpty() + profiles.map { it.id }
    }
    // 正在退场的方案（id → 删除前的位置与数据）：确认删除即开始原位收起；删除成功后列表里已没有它，
    // 仍按原位置暂留到收起完成再移除；删除失败则移出本表，原路展开回来
    val exitingProfiles = remember {
        mutableStateMapOf<String, IndexedValue<InputRemapRepository.ProfileSummary>>()
    }
    // 「恢复默认手柄映射」写盘成功的次数：驱动按钮落点回弹（第一次组合不弹）
    var gamepadResetTick by remember { mutableIntStateOf(0) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                reloadAll()
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
            // 读前限长：方案 JSON 正常只有几 KB，无上限 readText 会被超大文件撑爆内存
            val raw = withContext(Dispatchers.IO) {
                runCatching {
                    ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.use { reader ->
                        val buffer = CharArray(MAX_IMPORT_CHARS + 1)
                        var read = 0
                        while (read < buffer.size) {
                            val n = reader.read(buffer, read, buffer.size - read)
                            if (n < 0) break
                            read += n
                        }
                        if (read > MAX_IMPORT_CHARS) null else String(buffer, 0, read)
                    }
                }.getOrNull()
            }
            if (raw == null) {
                toast(ctx, R.string.input_settings_profile_import_failed)
                return@launch
            }
            // 解析与命名都涉及磁盘/JSON，放在 IO 线程
            val prepared = withContext(Dispatchers.IO) {
                val importedName = InputRemapRepository.importedProfileName(raw) ?: return@withContext null
                val id = InputRemapRepository.newProfileId(ctx)
                val name = InputRemapRepository.uniqueProfileName(ctx, importedName)
                Triple(id, name, raw)
            }
            if (prepared == null) {
                toast(ctx, R.string.input_settings_profile_import_failed)
                return@launch
            }
            val (id, name, payload) = prepared
            val created = withContext(Dispatchers.IO) {
                InputRemapRepository.importProfileAsNew(ctx, payload, id, name)
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
                    // openOutputStream 返回 null 时 ?.use{} 会短路成 null，isSuccess 仍为 true，
                    // 从而误报导出成功——必须显式判定写入是否真的发生
                    val stream = ctx.contentResolver.openOutputStream(uri) ?: return@runCatching false
                    stream.bufferedWriter().use { it.write(payload) }
                    true
                }.getOrDefault(false)
            }
            toast(ctx, if (ok) R.string.input_settings_profile_export_done else R.string.input_settings_profile_export_failed)
        }
    }

    fun requestExport(target: InputRemapRepository.ProfileSummary, displayName: String) {
        exportTargetId = target.id
        val suggested = (displayName.ifBlank { target.id }) + ".json"
        runCatching { exportLauncher.launch(suggested) }
            .onFailure {
                exportTargetId = null
                toast(ctx, R.string.input_settings_profile_export_failed)
            }
    }

    MiuixSettingsTheme {
        ProvideEntrance {
        MiuixScaffold(
            modifier = Modifier.fillMaxSize().skipEntranceOnDrag(),
            containerColor = ComposeColor.Transparent,
            contentWindowInsets = WindowInsets(0.dp),
            topBar = {
                AppSecondaryTopBar(
                    title = stringResource(R.string.input_settings_title),
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
                    EngineCard(stringResource(R.string.engine_settings_input_title)) {
                        // 开关展示值：未显式设置时按「开」显示（与 MV/MZ 的默认一致，
                        // 见 globalPadSwitchValue 的 KDoc），否则用户看到「关着但游戏里
                        // 有按键层」、且想关闭得点两下（先显式开、再显式关）。切换即写入
                        // 显式设置。Tyrano/VN/WebOther 的实际默认是关，由摘要文案说明。
                        SwitchPreference(
                            modifier = Modifier.pressSquish(scale = PressSquishDefaults.RowScale),
                            title = stringResource(R.string.engine_settings_input_pad_title),
                            summary = stringResource(R.string.engine_settings_input_pad_summary),
                            checked = padEnabled,
                            onCheckedChange = {
                                padEnabled = it
                                InputRemapRepository.setGlobalPadEnabled(ctx, it)
                            },
                        )
                        SwitchPreference(
                            modifier = Modifier.pressSquish(scale = PressSquishDefaults.RowScale),
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
                }

                item {
                    Box(Modifier.pressSquishGroup().staggeredEntrance(1)) {
                    EngineCard(stringResource(R.string.input_settings_profiles_title)) {
                        // 新建/导入置于方案列表上方（列表条目随方案数量增长，操作入口保持固定位置）
                        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
                            Box(Modifier.weight(1f)) {
                                NoRippleTextButton(
                                    text = stringResource(R.string.input_settings_profile_new),
                                    onClick = { newProfileDialog = true },
                                )
                            }
                            Box(Modifier.weight(1f)) {
                                NoRippleTextButton(
                                    text = stringResource(R.string.input_settings_profile_import),
                                    onClick = {
                                    // 系统选择器不可用时不应让整个页面崩掉
                                    runCatching { importLauncher.launch("*/*") }
                                        .onFailure { toast(ctx, R.string.input_settings_profile_import_failed) }
                                },
                                )
                            }
                        }
                        // 退场中的已删除方案按原位置暂留（「普通」档该表恒为空，等同原 profiles）
                        val copyName = stringResource(R.string.input_settings_profile_copy)
                        val defaultName = stringResource(R.string.input_settings_profile_default)
                        withExitingProfiles(profiles, exitingProfiles).forEach { profile ->
                          // 按 id 稳定身份：增删时其余行的状态（展开 / 退场进度）跟着各自的方案走
                          key(profile.id) {
                            // 展示名在组合期算好再传入：onExport/onCopy 等回调是普通 lambda，
                            // 不能在内部调用 @Composable 的 profileDisplayName
                            val displayName = profileDisplayName(profile)
                            val exiting = profile.id in exitingProfiles
                            ProfileRowMotion(
                                fresh = knownProfileIds?.contains(profile.id) == false,
                                exiting = exiting,
                                inList = !exiting || profiles.any { it.id == profile.id },
                                onExited = { exitingProfiles.remove(profile.id) },
                            ) {
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
                                // 内置默认方案的展示名恒取字符串资源，重命名不会生效——不给入口，
                                // 而不是点了没反应。迁移主布局只在**仍是占位名**时被本地化展示
                                // （见 isLegacyMain），给出重命名入口是有效的：改完即显示用户命名
                                onRename = if (profile.isDefault) null else ({ renameTarget = profile.id }),
                                onCopy = {
                                    // id/命名都要读方案目录，全部放 IO 线程（原先在主线程）
                                    scope.launch {
                                        val copied = withContext(Dispatchers.IO) {
                                            val id = InputRemapRepository.newProfileId(ctx)
                                            // 占位名的方案（内置默认 / 迁移主布局）复制时用本地化名字
                                            // 作底，否则副本会带上英文 "Default"
                                            val baseName = if (profile.isDefault || profile.isLegacyMain) {
                                                defaultName
                                            } else {
                                                profile.name
                                            }
                                            val name = InputRemapRepository.uniqueProfileName(
                                                ctx,
                                                baseName + " " + copyName,
                                            )
                                            InputRemapRepository.duplicateProfile(ctx, profile.id, id, name)
                                        }
                                        // 写盘失败（磁盘满等）必须提示：否则列表没有新条目，
                                        // 用户只会以为「点了复制没反应」
                                        if (copied == null) {
                                            toast(ctx, R.string.input_settings_profile_copy_failed)
                                        }
                                        reloadProfiles()
                                    }
                                },
                                onDelete = if (profile.isDefault) null else ({ deleteTarget = profile.id }),
                                onExport = { requestExport(profile, displayName) },
                            )
                            }
                          }
                        }
                    }
                    }
                }

                item {
                    // 卡片不设标题：条目自身已含标题与说明，避免与卡片标题重复
                    MiuixCard(
                        modifier = Modifier.pressSquishGroup().staggeredEntrance(2).fillMaxWidth().glassBorder(),
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
                                    NoRippleTextButton(
                                        text = stringResource(R.string.input_settings_reset_gamepad),
                                        // 落点反馈：写盘成功后按钮轻弹一下（与 Toast 同时，不被提示吞掉）
                                        modifier = Modifier.bounceOnChange(
                                            gamepadResetTick.takeIf { it > 0 },
                                            depth = 0.12f,
                                        ),
                                        onClick = {
                                            // 写盘带 fsync：放主线程会卡住点击瞬间，移到 IO 线程
                                            scope.launch {
                                                val ok = withContext(Dispatchers.IO) {
                                                    InputRemapRepository.resetGamepadMap(ctx)
                                                }
                                                if (ok) gamepadResetTick++
                                                // 写盘失败必须提示：否则用户看到「已恢复」但映射没变
                                                toast(
                                                    ctx,
                                                    if (ok) {
                                                        R.string.input_settings_reset_gamepad_done
                                                    } else {
                                                        R.string.input_settings_reset_gamepad_failed
                                                    },
                                                )
                                            }
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
    }

    renameTarget?.let { target ->
        val profile = profiles.firstOrNull { it.id == target }
        // 预填**展示名**：迁移主布局/内置默认存的是语言中立占位名（Default），
        // 直接把它填进输入框，用户点「确定」就把英文占位名固化成了正式名称。
        // 展示名未改动时不落盘（保留占位名语义，避免「改名成占位名」这类无意义写入）。
        val current = profile?.let { profileDisplayName(it) }.orEmpty()
        ProfileNameDialog(
            title = stringResource(R.string.input_settings_profile_rename),
            initial = current,
            onConfirm = { newName ->
                renameTarget = null
                if (newName.isNotBlank() && newName.trim() != current) {
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) {
                            InputRemapRepository.renameProfile(ctx, target, newName)
                        }
                        // 写盘失败必须提示，否则用户只看到弹窗关闭、名称未变
                        if (!ok) toast(ctx, R.string.input_settings_profile_rename_failed)
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
                    val created = withContext(Dispatchers.IO) {
                        val id = InputRemapRepository.newProfileId(ctx)
                        val unique = InputRemapRepository.uniqueProfileName(ctx, trimmed)
                        InputRemapRepository.createProfile(ctx, id, unique)
                    }
                    // 写盘失败必须提示：否则弹窗关闭、列表没变化，用户只会以为没反应
                    if (created == null) {
                        toast(ctx, R.string.input_settings_profile_new_failed)
                    } else {
                        InputRemapRepository.setGlobalProfileId(ctx, created.id)
                    }
                    reloadProfiles()
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
                        profiles.firstOrNull { it.id == target }?.let { profileDisplayName(it) }.orEmpty(),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                DialogTextButton(
                    text = stringResource(R.string.common_delete),
                    onClick = {
                        deleteTarget = null
                        // 「增强」档：确认即开始原位收起（不等写盘），记下原位置供删除后暂留
                        val exitIndex = profiles.indexOfFirst { it.id == target }
                        if (MotionMode.enhanced && exitIndex >= 0) {
                            exitingProfiles[target] = IndexedValue(exitIndex, profiles[exitIndex])
                        }
                        scope.launch {
                            val deleted = withContext(Dispatchers.IO) {
                                InputRemapRepository.deleteProfile(ctx, target)
                            }
                            // 删除失败：该行原路展开回来（与下方提示同时发生）
                            if (!deleted) exitingProfiles.remove(target)
                            // 删除失败（占用/权限）必须提示，否则列表条目还在、用户以为点了没反应
                            if (!deleted) toast(ctx, R.string.input_settings_profile_delete_failed)
                            // 只有删除成功才回退全局选择：失败时把选择切到 default 会
                            // 让用户失去当前方案（列表里它还在，但生效方案已被悄悄改掉）
                            if (deleted && profileId == target) {
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
    // 方案名是随方案持久化的用户数据。两类方案存的是语言中立占位名，此处按 id 本地化展示；
    // 迁移主布局用**不同文案**（默认方案（迁移）），否则它与内置默认方案同名、列表里
    // 出现两个无法区分的条目。用户改过名的方案原样显示（见 isLegacyMain 的判定）
    val displayName = profileDisplayName(profile)
    Column(Modifier.fillMaxWidth()) {
        // 选中指示过渡：「使用中」摘要的出现 / 消失与重命名后的标题都走模糊替换，行高随之弹簧过渡
        // （「普通」档瞬时替换，与原版一致）
        BlurCrossfade(
            targetState = displayName to selected,
            modifier = Modifier.fillMaxWidth(),
            label = "profileHeader",
        ) { (name, inUse) ->
        ArrowPreference(
            title = name,
            summary = if (inUse) stringResource(R.string.input_settings_profile_in_use) else null,
            onClick = onEdit,
        )
        }
        // 「使用」按钮随选中态出现 / 消失时，整排动作按钮重新排布：模糊替换而不是瞬间挤位
        BlurCrossfade(
            targetState = selected,
            modifier = Modifier.fillMaxWidth(),
            label = "profileActions",
        ) { inUse ->
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
            if (!inUse) {
                Box(Modifier.weight(1f)) {
                    NoRippleTextButton(text = stringResource(R.string.input_settings_profile_use), onClick = onUse)
                }
            }
            if (onRename != null) {
                Box(Modifier.weight(1f)) {
                    NoRippleTextButton(text = stringResource(R.string.input_settings_profile_rename), onClick = onRename)
                }
            }
            Box(Modifier.weight(1f)) {
                NoRippleTextButton(text = stringResource(R.string.input_settings_profile_copy), onClick = onCopy)
            }
            Box(Modifier.weight(1f)) {
                NoRippleTextButton(text = stringResource(R.string.input_settings_profile_export), onClick = onExport)
            }
            if (onDelete != null) {
                Box(Modifier.weight(1f)) {
                    NoRippleTextButton(
                        text = stringResource(R.string.input_settings_profile_delete),
                        // 删除是破坏性动作：语义色取 colorScheme.error（AGENTS.md 3.3）
                        contentColor = MaterialTheme.colorScheme.error,
                        onClick = onDelete,
                    )
                }
            }
        }
        }
    }
}

/**
 * 方案行的增删动效（仅「增强」档；「普通」档直接显示 [content]，与原版一致）：
 * 新增的方案（[fresh]）从 0 高度弹簧展开、由糊变清；被删除的方案（[exiting]）原路收起，
 * 收起完成且已不在列表（[inList] = false）时回调 [onExited] 真正移除。被打断（删除失败）时从当前高度原路展开回来。
 */
@Composable
private fun ProfileRowMotion(
    fresh: Boolean,
    exiting: Boolean,
    inList: Boolean,
    onExited: () -> Unit,
    content: @Composable () -> Unit,
) {
    if (!MotionMode.enhanced) {
        content()
        return
    }
    // 只在首次组合时决定起点：新增行从隐藏开始展开，其余行直接以原样显示
    val rowState = remember { MutableTransitionState(!fresh) }
    val currentInList by rememberUpdatedState(inList)
    val currentOnExited by rememberUpdatedState(onExited)
    if (exiting) {
        LaunchedEffect(rowState) {
            // 收起播完、且删除已落盘（列表里没有它）才移除暂留行：写盘慢于动画时保持收起态等待
            snapshotFlow { rowState.isIdle && !rowState.currentState && !currentInList }.first { it }
            currentOnExited()
        }
    }
    ExpandSectionVisibility(
        visible = !exiting,
        visibleState = rowState,
        label = "profileRow",
    ) {
        content()
    }
}

/** 把退场中的已删除方案按删除前的位置插回列表（已重新出现在列表里的不重复插入）。 */
private fun withExitingProfiles(
    profiles: List<InputRemapRepository.ProfileSummary>,
    exiting: Map<String, IndexedValue<InputRemapRepository.ProfileSummary>>,
): List<InputRemapRepository.ProfileSummary> {
    if (exiting.isEmpty()) return profiles
    val result = profiles.toMutableList()
    exiting.values.sortedBy { it.index }.forEach { ghost ->
        if (result.none { it.id == ghost.value.id }) {
            result.add(ghost.index.coerceIn(0, result.size), ghost.value)
        }
    }
    return result
}

/**
 * 方案展示名：内置默认与迁移主布局存的是语言中立占位名，按 id 本地化展示；
 * 用户改过名的方案原样显示（见 isLegacyMain 的判定）。
 *
 * `internal`：单游戏设置页的「按键方案」行复用同一套展示名（同包）。
 */
@Composable
internal fun profileDisplayName(profile: InputRemapRepository.ProfileSummary): String = when {
    profile.isDefault -> stringResource(R.string.input_settings_profile_default)
    profile.isLegacyMain -> stringResource(R.string.input_settings_profile_default_migrated)
    else -> profile.name
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
    // 弹窗在独立窗口里组合，拿不到调用点的 MiuixSettingsTheme 作用域：不包一层的话
    // AppSearchField/SwitchPreference 这类 Miuix 组件会落到库默认浅色板
    MiuixSettingsTheme {
        AppAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = title, style = MaterialTheme.typography.titleMedium) },
        text = {
            // 弹窗点击反馈规范：输入区内禁用全部点击/按压反馈（无涟漪）
            CompositionLocalProvider(LocalIndication provides NoIndication) {
                Column {
                    // 与其他命名弹窗（RenameGameDialog）对齐：语义图标 + 正文字号
                    AppSearchField(
                        query = value,
                        onQueryChange = { value = it },
                        onSearch = { onConfirm(value) },
                        leadingIcon = painterResource(R.drawable.ic_sheet_rename),
                        iconContentDescription = title,
                        imeAction = ImeAction.Done,
                        textStyle = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = stringResource(R.string.input_settings_profile_name_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        // 弹窗编排：输入框先到、说明文字随后轻轻升起（只改图层，停稳后与原样一致）
                        modifier = Modifier
                            .staggeredEntrance(2, distance = 10.dp, stepMillis = 45L)
                            .padding(top = 8.dp),
                    )
                }
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
}

/** 方案 JSON 导入上限（字符）：正常方案只有几 KB，仅用于挡住异常大文件。 */
private const val MAX_IMPORT_CHARS = 512 * 1024

private fun toast(context: Context, messageRes: Int) {
    AppNotice.makeText(context, messageRes, Toast.LENGTH_SHORT).show()
}
