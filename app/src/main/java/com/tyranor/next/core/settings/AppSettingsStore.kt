package com.tyranor.next.core.settings

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import androidx.annotation.VisibleForTesting
import androidx.core.content.edit
import com.tyranor.next.core.game.model.GameFilterOptions
import com.tyranor.next.core.game.model.GameLibraryConditions
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

internal data class GameCardCornerSettings(
    val enabled: Boolean = false,
    val radiusDp: Int = AppSettingsStore.DEFAULT_APP_CORNER_RADIUS,
)

internal data class GameCardColumnSettings(
    val narrow: Int = 3,
    val wide: Int = 6,
) {
    fun forScreen(wideScreen: Boolean): Int = if (wideScreen) wide else narrow

    companion object {
        fun range(wideScreen: Boolean): IntRange = if (wideScreen) 2..8 else 2..6
    }
}

/**
 * 应用设置存储层：与引擎无关的应用级偏好（如主题色、导航栏样式）。
 * 使用独立 prefs 文件 app_settings，避免混入引擎进程读取的 tyranor_prefs。
 */
object AppSettingsStore {

    /** 临时关闭首次使用引导展示，待页面入口稳定后再恢复。 */
    const val ONBOARDING_FEATURE_ENABLED = false

    const val KEY_ONBOARDING_COMPLETED = "onboarding_completed"

    private const val ONBOARDING_PENDING_INITIAL_STATE_FILE = "onboarding_initial_state"

    private var onboardingPreferences: OnboardingPreferences? = null

    @Synchronized
    private fun onboarding(context: Context): OnboardingPreferences =
        onboardingPreferences ?: OnboardingPreferences(
            settings = prefs(context),
            pendingStateFile = File(context.applicationContext.filesDir, ONBOARDING_PENDING_INITIAL_STATE_FILE),
        ).also { onboardingPreferences = it }

    /** 在主进程的偏好迁移/游戏库预热前调用；显式 false 防止中途退出被识别为老用户。 */
    fun initializeOnboarding(context: Context): OnboardingWriteResult {
        val settings = prefs(context)
        return onboarding(context).initialize {
            hasExistingOnboardingData(
                appSettingsHasData = settings.all.isNotEmpty(),
                legacyPreferencesHaveData = listOf(
                    AppStorageNames.ENGINE_PREFS,
                    AppStorageNames.LEGACY_ENGINE_PREFS,
                    AppStorageNames.GAME_SCANNER_PREFS,
                ).any { name ->
                    context.getSharedPreferences(name, Context.MODE_PRIVATE).all.isNotEmpty()
                },
                gameLibraryExists = context.getDatabasePath(AppStorageNames.GAME_LIBRARY_DATABASE).exists(),
            )
        }
    }

    /**
     * Centralizes the legacy-install evidence used by [initializeOnboarding]. Keeping this
     * decision pure makes the upgrade/first-install boundary directly regression-testable.
     */
    internal fun hasExistingOnboardingData(
        appSettingsHasData: Boolean,
        legacyPreferencesHaveData: Boolean,
        gameLibraryExists: Boolean,
    ): Boolean = appSettingsHasData || legacyPreferencesHaveData || gameLibraryExists

    @VisibleForTesting
    internal fun resetOnboardingForTests() {
        onboardingPreferences = null
    }

    fun isOnboardingCompleted(context: Context): Boolean =
        onboarding(context).isCompleted()

    /** IO 线程调用；只有写入成功才能离开引导，失败允许用户重试。 */
    fun setOnboardingCompleted(context: Context): OnboardingWriteResult =
        onboarding(context).complete()

    const val KEY_THEME_COLOR = "theme_color"
    const val KEY_NAV_STYLE = "nav_style"
    const val KEY_LIQUID_GLASS_ENHANCE = "liquid_glass_enhance"
    const val KEY_APPEARANCE_STYLE = "appearance_style"
    private const val KEY_DEFAULT_THEME_PARTICLES_ENABLED = "default_theme_particles_enabled"
    private const val KEY_DEFAULT_THEME_PARTICLE_STYLE = "default_theme_particle_style"
    private const val DEFAULT_THEME_PARTICLES_ENABLED = true
    const val KEY_SCAN_DEPTH = "scan_depth"
    const val KEY_APP_CORNER_RADIUS = "app_corner_radius"
    const val KEY_LANGUAGE = "language"
    const val KEY_THEME_MODE = "theme_mode"
    const val KEY_GAME_SORT = "game_sort"

    /** 游戏页引擎筛选（存 `EngineType.name`；[GAME_FILTER_ENGINE_ALL] = 全部）。 */
    const val KEY_GAME_FILTER_ENGINE = "game_filter_engine"

    /** 游戏页游玩状态筛选（[GAME_FILTER_PLAYED_ALL] / [GAME_FILTER_PLAYED_ONLY] / [GAME_FILTER_PLAYED_NONE]）。 */
    const val KEY_GAME_FILTER_PLAYED = "game_filter_played"

    /** 书架风格停留的那一本书（游戏 uri）：退出 / 清后台后再进来还停在这本上。 */
    const val KEY_GAME_SHELF_FOCUS_URI = "game_shelf_focus_uri"

    /** 已启用的第三方 APK 运行时模块包名集合；默认空集（新发现模块默认关闭）。 */
    const val KEY_RUNTIME_MODULE_ENABLED = "runtime_module_enabled"

    /** System4 一次性定点重检测迁移标志（纠正旧库中被误判为 XSYS35 的 System4 游戏）。 */
    private const val KEY_MIGRATION_XSYS4_RESCAN_DONE = "migration_xsys4_rescan_done"

    /** 首页样式：原生（最近打开/快捷启动）/ 网页。 */
    const val KEY_HOME_STYLE = "home_style"

    /** 显示首页：关闭后首页与导航栏首页入口隐藏，游戏页作为第一页；默认关（全新安装生效，老用户升级保留开启）。 */
    const val KEY_HOME_PAGE_ENABLED = "home_page_enabled"

    /** 经典应用图标：开启后启动器显示旧版图标；默认关。 */
    const val KEY_CLASSIC_ICON = "classic_icon_enabled"

    /** Web 首页展示的地址。 */
    const val KEY_HOME_WEB_URL = "home_web_url"

    /** 游戏页卡片隐藏名称标签（【】/[]）；默认开。 */
    const val KEY_GAME_CARD_HIDE_TITLE_TAG = "game_card_hide_title_tag"

    /** 游戏页卡片风格：瀑布（默认）/ 网格 / 封面流 / 书架。 */
    const val KEY_GAME_CARD_STYLE = "game_card_style"

    private const val KEY_GAME_CARD_COLUMNS_NARROW = "game_card_columns_narrow"
    private const val KEY_GAME_CARD_COLUMNS_WIDE = "game_card_columns_wide"
    private const val KEY_GAME_CARD_CORNER_ENABLED = "game_card_corner_enabled"
    private const val KEY_GAME_CARD_CORNER_RADIUS = "game_card_corner_radius"

    /** 游戏页卡片左上角引擎类型角标；默认关。 */
    const val KEY_GAME_CARD_BADGE = "game_card_badge"

    const val KEY_ENGINE_TABS = "engine_tabs"

