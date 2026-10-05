package com.tyranor.next.core.settings

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 应用设置存储层：与引擎无关的应用级偏好（如主题色、导航栏样式）。
 * 使用独立 prefs 文件 app_settings，避免混入引擎进程读取的 tyranor_prefs。
 */
object AppSettingsStore {

    const val KEY_THEME_COLOR = "theme_color"
    const val KEY_NAV_STYLE = "nav_style"
    const val KEY_LIQUID_GLASS_ENHANCE = "liquid_glass_enhance"
    const val KEY_APPEARANCE_STYLE = "appearance_style"
    const val KEY_SCAN_DEPTH = "scan_depth"
    const val KEY_LANGUAGE = "language"
    const val KEY_THEME_MODE = "theme_mode"
    const val KEY_TONE_SWITCH = "tone_switch"
    const val KEY_GAME_SORT = "game_sort"

    /** 首页样式：原生（最近打开/快捷启动）或网页。 */
    const val KEY_HOME_STYLE = "home_style"

    /** Web 首页展示的地址。 */
    const val KEY_HOME_WEB_URL = "home_web_url"

    /** 游戏页卡片隐藏名称标签（【】/[]）；默认开。 */
    const val KEY_GAME_CARD_HIDE_TITLE_TAG = "game_card_hide_title_tag"

    /** 游戏页卡片风格：网格 / 列表（封面流）；默认网格。 */
    const val KEY_GAME_CARD_STYLE = "game_card_style"

    /** 游戏页卡片左上角引擎类型角标；默认关。 */
    const val KEY_GAME_CARD_BADGE = "game_card_badge"

    /** 默认外观风格的页面背景渐变（纯色+柔光/颜色渐变）；默认开。 */
    const val KEY_DEFAULT_THEME_GRADIENT = "default_theme_gradient"
    const val KEY_ENGINE_TABS = "engine_tabs"
    const val KEY_SIDE_RAIL = "side_rail"
    const val KEY_COVER_SCRAPER_ONLY_MISSING = "cover_scraper_only_missing"
    const val KEY_COVER_SCRAPER_SOURCE_ORDER = "cover_scraper_source_order"
    private const val KEY_COVER_SCRAPER_SOURCE_ENABLED_PREFIX = "cover_scraper_source_enabled_"

    /** VNDB 封面是否下载原图大图（关闭时用缩略图）。 */
    const val KEY_VNDB_LARGE_COVER = "vndb_large_cover"

    /** VNDB 大图开关默认值：开（缩略图仅 256×362，默认直接取原图保证清晰度）。 */
    const val DEFAULT_VNDB_LARGE_COVER = true

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
    val languageState: MutableStateFlow<String> = MutableStateFlow(LANGUAGE_ZH)

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

    /** 卡片隐藏名称标签默认值：开。 */
    const val DEFAULT_GAME_CARD_HIDE_TITLE_TAG = true

    const val GAME_CARD_STYLE_GRID = "grid"
    const val GAME_CARD_STYLE_COVER_FLOW = "cover_flow"

    /** 卡片风格默认值：网格。 */
    const val DEFAULT_GAME_CARD_STYLE = GAME_CARD_STYLE_GRID

    /** 卡片引擎角标默认值：关。 */
    const val DEFAULT_GAME_CARD_BADGE = false

    /** 默认主题渐变默认值：开（应用设置可关闭，回退纯色页面背景）。 */
    const val DEFAULT_DEFAULT_THEME_GRADIENT = true

    /** 底部导航栏样式：默认（Material3 导航栏）。 */
    const val NAV_STYLE_DEFAULT = "default"

    /** 底部导航栏样式：液态玻璃（圆角玻璃栏，Android 12+ 生效）。 */
    const val NAV_STYLE_LIQUID_GLASS = "liquid_glass"

    /** 底部导航栏样式：液态玻璃 · 透镜（三层采样 + 折射透镜，Android 13+ 才有完整效果）。 */
    const val NAV_STYLE_LIQUID_GLASS_ENHANCED = "liquid_glass_enhanced"

