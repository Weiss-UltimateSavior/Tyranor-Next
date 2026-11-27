package com.tyranor.next.ui.settings

import android.widget.Toast
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.tyranor.next.R
import com.tyranor.next.core.engine.external.ExternalEngineLauncher
import com.tyranor.next.core.engine.external.ShiinaRioExternalEngineModule
import com.tyranor.next.core.settings.EngineSettingsStore
import com.tyranor.next.core.settings.ShiinaRioSettings
import com.tyranor.next.theme.MiuixSettingsTheme
import com.tyranor.next.theme.PageTextColor
import com.tyranor.next.ui.common.AppSecondaryTopBar
import com.tyranor.next.ui.common.ArrowPreference
import com.tyranor.next.ui.common.SwitchPreference
import com.tyranor.next.ui.common.TopBarIcon
import com.tyranor.next.ui.common.motion.PressSquishDefaults
import com.tyranor.next.ui.common.motion.pressSquish
import com.tyranor.next.ui.common.motion.skipEntranceOnDrag
import com.tyranor.next.ui.common.notice.AppNotice
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun ShiinaRioSettingsScreen() {
    val context = LocalContext.current
    var settings by remember { mutableStateOf(EngineSettingsStore.loadShiinaRio(context)) }
    var moduleReady by remember {
        mutableStateOf(ExternalEngineLauncher.checkInstallation(context, ShiinaRioExternalEngineModule) == null)
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        moduleReady = ExternalEngineLauncher.checkInstallation(context, ShiinaRioExternalEngineModule) == null
    }

    MiuixSettingsTheme {
        Scaffold(
            modifier = Modifier.skipEntranceOnDrag(),
            containerColor = Color.Transparent,
            contentWindowInsets = WindowInsets(0.dp),
            topBar = {
                AppSecondaryTopBar(
                    title = stringResource(R.string.engine_settings_shiinario_title),
                    background = Color.Transparent,
                    contentColor = PageTextColor,
                    trailing = {
                        TopBarIcon(
                            painterResource(R.drawable.ic_save),
                            stringResource(R.string.engine_settings_save_content_description),
                            MiuixTheme.colorScheme.primary,
                        ) {
                            EngineSettingsStore.saveShiinaRio(context, settings)
                            AppNotice.makeText(context, R.string.engine_settings_saved, Toast.LENGTH_SHORT).show()
                        }
                    },
                )
            },
        ) { padding ->
            LazyColumn(
                modifier = Modifier.fillMaxSize().navigationBarsPadding().padding(top = padding.calculateTopPadding()),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    EngineCard(stringResource(R.string.external_shiinario_module_name)) {
                        ArrowPreference(
                            title = stringResource(
                                if (moduleReady) R.string.engine_module_installed else R.string.engine_module_install_or_update,
                            ),
                            enabled = !moduleReady,
                            onClick = {
                                if (!ExternalEngineLauncher.openInstallPage(context, ShiinaRioExternalEngineModule)) {
                                    AppNotice.makeText(context, R.string.engine_open_download_failed, Toast.LENGTH_SHORT).show()
                                }
                            },
                        )
                    }
                }
                item {
                    EngineCard(stringResource(R.string.engine_settings_shiinario_player)) {
                        ShiinaRioSettingsRows(settings) { settings = it }
                    }
                }
            }
        }
    }
}

