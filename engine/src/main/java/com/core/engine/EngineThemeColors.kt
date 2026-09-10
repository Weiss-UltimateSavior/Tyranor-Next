package com.core.engine

import android.content.Intent

/**
 * 引擎模块统一主题色板（单一来源）。
 *
 * 启动器侧 LauncherUiBridge.appendEngineThemeExtras 会把启动器主题色写入引擎 Intent extras
 * （primaryColor / themeColorOnPrimary / themeColorCard / themeColorText / themeColorTextMuted /
 * darkMode）。engine 内所有自绘 UI（确认/输入弹窗、菜单等）统一从这里读取，在 engine 模块内
 * 复刻 LauncherDialogFactory 的视觉风格，不依赖 app 模块资源。
 */
object EngineThemeColors {

    data class Palette(
        val primary: Int,
        val onPrimary: Int,
        val card: Int,
        val text: Int,
        val textMuted: Int,
        /** 全屏游戏壳窗口/系统栏底色：固定黑色，任何 SurfaceView 未覆盖区域均非白底。 */
        val background: Int = 0xFF000000.toInt(),
    )

    /**
     * 从 Intent extras 读取主题色，缺失时按 darkMode 回落到 Launcher 默认色值。
     * 各引擎启动链路（Krkrsdl3Launcher / ScriptEngineLaunchers / ArtemisLauncher）均写入完整
     * extras，回落值仅作兜底；回落色一律使用 8 位 ARGB hex，避免 6 位 hex 高字节 alpha=0
     * 导致 GradientDrawable/TextView 渲染透明。
     */
    @JvmStatic
    fun fromIntent(intent: Intent?): Palette {
        val extras = intent?.extras
        val dark = extras?.getBoolean(LaunchContract.DARK_MODE, false) ?: false
        // 缺失 Intent extras 时按 darkMode 回落到 Launcher 默认色值
        val primary = if (dark) 0xFF22D88E.toInt() else 0xFF18B978.toInt()
        val onPrimary = if (dark) 0xFF06120D.toInt() else 0xFFFFFFFF.toInt()
        val card = if (dark) 0xFF1E1F1F.toInt() else 0xFFFFFFFF.toInt()
        val text = if (dark) 0xFFF0F0F0.toInt() else 0xFF14221B.toInt()
        val textMuted = if (dark) 0xFF9A9A9A.toInt() else 0xFF82908A.toInt()
        return Palette(
            primary = extras?.getInt(LaunchContract.PRIMARY_COLOR, primary) ?: primary,
            onPrimary = extras?.getInt(LaunchContract.THEME_COLOR_ON_PRIMARY, onPrimary) ?: onPrimary,
            card = extras?.getInt(LaunchContract.THEME_COLOR_CARD, card) ?: card,
            text = extras?.getInt(LaunchContract.THEME_COLOR_TEXT, text) ?: text,
            textMuted = extras?.getInt(LaunchContract.THEME_COLOR_TEXT_MUTED, textMuted) ?: textMuted,
        )
    }
}
