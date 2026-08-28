package com.tyranor.next.ui.settings

import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import com.tyranor.next.ui.common.AppSearchField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import com.tyranor.next.R
import com.tyranor.next.core.settings.AppSettingsStore
import com.tyranor.next.core.settings.EngineSettingsStore
import com.tyranor.next.core.game.scan.EngineScanner
import com.tyranor.next.theme.AppThemeColors
import com.tyranor.next.theme.MiuixSettingsTheme
import com.tyranor.next.theme.NavWhite
import com.tyranor.next.ui.common.AppNavItem
import com.tyranor.next.ui.common.AppAlertDialog
import com.tyranor.next.ui.common.TopBarIcon
import com.tyranor.next.ui.common.glassNavBottomInset
import com.tyranor.next.core.updater.GitHubUpdateChecker
import com.tyranor.next.core.updater.UpdateCheckResult
import com.tyranor.next.ui.cover.CoverScraperSettingsActivity
import com.tyranor.next.ui.game.startActivityWithPageTransition
import java.io.File
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card as MiuixCard
import top.yukonga.miuix.kmp.basic.Scaffold as MiuixScaffold
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.SliderDefaults
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 设置页：只展示各引擎全局设置入口，具体设置内容由独立 Activity 承载。列表项采用 Miuix Card + Preference 体系。 */
@Composable
fun SettingsScreen(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var checkingUpdate by remember { mutableStateOf(false) }
    var updateAvailable by remember { mutableStateOf<UpdateCheckResult.UpdateAvailable?>(null) }
    var showGroupDialog by remember { mutableStateOf(false) }
    var showScanDirs by remember { mutableStateOf(false) }
    var scanDirs by remember { mutableStateOf(EngineScanner.loadRoots(ctx)) }
    var showPathDialog by remember { mutableStateOf(false) }
    var pathInput by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        EngineScanner.rootsRevision.collect {
            scanDirs = withContext(Dispatchers.IO) { EngineScanner.loadRoots(ctx) }
        }
    }
    val dirPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { u ->
            runCatching {
                ctx.contentResolver.takePersistableUriPermission(
                    u,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            EngineScanner.saveRoot(ctx, u)
            scanDirs = EngineScanner.loadRoots(ctx)
        }
    }

    fun checkUpdate() {
        if (checkingUpdate) return
        checkingUpdate = true
        scope.launch {
            when (val result = GitHubUpdateChecker.check(ctx)) {
                is UpdateCheckResult.UpdateAvailable -> updateAvailable = result
                is UpdateCheckResult.UpToDate -> {
                    Toast.makeText(ctx, "已经是最新版本", Toast.LENGTH_SHORT).show()
                }
                is UpdateCheckResult.Failed -> {
                    Toast.makeText(ctx, "检查更新失败：${result.message}", Toast.LENGTH_SHORT).show()
                }
            }
            checkingUpdate = false
        }
    }

    MiuixSettingsTheme {
        MiuixScaffold(
            modifier = modifier,
            containerColor = MiuixTheme.colorScheme.background,
            contentWindowInsets = WindowInsets(0.dp),
            topBar = { SettingsTopBar("设置") },
        ) { innerPadding ->
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
                contentPadding = PaddingValues(top = innerPadding.calculateTopPadding() + 12.dp, bottom = 24.dp + glassNavBottomInset()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    MiuixCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 8.dp) {
                        Column(Modifier.padding(vertical = 4.dp)) {
                            ArrowPreference(
                                title = "游戏目录添加",
                                summary = "${scanDirs.size} 个目录",
                                onClick = { showScanDirs = true },
                            )
                            var depth by remember { mutableIntStateOf(AppSettingsStore.getScanDepth(ctx)) }
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    "扫描深度",
                                    style = MaterialTheme.typography.titleMedium,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    "$depth 级",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Slider(
                                value = depth.toFloat(),
                                onValueChange = { depth = it.roundToInt().coerceIn(1, 5) },
                                onValueChangeFinished = { AppSettingsStore.setScanDepth(ctx, depth) },
                                valueRange = 1f..5f,
                                showKeyPoints = true,
                                keyPoints = (1..5).map { it.toFloat() },
                                magnetThreshold = 0.25f,
                                hapticEffect = SliderDefaults.SliderHapticEffect.Step,
                                modifier = Modifier.padding(horizontal = 16.dp),
                            )
                            var gameSort by remember { mutableStateOf(AppSettingsStore.getGameSort(ctx)) }
                            val gameSortModes = listOf(
                                AppSettingsStore.GAME_SORT_ALPHA to "字母大小",
                                AppSettingsStore.GAME_SORT_BRACKET_TAG to "括号标签",
                            )
                            val sortIndex = gameSortModes.indexOfFirst { it.first == gameSort }
                                .let { if (it < 0) 0 else it }
                            OverlayDropdownPreference(
                                title = "游戏排序",
                                items = gameSortModes.map { it.second },
                                selectedIndex = sortIndex,
                                onSelectedIndexChange = { index ->
                                    gameSortModes.getOrNull(index)?.first?.let { sort ->
                                        gameSort = sort
                                        AppSettingsStore.setGameSort(ctx, sort)
                                    }
                                },
                            )
                        }
                    }
                }
                item {
                    MiuixCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 8.dp) {
                        Column(Modifier.padding(vertical = 4.dp)) {
                            ArrowPreference(
                                title = "引擎设置",
                                startAction = { SettingsItemIcon(R.drawable.ic_engine_manage) },
                                onClick = { startActivityWithPageTransition(ctx, EngineSettingsMenuActivity.createIntent(ctx)) },
                            )
                        }
                    }
                }
                item {
                    MiuixCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 8.dp) {
                        Column(Modifier.padding(vertical = 4.dp)) {
                            ArrowPreference(
                                title = "应用设置",
                                summary = "有关应用内的各项配置",
                                startAction = { SettingsItemIcon(R.drawable.ic_settings_app) },
                                onClick = { startActivityWithPageTransition(ctx, AppSettingsActivity.createIntent(ctx)) },
                            )
                            ArrowPreference(
                                title = "封面刮削",
                                summary = "设置多源封面来源、顺序与授权",
                                startAction = { SettingsItemIcon(R.drawable.ic_game_cover) },
                                onClick = { startActivityWithPageTransition(ctx, CoverScraperSettingsActivity.createIntent(ctx)) },
                            )
                            ArrowPreference(
                                title = if (checkingUpdate) "正在检查更新" else "更新检查",
                                summary = "对项目Github获取更新信息",
                                startAction = { SettingsItemIcon(R.drawable.ic_settings_update) },
                                onClick = { checkUpdate() },
                            )
                            ArrowPreference(
                                title = "加入群聊",
                                summary = "跳转到官方聊天群组",
                                startAction = { SettingsItemIcon(R.drawable.ic_settings_group) },
                                onClick = {
                                    showGroupDialog = true
                                },
                            )
                        }
                    }
                }
                item { BottomInsetSpacer() }
            }
        }
    }

    if (showScanDirs) {
        AppAlertDialog(
            onDismissRequest = { showScanDirs = false },
            title = { Text("游戏目录", style = MaterialTheme.typography.titleMedium) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (scanDirs.isEmpty()) {
                        Text(
                            "暂无游戏目录",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    scanDirs.forEach { dir ->
                        // 目录被改名/删除或权限失效后标记为已失效，提示用户手动清理。
                        // DocumentFile.isDirectory 可能触发 binder 调用，放到 IO 线程执行。
                        val valid by produceState(initialValue = false, dir) {
                            value = withContext(Dispatchers.IO) { isScanDirValid(ctx, dir) }
                        }
                        // Miuix 风格条目：圆角卡片 + 文件夹图标 + 目录名 + 删除按钮
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(NavWhite)
                                .padding(start = 16.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Folder,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(22.dp),
                            )
                            Text(
                                if (valid) scanDirName(ctx, dir) else "${scanDirName(ctx, dir)}（已失效）",
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (valid) {
                                    MaterialTheme.colorScheme.onSurface
                                } else {
                                    MaterialTheme.colorScheme.error
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                            )
                            TextButton(
                                onClick = {
                                    EngineScanner.removeRootAndGames(ctx, android.net.Uri.parse(dir))
                                    scanDirs = EngineScanner.loadRoots(ctx)
                                },
                            ) {
                                Text("删除", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        onClick = { showPathDialog = true },
                        modifier = Modifier.padding(end = 8.dp),
                    ) { Text("输入路径") }
                    TextButton(
                        onClick = { dirPicker.launch(null) },
                        modifier = Modifier.padding(end = 8.dp),
                    ) { Text("添加目录") }
                    TextButton(onClick = { showScanDirs = false }) { Text("完成") }
                }
            },
        )
    }

    if (showPathDialog) {
        AppAlertDialog(
            onDismissRequest = { showPathDialog = false },
            title = { Text("输入文件夹路径", style = MaterialTheme.typography.titleMedium) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppSearchField(
                        query = pathInput,
                        onQueryChange = { pathInput = it },
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = MiuixTheme.textStyles.main,
                    )
                }
            },
            confirmButton = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        onClick = {
                            val trimmed = pathInput.trim()
                            val target = File(trimmed)
                            val ok = runCatching { target.isDirectory }.getOrDefault(false)
                            if (ok) {
                                EngineScanner.saveRoot(ctx, trimmed)
                                scanDirs = EngineScanner.loadRoots(ctx)
                                showPathDialog = false
                                pathInput = ""
                            } else {
                                Toast.makeText(ctx, "路径不存在或不是文件夹", Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier.padding(end = 8.dp),
                    ) { Text("确定") }
                    TextButton(onClick = { showPathDialog = false }) { Text("取消") }
                }
            },
        )
    }

    if (showGroupDialog) {
        AppAlertDialog(
            onDismissRequest = { showGroupDialog = false },
            title = { Text("加入群聊", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    AppNavItem("企鹅群聊", leadingIcon = R.drawable.ic_group_qq) {
                        showGroupDialog = false
                        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://qm.qq.com/q/M9JH8A9Yys")))
                    }
                    AppNavItem("飞机频道", leadingIcon = R.drawable.ic_group_telegram) {
                        showGroupDialog = false
                        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/tyranornext")))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showGroupDialog = false }) { Text("取消") }
            },
        )
    }

    updateAvailable?.let { update ->
        AppAlertDialog(
            onDismissRequest = { updateAvailable = null },
            title = { Text("发现新版本", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "当前版本：${update.currentVersion}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MiuixTheme.colorScheme.onBackground,
                    )
                    Text(
                        "最新版本：${update.latestVersion}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MiuixTheme.colorScheme.onBackground,
                    )
                    Text(
                        "是否跳转到 GitHub 发布页下载新版本？",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MiuixTheme.colorScheme.onBackground,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { updateAvailable = null }) { Text("取消") }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        updateAvailable = null
                        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(update.releaseUrl)))
                    },
                ) { Text("去下载") }
            },
        )
    }
}

