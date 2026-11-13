package com.tyranor.next.ui.archive

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.animation.AnimatedContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tyranor.next.R
import com.tyranor.next.core.game.model.GamePathUtils
import com.tyranor.next.core.unpack.ScannedArchive
import com.tyranor.next.core.unpack.baseNameWithoutExtension
import com.tyranor.next.theme.AppComponentShape
import com.tyranor.next.theme.MiuixSettingsTheme
import com.tyranor.next.theme.NavWhite
import com.tyranor.next.theme.PageSecondaryTextColor
import com.tyranor.next.theme.TextColor
import com.tyranor.next.theme.glassBorder
import com.tyranor.next.ui.common.AppScreenActivity
import com.tyranor.next.ui.common.AppAlertDialog
import com.tyranor.next.ui.common.AppSecondaryTopBar
import com.tyranor.next.ui.common.DialogTextButton
import com.tyranor.next.ui.common.ByteFormats
import com.tyranor.next.ui.common.NoRippleTextButton
import com.tyranor.next.ui.common.NoRippleButton
import com.tyranor.next.ui.common.motion.BlurCrossfade
import com.tyranor.next.ui.common.motion.ExpandVisibility
import com.tyranor.next.ui.common.motion.listItemMotion
import com.tyranor.next.ui.common.motion.PressSquishDefaults
import com.tyranor.next.ui.common.motion.ProvideEntrance
import com.tyranor.next.ui.common.motion.RollingNumberText
import com.tyranor.next.ui.common.motion.bounceOnChange
import com.tyranor.next.ui.common.motion.breathing
import com.tyranor.next.ui.common.motion.skipEntranceOnDrag
import com.tyranor.next.ui.common.motion.pressSquish
import com.tyranor.next.ui.common.motion.staggeredEntrance
import com.tyranor.next.ui.common.Slider
import top.yukonga.miuix.kmp.basic.SliderDefaults
import kotlin.math.roundToInt
import com.tyranor.next.ui.common.motion.rememberLastNonNull

/** 解包 / 封包独立页：入口见应用设置。解包=选目录扫描XP3→主从预览→解到同名文件夹；封包=选目录→选压缩等级→输出同名 .xp3。 */
class ArchiveUnpackActivity : AppScreenActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setAppScreenContent {
            ArchiveScreen()
        }
    }

    companion object {
        fun createIntent(context: Context): Intent =
            Intent(context, ArchiveUnpackActivity::class.java)
    }
}

private const val TAG = "ArchiveUnpack"

private const val MAX_LISTED_ENTRIES = 2000

/** 目录展示名：优先映射真实路径（从进程视角主存储挂载根起），映射失败退回解码的文档 id 路径。 */
private fun dirLabelOf(uri: Uri): String =
    GamePathUtils.safUriToPath(uri.toString())
        ?: uri.lastPathSegment?.let { Uri.decode(it) }?.substringAfterLast(':')
            ?.takeIf { it.isNotBlank() }?.let { "/$it" }
        ?: uri.toString()

/** Android 11+ 需要「所有文件访问」才能 File 直读共享存储；低版本 legacy 存储无需。 */
private fun isAllFilesAccessGranted(): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()

