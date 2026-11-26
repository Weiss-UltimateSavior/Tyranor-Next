package com.core.engine

import android.content.Intent

/**
 * 引擎模块统一主题色板（单一来源）。
 *
 * 应用侧 EngineLauncher 将主题色快照写入引擎 Intent extras
 * （primaryColor / themeColorOnPrimary / themeColorCard / themeColorText / themeColorTextMuted /
 * darkMode）。引擎宿主在这里读取色板，公共 KRKR 弹窗和菜单共用同一来源。
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
     * 内置宿主由 EngineLauncher 传入完整色板。回落值使用不透明 ARGB。
     */
    @JvmStatic
    fun fromIntent(intent: Intent?): Palette {
        val extras = intent?.extras
        val dark = extras?.getBoolean(LaunchContract.DARK_MODE, false) ?: false
        val primary = if (dark) 0xFF22D88E.toInt() else 0xFF18B978.toInt()
        val onPrimary = if (dark) 0xFF06120D.toInt() else 0xFFFFFFFF.toInt()
        val card = if (dark) 0xFF1E1F1F.toInt() else 0xFFFFFFFF.toInt()
        val text = if (dark) 0xFFF0F0F0.toInt() else 0xFF14221B.toInt()
        val textMuted = if (dark) 0xFF9A9A9A.toInt() else 0xFF82908A.toInt()
        val legacyPrimary = extras?.getInt(LaunchContract.PRIMARY_COLOR, primary) ?: primary
        return Palette(
            primary = extras?.getInt(LaunchContract.THEME_COLOR_PRIMARY, legacyPrimary) ?: legacyPrimary,
            onPrimary = extras?.getInt(LaunchContract.THEME_COLOR_ON_PRIMARY, onPrimary) ?: onPrimary,
            card = extras?.getInt(LaunchContract.THEME_COLOR_CARD, card) ?: card,
            text = extras?.getInt(LaunchContract.THEME_COLOR_TEXT, text) ?: text,
            textMuted = extras?.getInt(LaunchContract.THEME_COLOR_TEXT_MUTED, textMuted) ?: textMuted,
        )
    }
}