    /** 首页样式：原生首页（最近打开 + 快捷启动）。 */
    const val HOME_STYLE_NATIVE = "native"

    /** 首页样式：Web 首页（内置 WebView 展示所选网址）。 */
    const val HOME_STYLE_WEB = "web"

    /** 首页样式默认值：原生。 */
    const val DEFAULT_HOME_STYLE = HOME_STYLE_NATIVE

    /** Web 首页预设：鲲Gal。 */
    const val HOME_WEB_URL_KUNGAL = "https://www.kungal.com"

    /** Web 首页预设：一起萌。 */
    const val HOME_WEB_URL_LETMOE = "https://www.letmoe.com"

    /** Web 首页默认地址：一起萌。 */
    const val DEFAULT_HOME_WEB_URL = HOME_WEB_URL_LETMOE

    /** 透镜档需要 Android 13（API 33）的 RuntimeShader 折射能力；更低版本不提供该选项。 */
    val supportsLiquidGlassEnhanced: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    /** 导航栏样式内存态：随设置页切换即时广播，供 MainScreen 重组切换样式。 */
    val navStyleState: MutableStateFlow<String> = MutableStateFlow(NAV_STYLE_DEFAULT)

    /** 引擎页分类显示默认关闭：关闭时平铺展示全部引擎项，开启后按 GAL/RPGM/主机/网页 分页。 */
    const val DEFAULT_ENGINE_TABS_ENABLED = false

    /** 平板侧边栏默认开启：平板/大窗口下主导航移到侧边，关闭则保持底部导航。 */
    const val DEFAULT_SIDE_RAIL_ENABLED = true

    /** 平板侧边栏内存态：设置页切换后主界面即时重组（平板判定 + 本开关决定是否用侧栏）。 */
    val sideRailState: MutableStateFlow<Boolean> = MutableStateFlow(DEFAULT_SIDE_RAIL_ENABLED)

    /** 引擎页分类显示内存态：设置页切换后引擎页即时重组。 */
    val engineTabsState: MutableStateFlow<Boolean> = MutableStateFlow(DEFAULT_ENGINE_TABS_ENABLED)

    /** 游戏排序内存态：设置页切换后游戏页可随重组读取。 */
    val gameSortState: MutableStateFlow<String> = MutableStateFlow(GAME_SORT_ALPHA)

    /** 卡片隐藏名称标签内存态：设置页切换后游戏页卡片即时重组。 */
    val gameCardTitleTagState: MutableStateFlow<Boolean> = MutableStateFlow(DEFAULT_GAME_CARD_HIDE_TITLE_TAG)

    /** 卡片风格内存态：设置页切换后游戏页即时切换布局。 */
    val gameCardStyleState: MutableStateFlow<String> = MutableStateFlow(DEFAULT_GAME_CARD_STYLE)

    /** 卡片引擎角标内存态：设置页切换后游戏页卡片即时重组。 */
    val gameCardBadgeState: MutableStateFlow<Boolean> = MutableStateFlow(DEFAULT_GAME_CARD_BADGE)

    /** 默认主题渐变内存态：设置页切换后页面背景即时重组。 */
    val defaultThemeGradientState: MutableStateFlow<Boolean> = MutableStateFlow(DEFAULT_DEFAULT_THEME_GRADIENT)

    /** 首页样式内存态：设置页切换后主界面首页即时切换原生/网页形态。 */
    val homeStyleState: MutableStateFlow<String> = MutableStateFlow(DEFAULT_HOME_STYLE)

    /** Web 首页地址内存态：设置页修改后网页首页即时加载新地址。 */
    val homeWebUrlState: MutableStateFlow<String> = MutableStateFlow(DEFAULT_HOME_WEB_URL)

    /** 封面刮削设置内存态：设置页修改后游戏页可即时读取。 */
    val coverScraperSettingsVersion: MutableStateFlow<Int> = MutableStateFlow(0)

