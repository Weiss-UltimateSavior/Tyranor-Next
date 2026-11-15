package com.tyranor.next.theme

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
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

    /**
     * 系统深浅色变化的自动换皮过程中暂时保持的旧值（见 SystemThemeSync）：截完旧画面前，refresh 不能改成新的系统深浅色，
     * 否则新主题会先于覆盖层出现一帧。非 null 期间「跟随系统」解析为该值；换皮应用时清空。
     */
    var systemDarkHold: Boolean? = null

    /** 外观风格（默认 / 复古玻璃 / 白雾玻璃）；变化时全 App 重组（背景/容器/文字随之切换）。 */
    var appearanceStyle by mutableStateOf(AppearanceStyle.DEFAULT)
        private set

    /**
     * 应用统一圆角（dp，5..50 共十档）：风格无关，卡片/条目/弹窗/抽屉统一取本值。
     * 变化时全 App 重组（`theme/AppShapes.kt` 的 getter 直接读取）。
     */
    var appCornerRadius by mutableStateOf(AppSettingsStore.DEFAULT_APP_CORNER_RADIUS)
        private set

    /**
     * 是否处于复古玻璃外观：深色玻璃面、白色文字、禁用外观模式等规则共用。
     * 派生自 [appearanceStyle]，供既有调用点零改动沿用。
     */
    val isGlass: Boolean
        get() = appearanceStyle.isGlass

    /** 是否处于白雾玻璃外观：图片底图 + 白雾条目 + 深色正文。 */
    val isMilkGlass: Boolean
        get() = appearanceStyle.isMilkGlass

    /** 页面背景是否为固定深色画面（复古玻璃 / 白雾玻璃），系统栏需浅色图标。 */
    val hasDarkBackdrop: Boolean
        get() = appearanceStyle.hasDarkBackdrop

    /** 是否使用固定配色（禁用「深浅色切换」）。 */
    val hasFixedContentPalette: Boolean
        get() = appearanceStyle.hasFixedContentPalette

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

    private fun systemIsDark(context: Context): Boolean = try {
        (android.content.res.Resources.getSystem().configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
    } catch (_: RuntimeException) {
        AppSettingsStore.isDarkEffective(context)
    }

    /** 从存储重读主题色与外观风格并广播变更（system 模式按系统当前深/浅解析）。
     *  复古玻璃固定深色；白雾玻璃固定浅色（条目为亮面黑字），两者都禁用深浅色切换。 */
    fun refresh(context: Context) {
        appearanceStyle = AppSettingsStore.getAppearanceStyle(context)
        primary = parseColorHex(AppSettingsStore.getThemeColorHex(context))
        isDark = when {
            appearanceStyle.isGlass -> true
            appearanceStyle.isMilkGlass -> false
            AppSettingsStore.getThemeMode(context) == AppSettingsStore.THEME_MODE_SYSTEM ->
                // 跟随系统：直接读系统全局配置（始终是最新的，不依赖可能过期的包装 context）；换皮过渡期间保持旧值
                systemDarkHold ?: systemIsDark(context)
            else -> AppSettingsStore.isDarkEffective(context)
        }
        appCornerRadius = AppSettingsStore.getAppCornerRadius(context)
        // 同步主题色快照给 core 启动编排（EngineLauncher 注入引擎 Intent 用），
        // 维持 core 层不反向依赖 theme 的依赖方向。
        ThemeColorPayloadStore.current = ThemeColorPayload(
            // 引擎壳沿用内容配色：白雾玻璃是亮面深字，不能因为页面底图深就把引擎 UI 标成 dark。
            darkMode = isDark,
            primaryArgb = primaryArgb,
            onPrimaryArgb = 0xFFFFFFFF.toInt(),
            cardArgb = when {
                appearanceStyle.isGlass -> GlassPanel.toArgb()
                appearanceStyle.isMilkGlass -> MilkSurfaceHigh.toArgb()
                isDark -> 0xFF1E1F1F.toInt()
                else -> 0xFFFFFFFF.toInt()
            },
            textArgb = when {
                appearanceStyle.isGlass -> GlassText.toArgb()
                appearanceStyle.isMilkGlass -> MilkText.toArgb()
                isDark -> 0xFFF0F0F0.toInt()
                else -> 0xFF14221B.toInt()
            },
            mutedArgb = when {
                appearanceStyle.isGlass -> GlassTextSecondary.toArgb()
                appearanceStyle.isMilkGlass -> MilkTextSecondary.toArgb()
                isDark -> 0xFF9A9A9A.toInt()
                else -> 0xFF82908A.toInt()
            },
        )
    }

    /** 设置页调整圆角时的即时生效（仅内存；持久化由调用方 `setAppCornerRadius` 负责）。 */
    fun applyAppCornerRadius(radius: Int) {
        appCornerRadius = AppSettingsStore.normalizeAppCornerRadius(radius)
    }
}

/** 解析 #RRGGBB 为 Compose Color，非法值回退默认蓝。 */
fun parseColorHex(hex: String): Color = try {
    Color(0xFF000000 or hex.removePrefix("#").toLong(16))
} catch (t: Throwable) {
    Blue40
}
