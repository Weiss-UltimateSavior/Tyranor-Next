package com.tyranor.next.core.settings

import android.content.Context
import android.content.res.Configuration
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf

/**
 * 应用设置存储层：与引擎无关的应用级偏好（如主题色、导航栏样式）。
 * 使用独立 prefs 文件 app_settings，避免混入引擎进程读取的 yukihub_prefs。
 */
object AppSettingsStore {

    const val KEY_THEME_COLOR = "theme_color"
    const val KEY_NAV_STYLE = "nav_style"
    const val KEY_SCAN_DEPTH = "scan_depth"
    const val KEY_LANGUAGE = "language"
    const val KEY_THEME_MODE = "theme_mode"
    const val KEY_TONE_SWITCH = "tone_switch"
    const val KEY_GAME_SORT = "game_sort"
    const val KEY_COVER_SCRAPER_ONLY_MISSING = "cover_scraper_only_missing"
    const val KEY_COVER_SCRAPER_SOURCE_ORDER = "cover_scraper_source_order"
    private const val KEY_COVER_SCRAPER_SOURCE_ENABLED_PREFIX = "cover_scraper_source_enabled_"

    const val COVER_SOURCE_HIKARINAGI = "hikarinagi"
    const val COVER_SOURCE_BANGUMI = "bangumi"
    const val COVER_SOURCE_STEAM = "steam"
    const val COVER_SOURCE_VNDB = "vndb"
    const val COVER_SOURCE_LOCAL = "local"
    const val COVER_SOURCE_CUSTOM = "custom"

    val DEFAULT_COVER_SCRAPER_SOURCES = listOf(
        COVER_SOURCE_VNDB,
        COVER_SOURCE_HIKARINAGI,
        COVER_SOURCE_BANGUMI,
        COVER_SOURCE_STEAM,
    )

    /** 默认主题色：#307DEF，与 theme/Color.kt 的 Blue40 一致。 */
    const val DEFAULT_THEME_COLOR = "#307DEF"

    /** App 内语言：跟随系统。 */
    const val LANGUAGE_SYSTEM = "system"

    /** App 内语言：简体中文。 */
    const val LANGUAGE_ZH = "zh"

    /** App 内语言：日文。 */
    const val LANGUAGE_JA = "ja"

    /** App 内语言：英文。 */
    const val LANGUAGE_EN = "en"

    /** App 语言内存态：设置页切换后根 Composable 可即时重组。 */
    val languageState: MutableState<String> = mutableStateOf(LANGUAGE_ZH)

    /** 外观模式：浅色。 */
    const val THEME_MODE_LIGHT = "light"

    /** 外观模式：深色。 */
    const val THEME_MODE_DARK = "dark"

    /** 外观模式：跟随系统深/浅色。 */
    const val THEME_MODE_SYSTEM = "system"

    /** 文件夹扫描深度默认值（层级，1..5）。 */
    const val DEFAULT_SCAN_DEPTH = 3

    /** 色调切换默认关闭：中性灰页面背景 + 白色组件。 */
    const val DEFAULT_TONE_SWITCH_ENABLED = false

    /** 游戏排序：按标题字母/字符顺序。 */
    const val GAME_SORT_ALPHA = "alpha"

    /** 游戏排序：按标题中 【】/[] 标签内容分组。 */
    const val GAME_SORT_BRACKET_TAG = "bracket_tag"

    /** 底部导航栏样式：默认。 */
    const val NAV_STYLE_DEFAULT = "default"

    /** 底部导航栏样式：圆角液态玻璃（流体玻璃）。 */
    const val NAV_STYLE_LIQUID_GLASS = "liquid_glass"

    /** 导航栏样式内存态：随设置页切换即时广播，供 MainScreen 重组切换样式。 */
    val navStyleState: MutableState<String> = mutableStateOf(NAV_STYLE_DEFAULT)

    /** 游戏排序内存态：设置页切换后游戏页可随重组读取。 */
    val gameSortState: MutableState<String> = mutableStateOf(GAME_SORT_ALPHA)

    /** 封面刮削设置内存态：设置页修改后游戏页可即时读取。 */
    val coverScraperSettingsVersion: MutableState<Int> = mutableStateOf(0)

    /** 首次组合时从持久化加载导航栏样式到内存态（幂等，重复调用仅重新读一次）。 */
    fun initNavStyle(c: Context) {
        navStyleState.value = getNavStyle(c)
    }

    fun initLanguage(c: Context) {
        languageState.value = getLanguage(c)
    }