    /** 导航样式读写的串行锁：迁移的读改写与用户写入必须互斥（见 [initNavStyle]）。 */
    private val navStyleLock = Any()

    /** 首页样式读写的串行锁：init 的异步加载与用户写入必须互斥（同 [navStyleLock] 的理由）。 */
    private val homeStyleLock = Any()

    /**
     * 首次组合时从持久化加载导航栏样式到内存态（幂等）。
     *
     * 与 [setNavStyle] 共用 [navStyleLock]：设置页在 IO 线程调用本方法的同时，下拉仍可交互，
     * 若不加锁，迁移的「读—改—写」可能覆盖用户在这一窗口内刚选择的样式。
     */
    fun initNavStyle(c: Context) {
        synchronized(navStyleLock) {
            migrateLegacyEnhanceFlag(c)
            navStyleState.value = getNavStyle(c)
        }
    }

    /**
     * 迁移：早期实现把「透镜档」存成独立的 `liquid_glass_enhance` 布尔开关，
     * 现已合并进 [KEY_NAV_STYLE] 的三态取值。这里做一次性升级并清掉旧键。
     */
    private fun migrateLegacyEnhanceFlag(c: Context) {
        val p = prefs(c)
        // 用 contains 而不是 getBoolean：旧键存在但值为 false 时也要清掉，否则它永远留在磁盘上
        if (!p.contains(KEY_LIQUID_GLASS_ENHANCE)) return
        val legacyEnhanced = p.getBoolean(KEY_LIQUID_GLASS_ENHANCE, false)
        val stored = p.getString(KEY_NAV_STYLE, NAV_STYLE_DEFAULT)
        val editor = p.edit().remove(KEY_LIQUID_GLASS_ENHANCE)
        if (legacyEnhanced && stored == NAV_STYLE_LIQUID_GLASS) {
            // 与 setNavStyle 一致地归一化：低版本设备读到备份里的透镜档时降为经典档
            editor.putString(
                KEY_NAV_STYLE,
                normalizeNavStyle(NAV_STYLE_LIQUID_GLASS_ENHANCED, supportsLiquidGlassEnhanced),
            )
        }
        editor.apply()
    }

    fun initLanguage(c: Context) {
        languageState.value = getLanguage(c)
    }

    fun initGameSort(c: Context) {
        gameSortState.value = getGameSort(c)
    }

    /** 首次组合时从持久化加载引擎页分类显示开关到内存态（幂等）。 */
    fun initEngineTabs(c: Context) {
        engineTabsState.value = isEngineTabsEnabled(c)
    }

