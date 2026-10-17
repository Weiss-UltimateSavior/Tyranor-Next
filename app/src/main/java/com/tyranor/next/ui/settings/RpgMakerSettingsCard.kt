package com.tyranor.next.ui.settings

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.tyranor.next.R
import com.tyranor.next.core.engine.external.ExternalEngineLauncher
import com.tyranor.next.core.engine.external.RpgMakerExternalEngineModule
import com.tyranor.next.core.engine.external.RpgMakerRuntimeEnvironment
import com.tyranor.next.core.engine.external.RtpImportRejection
import com.tyranor.next.core.settings.EngineSettingsStore
import com.tyranor.next.theme.DialogItemSurface
import com.tyranor.next.ui.common.AppAlertDialog
import com.tyranor.next.ui.common.AppNavItem
import com.tyranor.next.ui.common.DialogTextButton
import com.tyranor.next.ui.common.NoRippleTextButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.runInterruptible
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference

/** RTP 管理行：与插件 `MKXPConfigurationParser` 目录契约一一对应的三个可导入子类型。 */
private val RPGM_RTP_TYPES = listOf("rpgmxp", "rpgmvx", "rpgmvxace")

/**
 * 恢复态（core 登记来的「导入中」）的轮询间隔：导入结束只有 core 知道，而恢复态不会自己消失。
 * 只读 core 的进程内登记（ConcurrentHashMap），不触盘，500ms 足以让按钮及时放开。
 */
private const val RTP_IN_FLIGHT_POLL_MS = 500L

/**
 * RPG Maker RGSS 外置模块设置卡片（全局）。
 *
 * 仅承载 RPGM 插件 `MKXPConfigurationParser` 实际解析的字段：渲染/显示项即时以本地状态回填，
 * 由外层顶部保存按钮统一落盘（编辑→保存模型）；RTP 导入/清理与字体导入直接落共享目录。
 * 覆盖 MV/MZ Web 设置的入口不在此卡片内，避免双入口。
 */