    fun initGameSort(c: Context) {
        gameSortState.value = getGameSort(c)
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences("app_settings", Context.MODE_PRIVATE)

    /** 当前主题色 HEX（#RRGGBB）。 */
    fun getThemeColorHex(c: Context): String =
        prefs(c).getString(KEY_THEME_COLOR, DEFAULT_THEME_COLOR) ?: DEFAULT_THEME_COLOR

    fun setThemeColorHex(c: Context, hex: String) =
        prefs(c).edit().putString(KEY_THEME_COLOR, hex).apply()

    fun getLanguage(c: Context): String =
        normalizeLanguage(prefs(c).getString(KEY_LANGUAGE, LANGUAGE_ZH))

    fun setLanguage(c: Context, language: String) {
        val normalized = normalizeLanguage(language)
        prefs(c).edit().putString(KEY_LANGUAGE, normalized).apply()
        languageState.value = normalized
    }

    /** 当前底部导航栏样式（默认 / 液态玻璃）。 */
    fun getNavStyle(c: Context): String =
        prefs(c).getString(KEY_NAV_STYLE, NAV_STYLE_DEFAULT) ?: NAV_STYLE_DEFAULT

    fun setNavStyle(c: Context, style: String) {
        prefs(c).edit().putString(KEY_NAV_STYLE, style).apply()
        navStyleState.value = style
    }

    /** 文件夹扫描深度（1..5，默认 3）。 */
    fun getScanDepth(c: Context): Int =
        prefs(c).getInt(KEY_SCAN_DEPTH, DEFAULT_SCAN_DEPTH).coerceIn(1, 5)

    fun setScanDepth(c: Context, depth: Int) =
        prefs(c).edit().putInt(KEY_SCAN_DEPTH, depth.coerceIn(1, 5)).apply()

    fun getGameSort(c: Context): String =
        when (prefs(c).getString(KEY_GAME_SORT, GAME_SORT_ALPHA)) {
            GAME_SORT_BRACKET_TAG -> GAME_SORT_BRACKET_TAG
            else -> GAME_SORT_ALPHA
        }

    fun setGameSort(c: Context, sort: String) {
        val normalized = when (sort) {
            GAME_SORT_BRACKET_TAG -> GAME_SORT_BRACKET_TAG
            else -> GAME_SORT_ALPHA
        }
        prefs(c).edit().putString(KEY_GAME_SORT, normalized).apply()
        gameSortState.value = normalized
    }

    fun isCoverScraperOnlyMissing(c: Context): Boolean =
        prefs(c).getBoolean(KEY_COVER_SCRAPER_ONLY_MISSING, true)

    fun setCoverScraperOnlyMissing(c: Context, onlyMissing: Boolean) {
        prefs(c).edit().putBoolean(KEY_COVER_SCRAPER_ONLY_MISSING, onlyMissing).apply()
        bumpCoverScraperSettingsVersion()
    }

    fun getCoverScraperSourceOrder(c: Context): List<String> {
        val stored = prefs(c).getString(KEY_COVER_SCRAPER_SOURCE_ORDER, null)
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it in DEFAULT_COVER_SCRAPER_SOURCES }
            .orEmpty()
        return (stored + DEFAULT_COVER_SCRAPER_SOURCES).distinct()
    }

    fun setCoverScraperSourceOrder(c: Context, sources: List<String>) {
        val normalized = (sources.filter { it in DEFAULT_COVER_SCRAPER_SOURCES } + DEFAULT_COVER_SCRAPER_SOURCES)
            .distinct()
        prefs(c).edit().putString(KEY_COVER_SCRAPER_SOURCE_ORDER, normalized.joinToString(",")).apply()
        bumpCoverScraperSettingsVersion()
    }

    fun isCoverScraperSourceEnabled(c: Context, source: String): Boolean {
        if (source !in DEFAULT_COVER_SCRAPER_SOURCES) return false
        return prefs(c).getBoolean(KEY_COVER_SCRAPER_SOURCE_ENABLED_PREFIX + source, true)
    }

    fun setCoverScraperSourceEnabled(c: Context, source: String, enabled: Boolean) {
        if (source !in DEFAULT_COVER_SCRAPER_SOURCES) return
        prefs(c).edit().putBoolean(KEY_COVER_SCRAPER_SOURCE_ENABLED_PREFIX + source, enabled).apply()
        bumpCoverScraperSettingsVersion()
    }

    fun moveCoverScraperSource(c: Context, source: String, offset: Int) {
        val sources = getCoverScraperSourceOrder(c).toMutableList()
        val index = sources.indexOf(source)
        if (index < 0) return
        val target = (index + offset).coerceIn(0, sources.lastIndex)
        if (target == index) return
        val item = sources.removeAt(index)
        sources.add(target, item)
        setCoverScraperSourceOrder(c, sources)
    }

    /** 外观模式（跟随系统/浅色/深色）。 */
    fun getThemeMode(c: Context): String =
        prefs(c).getString(KEY_THEME_MODE, THEME_MODE_LIGHT) ?: THEME_MODE_LIGHT

    fun setThemeMode(c: Context, mode: String) =
        prefs(c).edit().putString(KEY_THEME_MODE, mode).apply()

    /** 色调切换：开启时使用白色页面背景 + 中性灰组件；关闭时使用中性灰页面背景 + 白色组件。 */
    fun isToneSwitchEnabled(c: Context): Boolean =
        prefs(c).getBoolean(KEY_TONE_SWITCH, DEFAULT_TONE_SWITCH_ENABLED)

    fun setToneSwitchEnabled(c: Context, enabled: Boolean) =
        prefs(c).edit().putBoolean(KEY_TONE_SWITCH, enabled).apply()

    /** 系统当前是否深色模式（资源配置 uiMode）。 */
    fun isSystemDark(c: Context): Boolean =
        (c.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    /** 实际生效的深色状态：dark 恒深色，system 跟随系统，其余（含 light 与未知值）为浅色。 */
    fun isDarkEffective(c: Context): Boolean = when (getThemeMode(c)) {
        THEME_MODE_DARK -> true
        THEME_MODE_SYSTEM -> isSystemDark(c)
        else -> false
    }

    private fun bumpCoverScraperSettingsVersion() {
        coverScraperSettingsVersion.value += 1
    }

    private fun normalizeLanguage(language: String?): String = when (language) {
        LANGUAGE_SYSTEM -> LANGUAGE_SYSTEM
        LANGUAGE_JA -> LANGUAGE_JA
        LANGUAGE_EN -> LANGUAGE_EN
        else -> LANGUAGE_ZH
    }
}