    /** 引擎页条目左侧图标（引擎横幅图）开关；默认开。 */
    const val KEY_ENGINE_CARD_ICON = "engine_card_icon"

    // ---- 自定义背景图片（应用设置 → 外观）----

    /** 自定义背景图 URI（SAF 持久授权）；空 = 未设置。 */
    const val KEY_BACKGROUND_IMAGE_URI = "background_image_uri"

    /** 自定义背景图默认 URI：空。 */
    const val DEFAULT_BACKGROUND_IMAGE_URI = ""

    /** 背景图透明度（0..100，100 = 完全不透明）；默认 100。 */
    const val KEY_BACKGROUND_IMAGE_ALPHA = "background_image_alpha"
    const val DEFAULT_BACKGROUND_IMAGE_ALPHA = 100

    /** 背景图模糊强度（0..100，0 = 不模糊）；默认 30。 */
    const val KEY_BACKGROUND_IMAGE_BLUR = "background_image_blur"
    const val DEFAULT_BACKGROUND_IMAGE_BLUR = 30

    const val KEY_SIDE_RAIL = "side_rail"

    /** 界面动效档位：true = 增强（默认），false = 普通。 */
    const val KEY_MOTION_ENHANCED = "motion_enhanced"
    const val DEFAULT_MOTION_ENHANCED = true
    const val KEY_COVER_SCRAPER_ONLY_MISSING = "cover_scraper_only_missing"
    const val KEY_COVER_SCRAPER_SOURCE_ORDER = "cover_scraper_source_order"
    private const val KEY_COVER_SCRAPER_SOURCE_ENABLED_PREFIX = "cover_scraper_source_enabled_"

    /** VNDB 封面是否下载原图大图（关闭时用缩略图）。 */
    const val KEY_VNDB_LARGE_COVER = "vndb_large_cover"

    /** VNDB 大图开关默认值：开（缩略图仅 256×362，默认直接取原图保证清晰度）。 */
    const val DEFAULT_VNDB_LARGE_COVER = true

    // ---- 隐私设置（ui/settings/PrivacySettingsActivity；运行时归 core/privacy）----

    /** 安全屏幕：窗口 FLAG_SECURE——最近任务显示空白卡，应用内无法截屏；默认关。 */
    const val KEY_PRIVACY_PREVENT_SCREENSHOT = "privacy_prevent_screenshot"

    /** 安全屏幕默认值：关。 */
    const val DEFAULT_PRIVACY_PREVENT_SCREENSHOT = false

    /** 隐藏通知内容：应用更新通知在系统锁屏上不显示（VISIBILITY_SECRET）；默认关。 */
    const val KEY_PRIVACY_HIDE_NOTIFICATION = "privacy_hide_notification"

    /** 隐藏通知内容默认值：关。 */
    const val DEFAULT_PRIVACY_HIDE_NOTIFICATION = false

    /** 应用锁：启动器界面回前台前要求解锁；默认关。 */
    const val KEY_PRIVACY_APP_LOCK_ENABLED = "privacy_app_lock_enabled"

    /** 应用锁默认值：关。 */
    const val DEFAULT_PRIVACY_APP_LOCK_ENABLED = false

    /** 应用锁允许「设备凭据」（指纹/人脸/PIN）快捷解锁；默认开。 */
    const val KEY_PRIVACY_APP_LOCK_BIOMETRIC = "privacy_app_lock_biometric"

    /** 设备凭据快捷解锁默认值：开。 */
    const val DEFAULT_PRIVACY_APP_LOCK_BIOMETRIC = true

    /**
     * 应用锁解锁延迟档位：存 [PrivacyLockTimeout] 的 storageIndex（0..3），默认 0（立即）。
     * 立即档下任何退后台（含路径选择器等系统界面往返）都会在回前台时要求解锁，
     * 这是应用锁的标准语义；不想被系统界面打扰时选 1 分钟及以上。
     * 归一化见 [normalizePrivacyLockTimeoutIndex]；枚举本体在 core/privacy，
     * 避免与存储互相依赖。
     */
    const val KEY_PRIVACY_APP_LOCK_TIMEOUT = "privacy_app_lock_timeout"

    /** 解锁延迟默认档位：立即。 */
    const val DEFAULT_PRIVACY_APP_LOCK_TIMEOUT_INDEX = 0

    /** 应用锁延迟档位总数（与 PrivacyLockTimeout.entries 一致；存储层只做范围钳制）。 */
    const val PRIVACY_APP_LOCK_TIMEOUT_COUNT = 4

    const val COVER_SOURCE_HIKARINAGI = "hikarinagi"
    const val COVER_SOURCE_BANGUMI = "bangumi"
    const val COVER_SOURCE_STEAM = "steam"
    const val COVER_SOURCE_VNDB = "vndb"
    /** 未萌 NextMoe（鲲 Galgame 论坛）：支持匿名刮削，游玩状态等需登录功能使用 OAuth。 */
    const val COVER_SOURCE_NEXTMOE = "nextmoe"
    const val COVER_SOURCE_LOCAL = "local"
    const val COVER_SOURCE_CUSTOM = "custom"