@Composable
internal fun RpgMakerRgssSettingsCard(
    settings: EngineSettingsStore.RpgMaker,
    onSettings: (EngineSettingsStore.RpgMaker) -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var moduleInstalled by remember {
        mutableStateOf(ExternalEngineLauncher.isPackageInstalled(ctx, RpgMakerExternalEngineModule))
    }
    var advancedExpanded by remember { mutableStateOf(false) }
    var showResetDialog by remember { mutableStateOf(false) }
    var showRtpSourceDialog by remember { mutableStateOf(false) }
    var pendingRtpType by remember { mutableStateOf<String?>(null) }
    var clearingRtpType by remember { mutableStateOf<String?>(null) }
    // null = 状态尚未读出（IO 还在跑）：先显示「检测中」，避免把真实状态反着显示、
    // 并顺手藏掉「清除」入口（首帧固定 false 时它会出现又消失）
    var rtpImported by remember { mutableStateOf(RPGM_RTP_TYPES.associateWith<String, Boolean?> { null }) }
    // 导入在途守卫：导入耗时可到分钟级，期间再次点「导入」会（在修复临时目录前）让两次
    // 导入互相破坏；现在临时目录已逐次唯一，但并发导入仍是无意义操作——**同一子类型**的入口
    // 置灰（其它子类型各有自己的目录，core 允许并行）。
    // 这只是「顺手」的 UI 便利：真正的互斥由 core 的进程级在途登记承担（clearRtp 会拒绝）。
    //
    // 三条约定（第八轮复审）：
    //  - 本页发起的导入由 localImportingRtpTypes 跟踪，结束时由 runRtpImport 收尾并给出提示；
    //  - **从 core 登记恢复来的**态由 coreImportingRtpTypes 跟踪——它的结束时机只有 core 知道
    //    （导入在 IO 线程继续跑），必须能被 core 的「无在途」收敛掉，否则「长导入期间切走页面
    //    再回来」会让按钮一直置灰、也没有完成提示；
    //  - 两个态都是**集合**（core 与 UI 都允许不同子类型并行导入），且恢复态要**排除本页正在
    //    跟踪的那些**：用单值时只装得下一个子类型——恢复态漏掉的那个永不收敛（状态陈旧、清除
    //    入口消失、无完成提示），本页发起、又被恢复态重复接管的那个则会与 runRtpImport 各弹
    //    一次「导入完成」。
    var localImportingRtpTypes by remember { mutableStateOf(emptySet<String>()) }
    var coreImportingRtpTypes by remember { mutableStateOf(emptySet<String>()) }

    /**
     * 重读全部子类型的状态 + 恢复 core 侧的在途导入登记（页面重建后在途导入仍在跑）。
     *
     * 局部函数必须在调用点之前声明（Kotlin 局部声明不提升）。
     */
    suspend fun refreshAllRtp() {
        val states = withContext(Dispatchers.IO) {
            RPGM_RTP_TYPES.associateWith { RpgMakerRuntimeEnvironment.isRtpImported(it) }
        }
        val inFlight = withContext(Dispatchers.IO) {
            RPGM_RTP_TYPES.filter { RpgMakerRuntimeEnvironment.isRtpImportInFlight(it) }.toSet()
        }
        rtpImported = states
        // core 是恢复态的唯一事实源，这里总是直接赋值（不保留旧值，否则导入结束后无法收敛为
        // 空）；但本页自己发起的导入由 runRtpImport 收尾与提示，不重复接管。
        coreImportingRtpTypes = inFlight - localImportingRtpTypes
    }

    // RTP 状态读盘（含中断恢复的文件操作）放 IO 线程：组合期在主线程逐个 stat/rename
    // 目录有 ANR 风险（恢复路径会 deleteRecursively 半成品）
    LaunchedEffect(Unit) {
        refreshAllRtp()
    }

    // 从安装器返回后刷新模块/RTP 状态（与引擎页 ON_RESUME 刷新策略一致）
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        moduleInstalled = ExternalEngineLauncher.isPackageInstalled(ctx, RpgMakerExternalEngineModule)
        scope.launch { refreshAllRtp() }
    }

    suspend fun refreshRtp(type: String) {
        val imported = withContext(Dispatchers.IO) { RpgMakerRuntimeEnvironment.isRtpImported(type) }
        rtpImported = rtpImported.toMutableMap().apply { put(type, imported) }
    }

    // 从 core 恢复来的「导入中」态必须收敛：导入结束（成功/失败）时 core 会注销登记，而本页
    // 恢复来的标记不会自己消失（旧实现只在本地为 null 时才接受 core 的值，导致按钮一直置灰到
    // 页面被销毁重建）。这里轮询登记（读 ConcurrentHashMap，不触盘、不走 IO，也**不加锁**——
    // importLock 会被提交段的整目录拷贝长时间占住），本批观察到的子类型全部无在途即收敛清态、
    // 刷新各自状态；只在状态真的翻成「已导入」时提示成功——恢复态拿不到失败原因，不猜。
    LaunchedEffect(coreImportingRtpTypes) {
        val watching = coreImportingRtpTypes
        if (watching.isEmpty()) return@LaunchedEffect
        val importedBefore = watching.associateWith { rtpImported[it] }
        while (watching.any { RpgMakerRuntimeEnvironment.isRtpImportInFlight(it) }) {
            delay(RTP_IN_FLIGHT_POLL_MS)
        }
        watching.forEach { refreshRtp(it) }
        // 只移除本批观察到的：轮询挂起期间 refreshAllRtp 新恢复进来的子类型不能被一并清掉
        coreImportingRtpTypes = coreImportingRtpTypes - watching
        watching.forEach { type ->
            if (importedBefore[type] != true && rtpImported[type] == true) {
                Toast.makeText(ctx, ctx.getString(R.string.engine_settings_rpgm_rtp_imported), Toast.LENGTH_LONG).show()
            }
        }
    }

    /** 导入在途标记的收尾：无论成功、失败还是被取消，都要把入口重新放开。 */
    suspend fun runRtpImport(type: String, block: suspend () -> RtpImportRejection?) {
        // 只增删自己那一个子类型：同页并发导入不同子类型时，先结束者不得把仍在跑的那次放开
        localImportingRtpTypes = localImportingRtpTypes + type
        try {
            val rejection = withContext(Dispatchers.IO) { block() }
            refreshRtp(type)
            // 失败时带上原因（如「条目数超过上限」「源里没有可导入内容」）：只报「导入失败」
            // 会让用户无从判断是包损坏、格式不对，还是被安全上限拦下。
            // core 返回类型化原因，文案与括号样式由 UI 层资源组装（AGENTS.md 错误处理协议）
            val message = rejection?.let {
                ctx.getString(R.string.engine_settings_rpgm_rtp_import_failed, it.userMessage(ctx))
            } ?: ctx.getString(R.string.engine_settings_rpgm_rtp_imported)
            Toast.makeText(ctx, message, Toast.LENGTH_LONG).show()
        } finally {
            localImportingRtpTypes = localImportingRtpTypes - type
        }
    }

    val zipLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val type = pendingRtpType
        pendingRtpType = null
        if (uri == null || type == null) return@rememberLauncherForActivityResult
        scope.launch { runRtpImport(type) { RpgMakerRuntimeEnvironment.importRtpZip(ctx, type, uri) } }
    }

    val treeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val type = pendingRtpType
        pendingRtpType = null
        if (uri == null || type == null) return@rememberLauncherForActivityResult
        runCatching {
            ctx.contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
        scope.launch { runRtpImport(type) { RpgMakerRuntimeEnvironment.importRtpTree(ctx, type, uri) } }
    }

    val fontLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val okMsg = ctx.getString(R.string.engine_settings_rpgm_custom_font_imported)
            val failedMsg = ctx.getString(R.string.engine_settings_rpgm_custom_font_import_failed)
            val path = runInterruptible(Dispatchers.IO) { RpgMakerRuntimeEnvironment.importCustomFont(ctx, uri) }
            if (path != null) onSettings(settings.copy(customFont = path))
            Toast.makeText(ctx, if (path != null) okMsg else failedMsg, Toast.LENGTH_SHORT).show()
        }
    }

    EngineCard(stringResource(R.string.engine_settings_rpgm_module_card)) {
        // 模块安装状态
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.engine_settings_rpgm_module_status),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    stringResource(
                        if (moduleInstalled) R.string.engine_module_installed else R.string.engine_module_not_installed,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                imageVector = if (moduleInstalled) Icons.Filled.CheckCircle else Icons.Filled.Cancel,
                contentDescription = null,
                modifier = Modifier.size(22.dp),
                tint = if (moduleInstalled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            )
        }
        if (!moduleInstalled) {
            NoRippleTextButton(
                text = stringResource(R.string.engine_settings_rpgm_module_download),
                modifier = Modifier.padding(horizontal = 8.dp),
                onClick = { ExternalEngineLauncher.openInstallPage(ctx, RpgMakerExternalEngineModule) },
            )
        }

        // 兼容性
        RpgmGroupLabel(stringResource(R.string.engine_settings_rpgm_group_compat))
        SwitchPreference(
            title = stringResource(R.string.engine_settings_rpgm_use_ruby18),
            checked = settings.useRuby18,
            onCheckedChange = { onSettings(settings.copy(useRuby18 = it)) },
        )

        // 渲染
        RpgmGroupLabel(stringResource(R.string.engine_settings_rpgm_group_render))
        SwitchPreference(
            title = stringResource(R.string.engine_settings_rpgm_smooth_scaling),
            checked = settings.smoothScaling,
            onCheckedChange = { onSettings(settings.copy(smoothScaling = it)) },
        )
        SwitchPreference(
            title = stringResource(R.string.engine_settings_rpgm_vsync),
            checked = settings.vsync,
            onCheckedChange = { onSettings(settings.copy(vsync = it)) },
        )
        SwitchPreference(
            title = stringResource(R.string.engine_settings_rpgm_frame_skip),
            checked = settings.frameSkip,
            onCheckedChange = { onSettings(settings.copy(frameSkip = it)) },
        )
        SwitchPreference(
            title = stringResource(R.string.engine_settings_rpgm_solid_fonts),
            checked = settings.solidFonts,
            onCheckedChange = { onSettings(settings.copy(solidFonts = it)) },
        )

        // 显示
        RpgmGroupLabel(stringResource(R.string.engine_settings_rpgm_group_display))
        DropdownRow(
            stringResource(R.string.engine_settings_rpgm_window_size),
            rpgWindowSizeOptions(),
            settings.windowSize,
        ) { onSettings(settings.copy(windowSize = it)) }
        DropdownRow(
            stringResource(R.string.engine_settings_rpgm_speed_up),
            rpgSpeedUpOptions(),
            settings.speedUp,
        ) { onSettings(settings.copy(speedUp = it)) }
        DropdownRow(
            stringResource(R.string.engine_settings_rpgm_font_scale),
            rpgFontScaleOptions(),
            settings.fontScale,
        ) { onSettings(settings.copy(fontScale = it)) }
        DropdownRow(
            stringResource(R.string.engine_settings_rpgm_vertical_align),
            rpgVerticalAlignOptions(),
            settings.verticalScreenAlign,
        ) { onSettings(settings.copy(verticalScreenAlign = it)) }
        FontPreference(
            label = stringResource(R.string.engine_settings_rpgm_custom_font),
            value = settings.customFont.takeIf { it.isNotBlank() }
                ?.let { RpgMakerRuntimeEnvironment.customFontFileName(it) }
                ?: stringResource(R.string.engine_settings_rpgm_custom_font_default),
            followLabel = stringResource(R.string.engine_settings_rpgm_custom_font_default),
            onFollow = { onSettings(settings.copy(customFont = "")) },
            onPick = { fontLauncher.launch("*/*") },
            valueInSummary = true,
        )

        // 高级（默认收起）
        ArrowPreference(
            title = stringResource(R.string.engine_settings_rpgm_group_advanced),
            summary = stringResource(R.string.engine_settings_rpgm_group_advanced_hint),
            onClick = { advancedExpanded = !advancedExpanded },
        )
        if (advancedExpanded) {
            SwitchPreference(
                title = stringResource(R.string.engine_settings_rpgm_path_cache),
                checked = settings.pathCache,
                onCheckedChange = { onSettings(settings.copy(pathCache = it)) },
            )
            SwitchPreference(
                title = stringResource(R.string.engine_settings_rpgm_prebuilt_path_cache),
                checked = settings.prebuiltPathCache,
                onCheckedChange = { onSettings(settings.copy(prebuiltPathCache = it)) },
            )
            SwitchPreference(
                title = stringResource(R.string.engine_settings_rpgm_fast_path_enum),
                checked = settings.fastPathEnum,
                onCheckedChange = { onSettings(settings.copy(fastPathEnum = it)) },
            )
            SwitchPreference(
                title = stringResource(R.string.engine_settings_rpgm_copy_text),
                checked = settings.copyText,
                onCheckedChange = { onSettings(settings.copy(copyText = it)) },
            )
            SwitchPreference(
                title = stringResource(R.string.engine_settings_rpgm_cheats),
                checked = settings.cheats,
                onCheckedChange = { onSettings(settings.copy(cheats = it)) },
            )
            SwitchPreference(
                title = stringResource(R.string.engine_settings_rpgm_use_cjk_font),
                checked = settings.useCJKFont,
                onCheckedChange = { onSettings(settings.copy(useCJKFont = it)) },
            )
            SwitchPreference(
                title = stringResource(R.string.engine_settings_rpgm_postload_scripts),
                checked = settings.enablePostloadScripts,
                onCheckedChange = { onSettings(settings.copy(enablePostloadScripts = it)) },
            )
            SwitchPreference(
                title = stringResource(R.string.engine_settings_rpgm_debug),
                checked = settings.debug,
                onCheckedChange = { onSettings(settings.copy(debug = it)) },
            )
        }

        // RTP
        RpgmGroupLabel(stringResource(R.string.engine_settings_rpgm_group_rtp))
        RPGM_RTP_TYPES.forEach { type ->
            RpgmRtpRow(
                title = stringResource(
                    when (type) {
                        "rpgmxp" -> R.string.engine_settings_rpgm_rtp_xp
                        "rpgmvx" -> R.string.engine_settings_rpgm_rtp_vx
                        else -> R.string.engine_settings_rpgm_rtp_vxace
                    },
                ),
                imported = rtpImported[type],
                importing = type in localImportingRtpTypes || type in coreImportingRtpTypes,
                onImport = {
                    pendingRtpType = type
                    showRtpSourceDialog = true
                },
                onClear = { clearingRtpType = type },
            )
        }
        Text(
            stringResource(R.string.engine_settings_rpgm_rtp_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        )

        // 恢复默认（仅 rpg 节）
        ArrowPreference(
            title = stringResource(R.string.engine_settings_rpgm_reset_defaults),
            onClick = { showResetDialog = true },
        )
    }

    if (showRtpSourceDialog) {
        AppAlertDialog(
            onDismissRequest = {
                showRtpSourceDialog = false
                pendingRtpType = null
            },
            title = {
                Text(
                    stringResource(R.string.engine_settings_rpgm_rtp_import),
                    style = MaterialTheme.typography.titleMedium,
                )
            },
            text = {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // 弹窗点击反馈规范：共享条目组件显式 indication = null（与其他弹窗一致）
                    AppNavItem(
                        title = stringResource(R.string.engine_settings_rpgm_rtp_from_zip),
                        containerColor = DialogItemSurface,
                        indication = null,
                    ) {
                        showRtpSourceDialog = false
                        zipLauncher.launch("*/*")
                    }
                    AppNavItem(
                        title = stringResource(R.string.engine_settings_rpgm_rtp_from_dir),
                        containerColor = DialogItemSurface,
                        indication = null,
                    ) {
                        showRtpSourceDialog = false
                        treeLauncher.launch(null)
                    }
                }
            },
            confirmButton = {
                DialogTextButton(text = stringResource(R.string.common_cancel), onClick = {
                    showRtpSourceDialog = false
                    pendingRtpType = null
                })
            },
        )
    }

    clearingRtpType?.let { type ->
        AppAlertDialog(
            onDismissRequest = { clearingRtpType = null },
            title = {
                Text(
                    stringResource(R.string.engine_settings_rpgm_rtp_clear),
                    style = MaterialTheme.typography.titleMedium,
                )
            },
            text = { Text(stringResource(R.string.engine_settings_rpgm_rtp_clear_confirm)) },
            confirmButton = {
                DialogTextButton(text = stringResource(R.string.common_confirm), onClick = {
                    // clearRtp 会递归删目录：放 IO 线程，避免清除大 RTP 时卡住点击瞬间
                    scope.launch {
                        val cleared = withContext(Dispatchers.IO) {
                            RpgMakerRuntimeEnvironment.clearRtp(type)
                        }
                        refreshRtp(type)
                        clearingRtpType = null
                        val msg = when (cleared) {
                            RpgMakerRuntimeEnvironment.RtpClearResult.CLEARED ->
                                R.string.engine_settings_rpgm_rtp_cleared
                            // 在途导入：core 拒绝清除（否则会把半成品装成 app），如实告知而不是报「失败」
                            RpgMakerRuntimeEnvironment.RtpClearResult.IMPORT_IN_FLIGHT ->
                                R.string.engine_settings_rpgm_rtp_clear_importing
                            RpgMakerRuntimeEnvironment.RtpClearResult.FAILED ->
                                R.string.engine_settings_rpgm_rtp_clear_failed
                        }
                        Toast.makeText(ctx, ctx.getString(msg), Toast.LENGTH_SHORT).show()
                    }
                })
            },
            dismissButton = {
                DialogTextButton(text = stringResource(R.string.common_cancel), onClick = { clearingRtpType = null })
            },
        )
    }

    if (showResetDialog) {
        AppAlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = {
                Text(
                    stringResource(R.string.engine_settings_rpgm_reset_defaults),
                    style = MaterialTheme.typography.titleMedium,
                )
            },
            text = { Text(stringResource(R.string.engine_settings_rpgm_reset_confirm)) },
            confirmButton = {
                DialogTextButton(text = stringResource(R.string.common_confirm), onClick = {
                    showResetDialog = false
                    advancedExpanded = false
                    onSettings(EngineSettingsStore.RpgMaker())
                })
            },
            dismissButton = {
                DialogTextButton(text = stringResource(R.string.common_cancel), onClick = { showResetDialog = false })
            },
        )
    }
}