/** 打开系统「所有文件访问」授权页（与 EngineLauncher 启动前校验同一模式）。 */
private fun launchAllFilesAccessSettings(context: Context) {
    val app = context.applicationContext
    val packageUri = Uri.parse("package:${app.packageName}")
    runCatching {
        app.startActivity(
            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, packageUri)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }.recoverCatching {
        app.startActivity(
            Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

/**
 * 组合权限提示、解包/封包面板与任务弹窗；运行状态和目录授权由现有 ViewModel 与系统入口管理。
 * 增强档为面板切换提供弹簧转场，普通档保留原有水平滑动与淡入淡出。
 */
@Composable
private fun ArchiveScreen(vm: ArchiveViewModel = viewModel()) {
    val context = LocalContext.current
    val appContext = context.applicationContext

    LaunchedEffect(Unit) {
        vm.cleanStaleStagingOnce(appContext)
    }

    // 「所有文件访问」授权状态：从系统设置返回本页即刷新。未授权时扫描只能走
    // SAF 回退（部分目录扫不到、输入需慢速中转），横幅引导授权后走真实路径直读。
    var allFilesGranted by remember { mutableStateOf(isAllFilesAccessGranted()) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        allFilesGranted = isAllFilesAccessGranted()
    }

    // 强制模态锁：运行中系统手势/返回关掉弹窗立即重建，操作结束（完成/取消/失败）后才放行。
    var dialogNonce by remember { mutableStateOf(0) }
    if (vm.dialogVisible) {
        key(dialogNonce) {
            ArchiveProgressDialog(vm) {
                if (vm.working) dialogNonce++ else vm.dismissDialog()
            }
        }
    }

    fun takeTreePermissions(uri: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }.onFailure { error -> Log.w(TAG, "take persistable permission failed for $uri", error) }
    }

    val pickSourceDir = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        takeTreePermissions(uri)
        vm.chooseSourceDir(appContext, uri, dirLabelOf(uri))
    }
    val pickPackDir = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        takeTreePermissions(uri)
        vm.choosePackDir(appContext, uri, dirLabelOf(uri))
    }
    val createPackFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        vm.finishSave(appContext, uri)
    }
    val pendingSave = vm.pendingSaveFile
    LaunchedEffect(pendingSave) {
        // saveDialogActive 单次消费：旋转重组合时不重复 launch 系统保存框
        if (pendingSave != null && !vm.saveDialogActive) {
            vm.markSaveDialogLaunched()
            createPackFile.launch(vm.pendingSaveName.ifBlank { "archive" })
        }
    }

    ProvideEntrance {
        Column(Modifier.fillMaxSize().skipEntranceOnDrag()) {
            AppSecondaryTopBar(title = stringResource(R.string.archive_title))

            ExpandVisibility(visible = !allFilesGranted) {
                ArchiveCard(title = stringResource(R.string.archive_permission_rationale)) {
                    NoRippleButton(
                        text = stringResource(R.string.archive_permission_grant),
                        tonal = true,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { launchAllFilesAccessSettings(appContext) },
                    )
                }
            }

            ModeTabs(mode = vm.mode, onSelect = { vm.switchMode(it) })

            // 面板占剩余空间：给关闭弹窗后的结果消息（StatusBar）留出可见区域。
            // 模式切换保留滑动 + 淡入淡出特效（与主界面水平移动切换同向语义）。
            // 面板区排在模式切换之后升起（页面主体，入场次序晚于权限卡与模式按钮）
            Box(Modifier.fillMaxWidth().weight(1f).staggeredEntrance(2)) {
                AnimatedContent(
                    targetState = vm.mode,
                    transitionSpec = { archivePaneTransition(targetState) },
                    label = "archivePane",
                ) { paneMode ->
                    when (paneMode) {
                        ArchiveMode.UNPACK -> UnpackPane(
                            vm = vm,
                            appContext = appContext,
                            onPickDir = { pickSourceDir.launch(null) },
                        )
                        ArchiveMode.PACK -> PackPane(
                            vm = vm,
                            appContext = appContext,
                            onPickDir = { pickPackDir.launch(null) },
                        )
                    }
                }
            }

            ExpandVisibility(visible = vm.message != null && !vm.dialogVisible) {
                StatusBar(vm = vm)
            }
            Spacer(Modifier.navigationBarsPadding().height(4.dp))
        }
    }
}

/** 展示解包/封包两种模式，由调用方切换任务模型；整组参与页面的错峰入场。 */
@Composable
private fun ModeTabs(mode: ArchiveMode, onSelect: (ArchiveMode) -> Unit) {
    // 横排独立小按钮（形态对齐主界面左侧纵栏的小胶囊）：玻璃面 + 描边，选中态主色浅底
    Row(
        modifier = Modifier
            .staggeredEntrance(1)
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(AppComponentShape)
            .background(NavWhite)
            .glassBorder(AppComponentShape)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        ModeTab(
            label = stringResource(R.string.archive_mode_unpack),
            selected = mode == ArchiveMode.UNPACK,
            onClick = { onSelect(ArchiveMode.UNPACK) },
        )
        ModeTab(
            label = stringResource(R.string.archive_mode_pack),
            selected = mode == ArchiveMode.PACK,
            onClick = { onSelect(ArchiveMode.PACK) },
        )
    }
}

