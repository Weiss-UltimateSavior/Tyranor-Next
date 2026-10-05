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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import com.tyranor.next.core.settings.EngineSettingsStore
import com.tyranor.next.theme.DialogItemSurface
import com.tyranor.next.ui.common.AppAlertDialog
import com.tyranor.next.ui.common.AppNavItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference

/** RTP 管理行：与插件 `MKXPConfigurationParser` 目录契约一一对应的三个可导入子类型。 */
private val RPGM_RTP_TYPES = listOf("rpgmxp", "rpgmvx", "rpgmvxace")

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
    var rtpImported by remember {
        mutableStateOf(RPGM_RTP_TYPES.associateWith { RpgMakerRuntimeEnvironment.isRtpImported(it) })
    }

    // 从安装器返回后刷新模块/RTP 状态（与引擎页 ON_RESUME 刷新策略一致）
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        moduleInstalled = ExternalEngineLauncher.isPackageInstalled(ctx, RpgMakerExternalEngineModule)
        rtpImported = RPGM_RTP_TYPES.associateWith { RpgMakerRuntimeEnvironment.isRtpImported(it) }
    }

    fun refreshRtp(type: String) {
        rtpImported = rtpImported.toMutableMap().apply {
            put(type, RpgMakerRuntimeEnvironment.isRtpImported(type))
        }
    }

    val zipLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val type = pendingRtpType
        pendingRtpType = null
        if (uri == null || type == null) return@rememberLauncherForActivityResult
        scope.launch {
            val importedMsg = ctx.getString(R.string.engine_settings_rpgm_rtp_imported)
            val failedMsg = ctx.getString(R.string.engine_settings_rpgm_rtp_import_failed)
            val ok = withContext(Dispatchers.IO) { RpgMakerRuntimeEnvironment.importRtpZip(ctx, type, uri) }
            refreshRtp(type)
            Toast.makeText(ctx, if (ok) importedMsg else failedMsg, Toast.LENGTH_SHORT).show()
        }
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
        scope.launch {
            val importedMsg = ctx.getString(R.string.engine_settings_rpgm_rtp_imported)
            val failedMsg = ctx.getString(R.string.engine_settings_rpgm_rtp_import_failed)
            val ok = withContext(Dispatchers.IO) { RpgMakerRuntimeEnvironment.importRtpTree(ctx, type, uri) }
            refreshRtp(type)
            Toast.makeText(ctx, if (ok) importedMsg else failedMsg, Toast.LENGTH_SHORT).show()
        }
    }

    val fontLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val okMsg = ctx.getString(R.string.engine_settings_rpgm_custom_font_imported)
            val failedMsg = ctx.getString(R.string.engine_settings_rpgm_custom_font_import_failed)
            val path = withContext(Dispatchers.IO) { RpgMakerRuntimeEnvironment.importCustomFont(ctx, uri) }
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
            TextButton(
                onClick = { ExternalEngineLauncher.openInstallPage(ctx, RpgMakerExternalEngineModule) },
                modifier = Modifier.padding(horizontal = 8.dp),
            ) {
                Text(stringResource(R.string.engine_settings_rpgm_module_download))
            }
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
                imported = rtpImported[type] == true,
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
                    AppNavItem(
                        title = stringResource(R.string.engine_settings_rpgm_rtp_from_zip),
                        containerColor = DialogItemSurface,
                    ) {
                        showRtpSourceDialog = false
                        zipLauncher.launch("*/*")
                    }
                    AppNavItem(
                        title = stringResource(R.string.engine_settings_rpgm_rtp_from_dir),
                        containerColor = DialogItemSurface,
                    ) {
                        showRtpSourceDialog = false
                        treeLauncher.launch(null)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showRtpSourceDialog = false
                    pendingRtpType = null
                }) { Text(stringResource(R.string.common_cancel)) }
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
                TextButton(onClick = {
                    val cleared = RpgMakerRuntimeEnvironment.clearRtp(type)
                    refreshRtp(type)
                    clearingRtpType = null
                    val msg = if (cleared) {
                        R.string.engine_settings_rpgm_rtp_cleared
                    } else {
                        R.string.engine_settings_rpgm_rtp_clear_failed
                    }
                    Toast.makeText(ctx, ctx.getString(msg), Toast.LENGTH_SHORT).show()
                }) { Text(stringResource(R.string.common_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { clearingRtpType = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
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
                TextButton(onClick = {
                    showResetDialog = false
                    advancedExpanded = false
                    onSettings(EngineSettingsStore.RpgMaker())
                }) { Text(stringResource(R.string.common_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }
}

@Composable
private fun RpgmGroupLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 2.dp),
    )
}

@Composable
private fun RpgmRtpRow(
    title: String,
    imported: Boolean,
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
                    if (imported) R.string.engine_settings_rpgm_rtp_imported_status else R.string.engine_settings_rpgm_rtp_missing_status,
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onImport) {
            Text(stringResource(R.string.engine_settings_rpgm_rtp_import))
        }
        if (imported) {
            TextButton(onClick = onClear) {
                Text(
                    stringResource(R.string.engine_settings_rpgm_rtp_clear),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}