@Composable
internal fun EngineSettingsDetailScreen(kind: EngineSettingsKind) {
    val ctx = LocalContext.current

    var krVersion by remember { mutableStateOf(EngineSettingsStore.getKrEngineVersion(ctx)) }
    var krKernel by remember { mutableStateOf(EngineSettingsStore.getKrKernel(ctx)) }
    var krScoped by remember { mutableStateOf(EngineSettingsStore.isKrScopedSaveDir(ctx)) }
    var krFont by remember { mutableStateOf(EngineSettingsStore.getKrDefaultFont(ctx)) }
    var krForceFont by remember { mutableStateOf(EngineSettingsStore.isKrForceDefaultFont(ctx)) }
    var krRenderer by remember { mutableStateOf(EngineSettingsStore.getKrRenderer(ctx)) }
    var krDrawThread by remember { mutableStateOf(EngineSettingsStore.getKrSoftwareDrawThread(ctx)) }
    var krSwCompress by remember { mutableStateOf(EngineSettingsStore.getKrSoftwareCompressTex(ctx)) }
    var krOglCompress by remember { mutableStateOf(EngineSettingsStore.getKrOglCompressTex(ctx)) }
    var krMem by remember { mutableStateOf(EngineSettingsStore.getKrMemUsage(ctx)) }
    var krTexsize by remember { mutableStateOf(EngineSettingsStore.getKrOglMaxTexsize(ctx)) }
    var krAccurate by remember { mutableStateOf(EngineSettingsStore.getKrOglAccurateRender(ctx)) }
    var krFps by remember { mutableStateOf(EngineSettingsStore.getKrFpsLimit(ctx)) }

    var ons by remember { mutableStateOf(EngineSettingsStore.loadOns(ctx)) }

    var artVersion by remember { mutableStateOf(EngineSettingsStore.getArtEngineVersion(ctx)) }
    var artRotate by remember { mutableStateOf(EngineSettingsStore.isArtRotateScreen(ctx)) }
    var artPatch by remember { mutableStateOf(EngineSettingsStore.getArtAutoPatch(ctx)) }

    var tyExternal by remember { mutableStateOf(EngineSettingsStore.isTyranoExternalNetwork(ctx)) }
    var tyScoped by remember { mutableStateOf(EngineSettingsStore.isTyranoScopedSaveDir(ctx)) }
    var rpgMakerMod by remember { mutableStateOf(EngineSettingsStore.isRpgMakerModEnabled(ctx)) }
    var renpyVersion by remember { mutableStateOf(EngineSettingsStore.getRenpyVersion(ctx)) }

    val fontLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            val path = importFont(ctx, uri)
            if (path != null) {
                krFont = path
            }
        }
    }

    val isSdl3 = krKernel == EngineSettingsStore.KERNEL_KRKRSDL3
    val krIs134126 = krVersion == EngineSettingsStore.KR_134 || krVersion == EngineSettingsStore.KR_126

    // 编辑→保存模型：控件仅更新本地状态，点顶部保存按钮才统一写盘
    fun saveAll() {
        EngineSettingsStore.setKrEngineVersion(ctx, krVersion)
        EngineSettingsStore.setKrKernel(ctx, krKernel)
        EngineSettingsStore.setKrScopedSaveDir(ctx, krScoped)
        EngineSettingsStore.setKrDefaultFont(ctx, krFont)
        EngineSettingsStore.setKrForceDefaultFont(ctx, krForceFont)
        EngineSettingsStore.setKrRenderer(ctx, krRenderer)
        EngineSettingsStore.setKrSoftwareDrawThread(ctx, krDrawThread)
        EngineSettingsStore.setKrSoftwareCompressTex(ctx, krSwCompress)
        EngineSettingsStore.setKrOglCompressTex(ctx, krOglCompress)
        EngineSettingsStore.setKrMemUsage(ctx, krMem)
        EngineSettingsStore.setKrOglMaxTexsize(ctx, krTexsize)
        EngineSettingsStore.setKrOglAccurateRender(ctx, krAccurate)
        EngineSettingsStore.setKrFpsLimit(ctx, krFps)
        EngineSettingsStore.saveOns(ctx, ons)
        EngineSettingsStore.setArtEngineVersion(ctx, artVersion)
        EngineSettingsStore.setArtRotateScreen(ctx, artRotate)
        EngineSettingsStore.setArtAutoPatch(ctx, artPatch)
        EngineSettingsStore.setTyranoExternalNetwork(ctx, tyExternal)
        EngineSettingsStore.setTyranoScopedSaveDir(ctx, tyScoped)
        EngineSettingsStore.setRpgMakerModEnabled(ctx, rpgMakerMod)
        EngineSettingsStore.setRenpyVersion(ctx, renpyVersion)
    }

    MiuixSettingsTheme {
        MiuixScaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = MiuixTheme.colorScheme.background,
            contentWindowInsets = WindowInsets(0.dp),
            topBar = {
                Column(modifier = Modifier.fillMaxWidth().background(MiuixTheme.colorScheme.background)) {
                    Column(modifier = Modifier.fillMaxWidth().statusBarsPadding()) {
                        Row(
                            modifier = Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                kind.title,
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = MiuixTheme.colorScheme.onBackground,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            TopBarIcon(painterResource(R.drawable.ic_save), "保存设置", MiuixTheme.colorScheme.primary) {
                                saveAll()
                                android.widget.Toast.makeText(ctx, "引擎设置已保存", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
            },
        ) { innerPadding ->
            LazyListPlaceholder(
                kind,
                krVersion, krKernel, krScoped, krFont, krForceFont, krRenderer, krDrawThread,
                krSwCompress, krOglCompress, krMem, krTexsize, krAccurate, krFps, isSdl3, krIs134126,
                ons, artVersion, artRotate, artPatch, tyExternal, tyScoped, rpgMakerMod, renpyVersion, fontLauncher,
                topInset = innerPadding.calculateTopPadding(),
                onKrVersion = { krVersion = it },
                onKrKernel = { krKernel = it },
                onKrScoped = { krScoped = it },
                onKrForceFont = { krForceFont = it },
                onKrRenderer = { krRenderer = it },
                onKrDrawThread = { krDrawThread = it },
                onKrSwCompress = { krSwCompress = it },
                onKrOglCompress = { krOglCompress = it },
                onKrMem = { krMem = it },
                onKrTexsize = { krTexsize = it },
                onKrAccurate = { krAccurate = it },
                onKrFps = { krFps = it },
                onResetKrFont = { krFont = "" },
                onOns = { ons = it },
                onArtVersion = { artVersion = it },
                onArtRotate = { artRotate = it },
                onArtPatch = { artPatch = it },
                onTyExternal = { tyExternal = it },
                onTyScoped = { tyScoped = it },
                onRpgMakerMod = { rpgMakerMod = it },
                onRenpyVersion = { renpyVersion = it },
            )
        }
    }
}

@Composable
private fun SettingsItemIcon(@DrawableRes iconRes: Int) {
    Image(
        painter = painterResource(iconRes),
        contentDescription = null,
        // 深色模式下整体染白，保证低亮度背景上的可读性
        colorFilter = if (AppThemeColors.isDark) ColorFilter.tint(Color.White) else null,
        modifier = Modifier.padding(end = 6.dp).size(24.dp),
    )
}

/** 顶部栏：遵守全局规范（Column + 页面背景色 + statusBarsPadding + 64dp 标题区，沉浸式）。 */
@Composable
private fun SettingsTopBar(title: String) {
    Column(modifier = Modifier.fillMaxWidth().background(MiuixTheme.colorScheme.background)) {
        Column(modifier = Modifier.fillMaxWidth().statusBarsPadding()) {
            Row(
                modifier = Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MiuixTheme.colorScheme.onBackground, modifier = Modifier.weight(1f))
            }
        }
    }
}

/** 列表底部占位：避让系统导航栏。 */
@Composable
private fun BottomInsetSpacer() {
    Box(Modifier.fillMaxWidth().height(WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()))
}

private typealias FontPickerLauncher = androidx.activity.compose.ManagedActivityResultLauncher<String, Uri?>

@Composable
private fun LazyListPlaceholder(
    kind: EngineSettingsKind,
    krVersion: String, krKernel: String, krScoped: Boolean, krFont: String, krForceFont: Boolean,
    krRenderer: String, krDrawThread: String, krSwCompress: String, krOglCompress: String,
    krMem: String, krTexsize: String, krAccurate: String, krFps: String, isSdl3: Boolean, krIs134126: Boolean,
    ons: EngineSettingsStore.Ons, artVersion: String, artRotate: Boolean, artPatch: String,
    tyExternal: Boolean, tyScoped: Boolean, rpgMakerMod: Boolean, renpyVersion: String, fontLauncher: FontPickerLauncher,
    topInset: Dp,
    onKrVersion: (String) -> Unit, onKrKernel: (String) -> Unit, onKrScoped: (Boolean) -> Unit,
    onKrForceFont: (Boolean) -> Unit, onKrRenderer: (String) -> Unit, onKrDrawThread: (String) -> Unit,
    onKrSwCompress: (String) -> Unit, onKrOglCompress: (String) -> Unit, onKrMem: (String) -> Unit,
    onKrTexsize: (String) -> Unit, onKrAccurate: (String) -> Unit, onKrFps: (String) -> Unit,
    onResetKrFont: () -> Unit, onOns: (EngineSettingsStore.Ons) -> Unit,
    onArtVersion: (String) -> Unit, onArtRotate: (Boolean) -> Unit, onArtPatch: (String) -> Unit,
    onTyExternal: (Boolean) -> Unit, onTyScoped: (Boolean) -> Unit, onRpgMakerMod: (Boolean) -> Unit,
    onRenpyVersion: (String) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
        contentPadding = PaddingValues(top = topInset + 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (kind == EngineSettingsKind.KRKR) item {
            EngineCard("KRKR") {
                SwitchPreference(title = "独立存档目录", checked = krScoped, onCheckedChange = onKrScoped)
                DropdownRow("引擎版本", KR_SELECT_MAP, krVersion, onKrVersion)
                DropdownRow("引擎内核", KR_KERNEL_MAP, krKernel, onKrKernel)
            }
        }

        if (kind == EngineSettingsKind.KRKR) item {
            EngineCard("渲染") {
                if (!isSdl3) {
                    SwitchPreference(title = "OpenGL 精确渲染", checked = krAccurate == "1", onCheckedChange = { b -> onKrAccurate(if (b) "1" else "0") })
                    EnumSliderRow("内存用量", KR_MEM_MAP, krMem, onKrMem)
                }
                val rendererOptions = if (isSdl3) KR_SDL3_RENDERER_MAP else KR_RENDERER_MAP
                val selectedRenderer = if (isSdl3) {
                    krRenderer.ifEmpty { EngineSettingsStore.RENDERER_OPENGL }
                } else {
                    krRenderer.ifEmpty { "default" }
                }
                DropdownRow("渲染器", rendererOptions, selectedRenderer) {
                    onKrRenderer(if (!isSdl3 && it == "default") "" else it)
                }
                if (!isSdl3 && (krRenderer == "" || krRenderer == EngineSettingsStore.RENDERER_SOFTWARE)) {
                    EnumSliderRow("软件渲染线程数", KR_THREAD_MAP, krDrawThread, onKrDrawThread)
                    DropdownRow("软件纹理压缩", KR_SW_COMPRESS_MAP, krSwCompress, onKrSwCompress)
                }
                if (!isSdl3 && !krIs134126) {
                    EnumSliderRow("FPS 限制", KR_FPS_MAP, krFps, onKrFps)
                }
                if (!isSdl3 && (krRenderer == "" || krRenderer == EngineSettingsStore.RENDERER_OPENGL)) {
                    DropdownRow("OpenGL 纹理压缩", KR_OGL_COMPRESS_MAP, krOglCompress, onKrOglCompress)
                    EnumSliderRow("最大纹理尺寸", KR_TEXSIZE_MAP, krTexsize, onKrTexsize)
                }
            }
        }

        if (kind == EngineSettingsKind.KRKR && !isSdl3) item {
            EngineCard("字体") {
                FontRow("默认字体", krFont.ifEmpty { "内置字体" }, onResetKrFont, { fontLauncher.launch("*/*") })
                if (krVersion != EngineSettingsStore.KR_126) {
                    SwitchPreference(title = "强制使用默认字体", checked = krForceFont, onCheckedChange = onKrForceFont)
                }
            }
        }

        if (kind == EngineSettingsKind.ONS) item {
            EngineCard("ONS") {
                SwitchPreference(title = "独立存档目录", checked = ons.scopedSaveDir, onCheckedChange = { b -> onOns(ons.copy(scopedSaveDir = b)) })
                SwitchPreference(title = "全屏拉伸", checked = ons.stretchFull, onCheckedChange = { b -> onOns(ons.copy(stretchFull = b)) })
                SwitchPreference(title = "忽略刘海", checked = ons.ignoreCutout, onCheckedChange = { b -> onOns(ons.copy(ignoreCutout = b)) })
                SwitchPreference(title = "禁用视频", checked = ons.disableVideo, onCheckedChange = { b -> onOns(ons.copy(disableVideo = b)) })
                SwitchPreference(title = "画面锐化", checked = ons.sharpness, onCheckedChange = { b -> onOns(ons.copy(sharpness = b)) })
                if (ons.sharpness) {
                    EnumSliderRow("锐化强度", ONS_SHARPNESS_MAP, ons.sharpnessValue) {
                        onOns(ons.copy(sharpnessValue = it))
                    }
                }
                DropdownRow("文本编码", ONS_ENCODING_MAP, EngineSettingsStore.normalizeEncoding(ons.encoding)) {
                    onOns(ons.copy(encoding = it))
                }
            }
        }

        if (kind == EngineSettingsKind.ARTEMIS) item {
            EngineCard("Artemis") {
                DropdownRow("引擎版本", ART_VERSION_MAP, artVersion, onArtVersion)
                SwitchPreference(title = "画面反转", checked = artRotate, onCheckedChange = onArtRotate)
                DropdownRow("自动补丁", ART_PATCH_MAP, artPatch, onArtPatch)
            }
        }

        if (kind == EngineSettingsKind.TYRANO) item {
            EngineCard("Tyrano") {
                // RPG Maker Web 与 Tyrano 共用同一套 WebView 宿主开关，避免同类引擎重复配置。
                SwitchPreference(title = "允许加载外部网络资源", checked = tyExternal, onCheckedChange = onTyExternal)
                SwitchPreference(title = "独立存档目录", checked = tyScoped, onCheckedChange = onTyScoped)
            }
        }

        if (kind == EngineSettingsKind.RPG_MAKER) item {
            EngineCard("RPG Maker MV/MZ") {
                SwitchPreference(title = "允许加载外部网络资源", checked = tyExternal, onCheckedChange = onTyExternal)
                SwitchPreference(title = "独立存档目录", checked = tyScoped, onCheckedChange = onTyScoped)
                SwitchPreference(title = "游戏修改器", checked = rpgMakerMod, onCheckedChange = onRpgMakerMod)
            }
        }

        if (kind == EngineSettingsKind.RENPY) item {
            EngineCard("Ren'Py") {
                DropdownRow("引擎版本", RENPY_VERSION_MAP, renpyVersion, onRenpyVersion)
                Text(
                    "Ren'Py 使用外置 APK 引擎模块，按指定版本打开对应的插件。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MiuixTheme.colorScheme.onBackground,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }

        item { BottomInsetSpacer() }
    }
}

@Composable
private fun EngineCard(header: String, content: @Composable () -> Unit) {
    MiuixCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 8.dp) {
        Column(Modifier.padding(vertical = 6.dp)) {
            Text(
                header,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            )
            content()
        }
    }
}

/** 单选下拉行：Miuix OverlayDropdownPreference，点击展开覆盖式选项浮层，选中即回填。 */
@Composable
private fun DropdownRow(
    label: String,
    options: List<Pair<String, String>>,
    current: String,
    onSelect: (String) -> Unit,
) {
    val keys = options.map { it.first }
    val labels = options.map { it.second }
    val index = keys.indexOf(current).takeIf { it >= 0 } ?: 0
    OverlayDropdownPreference(
        title = label,
        items = labels,
        selectedIndex = index,
        onSelectedIndexChange = { onSelect(keys[it]) },
    )
}

/**
 * 档次/数字滑杆行：复刻参考项目「界面缩放」交互 —— ArrowPreference 底部内嵌 Slider，
 * 档位映射为整数索引并开启 keyPoints 磁吸 + Step 震动反馈；右侧实时显示当前档位文本。
 * 拖拽只更新本地状态，松手（onValueChangeFinished）才回调写盘，避免拖动过程中频繁 IO。
 */
@Composable
private fun EnumSliderRow(
    label: String,
    options: List<Pair<String, String>>,
    current: String,
    onSelect: (String) -> Unit,
) {
    val initIndex = options.indexOfFirst { it.first == current }.takeIf { it >= 0 } ?: 0
    var sliderIndex by remember(current) { mutableIntStateOf(initIndex) }
    ArrowPreference(
        title = label,
        endActions = {
            Text(
                options[sliderIndex].second,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        onClick = { },
        bottomAction = {
            Slider(
                value = sliderIndex.toFloat(),
                onValueChange = { sliderIndex = it.roundToInt().coerceIn(0, options.size - 1) },
                onValueChangeFinished = { onSelect(options[sliderIndex].first) },
                valueRange = 0f..(options.size - 1).toFloat(),
                showKeyPoints = true,
                keyPoints = (0 until options.size).map { it.toFloat() },
                magnetThreshold = 0.25f,
                hapticEffect = SliderDefaults.SliderHapticEffect.Step,
            )
        },
    )
}

/** 字体行：Miuix ArrowPreference，右侧展示当前字体；点击弹出「内置字体 / 选择字体文件」。 */
@Composable
private fun FontRow(label: String, value: String, onReset: () -> Unit, onPick: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    ArrowPreference(
        title = label,
        endActions = {
            Text(
                value,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        onClick = { open = true },
    )
    if (open) {
        AppAlertDialog(
            onDismissRequest = { open = false },
            title = { Text(label, style = MaterialTheme.typography.titleMedium) },
            text = {
                Column {
                    Row(Modifier.fillMaxWidth().clickable { onReset(); open = false }.padding(vertical = 8.dp)) {
                        Text("使用内置字体", style = MaterialTheme.typography.bodyMedium)
                    }
                    Row(Modifier.fillMaxWidth().clickable { open = false; onPick() }.padding(vertical = 8.dp)) {
                        Text("选择字体文件…", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { open = false }) { Text("取消") } },
        )
    }
}

private fun importFont(ctx: android.content.Context, uri: Uri): String? = try {
    val displayName = runCatching {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment
    val name = (displayName ?: "font.ttf").substringAfterLast('/').substringAfterLast('\\')
    if (!listOf(".ttf", ".ttc", ".otf", ".otc").any { name.lowercase().endsWith(it) }) return null
    val dir = File(ctx.filesDir, "fonts")
    if (!dir.isDirectory && !dir.mkdirs()) return null
    val target = File(dir, name)
    ctx.contentResolver.openInputStream(uri)?.use { input ->
        target.outputStream().use { out -> input.copyTo(out) }
    } ?: return null
    target.absolutePath
} catch (t: Throwable) {
    null
}

// 选项表（保序；"" 表示未设置 = 引擎默认/自动，选中后写回空串，语义与旧实现一致）
private val KR_SELECT_MAP = listOf(
    EngineSettingsStore.KR_AUTO to "自动",
    EngineSettingsStore.KR_139 to "1.3.9",
    EngineSettingsStore.KR_134 to "1.3.4",
    EngineSettingsStore.KR_126 to "1.2.6",
)
private val KR_KERNEL_MAP = listOf(
    EngineSettingsStore.KR_AUTO to "自动",
    EngineSettingsStore.KERNEL_KIRIKIRI2 to "吉里吉里2",
    EngineSettingsStore.KERNEL_KRKRSDL3 to "krkrsdl3",
)
private val KR_RENDERER_MAP = listOf(
    "default" to "引擎默认",
    EngineSettingsStore.RENDERER_SOFTWARE to "软件渲染",
    EngineSettingsStore.RENDERER_OPENGL to "OpenGL",
)
private val KR_SDL3_RENDERER_MAP = listOf(
    EngineSettingsStore.RENDERER_OPENGL to "OpenGL（默认）",
    EngineSettingsStore.RENDERER_SOFTWARE to "软件渲染",
)
private val KR_THREAD_MAP = listOf("0" to "自动") + (1..8).map { it.toString() to "$it 线程" }
private val KR_SW_COMPRESS_MAP = listOf(
    "" to "引擎默认", "none" to "无", "halfline" to "半行", "lz4" to "LZ4", "lz4+tlg5" to "LZ4+TLG5",
)
private val KR_OGL_COMPRESS_MAP = listOf(
    "" to "引擎默认", "none" to "无", "half" to "半精度", "etc2" to "ETC2", "pvrtc" to "PVRTC",
)
private val KR_MEM_MAP = listOf(
    "" to "引擎默认",
    EngineSettingsStore.MEM_USAGE_UNLIMITED to "不限制",
    EngineSettingsStore.MEM_USAGE_HIGH to "高",
    EngineSettingsStore.MEM_USAGE_MEDIUM to "中",
    EngineSettingsStore.MEM_USAGE_LOW to "低",
)
private val KR_TEXSIZE_MAP = listOf("0" to "自动") +
    listOf(1024, 2048, 4096, 8192, 16384).map { it.toString() to it.toString() }
private val KR_FPS_MAP = listOf("" to "引擎默认", "60" to "60", "45" to "45", "30" to "30", "15" to "15")
private val ONS_SHARPNESS_MAP = listOf("1" to "1.0", "2" to "2.0", "3" to "3.0", "4" to "4.0", "5" to "5.0")
private val ONS_ENCODING_MAP = listOf("gbk" to "GBK", "sjis" to "Shift-JIS", "utf8" to "UTF-8")
private val ART_VERSION_MAP = listOf(
    EngineSettingsStore.ART_ENGINE_AUTO to "自动",
    EngineSettingsStore.ART_ENGINE_V1 to "V1",
    EngineSettingsStore.ART_ENGINE_V2 to "V2",
    EngineSettingsStore.ART_ENGINE_V3 to "V3",
)
private val RENPY_VERSION_MAP = listOf(
    EngineSettingsStore.RENPY_AUTO to "自动",
    EngineSettingsStore.RENPY_85 to "8.5",
    EngineSettingsStore.RENPY_77 to "7.7.1",
)
private val ART_PATCH_MAP = listOf(
    EngineSettingsStore.AUTO_PATCH_ASK to "启动时询问",
    EngineSettingsStore.AUTO_PATCH_AUTO to "自动",
    EngineSettingsStore.AUTO_PATCH_OFF to "关闭",
)

/** 游戏目录 URI → 可读目录名（取 SAF documentId 的最后一段，失败回退原 uri）。 */
private fun scanDirName(context: android.content.Context, uri: String): String =
    if (uri.startsWith('/')) {
        runCatching { File(uri).name.takeIf { it.isNotBlank() } ?: uri }.getOrDefault(uri)
    } else {
        runCatching {
            val docId = DocumentsContract.getTreeDocumentId(android.net.Uri.parse(uri))
            docId.substringAfterLast(':').substringAfterLast('/').ifBlank { uri }
        }.getOrDefault(uri)
    }


/** 游戏根目录是否仍可访问（被改名/删除/权限失效时返回 false；TF 卡暂时拔出也会显示失效，重插后恢复）。 */
private fun isScanDirValid(context: android.content.Context, uri: String): Boolean =
    if (uri.startsWith('/')) {
        runCatching { File(uri).isDirectory }.getOrDefault(false)
    } else {
        runCatching {
            val doc = DocumentFile.fromTreeUri(context, android.net.Uri.parse(uri))
            doc != null && doc.isDirectory
        }.getOrDefault(false)
    }

