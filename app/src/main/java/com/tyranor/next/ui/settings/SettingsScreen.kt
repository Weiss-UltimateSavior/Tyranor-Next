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
import androidx.compose.ui.res.stringResource
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
                    Toast.makeText(ctx, ctx.getString(R.string.settings_update_latest), Toast.LENGTH_SHORT).show()
                }
                is UpdateCheckResult.Failed -> {
                    Toast.makeText(ctx, ctx.getString(R.string.settings_update_failed, result.message), Toast.LENGTH_SHORT).show()
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
            topBar = { SettingsTopBar(stringResource(R.string.nav_settings)) },
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
                                title = stringResource(R.string.settings_add_game_dir),
                                summary = stringResource(R.string.settings_dir_count, scanDirs.size),
                                onClick = { showScanDirs = true },
                            )
                            var depth by remember { mutableIntStateOf(AppSettingsStore.getScanDepth(ctx)) }
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    stringResource(R.string.settings_scan_depth),
                                    style = MaterialTheme.typography.titleMedium,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    stringResource(R.string.settings_scan_depth_level, depth),
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
                                AppSettingsStore.GAME_SORT_ALPHA to stringResource(R.string.settings_sort_alpha),
                                AppSettingsStore.GAME_SORT_BRACKET_TAG to stringResource(R.string.settings_sort_bracket_tag),
                            )
                            val sortIndex = gameSortModes.indexOfFirst { it.first == gameSort }
                                .let { if (it < 0) 0 else it }
                            OverlayDropdownPreference(
                                title = stringResource(R.string.settings_game_sort),
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
                                title = stringResource(R.string.settings_engine_settings),
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
                                title = stringResource(R.string.settings_app_title),
                                summary = stringResource(R.string.settings_app_summary),
                                startAction = { SettingsItemIcon(R.drawable.ic_settings_app) },
                                onClick = { startActivityWithPageTransition(ctx, AppSettingsActivity.createIntent(ctx)) },
                            )
                            ArrowPreference(
                                title = stringResource(R.string.settings_cover_scraper),
                                summary = stringResource(R.string.settings_cover_scraper_summary),
                                startAction = { SettingsItemIcon(R.drawable.ic_game_cover) },
                                onClick = { startActivityWithPageTransition(ctx, CoverScraperSettingsActivity.createIntent(ctx)) },
                            )
                            ArrowPreference(
                                title = if (checkingUpdate) stringResource(R.string.settings_update_checking) else stringResource(R.string.settings_update_check),
                                summary = stringResource(R.string.settings_update_check_summary),
                                startAction = { SettingsItemIcon(R.drawable.ic_settings_update) },
                                onClick = { checkUpdate() },
                            )
                            ArrowPreference(
                                title = stringResource(R.string.settings_join_group),
                                summary = stringResource(R.string.settings_join_group_summary),
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
            title = { Text(stringResource(R.string.settings_game_dirs_title), style = MaterialTheme.typography.titleMedium) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (scanDirs.isEmpty()) {
                        Text(
                            stringResource(R.string.settings_no_game_dirs),
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
                                if (valid) scanDirName(ctx, dir) else "${scanDirName(ctx, dir)}（${stringResource(R.string.settings_invalid_suffix)}）",
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
                                Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        onClick = { dirPicker.launch(null) },
                        modifier = Modifier.padding(end = 8.dp),
                    ) { Text(stringResource(R.string.settings_add_dir)) }
                    TextButton(onClick = { showScanDirs = false }) { Text(stringResource(R.string.settings_done)) }
                }
            },
        )
    }

    if (showGroupDialog) {
        AppAlertDialog(
            onDismissRequest = { showGroupDialog = false },
            title = { Text(stringResource(R.string.settings_join_group), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    AppNavItem(stringResource(R.string.settings_qq_group), leadingIcon = R.drawable.ic_group_qq) {
                        showGroupDialog = false
                        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://qm.qq.com/q/M9JH8A9Yys")))
                    }
                    AppNavItem(stringResource(R.string.settings_telegram_channel), leadingIcon = R.drawable.ic_group_telegram) {
                        showGroupDialog = false
                        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/tyranornext")))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showGroupDialog = false }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }

    updateAvailable?.let { update ->
        AppAlertDialog(
            onDismissRequest = { updateAvailable = null },
            title = { Text(stringResource(R.string.update_found_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(R.string.update_current_version, update.currentVersion),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MiuixTheme.colorScheme.onBackground,
                    )
                    Text(
                        stringResource(R.string.update_latest_version, update.latestVersion),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MiuixTheme.colorScheme.onBackground,
                    )
                    Text(
                        stringResource(R.string.update_open_github_message),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MiuixTheme.colorScheme.onBackground,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { updateAvailable = null }) { Text(stringResource(R.string.common_cancel)) }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        updateAvailable = null
                        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(update.releaseUrl)))
                    },
                ) { Text(stringResource(R.string.update_go_download)) }
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
                                engineSettingsKindTitle(kind),
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = MiuixTheme.colorScheme.onBackground,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            TopBarIcon(painterResource(R.drawable.ic_save), stringResource(R.string.engine_settings_save_content_description), MiuixTheme.colorScheme.primary) {
                                saveAll()
                                android.widget.Toast.makeText(ctx, ctx.getString(R.string.engine_settings_saved), android.widget.Toast.LENGTH_SHORT).show()
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
    val krSelectMap = krSelectOptions()
    val krKernelMap = krKernelOptions()
    val krRendererMap = krRendererOptions()
    val krSdl3RendererMap = krSdl3RendererOptions()
    val krThreadMap = krThreadOptions()
    val krSwCompressMap = krSoftwareCompressOptions()
    val krOglCompressMap = krOglCompressOptions()
    val krMemMap = krMemOptions()
    val krTexsizeMap = krTexSizeOptions()
    val krFpsMap = krFpsOptions()
    val onsSharpnessMap = onsSharpnessOptions()
    val onsEncodingMap = onsEncodingOptions()
    val artVersionMap = artVersionOptions()
    val renpyVersionMap = renpyVersionOptions()
    val artPatchMap = artPatchOptions()

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
        contentPadding = PaddingValues(top = topInset + 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (kind == EngineSettingsKind.KRKR) item {
            EngineCard("KRKR") {
                SwitchPreference(title = stringResource(R.string.engine_settings_scoped_save_dir), checked = krScoped, onCheckedChange = onKrScoped)
                DropdownRow(stringResource(R.string.engine_settings_engine_version), krSelectMap, krVersion, onKrVersion)
                DropdownRow(stringResource(R.string.engine_settings_engine_kernel), krKernelMap, krKernel, onKrKernel)
            }
        }

        if (kind == EngineSettingsKind.KRKR) item {
            EngineCard(stringResource(R.string.engine_settings_render)) {
                if (!isSdl3) {
                    SwitchPreference(title = stringResource(R.string.engine_settings_opengl_accurate_render), checked = krAccurate == "1", onCheckedChange = { b -> onKrAccurate(if (b) "1" else "0") })
                    EnumSliderRow(stringResource(R.string.engine_settings_memory_usage), krMemMap, krMem, onKrMem)
                }
                val rendererOptions = if (isSdl3) krSdl3RendererMap else krRendererMap
                val selectedRenderer = if (isSdl3) {
                    krRenderer.ifEmpty { EngineSettingsStore.RENDERER_OPENGL }
                } else {
                    krRenderer.ifEmpty { "default" }
                }
                DropdownRow(stringResource(R.string.engine_settings_renderer), rendererOptions, selectedRenderer) {
                    onKrRenderer(if (!isSdl3 && it == "default") "" else it)
                }
                if (!isSdl3 && (krRenderer == "" || krRenderer == EngineSettingsStore.RENDERER_SOFTWARE)) {
                    EnumSliderRow(stringResource(R.string.engine_settings_software_draw_threads), krThreadMap, krDrawThread, onKrDrawThread)
                    DropdownRow(stringResource(R.string.engine_settings_software_texture_compression), krSwCompressMap, krSwCompress, onKrSwCompress)
                }
                if (!isSdl3 && !krIs134126) {
                    EnumSliderRow(stringResource(R.string.engine_settings_fps_limit), krFpsMap, krFps, onKrFps)
                }
                if (!isSdl3 && (krRenderer == "" || krRenderer == EngineSettingsStore.RENDERER_OPENGL)) {
                    DropdownRow(stringResource(R.string.engine_settings_opengl_texture_compression), krOglCompressMap, krOglCompress, onKrOglCompress)
                    EnumSliderRow(stringResource(R.string.engine_settings_max_texture_size), krTexsizeMap, krTexsize, onKrTexsize)
                }
            }
        }

        if (kind == EngineSettingsKind.KRKR && !isSdl3) item {
            EngineCard(stringResource(R.string.engine_settings_font)) {
                FontRow(stringResource(R.string.engine_settings_default_font), krFont.ifEmpty { stringResource(R.string.engine_settings_builtin_font) }, onResetKrFont, { fontLauncher.launch("*/*") })
                if (krVersion != EngineSettingsStore.KR_126) {
                    SwitchPreference(title = stringResource(R.string.engine_settings_force_default_font), checked = krForceFont, onCheckedChange = onKrForceFont)
                }
            }
        }

        if (kind == EngineSettingsKind.ONS) item {
            EngineCard("ONS") {
                SwitchPreference(title = stringResource(R.string.engine_settings_scoped_save_dir), checked = ons.scopedSaveDir, onCheckedChange = { b -> onOns(ons.copy(scopedSaveDir = b)) })
                SwitchPreference(title = stringResource(R.string.engine_settings_fullscreen_stretch), checked = ons.stretchFull, onCheckedChange = { b -> onOns(ons.copy(stretchFull = b)) })
                SwitchPreference(title = stringResource(R.string.engine_settings_ignore_cutout), checked = ons.ignoreCutout, onCheckedChange = { b -> onOns(ons.copy(ignoreCutout = b)) })
                SwitchPreference(title = stringResource(R.string.engine_settings_disable_video), checked = ons.disableVideo, onCheckedChange = { b -> onOns(ons.copy(disableVideo = b)) })
                SwitchPreference(title = stringResource(R.string.engine_settings_sharpness), checked = ons.sharpness, onCheckedChange = { b -> onOns(ons.copy(sharpness = b)) })
                if (ons.sharpness) {
                    EnumSliderRow(stringResource(R.string.engine_settings_sharpness_strength), onsSharpnessMap, ons.sharpnessValue) {
                        onOns(ons.copy(sharpnessValue = it))
                    }
                }
                DropdownRow(stringResource(R.string.engine_settings_text_encoding), onsEncodingMap, EngineSettingsStore.normalizeEncoding(ons.encoding)) {
                    onOns(ons.copy(encoding = it))
                }
            }
        }

        if (kind == EngineSettingsKind.ARTEMIS) item {
            EngineCard("Artemis") {
                DropdownRow(stringResource(R.string.engine_settings_engine_version), artVersionMap, artVersion, onArtVersion)
                SwitchPreference(title = stringResource(R.string.engine_settings_rotate_screen), checked = artRotate, onCheckedChange = onArtRotate)
                DropdownRow(stringResource(R.string.engine_settings_auto_patch), artPatchMap, artPatch, onArtPatch)
            }
        }

        if (kind == EngineSettingsKind.TYRANO) item {
            EngineCard("Tyrano") {
                // RPG Maker Web 与 Tyrano 共用同一套 WebView 宿主开关，避免同类引擎重复配置。
                SwitchPreference(title = stringResource(R.string.engine_settings_external_network_resources), checked = tyExternal, onCheckedChange = onTyExternal)
                SwitchPreference(title = stringResource(R.string.engine_settings_scoped_save_dir), checked = tyScoped, onCheckedChange = onTyScoped)
            }
        }

        if (kind == EngineSettingsKind.RPG_MAKER) item {
            EngineCard("RPG Maker MV/MZ") {
                SwitchPreference(title = stringResource(R.string.engine_settings_external_network_resources), checked = tyExternal, onCheckedChange = onTyExternal)
                SwitchPreference(title = stringResource(R.string.engine_settings_scoped_save_dir), checked = tyScoped, onCheckedChange = onTyScoped)
                SwitchPreference(title = stringResource(R.string.engine_settings_game_modifier), checked = rpgMakerMod, onCheckedChange = onRpgMakerMod)
            }
        }

        if (kind == EngineSettingsKind.RENPY) item {
            EngineCard("Ren'Py") {
                DropdownRow(stringResource(R.string.engine_settings_engine_version), renpyVersionMap, renpyVersion, onRenpyVersion)
                Text(
                    stringResource(R.string.engine_settings_renpy_module_description_global),
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
                        Text(stringResource(R.string.engine_settings_use_builtin_font), style = MaterialTheme.typography.bodyMedium)
                    }
                    Row(Modifier.fillMaxWidth().clickable { open = false; onPick() }.padding(vertical = 8.dp)) {
                        Text(stringResource(R.string.engine_settings_select_font_file), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.common_cancel)) } },
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

/** 游戏目录 URI → 可读目录名（取 SAF documentId 的最后一段，失败回退原 uri）。 */
private fun scanDirName(context: android.content.Context, uri: String): String = runCatching {
    val docId = DocumentsContract.getTreeDocumentId(android.net.Uri.parse(uri))
    docId.substringAfterLast(':').substringAfterLast('/').ifBlank { uri }
}.getOrDefault(uri)

/** 游戏根目录是否仍可访问（被改名/删除/权限失效时返回 false；TF 卡暂时拔出也会显示失效，重插后恢复）。 */
private fun isScanDirValid(context: android.content.Context, uri: String): Boolean = runCatching {
    val doc = DocumentFile.fromTreeUri(context, android.net.Uri.parse(uri))
    doc != null && doc.isDirectory
}.getOrDefault(false)