    val DEFAULT_COVER_SCRAPER_SOURCES = listOf(
        COVER_SOURCE_VNDB,
        COVER_SOURCE_NEXTMOE,
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

    /** 应用统一圆角可选档位（dp）：5..50，步长 5，共十档。 */
    val APP_CORNER_RADIUS_STEPS: List<Int> = listOf(5, 10, 15, 20, 25, 30, 35, 40, 45, 50)

    /** 应用统一圆角默认值（dp）：15dp 档。 */
    const val DEFAULT_APP_CORNER_RADIUS = 15

    const val GAME_SORT_ALPHA = GameFilterOptions.GAME_SORT_ALPHA
    const val GAME_SORT_BRACKET_TAG = GameFilterOptions.GAME_SORT_BRACKET_TAG
    const val GAME_SORT_ENGINE = GameFilterOptions.GAME_SORT_ENGINE
    const val GAME_SORT_RECENT = GameFilterOptions.GAME_SORT_RECENT
    const val GAME_FILTER_ENGINE_ALL = GameFilterOptions.GAME_FILTER_ENGINE_ALL
    const val GAME_FILTER_PLAYED_ALL = GameFilterOptions.GAME_FILTER_PLAYED_ALL
    const val GAME_FILTER_PLAYED_ONLY = GameFilterOptions.GAME_FILTER_PLAYED_ONLY
    const val GAME_FILTER_PLAYED_NONE = GameFilterOptions.GAME_FILTER_PLAYED_NONE

    /** 卡片隐藏名称标签默认值：开。 */
    const val DEFAULT_GAME_CARD_HIDE_TITLE_TAG = true

    /** 瀑布流（默认）：封面高低错落，标题在封面下方白色信息区内。 */
    const val GAME_CARD_STYLE_WATERFALL = "waterfall"

    /** 网格：恢复瀑布流之前的等高卡片（3:4 封面 + 封面下方单行标题）。 */
    const val GAME_CARD_STYLE_GRID = "uniform_grid"

    /** 列表：封面流横向轮播。 */
    const val GAME_CARD_STYLE_COVER_FLOW = "cover_flow"

    /** 书架：游戏盒立在搁板上，居中的一盒正面朝外，其余侧转露出书脊，下方刻度尺可拖动快速翻找。 */
    const val GAME_CARD_STYLE_SHELF = "shelf"

    /** 卡片风格默认值：瀑布。 */
    const val DEFAULT_GAME_CARD_STYLE = GAME_CARD_STYLE_WATERFALL

    /** 卡片引擎角标默认值：关。 */
    const val DEFAULT_GAME_CARD_BADGE = false

    /**
     * 底部导航栏样式：经典 MD（Material3 导航栏）。
     * 持久化值沿用历史的 `"default"`：此前显式选过「默认」的用户升级后保持原样，不被静默改样式。
     */
    const val NAV_STYLE_CLASSIC_MD = "default"

    /** 底部导航栏样式：液态玻璃（圆角玻璃栏，Android 12+ 生效）。 */
    const val NAV_STYLE_LIQUID_GLASS = "liquid_glass"

    /** 底部导航栏样式：液态玻璃 · 透镜（三层采样 + 折射透镜，Android 13+ 才有完整效果）。 */
    const val NAV_STYLE_LIQUID_GLASS_ENHANCED = "liquid_glass_enhanced"

    /** 底部导航栏样式：悬浮按钮（右下角圆形玻璃按钮，点击沿 1/4 圆弧展开四项）。 */
    const val NAV_STYLE_FLOATING_BUTTON = "floating_button"

    // 暂停提供悬浮按钮导航，保留已保存的样式和位置。
    internal const val FLOATING_NAV_ENABLED = false

    /**
     * 导航栏样式「未显式设置」：仅作内存态 / 归一化结果，**不会持久化**。
     * 实际样式由 [resolveNavStyle] 解析，三个外观主题统一默认使用液态玻璃 · 经典。
     */
    const val NAV_STYLE_AUTO = "auto"

    /** 首页样式：原生首页（最近打开 + 快捷启动）。 */
    const val HOME_STYLE_NATIVE = "native"

    /** 首页样式：Web 首页（内置 WebView 展示所选网址）。 */
    const val HOME_STYLE_WEB = "web"

    /** 首页样式默认值：原生。 */
    const val DEFAULT_HOME_STYLE = HOME_STYLE_NATIVE

    /** 显示首页默认值：关（全新安装生效，老用户升级与已有显式设置保持开启）。 */
    const val DEFAULT_HOME_PAGE_ENABLED = false

    /** 经典应用图标默认值：关（使用当前新图标）。 */
    const val DEFAULT_CLASSIC_ICON = false

    /** Web 首页预设：鲲Gal。 */
    const val HOME_WEB_URL_KUNGAL = "https://www.kungal.com"

    /** Web 首页预设：一起萌。 */
    const val HOME_WEB_URL_LETMOE = "https://www.letmoe.com"

    /** Web 首页预设：小鸟游。 */
    const val HOME_WEB_URL_TAKANASHI = "https://t-satoru.top"

    /** Web 首页默认地址：一起萌。 */
    const val DEFAULT_HOME_WEB_URL = HOME_WEB_URL_LETMOE

    /** 透镜档需要 Android 13（API 33）的 RuntimeShader 折射能力；更低版本不提供该选项。 */
    val supportsLiquidGlassEnhanced: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    /** 导航栏样式内存态：随设置页切换即时广播，供 MainScreen 重组切换样式。 */
    val navStyleState: MutableStateFlow<String> =
        MutableStateFlow(NAV_STYLE_AUTO)

    /** 引擎页分类显示默认关闭：关闭时平铺展示全部引擎项，开启后按 GAL/RPGM/主机/网页 分页。 */
    const val DEFAULT_ENGINE_TABS_ENABLED = false

    /** 引擎页条目左侧图标默认开启：关闭后条目只显示引擎名与状态图标。 */
    const val DEFAULT_ENGINE_CARD_ICON = true

    /** 平板侧边栏默认开启：平板/大窗口下主导航移到侧边，关闭则保持底部导航。 */
    const val DEFAULT_SIDE_RAIL_ENABLED = true

    /** 平板侧边栏内存态：设置页切换后主界面即时重组（平板判定 + 本开关决定是否用侧栏）。 */
    val sideRailState: MutableStateFlow<Boolean> = MutableStateFlow(DEFAULT_SIDE_RAIL_ENABLED)

    /** 界面动效档位内存态：应用设置切换后全局即时生效。 */
    val motionEnhancedState: MutableStateFlow<Boolean> = MutableStateFlow(DEFAULT_MOTION_ENHANCED)

    /** 引擎页分类显示内存态：设置页切换后引擎页即时重组。 */
    val engineTabsState: MutableStateFlow<Boolean> = MutableStateFlow(DEFAULT_ENGINE_TABS_ENABLED)

    /** 引擎页条目左侧图标内存态：设置页切换后引擎页即时重组。 */
    val engineCardIconState: MutableStateFlow<Boolean> = MutableStateFlow(DEFAULT_ENGINE_CARD_ICON)

    /** 自定义背景图 URI 内存态（空 = 不启用）；切换后所有页面背景即时重组。 */
    val backgroundImageUriState: MutableStateFlow<String> =
        MutableStateFlow(DEFAULT_BACKGROUND_IMAGE_URI)

    /** 背景图透明度内存态（0..100）。 */
    val backgroundImageAlphaState: MutableStateFlow<Int> =
        MutableStateFlow(DEFAULT_BACKGROUND_IMAGE_ALPHA)

    /** 背景图模糊强度内存态（0..100）。 */
    val backgroundImageBlurState: MutableStateFlow<Int> =
        MutableStateFlow(DEFAULT_BACKGROUND_IMAGE_BLUR)

    private val gameConditionsLock = Any()
    private val mutableGameConditions = MutableStateFlow(GameLibraryConditions())

    /** 游戏页抽屉与设置页共用的排序和筛选快照，重置时一次发布。 */
    internal val gameConditions = mutableGameConditions.asStateFlow()

    /** 卡片隐藏名称标签内存态：设置页切换后游戏页卡片即时重组。 */
    val gameCardTitleTagState: MutableStateFlow<Boolean> = MutableStateFlow(DEFAULT_GAME_CARD_HIDE_TITLE_TAG)

    /** 卡片风格内存态：设置页切换后游戏页即时切换布局。 */
    val gameCardStyleState: MutableStateFlow<String> = MutableStateFlow(DEFAULT_GAME_CARD_STYLE)

    private val mutableGameCardColumns = MutableStateFlow(GameCardColumnSettings())
    internal val gameCardColumns = mutableGameCardColumns.asStateFlow()

    private val mutableGameCardCorners = MutableStateFlow(GameCardCornerSettings())
    internal val gameCardCorners = mutableGameCardCorners.asStateFlow()

    /** 卡片引擎角标内存态：设置页切换后游戏页卡片即时重组。 */
    val gameCardBadgeState: MutableStateFlow<Boolean> = MutableStateFlow(DEFAULT_GAME_CARD_BADGE)

    /** 首页样式内存态：设置页切换后主界面首页即时切换原生/网页形态。 */
    val homeStyleState: MutableStateFlow<String> = MutableStateFlow(DEFAULT_HOME_STYLE)

    /** 首页显示内存态：设置页切换后主导航即时隐藏/恢复首页入口。 */
    val homePageEnabledState: MutableStateFlow<Boolean> = MutableStateFlow(DEFAULT_HOME_PAGE_ENABLED)

    private val mutableDefaultThemeParticlesEnabled = MutableStateFlow(DEFAULT_THEME_PARTICLES_ENABLED)
    val defaultThemeParticlesEnabled = mutableDefaultThemeParticlesEnabled.asStateFlow()

    private val mutableDefaultThemeParticleStyle = MutableStateFlow(DefaultThemeParticleStyle.RISING_BUBBLES)
    internal val defaultThemeParticleStyle = mutableDefaultThemeParticleStyle.asStateFlow()

    /** 经典应用图标内存态：设置页切换后由 [AppIconSwitcher] 同步启动器别名。 */
    val classicIconEnabledState: MutableStateFlow<Boolean> = MutableStateFlow(DEFAULT_CLASSIC_ICON)

    /** Web 首页地址内存态：设置页修改后网页首页即时加载新地址。 */
    val homeWebUrlState: MutableStateFlow<String> = MutableStateFlow(DEFAULT_HOME_WEB_URL)

    // ---- 隐私设置内存态：设置页切换后各挂载点（主界面 / 二级页 / 通知）即时生效 ----

    /** 安全屏幕内存态：窗口 FLAG_SECURE 的开关，随 [AppLockController.lockedState] 一并参与判定。 */
    val privacyPreventScreenshotState: MutableStateFlow<Boolean> =
        MutableStateFlow(DEFAULT_PRIVACY_PREVENT_SCREENSHOT)

    /** 隐藏通知内容内存态：隐私设置页订阅显示；通知构建时直接读持久化值。 */
    val privacyHideNotificationState: MutableStateFlow<Boolean> =
        MutableStateFlow(DEFAULT_PRIVACY_HIDE_NOTIFICATION)

    /** 应用锁开关内存态：隐私设置页订阅显示；锁运行时直接读持久化值，不依赖 Compose 状态。 */
    val privacyAppLockEnabledState: MutableStateFlow<Boolean> =
        MutableStateFlow(DEFAULT_PRIVACY_APP_LOCK_ENABLED)

    /** 设备凭据快捷解锁内存态：锁门订阅「使用设备解锁方式」按钮的显隐。 */
    val privacyAppLockBiometricState: MutableStateFlow<Boolean> =
        MutableStateFlow(DEFAULT_PRIVACY_APP_LOCK_BIOMETRIC)

    /** 解锁延迟档位内存态：隐私设置页订阅显示；锁运行时直接读持久化值并按档位归一。 */
    val privacyAppLockTimeoutIndexState: MutableStateFlow<Int> =
        MutableStateFlow(DEFAULT_PRIVACY_APP_LOCK_TIMEOUT_INDEX)

    /** 封面刮削设置内存态：设置页修改后游戏页可即时读取。 */
    val coverScraperSettingsVersion: MutableStateFlow<Int> = MutableStateFlow(0)

    /** 导航样式读写的串行锁：迁移的读改写与用户写入必须互斥（见 [initNavStyle]）。 */
    private val navStyleLock = Any()

    /** 首页样式读写的串行锁：init 的异步加载与用户写入必须互斥（同 [navStyleLock] 的理由）。 */
    private val homeStyleLock = Any()

    /** 「显示首页」初始化迁移与读写的串行锁：首次判定落盘与用户手动切换必须互斥。 */
    private val homePageLock = Any()

    /**
     * 隐私设置读写的串行锁：[initPrivacySettings] 可能在 IO 线程执行，与用户在同一时刻
     * 拨动的开关必须互斥——否则 init 读到旧值、在用户写入之后才回填 StateFlow，
     * 会把刚打开的 FLAG_SECURE 又关回 false。
     */
    private val privacyLock = Any()

    /**
     * 从持久化加载导航栏样式到内存态（幂等）；主界面在首帧前调用。
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
        val stored = p.getString(KEY_NAV_STYLE, null)
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

    /** 主进程在 Application 启动阶段调用，恢复条件后页面才会创建。 */
    internal fun initGameConditions(c: Context) {
        synchronized(gameConditionsLock) {
            mutableGameConditions.value = decodeGameConditions(prefs(c).all)
        }
    }

    /** 首次组合时从持久化加载引擎页分类显示开关到内存态（幂等）。 */
    fun initEngineTabs(c: Context) {
        engineTabsState.value = isEngineTabsEnabled(c)
    }

    /** 首次组合时从持久化加载引擎页条目图标开关到内存态（幂等）。 */
    fun initEngineCardIcon(c: Context) {
        engineCardIconState.value = isEngineCardIconEnabled(c)
    }

    /** 首次组合时从持久化加载自定义背景图设置到内存态（幂等）。 */
    fun initBackgroundImage(c: Context) {
        backgroundImageUriState.value = getBackgroundImageUri(c)
        backgroundImageAlphaState.value = getBackgroundImageAlpha(c)
        backgroundImageBlurState.value = getBackgroundImageBlur(c)
    }

    /** 自定义背景图 URI（空 = 未设置，使用外观风格自带背景）。 */
    fun getBackgroundImageUri(c: Context): String =
        prefs(c).getString(KEY_BACKGROUND_IMAGE_URI, DEFAULT_BACKGROUND_IMAGE_URI).orEmpty()

    /**
     * 保存自定义背景图 URI。使用同步 `commit()` 并返回是否落盘成功：调用方（设置页）
     * 必须在新 URI 真正写入磁盘后再释放旧图的持久授权，否则进程在异步落盘前被杀会留下
     * 「磁盘旧 URI + 旧授权已释放」，背景永久失效且没有提示。
     */
    fun setBackgroundImageUri(c: Context, uri: String): Boolean {
        val committed = runCatching {
            prefs(c).edit().putString(KEY_BACKGROUND_IMAGE_URI, uri).commit()
        }.getOrDefault(false)
        if (committed) backgroundImageUriState.value = uri
        return committed
    }

    /** 背景图透明度（0..100）。 */
    fun getBackgroundImageAlpha(c: Context): Int =
        prefs(c).getInt(KEY_BACKGROUND_IMAGE_ALPHA, DEFAULT_BACKGROUND_IMAGE_ALPHA).coerceIn(0, 100)

    /** 拖动预览：只更新内存态（不写盘），松手后再用 [setBackgroundImageAlpha] 落盘。 */
    fun previewBackgroundImageAlpha(alpha: Int) {
        backgroundImageAlphaState.value = alpha.coerceIn(0, 100)
    }

    fun setBackgroundImageAlpha(c: Context, alpha: Int) {
        val normalized = alpha.coerceIn(0, 100)
        prefs(c).edit().putInt(KEY_BACKGROUND_IMAGE_ALPHA, normalized).apply()
        backgroundImageAlphaState.value = normalized
    }

    /** 背景图模糊强度（0..100）。 */
    fun getBackgroundImageBlur(c: Context): Int =
        prefs(c).getInt(KEY_BACKGROUND_IMAGE_BLUR, DEFAULT_BACKGROUND_IMAGE_BLUR).coerceIn(0, 100)

    /** 拖动预览：只更新内存态（不写盘），松手后再用 [setBackgroundImageBlur] 落盘。 */
    fun previewBackgroundImageBlur(blur: Int) {
        backgroundImageBlurState.value = blur.coerceIn(0, 100)
    }

    fun setBackgroundImageBlur(c: Context, blur: Int) {
        val normalized = blur.coerceIn(0, 100)
        prefs(c).edit().putInt(KEY_BACKGROUND_IMAGE_BLUR, normalized).apply()
        backgroundImageBlurState.value = normalized
    }

    /** 从持久化加载平板侧边栏开关到内存态（幂等）；主界面在首帧前调用。 */
    fun initSideRail(c: Context) {
        sideRailState.value = isSideRailEnabled(c)
    }

    fun initMotionEnhanced(c: Context) {
        motionEnhancedState.value = isMotionEnhanced(c)
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(AppStorageNames.APP_SETTINGS_PREFS, Context.MODE_PRIVATE)

    /** System4 一次性定点重检测迁移：读取标志。 */
    fun isXs4RescanMigrationDone(c: Context): Boolean =
        prefs(c).getBoolean(KEY_MIGRATION_XSYS4_RESCAN_DONE, false)

    /** 同步写（commit）：迁移在 IO 线程执行，标志落盘后才视为完成。 */
    fun markXs4RescanMigrationDone(c: Context): Boolean =
        prefs(c).edit().putBoolean(KEY_MIGRATION_XSYS4_RESCAN_DONE, true).commit()

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

    /**
     * 用户显式选择的底部导航栏样式（经典 MD / 液态玻璃经典 / 透镜 / 悬浮按钮）；
     * 从未设置过时为 [NAV_STYLE_AUTO]，须经 [resolveNavStyle] 解析为统一默认样式后使用。
     */
    fun getNavStyle(c: Context): String =
        synchronized(navStyleLock) {
            normalizeNavStyle(
                stored = prefs(c).getString(KEY_NAV_STYLE, null),
                enhancedSupported = supportsLiquidGlassEnhanced,
            )
        }

    fun setNavStyle(c: Context, style: String) {
        val normalized = normalizeNavStyle(style, supportsLiquidGlassEnhanced)
        synchronized(navStyleLock) {
            // AUTO 不落盘：清掉显式选择，回到统一默认样式。
            prefs(c).edit().apply {
                if (normalized == NAV_STYLE_AUTO) remove(KEY_NAV_STYLE) else putString(KEY_NAV_STYLE, normalized)
            }.apply()
            navStyleState.value = normalized
        }
    }

    /**
     * 导航样式归一化（纯函数，便于单元测试）：
     * 缺省 / 未知值归为 [NAV_STYLE_AUTO]（未显式设置）；透镜档在不支持的版本（< Android 13）回退普通档——
     * 这样即使从更新的设备备份恢复数据，旧设备也只会得到普通档而不是降级画面。
     * 暂停提供悬浮按钮时，旧配置读为液态玻璃 · 经典。
     */
    fun normalizeNavStyle(stored: String?, enhancedSupported: Boolean): String =
        when (stored) {
            NAV_STYLE_CLASSIC_MD -> NAV_STYLE_CLASSIC_MD
            NAV_STYLE_LIQUID_GLASS -> NAV_STYLE_LIQUID_GLASS
            NAV_STYLE_LIQUID_GLASS_ENHANCED ->
                if (enhancedSupported) NAV_STYLE_LIQUID_GLASS_ENHANCED else NAV_STYLE_LIQUID_GLASS
            NAV_STYLE_FLOATING_BUTTON ->
                if (FLOATING_NAV_ENABLED) NAV_STYLE_FLOATING_BUTTON else NAV_STYLE_LIQUID_GLASS
            else -> NAV_STYLE_AUTO
        }

    /**
     * 三个外观主题的未显式设置值统一解析为液态玻璃 · 经典。
     * 显式选择只做版本兼容归一化，不随外观主题改变。
     */
    fun resolveNavStyle(style: String, enhancedSupported: Boolean): String {
        val normalized = normalizeNavStyle(style, enhancedSupported)
        return when {
            normalized != NAV_STYLE_AUTO -> normalized
            else -> NAV_STYLE_LIQUID_GLASS
        }
    }

    /** 悬浮按钮导航位置键：归一化 "x,y"（0..1，相对安全区内可拖动范围）。 */
    const val KEY_FLOATING_NAV_POS = "floating_nav_pos"

    /** 悬浮按钮位置默认值：右下角。 */
    val DEFAULT_FLOATING_NAV_POSITION: Pair<Float, Float> = 1f to 1f

    /** 悬浮按钮位置内存态：拖动落盘 / 设置页重置后即时广播，供主界面重组。 */
    val floatingNavPositionState: MutableStateFlow<Pair<Float, Float>> =
        MutableStateFlow(DEFAULT_FLOATING_NAV_POSITION)

    /** 首次组合时从持久化加载悬浮按钮位置到内存态（幂等）。 */
    fun initFloatingNavPosition(c: Context) {
        floatingNavPositionState.value = getFloatingNavPosition(c)
    }

    fun getFloatingNavPosition(c: Context): Pair<Float, Float> =
        normalizeFloatingNavPosition(prefs(c).getString(KEY_FLOATING_NAV_POS, null))

    fun setFloatingNavPosition(c: Context, x: Float, y: Float) {
        val normalized = normalizeFloatingNavPosition("$x,$y")
        prefs(c).edit()
            .putString(KEY_FLOATING_NAV_POS, "${normalized.first},${normalized.second}")
            .apply()
        floatingNavPositionState.value = normalized
    }

    /**
     * 位置归一化（纯函数，便于单测）：解析 "x,y" 两个 0..1 浮点；
     * 缺失 / 格式错误 / NaN / 无穷一律回退默认右下角，越界值钳到 [0,1]。
     */
    fun normalizeFloatingNavPosition(stored: String?): Pair<Float, Float> {
        val parts = stored?.split(",") ?: return DEFAULT_FLOATING_NAV_POSITION
        if (parts.size != 2) return DEFAULT_FLOATING_NAV_POSITION
        val x = parts[0].trim().toFloatOrNull() ?: return DEFAULT_FLOATING_NAV_POSITION
        val y = parts[1].trim().toFloatOrNull() ?: return DEFAULT_FLOATING_NAV_POSITION
        if (!x.isFinite() || !y.isFinite()) return DEFAULT_FLOATING_NAV_POSITION
        return x.coerceIn(0f, 1f) to y.coerceIn(0f, 1f)
    }

    /** 当前外观风格（默认 / 复古玻璃；未知值归一为默认）。 */
    fun getAppearanceStyle(c: Context): AppearanceStyle =
        AppearanceStyle.fromStorage(prefs(c).getString(KEY_APPEARANCE_STYLE, null))

    fun setAppearanceStyle(c: Context, style: AppearanceStyle) =
        prefs(c).edit().putString(KEY_APPEARANCE_STYLE, style.storageValue).apply()

    /** 文件夹扫描深度（1..5，默认 3）。 */
    fun getScanDepth(c: Context): Int =
        prefs(c).getInt(KEY_SCAN_DEPTH, DEFAULT_SCAN_DEPTH).coerceIn(1, 5)

    fun setScanDepth(c: Context, depth: Int) =
        prefs(c).edit().putInt(KEY_SCAN_DEPTH, depth.coerceIn(1, 5)).apply()

    /**
     * 应用统一圆角归一：吸附到 [APP_CORNER_RADIUS_STEPS] 中最近的档位
     * （纯函数，便于 JVM 单测；非法/越界值同样收敛到档位内）。
     */
    fun normalizeAppCornerRadius(value: Int): Int =
        APP_CORNER_RADIUS_STEPS.minByOrNull { kotlin.math.abs(it.toLong() - value) }
            ?: DEFAULT_APP_CORNER_RADIUS

    fun getAppCornerRadius(c: Context): Int =
        normalizeAppCornerRadius(prefs(c).getInt(KEY_APP_CORNER_RADIUS, DEFAULT_APP_CORNER_RADIUS))

    fun setAppCornerRadius(c: Context, radius: Int) {
        prefs(c).edit().putInt(KEY_APP_CORNER_RADIUS, normalizeAppCornerRadius(radius)).apply()
    }

    @Synchronized
    internal fun initGameCardCorners(context: Context) {
        val settings = prefs(context)
        mutableGameCardCorners.value = GameCardCornerSettings(
            enabled = settings.getBoolean(KEY_GAME_CARD_CORNER_ENABLED, false),
            radiusDp = normalizeAppCornerRadius(
                settings.getInt(KEY_GAME_CARD_CORNER_RADIUS, DEFAULT_APP_CORNER_RADIUS),
            ),
        )
    }

    @Synchronized
    internal fun updateGameCardCorners(
        context: Context,
        transform: (GameCardCornerSettings) -> GameCardCornerSettings,
    ) {
        val current = mutableGameCardCorners.value
        val updated = transform(current)
        val normalized = updated.copy(radiusDp = normalizeAppCornerRadius(updated.radiusDp))
        if (normalized == current) return
        prefs(context).edit {
            putBoolean(KEY_GAME_CARD_CORNER_ENABLED, normalized.enabled)
            putInt(KEY_GAME_CARD_CORNER_RADIUS, normalized.radiusDp)
        }
        mutableGameCardCorners.value = normalized
    }

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

    /**
     * 解析尚未持久化 `KEY_HOME_PAGE_ENABLED` 时的初始「显示首页」状态：
     * 已检测到既有安装数据时为老用户升级保留开启（`true`），否则使用全新安装默认值（`false`）。
     */
    internal fun resolveInitialHomePageEnabled(
        storedValue: Boolean?,
        existingInstallDetected: Boolean,
    ): Boolean = storedValue ?: if (existingInstallDetected) true else DEFAULT_HOME_PAGE_ENABLED

    /**
     * 判定升级迁移前的历史安装痕迹：忽略首次引导初始化写入的 `KEY_ONBOARDING_COMPLETED=false` 及本键自身，
     * 仅当已完成过引导、存在其它应用设置项、旧版偏好非空或游戏库文件已存在时视为老用户。
     */
    internal fun hasExistingInstallForHomePageMigration(
        onboardingCompleted: Boolean,
        appSettingsKeys: Set<String>,
        legacyPreferencesHaveData: Boolean,
        gameLibraryExists: Boolean,
    ): Boolean {
        val hasOtherAppSettings = appSettingsKeys.any {
            it != KEY_ONBOARDING_COMPLETED && it != KEY_HOME_PAGE_ENABLED
        }
        return onboardingCompleted || hasOtherAppSettings || legacyPreferencesHaveData || gameLibraryExists
    }

    private fun resolveAndPersistHomePageEnabledLocked(c: Context): Boolean {
        val settings = prefs(c)
        if (settings.contains(KEY_HOME_PAGE_ENABLED)) {
            return settings.getBoolean(KEY_HOME_PAGE_ENABLED, DEFAULT_HOME_PAGE_ENABLED)
        }
        val existingInstall = hasExistingInstallForHomePageMigration(
            onboardingCompleted = settings.getBoolean(KEY_ONBOARDING_COMPLETED, false),
            appSettingsKeys = settings.all.keys,
            legacyPreferencesHaveData = listOf(
                AppStorageNames.ENGINE_PREFS,
                AppStorageNames.LEGACY_ENGINE_PREFS,
                AppStorageNames.GAME_SCANNER_PREFS,
            ).any { name ->
                c.getSharedPreferences(name, Context.MODE_PRIVATE).all.isNotEmpty()
            },
            gameLibraryExists = c.getDatabasePath(AppStorageNames.GAME_LIBRARY_DATABASE).exists(),
        )
        val resolved = resolveInitialHomePageEnabled(storedValue = null, existingInstallDetected = existingInstall)
        settings.edit { putBoolean(KEY_HOME_PAGE_ENABLED, resolved) }
        return resolved
    }

    /** 首次组合或应用启动时从持久化加载首页显示开关到内存态（未写入过时一次性迁移老用户并落盘；幂等）。 */
    fun initHomePageEnabled(c: Context) {
        synchronized(homePageLock) {
            homePageEnabledState.value = resolveAndPersistHomePageEnabledLocked(c)
        }
    }

    /** 启动/设置页读取时把经典应用图标开关同步到内存态（幂等）。 */
    fun initClassicIcon(c: Context) {
        classicIconEnabledState.value = isClassicIconEnabled(c)
    }

    /**
     * 首页样式归一：仅 web 精确接受，其余（含空/未知/历史值 "category"）回退原生
     * （纯函数，便于单测）。
     */
    fun normalizeHomeStyle(style: String?): String = when (style) {
        HOME_STYLE_WEB -> HOME_STYLE_WEB
        else -> HOME_STYLE_NATIVE
    }

    fun getHomeStyle(c: Context): String =
        normalizeHomeStyle(prefs(c).getString(KEY_HOME_STYLE, HOME_STYLE_NATIVE))

    fun setHomeStyle(c: Context, style: String) {
        val normalized = normalizeHomeStyle(style)
        synchronized(homeStyleLock) {
            prefs(c).edit().putString(KEY_HOME_STYLE, normalized).apply()
            homeStyleState.value = normalized
        }
    }

    /** 是否显示首页（关闭后首页不参与主导航）。 */
    fun isHomePageEnabled(c: Context): Boolean =
        synchronized(homePageLock) {
            resolveAndPersistHomePageEnabledLocked(c)
        }

    fun setHomePageEnabled(c: Context, enabled: Boolean) {
        synchronized(homePageLock) {
            prefs(c).edit { putBoolean(KEY_HOME_PAGE_ENABLED, enabled) }
            homePageEnabledState.value = enabled
        }
    }

    internal fun initDefaultThemeParticles(c: Context) {
        val settings = prefs(c)
        mutableDefaultThemeParticlesEnabled.value =
            settings.getBoolean(KEY_DEFAULT_THEME_PARTICLES_ENABLED, DEFAULT_THEME_PARTICLES_ENABLED)
        val storedStyle = settings.getString(KEY_DEFAULT_THEME_PARTICLE_STYLE, null)
        mutableDefaultThemeParticleStyle.value =
            DefaultThemeParticleStyle.entries.firstOrNull { it.storedValue == storedStyle }
                ?: DefaultThemeParticleStyle.RISING_BUBBLES
    }

    fun setDefaultThemeParticlesEnabled(c: Context, enabled: Boolean) {
        prefs(c).edit { putBoolean(KEY_DEFAULT_THEME_PARTICLES_ENABLED, enabled) }
        mutableDefaultThemeParticlesEnabled.value = enabled
    }

    internal fun setDefaultThemeParticleStyle(c: Context, style: DefaultThemeParticleStyle) {
        prefs(c).edit { putString(KEY_DEFAULT_THEME_PARTICLE_STYLE, style.storedValue) }
        mutableDefaultThemeParticleStyle.value = style
    }

    /** 是否使用经典应用图标（旧版图标）；启动器别名状态由 [AppIconSwitcher] 负责。 */
    fun isClassicIconEnabled(c: Context): Boolean =
        prefs(c).getBoolean(KEY_CLASSIC_ICON, DEFAULT_CLASSIC_ICON)

    fun setClassicIconEnabled(c: Context, enabled: Boolean) {
        prefs(c).edit().putBoolean(KEY_CLASSIC_ICON, enabled).apply()
        classicIconEnabledState.value = enabled
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

    /** 卡片风格归一：仅接受 waterfall / uniform_grid / cover_flow / shelf；历史值 grid 与空值回退瀑布。 */
    fun normalizeGameCardStyle(style: String?): String =
        when (style?.trim()?.lowercase()) {
            GAME_CARD_STYLE_GRID -> GAME_CARD_STYLE_GRID
            GAME_CARD_STYLE_COVER_FLOW -> GAME_CARD_STYLE_COVER_FLOW
            GAME_CARD_STYLE_SHELF -> GAME_CARD_STYLE_SHELF
            // 历史值 "grid"（当时渲染瀑布流）与空值/未知值统一归一到瀑布，避免升级后样式跳变
            else -> GAME_CARD_STYLE_WATERFALL
        }

    fun setGameCardStyle(c: Context, style: String) {
        val normalized = normalizeGameCardStyle(style)
        prefs(c).edit().putString(KEY_GAME_CARD_STYLE, normalized).apply()
        gameCardStyleState.value = normalized
    }

    /** 主进程启动时初始化；页面只订阅列数，不重新从偏好回填。 */
    @Synchronized
    internal fun initGameCardColumns(context: Context) {
        val settings = prefs(context)
        val defaults = GameCardColumnSettings()
        mutableGameCardColumns.value = GameCardColumnSettings(
            narrow = settings.getInt(KEY_GAME_CARD_COLUMNS_NARROW, defaults.narrow)
                .coerceIn(GameCardColumnSettings.range(wideScreen = false)),
            wide = settings.getInt(KEY_GAME_CARD_COLUMNS_WIDE, defaults.wide)
                .coerceIn(GameCardColumnSettings.range(wideScreen = true)),
        )
    }

    @Synchronized
    internal fun setGameCardColumns(context: Context, wideScreen: Boolean, columns: Int) {
        val normalized = columns.coerceIn(GameCardColumnSettings.range(wideScreen))
        val current = mutableGameCardColumns.value
        if (current.forScreen(wideScreen) == normalized) return
        val key = if (wideScreen) KEY_GAME_CARD_COLUMNS_WIDE else KEY_GAME_CARD_COLUMNS_NARROW
        prefs(context).edit { putInt(key, normalized) }
        mutableGameCardColumns.value = if (wideScreen) current.copy(wide = normalized) else current.copy(narrow = normalized)
    }

    fun setGameSort(c: Context, sort: String) {
        updateGameConditions(c) { it.copy(sortMode = normalizeGameSort(sort)) }
    }

    fun setGameFilterEngine(c: Context, engine: String) {
        updateGameConditions(c) { it.copy(engineFilter = GameFilterOptions.normalizeEngineFilter(engine)) }
    }

    fun setGameFilterPlayed(c: Context, played: String) {
        updateGameConditions(c) { it.copy(playedFilter = normalizeGameFilterPlayed(played)) }
    }

    /** 书架上最后停留的那本书的 uri；没记过为 null。 */
    fun gameShelfFocusUri(c: Context): String? =
        prefs(c).getString(KEY_GAME_SHELF_FOCUS_URI, null)?.takeIf { it.isNotEmpty() }

    fun setGameShelfFocusUri(c: Context, uri: String) {
        val p = prefs(c)
        if (p.getString(KEY_GAME_SHELF_FOCUS_URI, null) != uri) {
            p.edit().putString(KEY_GAME_SHELF_FOCUS_URI, uri).apply()
        }
    }

    fun resetGameConditions(c: Context) {
        updateGameConditions(c) { GameLibraryConditions() }
    }

    private fun updateGameConditions(c: Context, transform: (GameLibraryConditions) -> GameLibraryConditions) {
        synchronized(gameConditionsLock) {
            val next = transform(mutableGameConditions.value)
            prefs(c).edit()
                .putString(KEY_GAME_SORT, next.sortMode)
                .putString(KEY_GAME_FILTER_ENGINE, next.engineFilter)
                .putString(KEY_GAME_FILTER_PLAYED, next.playedFilter)
                .apply()
            mutableGameConditions.value = next
        }
    }

    internal fun decodeGameConditions(values: Map<String, *>): GameLibraryConditions = GameLibraryConditions(
        sortMode = normalizeGameSort(values[KEY_GAME_SORT] as? String),
        engineFilter = GameFilterOptions.normalizeEngineFilter(values[KEY_GAME_FILTER_ENGINE] as? String),
        playedFilter = normalizeGameFilterPlayed(values[KEY_GAME_FILTER_PLAYED] as? String),
    )

    private fun normalizeGameSort(sort: String?): String = when (val value = sort.orEmpty()) {
        GAME_SORT_BRACKET_TAG, GAME_SORT_ENGINE, GAME_SORT_RECENT -> value
        else -> GAME_SORT_ALPHA
    }

    private fun normalizeGameFilterPlayed(played: String?): String = when (val value = played.orEmpty()) {
        GAME_FILTER_PLAYED_ONLY, GAME_FILTER_PLAYED_NONE -> value
        else -> GAME_FILTER_PLAYED_ALL
    }

    /** 引擎页是否按分类（GAL / RPGM / 主机 / 网页）分页展示。 */
    fun isEngineTabsEnabled(c: Context): Boolean =
        prefs(c).getBoolean(KEY_ENGINE_TABS, DEFAULT_ENGINE_TABS_ENABLED)

    fun setEngineTabsEnabled(c: Context, enabled: Boolean) {
        prefs(c).edit().putBoolean(KEY_ENGINE_TABS, enabled).apply()
        engineTabsState.value = enabled
    }

    /** 引擎页条目是否显示左侧图标（引擎横幅图）。 */
    fun isEngineCardIconEnabled(c: Context): Boolean =
        prefs(c).getBoolean(KEY_ENGINE_CARD_ICON, DEFAULT_ENGINE_CARD_ICON)

    fun setEngineCardIconEnabled(c: Context, enabled: Boolean) {
        prefs(c).edit().putBoolean(KEY_ENGINE_CARD_ICON, enabled).apply()
        engineCardIconState.value = enabled
    }

    /** 平板/大窗口下是否使用侧边导航（默认开）。 */
    fun isSideRailEnabled(c: Context): Boolean =
        prefs(c).getBoolean(KEY_SIDE_RAIL, DEFAULT_SIDE_RAIL_ENABLED)

    fun setSideRailEnabled(c: Context, enabled: Boolean) {
        prefs(c).edit().putBoolean(KEY_SIDE_RAIL, enabled).apply()
        sideRailState.value = enabled
    }

    fun isMotionEnhanced(c: Context): Boolean =
        prefs(c).getBoolean(KEY_MOTION_ENHANCED, DEFAULT_MOTION_ENHANCED)

    fun setMotionEnhanced(c: Context, enhanced: Boolean) {
        prefs(c).edit().putBoolean(KEY_MOTION_ENHANCED, enhanced).apply()
        motionEnhancedState.value = enhanced
    }

    // ---- 隐私设置：Application 启动阶段经 [initPrivacySettings] 一次加载，读写即写即广播 ----

    /**
     * Application 启动阶段一次性加载全部隐私设置（幂等）。
     * FLAG_SECURE / 通知构建都要在页面或窗口尚不可见时读到正确值，
     * 不能等隐私设置页首次组合才加载。
     *
     * 与各 setter 共用 [privacyLock]：本方法可能在 IO 线程执行，若不加锁，
     * 读到旧值后回填 StateFlow 会覆盖用户在这段时间里刚改的设置。
     */
    fun initPrivacySettings(c: Context) {
        synchronized(privacyLock) {
            privacyPreventScreenshotState.value = isPrivacyPreventScreenshot(c)
            privacyHideNotificationState.value = isPrivacyHideNotification(c)
            privacyAppLockEnabledState.value = isPrivacyAppLockEnabled(c)
            privacyAppLockBiometricState.value = isPrivacyAppLockBiometric(c)
            privacyAppLockTimeoutIndexState.value = getPrivacyAppLockTimeoutIndex(c)
        }
    }

    /** 防止截图（FLAG_SECURE）。 */
    fun isPrivacyPreventScreenshot(c: Context): Boolean =
        prefs(c).getBoolean(KEY_PRIVACY_PREVENT_SCREENSHOT, DEFAULT_PRIVACY_PREVENT_SCREENSHOT)

    fun setPrivacyPreventScreenshot(c: Context, enabled: Boolean) {
        synchronized(privacyLock) {
            prefs(c).edit().putBoolean(KEY_PRIVACY_PREVENT_SCREENSHOT, enabled).apply()
            privacyPreventScreenshotState.value = enabled
        }
    }

    /** 隐藏通知内容（更新通知在系统锁屏上不显示）。 */
    fun isPrivacyHideNotification(c: Context): Boolean =
        prefs(c).getBoolean(KEY_PRIVACY_HIDE_NOTIFICATION, DEFAULT_PRIVACY_HIDE_NOTIFICATION)

    fun setPrivacyHideNotification(c: Context, enabled: Boolean) {
        synchronized(privacyLock) {
            prefs(c).edit().putBoolean(KEY_PRIVACY_HIDE_NOTIFICATION, enabled).apply()
            privacyHideNotificationState.value = enabled
        }
    }

    /** 应用锁开关（图案凭据由 core/privacy 的 AppLockCredentialStore 单独保管）。 */
    fun isPrivacyAppLockEnabled(c: Context): Boolean =
        prefs(c).getBoolean(KEY_PRIVACY_APP_LOCK_ENABLED, DEFAULT_PRIVACY_APP_LOCK_ENABLED)

    fun setPrivacyAppLockEnabled(c: Context, enabled: Boolean) {
        synchronized(privacyLock) {
            prefs(c).edit().putBoolean(KEY_PRIVACY_APP_LOCK_ENABLED, enabled).apply()
            privacyAppLockEnabledState.value = enabled
        }
    }

    /** 允许「设备凭据」（指纹/人脸/PIN）快捷解锁。 */
    fun isPrivacyAppLockBiometric(c: Context): Boolean =
        prefs(c).getBoolean(KEY_PRIVACY_APP_LOCK_BIOMETRIC, DEFAULT_PRIVACY_APP_LOCK_BIOMETRIC)

    fun setPrivacyAppLockBiometric(c: Context, enabled: Boolean) {
        synchronized(privacyLock) {
            prefs(c).edit().putBoolean(KEY_PRIVACY_APP_LOCK_BIOMETRIC, enabled).apply()
            privacyAppLockBiometricState.value = enabled
        }
    }

    /** 解锁延迟档位（0..3，与 PrivacyLockTimeout 的 storageIndex 对应）。 */
    fun getPrivacyAppLockTimeoutIndex(c: Context): Int =
        normalizePrivacyLockTimeoutIndex(prefs(c).getInt(KEY_PRIVACY_APP_LOCK_TIMEOUT, DEFAULT_PRIVACY_APP_LOCK_TIMEOUT_INDEX))

    fun setPrivacyAppLockTimeoutIndex(c: Context, index: Int) {
        val normalized = normalizePrivacyLockTimeoutIndex(index)
        synchronized(privacyLock) {
            prefs(c).edit().putInt(KEY_PRIVACY_APP_LOCK_TIMEOUT, normalized).apply()
            privacyAppLockTimeoutIndexState.value = normalized
        }
    }

    /**
     * 解锁延迟档位归一（纯函数，便于单测）：越界 / 非法档位收敛到立即档，
     * 从更新的设备备份恢复时不会读到越界索引。
     */
    fun normalizePrivacyLockTimeoutIndex(index: Int): Int =
        if (index in 0 until PRIVACY_APP_LOCK_TIMEOUT_COUNT) index else 0

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
        coverScraperSettingsVersion.update { it + 1 }
    }

    /** 已启用的第三方 APK 运行时模块包名集合；始终返回副本，调用方可安全修改。 */
    fun getEnabledRuntimeModules(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_RUNTIME_MODULE_ENABLED, emptySet())?.toSet().orEmpty()

    /** 按包名增删启用状态；空包名忽略，未发生变更时不写盘。 */
    fun setRuntimeModuleEnabled(context: Context, packageName: String, enabled: Boolean) {
        val target = packageName.trim()
        if (target.isEmpty()) return
        val current = getEnabledRuntimeModules(context).toMutableSet()
        val changed = if (enabled) current.add(target) else current.remove(target)
        if (!changed) return
        prefs(context).edit().putStringSet(KEY_RUNTIME_MODULE_ENABLED, current).apply()
    }

    fun isRuntimeModuleEnabled(context: Context, packageName: String): Boolean {
        val target = packageName.trim()
        return target.isNotEmpty() && target in getEnabledRuntimeModules(context)
    }

    private fun normalizeLanguage(language: String?): String = when (language) {
        LANGUAGE_SYSTEM -> LANGUAGE_SYSTEM
        LANGUAGE_JA -> LANGUAGE_JA
        LANGUAGE_EN -> LANGUAGE_EN
        else -> LANGUAGE_ZH
    }
}
