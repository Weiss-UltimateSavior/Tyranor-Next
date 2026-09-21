package com.tyranor.next.theme

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toArgb
import com.tyranor.next.core.settings.AppSettingsStore
import com.tyranor.next.core.settings.AppearanceStyle
import com.tyranor.next.core.theme.ThemeColorPayload
import com.tyranor.next.core.theme.ThemeColorPayloadStore

/**
 * 全局主题色：读写 AppSettingsStore，变化时通过 snapshot state 通知所有已组合页面
 * 即时重组（应用设置「色调轮盘」确认后全 App 生效）。
 */
object AppThemeColors {
    private var loaded = false
    var primary by mutableStateOf(Blue40)
        private set

    /** 深色模式（应用设置「外观模式」）；变化时全 App 重组。 */
    var isDark by mutableStateOf(false)
        private set

    /** 外观风格（默认 / 复古玻璃 / 高级玻璃）；变化时全 App 重组（背景/容器/文字随之切换）。 */
    var appearanceStyle by mutableStateOf(AppearanceStyle.DEFAULT)
        private set

    /**
     * 是否处于玻璃系外观（复古 / 高级）：透明页面背景、玻璃面、禁用外观模式等规则共用。
     * 派生自 [appearanceStyle]，供既有调用点零改动沿用。
     */
    val isGlass: Boolean
        get() = appearanceStyle.isGlass

    /** 是否为高级玻璃档（封面拼贴模糊背景 + 浅色磨砂膜 + 光学描边；真采样增强仅 API 31+）。 */
    val isAdvancedGlass: Boolean
        get() = appearanceStyle.isAdvancedGlass

    /**
     * 高级玻璃的封面拼贴模糊底图；由 MainScreen 依游戏库封面生成后写入（见
     * `ui/common/glass/AmbientBackdrop.kt`），离开高级玻璃时清空释放内存。
     * 为 null 时背景退化为主题色软色斑（库内无封面 / 尚未生成）。
     */
    var ambientBackdrop by mutableStateOf<ImageBitmap?>(null)
        private set

    fun updateAmbientBackdrop(image: ImageBitmap?) {
        ambientBackdrop = image
    }

    /** 色调切换：控制页面背景色与组件色是否互换。 */
    var toneSwitchEnabled by mutableStateOf(AppSettingsStore.DEFAULT_TONE_SWITCH_ENABLED)
        private set

    /** 当前主题色的 Android ARGB int，用于传给非 Compose 引擎壳。 */
    val primaryArgb: Int
        get() = primary.toArgb()

    /** 首次组合时从存储加载（幂等，避免每次重组都读 prefs）；
     *  跟随系统时每次组合都重读，系统深/浅切换（Activity 重建）后能立即拿到新值。 */
    fun ensureLoaded(context: Context) {
        if (!loaded || AppSettingsStore.getThemeMode(context) == AppSettingsStore.THEME_MODE_SYSTEM) {
            loaded = true
            refresh(context)
        }
    }

    /** 从存储重读主题色与外观风格并广播变更（system 模式按系统当前深/浅解析）。
     *  玻璃系风格下强制深色、禁用色调切换（背景固定深色画面）。 */
    fun refresh(context: Context) {
        appearanceStyle = AppSettingsStore.getAppearanceStyle(context)
        primary = parseColorHex(AppSettingsStore.getThemeColorHex(context))
        isDark = appearanceStyle.isGlass || AppSettingsStore.isDarkEffective(context)
        toneSwitchEnabled = !appearanceStyle.isGlass && AppSettingsStore.isToneSwitchEnabled(context)
        // 同步主题色快照给 core 启动编排（EngineLauncher 注入引擎 Intent 用），
        // 维持 core 层不反向依赖 theme 的依赖方向。
        ThemeColorPayloadStore.current = ThemeColorPayload(
            darkMode = isDark,
            primaryArgb = primaryArgb,
            onPrimaryArgb = 0xFFFFFFFF.toInt(),
            cardArgb = when {
                // 引擎网页壳不支持磨砂膜，取高级玻璃膜在深底上的不透明合成色
                appearanceStyle.isAdvancedGlass -> AdvancedGlassSurfaceSolid.toArgb()
                appearanceStyle.isGlass -> GlassPanel.toArgb()
                isDark -> 0xFF1E1F1F.toInt()
                else -> 0xFFFFFFFF.toInt()
            },
            textArgb = when {
                appearanceStyle.isGlass -> GlassText.toArgb()
                isDark -> 0xFFF0F0F0.toInt()
                else -> 0xFF14221B.toInt()
            },
            mutedArgb = when {
                appearanceStyle.isGlass -> GlassTextSecondary.toArgb()
                isDark -> 0xFF9A9A9A.toInt()
                else -> 0xFF82908A.toInt()
            },
        )
    }
}

/** 解析 #RRGGBB 为 Compose Color，非法值回退默认蓝。 */
fun parseColorHex(hex: String): Color = try {
    Color(0xFF000000 or hex.removePrefix("#").toLong(16))
} catch (t: Throwable) {
    Blue40
}
