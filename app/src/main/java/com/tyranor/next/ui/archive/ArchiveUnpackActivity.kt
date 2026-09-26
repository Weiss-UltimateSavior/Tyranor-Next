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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
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
import com.tyranor.next.theme.NavWhite
import com.tyranor.next.ui.common.AppScreenActivity
import com.tyranor.next.ui.common.AppTopBar
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.SliderDefaults
import java.util.Locale
import kotlin.math.roundToInt

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

/** 目录展示名：优先映射真实路径（从 /storage/emulated/0 起），映射失败退回解码的文档 id 路径。 */
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
        if (pendingSave != null) createPackFile.launch(vm.pendingSaveName.ifBlank { "archive" })
    }

    Column(Modifier.fillMaxSize()) {
        AppTopBar(title = stringResource(R.string.archive_title))

        if (!allFilesGranted) {
            ArchiveCard(title = stringResource(R.string.archive_permission_rationale)) {
                Button(onClick = { launchAllFilesAccessSettings(appContext) }) {
                    Text(stringResource(R.string.archive_permission_grant), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        ModeTabs(mode = vm.mode, onSelect = { vm.switchMode(it) })

        when (vm.mode) {
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

        if (vm.working || vm.message != null) {
            StatusBar(vm = vm)
        }
        Spacer(Modifier.navigationBarsPadding().height(4.dp))
    }
}

@Composable
private fun ModeTabs(mode: ArchiveMode, onSelect: (ArchiveMode) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(
            selected = mode == ArchiveMode.UNPACK,
            onClick = { onSelect(ArchiveMode.UNPACK) },
            label = { Text(stringResource(R.string.archive_mode_unpack), style = MaterialTheme.typography.bodyMedium) },
        )
        FilterChip(
            selected = mode == ArchiveMode.PACK,
            onClick = { onSelect(ArchiveMode.PACK) },
            label = { Text(stringResource(R.string.archive_mode_pack), style = MaterialTheme.typography.bodyMedium) },
        )
    }
}

@Composable
private fun UnpackPane(vm: ArchiveViewModel, appContext: Context, onPickDir: () -> Unit) {
    if (vm.sourceTreeUri == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            BigAction(
                label = stringResource(R.string.archive_pick_source_dir),
                onClick = onPickDir,
                enabled = !vm.working,
            )
        }
        return
    }

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
                    items(vm.archives, key = { it.id }) { archive ->
                        ArchiveListRow(
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
                val selected = vm.selectedArchive
                if (selected == null) {
                    PaneHeader(stringResource(R.string.archive_select_hint))
                } else {
                    val fileCount = vm.entries.count { !it.isDirectory }
                    val totalSize = vm.entries.filter { !it.isDirectory }.sumOf { it.size }
                    PaneHeader(
                        if (fileCount > 0) {
                            stringResource(R.string.archive_entries_summary).format(fileCount, formatBytes(totalSize))
                        } else {
                            selected.fileName
                        },
                    )
                    Button(
                        onClick = { vm.extractSelected(appContext) },
                        enabled = !vm.working && vm.entriesListed,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    ) {
                        Text(
                            stringResource(R.string.archive_extract_to, baseNameOf(selected.fileName)),
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    // 展开集合随 entries 换包自动重置；默认全收起
                    val expandedDirs = remember(vm.entries) { mutableStateOf(setOf<String>()) }
                    val visibleRows = remember(vm.entries, expandedDirs.value) {
                        buildVisibleTree(vm.entries, expandedDirs.value)
                    }
                    LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                        items(visibleRows.take(MAX_LISTED_ENTRIES), key = { (if (it.entry.isDirectory) "d:" else "f:") + it.entry.name }) { row ->
                            EntryListRow(row) { toggle ->
                                expandedDirs.value = if (toggle) expandedDirs.value + row.entry.name else expandedDirs.value - row.entry.name
                            }
                        }
                        if (visibleRows.size > MAX_LISTED_ENTRIES) {
                            item {
                                Text(
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

@Composable
private fun PackPane(vm: ArchiveViewModel, appContext: Context, onPickDir: () -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 8.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            ArchiveCard(title = stringResource(R.string.archive_pack_pick_dir)) {
                Button(
                    onClick = onPickDir,
                    enabled = !vm.working,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        if (vm.packDirName.isBlank()) stringResource(R.string.archive_pack_pick_dir)
                        else vm.packDirName,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
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
                Button(
                    onClick = { vm.pack(appContext) },
                    enabled = !vm.working && vm.packTreeUri != null,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                ) {
                    Text(stringResource(R.string.archive_pack_action), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

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
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            name.ifBlank { "-" },
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onRescan, enabled = enabled) {
            Text(rescanLabel, style = MaterialTheme.typography.bodyMedium)
        }
        TextButton(onClick = onRePick, enabled = enabled) {
            Text(rePickLabel, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun PaneHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    )
}

@Composable
private fun ArchiveListRow(archive: ScannedArchive, selected: Boolean, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else NavWhite)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Text(
            archive.fileName,
            style = MaterialTheme.typography.bodyMedium,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
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

@Composable
private fun EntryListRow(row: VisibleEntry, onToggle: (Boolean) -> Unit) {
    val (entry, depth, expanded) = row
    Row(
        modifier = Modifier.fillMaxWidth()
            .padding(start = (depth * 14).dp)
            .padding(vertical = 1.dp)
            .clip(RoundedCornerShape(6.dp))
            .then(if (entry.isDirectory) Modifier.clickable { onToggle(!expanded) } else Modifier),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            (if (entry.isDirectory) (if (expanded) "▾ " else "▸ ") else "· ") + entry.name.substringAfterLast('/'),
            style = MaterialTheme.typography.bodyMedium,
            color = if (entry.isDirectory) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
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

@Composable
private fun StatusBar(vm: ArchiveViewModel) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        if (vm.working) {
            if (vm.progressDeterminate) {
                LinearProgressIndicator(progress = { vm.progress }, modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    vm.progressName.ifBlank { vm.workingLabel },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { vm.cancel() }) {
                    Text(stringResource(R.string.archive_cancel), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        vm.message?.let { text ->
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

@Composable
private fun BigAction(label: String, onClick: () -> Unit, enabled: Boolean) {
    Button(onClick = onClick, enabled = enabled) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ArchiveCard(title: String, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        colors = CardDefaults.cardColors(containerColor = NavWhite),
        shape = RoundedCornerShape(8.dp),
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

private fun baseNameOf(fileName: String): String {
    val dot = fileName.lastIndexOf('.')
    return if (dot > 0) fileName.substring(0, dot) else fileName
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble() / 1024
    var unit = 0
    while (value >= 1024 && unit < units.size - 1) {
        value /= 1024
        unit++
    }
    return String.format(Locale.US, "%.1f %s", value, units[unit])
}