@Composable
private fun RpgmGroupLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 2.dp),
    )
}

@Composable
private fun RpgmRtpRow(
    title: String,
    /** null = 状态尚未读出（IO 在跑）。 */
    imported: Boolean?,
    /** 本类型正在导入：入口全部置灰（导入与清除互斥，见 RpgmRtpRow 契约）。 */
    importing: Boolean,
    onImport: () -> Unit,
    onClear: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(
                    when (imported) {
                        null -> R.string.engine_settings_rpgm_rtp_checking_status
                        true -> R.string.engine_settings_rpgm_rtp_imported_status
                        false -> R.string.engine_settings_rpgm_rtp_missing_status
                    },
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        NoRippleTextButton(
            text = stringResource(R.string.engine_settings_rpgm_rtp_import),
            // 导入在途：禁用入口，避免两次导入并发（也避免把清除与导入交错）
            enabled = !importing,
            onClick = onImport,
        )
        if (imported == true) {
            // 清除是破坏性动作；无点击反馈实现（AGENTS.md 弹窗/页内按钮规范），
            // 语义色取 colorScheme.error（AGENTS.md 3.3：error 用于错误/删除）
            NoRippleTextButton(
                text = stringResource(R.string.engine_settings_rpgm_rtp_clear),
                enabled = !importing,
                contentColor = MaterialTheme.colorScheme.error,
                onClick = onClear,
            )
        }
    }
}