/** 绘制单个模式选项的选中配色，并将按压形变限制在不改变静止布局的图层中。 */
@Composable
private fun ModeTab(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = rememberArchiveTabColors(selected)
    Box(
        modifier = Modifier
            .pressSquish()
            // 切到该模式时选中胶囊轻轻落一下（与背景 / 文字颜色过渡同步）
            .bounceOnChange(selected, depth = 0.08f)
            .clip(AppComponentShape)
            .background(colors.background.value)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = colors.foreground.value,
        )
    }
}

/**
 * 在目录选择提示与归档浏览器间切换；浏览器按归档身份保存选择，按条目集合重置目录展开状态。
 * 内容树只展示限定数量的可见条目，运行任务期间禁用重扫、换目录与重复解包入口。
 */
@Composable
private fun UnpackPane(vm: ArchiveViewModel, appContext: Context, onPickDir: () -> Unit) {
    BlurCrossfade(targetState = vm.sourceTreeUri == null, modifier = Modifier.fillMaxSize()) { noSourceDir ->
    if (noSourceDir) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            BigAction(
                label = stringResource(R.string.archive_pick_source_dir),
                onClick = onPickDir,
                enabled = !vm.working,
            )
        }
    } else {
        Column(Modifier.fillMaxSize()) {
            DirStrip(
                name = vm.sourceDirName,
                onRescan = { vm.rescan(appContext) },
                onRePick = onPickDir,
                enabled = !vm.working,
                rescanLabel = stringResource(R.string.archive_rescan),
                rePickLabel = stringResource(R.string.archive_change_dir),
            )

            Row(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                // 左栏：归档列表
                Column(
                    modifier = Modifier.weight(0.42f).fillMaxSize().padding(end = 6.dp),
                ) {
                    PaneHeader(stringResource(R.string.archive_archives_count, vm.archives.size))
                    LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                        itemsIndexed(vm.archives, key = { _, item -> item.id }) { index, archive ->
                            ArchiveListRow(
                                modifier = Modifier
                                    .then(listItemMotion())
                                    .staggeredEntrance(index),
                                archive = archive,
                                selected = archive.id == vm.selectedId,
                                onClick = { vm.selectArchive(appContext, archive.id) },
                            )
                        }
                    }
                }
                // 右栏：选中归档的内容
                Column(
                    modifier = Modifier.weight(0.58f).fillMaxSize().padding(start = 6.dp),
                ) {
                    // 「请选择归档」提示 ↔ 归档内容整块模糊替换，而不是瞬间换出；
                    // 退场途中沿用最后选中的归档，不会先清空再淡出
                    val selectedNow = vm.selectedArchive
                    val lastSelected = rememberLastNonNull(selectedNow)
                    BlurCrossfade(
                        targetState = selectedNow == null,
                        modifier = Modifier.fillMaxSize(),
                        label = "archiveSelection",
                    ) { noSelection ->
                    Column(Modifier.fillMaxSize()) {
                    val selected = if (noSelection) null else lastSelected
                    if (selected == null) {
                        PaneHeader(stringResource(R.string.archive_select_hint))
                    } else {
                        // 汇总只在 entries 变化时算一次：敌意大档的逐条统计不能跟随重组反复跑。
                        val (fileCount, totalSize) = remember(vm.entries) {
                            var count = 0
                            var size = 0L
                            for (entry in vm.entries) {
                                if (!entry.isDirectory) {
                                    count++
                                    size += entry.size
                                }
                            }
                            count to size
                        }
                        PaneHeader(
                            if (fileCount > 0) {
                                stringResource(R.string.archive_entries_summary).format(fileCount, formatBytes(totalSize))
                            } else {
                                selected.fileName
                            },
                        )
                        NoRippleButton(
                            text = stringResource(R.string.archive_extract_to, baseNameWithoutExtension(selected.fileName)),
                            enabled = !vm.working && vm.entriesListed,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                            onClick = { vm.extractSelected(appContext) },
                        )
                        // 展开集合随 entries 换包自动重置；默认全收起
                        val expandedDirs = remember(vm.entries) { mutableStateOf(setOf<String>()) }
                        val visibleRows = remember(vm.entries, expandedDirs.value) {
                            buildVisibleTree(vm.entries, expandedDirs.value)
                        }
                        BlurCrossfade(
                            targetState = vm.entriesListed && vm.entries.isEmpty(),
                            modifier = Modifier.fillMaxWidth().weight(1f),
                        ) { listEmpty ->
                            if (listEmpty) {
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Text(
                                        stringResource(R.string.archive_empty_list),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = PageSecondaryTextColor,
                                    )
                                }
                            } else {
                                LazyColumn(Modifier.fillMaxSize()) {
                                    // key 带索引：归档内允许重名条目，纯名字 key 会撞 Compose 崩溃
                                    itemsIndexed(visibleRows.take(MAX_LISTED_ENTRIES), key = { index, row ->
                                        "$index:" + (if (row.entry.isDirectory) "d:" else "f:") + row.entry.name
                                    }) { index, row ->
                                        EntryListRow(
                                            row,
                                            modifier = Modifier
                                                .then(listItemMotion())
                                                .staggeredEntrance(index),
                                        ) { toggle ->
                                            expandedDirs.value = if (toggle) expandedDirs.value + row.entry.name else expandedDirs.value - row.entry.name
                                        }
                                    }
                                    if (visibleRows.size > MAX_LISTED_ENTRIES) {
                                        item {
                                            // 展开 / 收起目录后，溢出计数逐位滚动
                                            RollingNumberText(
                                                "+${visibleRows.size - MAX_LISTED_ENTRIES}",
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.primary,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                    }
                    }
                }
            }
        }
    }
    }
}

@Composable
private fun PackPane(vm: ArchiveViewModel, appContext: Context, onPickDir: () -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 8.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            ArchiveCard(title = stringResource(R.string.archive_pack_pick_dir)) {
                NoRippleButton(
                    text = if (vm.packDirName.isBlank()) stringResource(R.string.archive_pack_pick_dir)
                    else vm.packDirName,
                    enabled = !vm.working,
                    leadingIcon = painterResource(R.drawable.ic_sheet_folder),
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onPickDir,
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.archive_level),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        vm.packLevel.toString(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // Slider 是 Miuix 组件，默认色读库默认 MiuixTheme（固定蓝），不随主题色；
                // 本页只有 Material 主题包裹，必须显式套 Miuix 子主题才能跟随色调轮盘。
                MiuixSettingsTheme {
                    Slider(
                        value = vm.packLevel.toFloat(),
                        onValueChange = { vm.choosePackLevel(it.roundToInt()) },
                        valueRange = 0f..9f,
                        showKeyPoints = true,
                        keyPoints = (0..9).map { it.toFloat() },
                        magnetThreshold = 0.25f,
                        hapticEffect = SliderDefaults.SliderHapticEffect.Step,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
                NoRippleButton(
                    text = stringResource(R.string.archive_pack_action),
                    enabled = !vm.working && vm.packTreeUri != null,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    onClick = { vm.pack(appContext) },
                )
            }
        }
    }
}

/** 当前目录摘要与重扫/换目录入口；目录名变化使用内容转场，操作是否可用由任务状态决定。 */
@Composable
private fun DirStrip(
    name: String,
    onRescan: () -> Unit,
    onRePick: () -> Unit,
    enabled: Boolean,
    rescanLabel: String,
    rePickLabel: String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(AppComponentShape)
            .background(NavWhite)
            .glassBorder(AppComponentShape)
            .padding(start = 12.dp, end = 2.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_sheet_folder),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        BlurCrossfade(targetState = name.ifBlank { "-" }, modifier = Modifier.weight(1f)) { shown ->
            Text(
                shown,
                style = MaterialTheme.typography.bodyMedium,
                color = TextColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        NoRippleTextButton(text = rescanLabel, enabled = enabled, onClick = onRescan)
        NoRippleTextButton(text = rePickLabel, enabled = enabled, onClick = onRePick)
    }
}

/** 将归档名称或统计摘要的替换约束在同一行内，保持两栏布局的宽度不变。 */
@Composable
private fun PaneHeader(text: String) {
    BlurCrossfade(targetState = text, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) { shown ->
        Text(
            shown,
            style = MaterialTheme.typography.bodyMedium,
            color = PageSecondaryTextColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 展示归档大小与选中态；接收列表提供的位移动画和入场修饰符，点击选择由调用方处理。 */
@Composable
private fun ArchiveListRow(archive: ScannedArchive, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = rememberArchiveRowColors(selected)
    Column(
        modifier = modifier
            .pressSquish(scale = PressSquishDefaults.CardScale)
            // 被选中的那一刻轻轻落一下
            .bounceOnChange(selected, depth = 0.05f)
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clip(AppComponentShape)
            .drawBehind { drawRect(colors.background.value) }
            .glassBorder()
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            archive.fileName,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.foreground.value,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            "XP3 · ${formatBytes(archive.size)}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 按树深度缩进条目；只有目录可点击展开并产生按压形变，普通文件只展示名称与大小。 */
@Composable
private fun EntryListRow(row: VisibleEntry, modifier: Modifier = Modifier, onToggle: (Boolean) -> Unit) {
    val (entry, depth, expanded) = row
    Row(
        modifier = modifier
            .pressSquish(enabled = entry.isDirectory, scale = PressSquishDefaults.RowScale)
            .fillMaxWidth()
            .padding(start = (depth * 14).dp)
            .padding(vertical = 1.dp)
            .clip(AppComponentShape)
            .then(if (entry.isDirectory) Modifier.clickable { onToggle(!expanded) } else Modifier),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (entry.isDirectory) {
            // 目录展开 / 收起：▸ ↔ ▾ 模糊替换（只给目录行挂转场，普通文件行保持零开销）
            BlurCrossfade(targetState = expanded, modifier = Modifier.weight(1f), label = "archiveDirToggle") { shownExpanded ->
                Text(
                    (if (shownExpanded) "▾ " else "▸ ") + entry.name.substringAfterLast('/'),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        } else {
            Text(
                "· " + entry.name.substringAfterLast('/'),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        if (!entry.isDirectory) {
            Text(
                formatBytes(entry.size),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 6.dp),
            )
        }
    }
}

/** 可见树节点：条目 + 缩进深度 + 文件夹展开态。 */
private data class VisibleEntry(val entry: EntryRow, val depth: Int, val expanded: Boolean)

/**
 * 把平铺条目（name 为归档内 `/` 分隔全路径，含派生目录）组装成按层展开的可见列表：
 * 只展开 [expanded] 中的文件夹，每层内文件夹在前、按名排序。
 */
private fun buildVisibleTree(entries: List<EntryRow>, expanded: Set<String>): List<VisibleEntry> {
    val byParent = entries.groupBy { it.name.substringBeforeLast('/', missingDelimiterValue = "") }
    val out = mutableListOf<VisibleEntry>()
    fun walk(children: List<EntryRow>, depth: Int) {
        for (entry in children.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))) {
            val isExpanded = entry.isDirectory && entry.name in expanded
            out.add(VisibleEntry(entry, depth, isExpanded))
            if (isExpanded) walk(byParent[entry.name].orEmpty(), depth + 1)
        }
    }
    walk(byParent[""].orEmpty(), 0)
    return out
}

/** 保留最后一条非空结果，直到外层退出转场完成，避免消息先清空后留下收起中的空框。 */
@Composable
private fun StatusBar(vm: ArchiveViewModel) {
    // 收起动画期间消息可能已清空：沿用最后一条，避免先消失再收起空框
    rememberLastNonNull(vm.message)?.let { text ->
        BlurCrossfade(targetState = text, modifier = Modifier.padding(top = 2.dp)) { shown ->
            Text(
                shown,
                style = MaterialTheme.typography.bodyMedium,
                color = PageSecondaryTextColor,
            )
        }
    }
}

/**
 * 运行模态弹窗：运行中显示双层字节进度（总进度 + 当前文件进度）并锁定页面，
 * 仅「取消」可点；结束后显示结果消息，「完成」按钮关闭弹窗。
 */
@Composable
private fun ArchiveProgressDialog(vm: ArchiveViewModel, onDismissRequest: () -> Unit) {
    AppAlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(stringResource(R.string.archive_title), style = MaterialTheme.typography.titleMedium) },
        text = {
            val totalProgress = archiveProgress({ vm.progress }, label = "archiveProgress")
            val fileProgress = archiveProgress({ vm.fileProgress }, label = "archiveFileProgress")
            BlurCrossfade(targetState = vm.working) { working ->
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (working) {
                    // 扫描 / 中转（不定进度）↔ 字节进度（确定进度）：整块模糊替换，而不是瞬间换条
                    BlurCrossfade(targetState = vm.progressDeterminate, label = "archiveProgressKind") { determinate ->
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (determinate) {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            LinearProgressIndicator(progress = totalProgress, modifier = Modifier.fillMaxWidth())
                            // 当前文件进度条随单文件字节数出现 / 消失时原位展开、收回
                            ExpandVisibility(visible = vm.fileBytes.second > 0, label = "archiveFileProgress") {
                                LinearProgressIndicator(progress = fileProgress, modifier = Modifier.fillMaxWidth())
                            }
                        }
                        Text(
                            progressBytesText(vm),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    }
                    }
                    // 还没有具体文件名时（扫描 / 准备中）阶段文案缓缓明灭
                    Text(
                        vm.progressName.ifBlank { vm.workingLabel },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.breathing(vm.progressName.isBlank()),
                    )
                } else {
                    Text(
                        vm.message ?: "",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            }
        },
        confirmButton = {
            // 「取消」→「完成」与正文的结果替换同步模糊切换
            BlurCrossfade(targetState = vm.working, label = "archiveDialogAction") { working ->
                if (working) {
                    DialogTextButton(stringResource(R.string.common_cancel), onClick = { vm.cancel() })
                } else {
                    DialogTextButton(stringResource(R.string.common_done), onClick = { vm.dismissDialog() })
                }
            }
        },
    )
}

/** 字节计数文本：总进度 `已写 / 总量`，双层时追加当前文件的 `已写 / 总量`。 */
private fun progressBytesText(vm: ArchiveViewModel): String {
    val (written, total) = vm.progressBytes
    val overall = if (total > 0) "${formatBytes(written)} / ${formatBytes(total)}" else formatBytes(written)
    val (fileWritten, fileTotal) = vm.fileBytes
    return if (fileTotal > 0) "$overall · ${formatBytes(fileWritten)} / ${formatBytes(fileTotal)}" else overall
}

@Composable
private fun BigAction(label: String, onClick: () -> Unit, enabled: Boolean) {
    NoRippleButton(text = label, enabled = enabled, onClick = onClick)
}

/** 承载权限提示等归档辅助内容，复用统一面板材质、圆角和首组入场次序。 */
@Composable
private fun ArchiveCard(title: String, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.staggeredEntrance(0).fillMaxWidth().glassBorder(AppComponentShape),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        colors = CardDefaults.cardColors(containerColor = NavWhite, contentColor = TextColor),
        shape = AppComponentShape,
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                content()
            }
        }
    }
}

/** 字节格式化统一走 [ByteFormats]（本文件内的 5 处调用点沿用简短别名）。 */
private fun formatBytes(bytes: Long): String = ByteFormats.formatBinarySize(bytes)