    /** 首次组合时从持久化加载平板侧边栏开关到内存态（幂等）。 */
    fun initSideRail(c: Context) {
        sideRailState.value = isSideRailEnabled(c)
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

    /** 当前底部导航栏样式（默认 / 经典 / 透镜）。 */
    fun getNavStyle(c: Context): String =
        synchronized(navStyleLock) {
            normalizeNavStyle(
                stored = prefs(c).getString(KEY_NAV_STYLE, NAV_STYLE_DEFAULT),
                enhancedSupported = supportsLiquidGlassEnhanced,
            )
        }

    fun setNavStyle(c: Context, style: String) {
        val normalized = normalizeNavStyle(style, supportsLiquidGlassEnhanced)
        synchronized(navStyleLock) {
            prefs(c).edit().putString(KEY_NAV_STYLE, normalized).apply()
            navStyleState.value = normalized
        }
    }

    /**
     * 导航样式归一化（纯函数，便于单元测试）：
     * 未知值回退默认；透镜档在不支持的版本（< Android 13）回退普通档——
     * 这样即使从更新的设备备份恢复数据，旧设备也只会得到普通档而不是降级画面。
     */
    fun normalizeNavStyle(stored: String?, enhancedSupported: Boolean): String =
        when (stored) {
            NAV_STYLE_LIQUID_GLASS -> NAV_STYLE_LIQUID_GLASS
            NAV_STYLE_LIQUID_GLASS_ENHANCED ->
                if (enhancedSupported) NAV_STYLE_LIQUID_GLASS_ENHANCED else NAV_STYLE_LIQUID_GLASS
            else -> NAV_STYLE_DEFAULT
        }

    /** 当前外观风格（默认 / 复古玻璃 / 高级玻璃；未知值归一为默认）。 */
    fun getAppearanceStyle(c: Context): AppearanceStyle =
        AppearanceStyle.fromStorage(prefs(c).getString(KEY_APPEARANCE_STYLE, null))

    fun setAppearanceStyle(c: Context, style: AppearanceStyle) =
        prefs(c).edit().putString(KEY_APPEARANCE_STYLE, style.storageValue).apply()

    /** 文件夹扫描深度（1..5，默认 3）。 */
    fun getScanDepth(c: Context): Int =
        prefs(c).getInt(KEY_SCAN_DEPTH, DEFAULT_SCAN_DEPTH).coerceIn(1, 5)

    fun setScanDepth(c: Context, depth: Int) =
        prefs(c).edit().putInt(KEY_SCAN_DEPTH, depth.coerceIn(1, 5)).apply()

    /** 首次组合时从持久化加载「卡片隐藏名称标签」到内存态（幂等）。 */
    fun initGameCardHideTitleTag(c: Context) {
        gameCardTitleTagState.value = isGameCardHideTitleTag(c)
    }

    fun isGameCardHideTitleTag(c: Context): Boolean =
        prefs(c).getBoolean(KEY_GAME_CARD_HIDE_TITLE_TAG, DEFAULT_GAME_CARD_HIDE_TITLE_TAG)

    fun setGameCardHideTitleTag(c: Context, hidden: Boolean) {
        prefs(c).edit().putBoolean(KEY_GAME_CARD_HIDE_TITLE_TAG, hidden).apply()
        gameCardTitleTagState.value = hidden
    }

    /** 首次组合时从持久化加载「卡片风格」到内存态（幂等）。 */
    /** 首次组合时从持久化加载「默认主题渐变」到内存态（幂等）。 */
    fun initDefaultThemeGradient(c: Context) {
        defaultThemeGradientState.value = isDefaultThemeGradientEnabled(c)
    }

    fun isDefaultThemeGradientEnabled(c: Context): Boolean =
        prefs(c).getBoolean(KEY_DEFAULT_THEME_GRADIENT, DEFAULT_DEFAULT_THEME_GRADIENT)

    fun setDefaultThemeGradientEnabled(c: Context, enabled: Boolean) {
        prefs(c).edit().putBoolean(KEY_DEFAULT_THEME_GRADIENT, enabled).apply()
        defaultThemeGradientState.value = enabled
    }

    /** 首次组合时从持久化加载「卡片引擎角标」到内存态（幂等）。 */
    fun initGameCardBadge(c: Context) {
        gameCardBadgeState.value = isGameCardBadgeEnabled(c)
    }

    /** 首次组合时从持久化加载首页样式与 Web 首页地址到内存态（幂等）。 */
    fun initHomeStyle(c: Context) {
        synchronized(homeStyleLock) {
            homeStyleState.value = getHomeStyle(c)
            homeWebUrlState.value = getHomeWebUrl(c)
        }
    }

    /** 首页样式归一：仅接受 web，其余（含空/未知）回退原生。 */
    fun getHomeStyle(c: Context): String =
        when (prefs(c).getString(KEY_HOME_STYLE, HOME_STYLE_NATIVE)) {
            HOME_STYLE_WEB -> HOME_STYLE_WEB
            else -> HOME_STYLE_NATIVE
        }

    fun setHomeStyle(c: Context, style: String) {
        val normalized = if (style == HOME_STYLE_WEB) HOME_STYLE_WEB else HOME_STYLE_NATIVE
        synchronized(homeStyleLock) {
            prefs(c).edit().putString(KEY_HOME_STYLE, normalized).apply()
            homeStyleState.value = normalized
        }
    }

    /** 当前 Web 首页地址；磁盘值非法/为空时回退默认（一起萌）。 */
    fun getHomeWebUrl(c: Context): String =
        prefs(c).getString(KEY_HOME_WEB_URL, DEFAULT_HOME_WEB_URL)
            ?.let { HomeWebUrls.normalize(it) }
            ?: DEFAULT_HOME_WEB_URL

    fun setHomeWebUrl(c: Context, url: String) {
        val normalized = HomeWebUrls.normalize(url) ?: DEFAULT_HOME_WEB_URL
        synchronized(homeStyleLock) {
            prefs(c).edit().putString(KEY_HOME_WEB_URL, normalized).apply()
            homeWebUrlState.value = normalized
        }
    }

    fun isGameCardBadgeEnabled(c: Context): Boolean =
        prefs(c).getBoolean(KEY_GAME_CARD_BADGE, DEFAULT_GAME_CARD_BADGE)

    fun setGameCardBadgeEnabled(c: Context, enabled: Boolean) {
        prefs(c).edit().putBoolean(KEY_GAME_CARD_BADGE, enabled).apply()
        gameCardBadgeState.value = enabled
    }

    fun initGameCardStyle(c: Context) {
        gameCardStyleState.value = getGameCardStyle(c)
    }

    fun getGameCardStyle(c: Context): String =
        normalizeGameCardStyle(prefs(c).getString(KEY_GAME_CARD_STYLE, DEFAULT_GAME_CARD_STYLE))

    /** 卡片风格归一：仅接受 cover_flow / grid，其余（含空）回退网格。 */
    fun normalizeGameCardStyle(style: String?): String =
        if (style?.trim()?.lowercase() == GAME_CARD_STYLE_COVER_FLOW) GAME_CARD_STYLE_COVER_FLOW else GAME_CARD_STYLE_GRID

    fun setGameCardStyle(c: Context, style: String) {
        val normalized = normalizeGameCardStyle(style)
        prefs(c).edit().putString(KEY_GAME_CARD_STYLE, normalized).apply()
        gameCardStyleState.value = normalized
    }

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

    /** 引擎页是否按分类（GAL / RPGM / 主机 / 网页）分页展示。 */
    fun isEngineTabsEnabled(c: Context): Boolean =
        prefs(c).getBoolean(KEY_ENGINE_TABS, DEFAULT_ENGINE_TABS_ENABLED)

    fun setEngineTabsEnabled(c: Context, enabled: Boolean) {
        prefs(c).edit().putBoolean(KEY_ENGINE_TABS, enabled).apply()
        engineTabsState.value = enabled
    }

    /** 平板/大窗口下是否使用侧边导航（默认开）。 */
    fun isSideRailEnabled(c: Context): Boolean =
        prefs(c).getBoolean(KEY_SIDE_RAIL, DEFAULT_SIDE_RAIL_ENABLED)

    fun setSideRailEnabled(c: Context, enabled: Boolean) {
        prefs(c).edit().putBoolean(KEY_SIDE_RAIL, enabled).apply()
        sideRailState.value = enabled
    }

    fun isCoverScraperOnlyMissing(c: Context): Boolean =
        prefs(c).getBoolean(KEY_COVER_SCRAPER_ONLY_MISSING, true)

    fun setCoverScraperOnlyMissing(c: Context, onlyMissing: Boolean) {
        prefs(c).edit().putBoolean(KEY_COVER_SCRAPER_ONLY_MISSING, onlyMissing).apply()
        bumpCoverScraperSettingsVersion()
    }

    fun isVndbLargeCover(c: Context): Boolean =
        prefs(c).getBoolean(KEY_VNDB_LARGE_COVER, DEFAULT_VNDB_LARGE_COVER)

    fun setVndbLargeCover(c: Context, enabled: Boolean) {
        prefs(c).edit().putBoolean(KEY_VNDB_LARGE_COVER, enabled).apply()
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