@Composable
internal fun ShiinaRioSettingsRows(
    settings: ShiinaRioSettings,
    global: ShiinaRioSettings? = null,
    onChange: (ShiinaRioSettings) -> Unit,
) {
    val defaults = ShiinaRioSettings.Defaults
    val base = global ?: defaults
    val isOverride = global != null

    ShiinaRioChoice(R.string.engine_settings_renderer, ShiinaRioSettings.RENDERERS.zip(listOf("Vulkan (GPU)", "CPU")),
        settings.renderer, requireNotNull(base.renderer), isOverride) { onChange(settings.copy(renderer = it)) }
    ShiinaRioChoice(R.string.engine_settings_shiinario_scaling, ShiinaRioSettings.SCALINGS.zip(listOf(
        stringResource(R.string.engine_settings_shiinario_scaling_sharp),
        stringResource(R.string.engine_settings_easyrpg_scaling_integer),
        stringResource(R.string.engine_settings_shiinario_scaling_smooth),
        stringResource(R.string.engine_settings_easyrpg_scaling_nearest),
        "FSR",
        "Anime4K",
    )), settings.scaling, requireNotNull(base.scaling), isOverride) { onChange(settings.copy(scaling = it)) }
    if ((settings.scaling ?: base.scaling) == "Fsr") {
        ShiinaRioChoice(R.string.engine_settings_shiinario_fsr,
            ShiinaRioSettings.FSR_SHARPNESS.map { it.toString() to it.toString() },
            settings.fsrSharpness?.toString(), base.fsrSharpness.toString(), isOverride,
        ) { onChange(settings.copy(fsrSharpness = it?.toInt())) }
    }
    ShiinaRioChoice(R.string.engine_settings_shiinario_pacing, ShiinaRioSettings.FRAME_PACING.zip(listOf(
        stringResource(R.string.engine_settings_shiinario_pacing_game),
        stringResource(R.string.engine_settings_shiinario_pacing_display),
    )), settings.framePacing, requireNotNull(base.framePacing), isOverride) { onChange(settings.copy(framePacing = it)) }
    ShiinaRioChoice(R.string.engine_settings_shiinario_cache, ShiinaRioSettings.FILE_CACHES.zip(listOf(
        stringResource(R.string.engine_settings_shiinario_cache_auto),
        stringResource(R.string.engine_settings_shiinario_cache_minimum),
        stringResource(R.string.engine_settings_shiinario_cache_low),
        stringResource(R.string.engine_settings_shiinario_cache_medium),
        stringResource(R.string.engine_settings_shiinario_cache_high),
        stringResource(R.string.engine_settings_shiinario_cache_maximum),
    )), settings.fileCache, requireNotNull(base.fileCache), isOverride) { onChange(settings.copy(fileCache = it)) }
    ShiinaRioSwitch(R.string.engine_settings_shiinario_translate, settings.translateX86, base.translateX86 == true, isOverride) {
        onChange(settings.copy(translateX86 = it))
    }
    ShiinaRioSwitch(R.string.engine_settings_shiinario_pause, settings.pauseInBackground, base.pauseInBackground == true, isOverride) {
        onChange(settings.copy(pauseInBackground = it))
    }
    ShiinaRioSwitch(R.string.engine_settings_shiinario_controller, settings.gameController, base.gameController == true, isOverride) {
        onChange(settings.copy(gameController = it))
    }
    ShiinaRioSwitch(R.string.engine_settings_easyrpg_show_fps, settings.showFrameRate, base.showFrameRate == true, isOverride) {
        onChange(settings.copy(showFrameRate = it))
    }
    ShiinaRioChoice(R.string.engine_settings_shiinario_theme, ShiinaRioSettings.THEMES.zip(listOf(
        "Shiina",
        stringResource(R.string.engine_settings_shiinario_theme_parchment),
        stringResource(R.string.engine_settings_shiinario_theme_graphite),
        stringResource(R.string.engine_settings_shiinario_theme_white),
        stringResource(R.string.settings_language_system),
    )), settings.theme, requireNotNull(base.theme), isOverride) { onChange(settings.copy(theme = it)) }
    ShiinaRioChoice(R.string.engine_settings_shiinario_language, ShiinaRioSettings.LANGUAGES.zip(listOf(
        stringResource(R.string.engine_settings_shiinario_follow_app),
        stringResource(R.string.settings_language_zh),
        stringResource(R.string.settings_language_ja),
        stringResource(R.string.settings_language_en),
    )), settings.language, requireNotNull(base.language), isOverride) { onChange(settings.copy(language = it)) }
}

@Composable
private fun ShiinaRioChoice(
    @StringRes label: Int,
    options: List<Pair<String, String>>,
    value: String?,
    base: String,
    isOverride: Boolean,
    onChange: (String?) -> Unit,
) {
    val title = stringResource(label)
    if (isOverride) {
        val optionMap = options.toMap()
        OverrideChoice(title, optionMap, base, value, emptyLabel = optionMap[""].orEmpty(), onSet = onChange)
    } else {
        DropdownRow(title, options, value ?: base, onChange)
    }
}

@Composable
private fun ShiinaRioSwitch(
    @StringRes label: Int,
    value: Boolean?,
    base: Boolean,
    isOverride: Boolean,
    onChange: (Boolean?) -> Unit,
) {
    val title = stringResource(label)
    if (isOverride) {
        OverrideSwitch(title, base, value, onSet = onChange)
    } else {
        SwitchPreference(
            modifier = Modifier.pressSquish(scale = PressSquishDefaults.RowScale),
            title = title,
            checked = value ?: base,
            onCheckedChange = onChange,
        )
    }
}
