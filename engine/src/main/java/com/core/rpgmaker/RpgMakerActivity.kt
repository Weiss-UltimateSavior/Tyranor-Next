package com.core.rpgmaker

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.view.KeyEvent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import com.core.engine.DoubleBackExit
import com.core.engine.EnginePrefs
import com.core.engine.EngineSessionRegistry
import com.core.engine.EngineThemeColors
import com.core.engine.R
import java.io.ByteArrayInputStream
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject

/**
 * RPG Maker MV/MZ 独立运行时（v1/v2）的 WebView 宿主。
 *
 * 由 tyrano 运行时的 v0 宿主（engine 模块 TyranoActivity）复制而来并按本运行时
 * 的需要裁剪：仅承载 RPG Maker MV/MZ 会话，v0 仍由 engine 模块的 tyrano 宿主
 * 承载（行为保持不变）。v1/v2 的 NW.js 兼容层（polyfill、rpgmv-v1 核心覆盖、
 * PC 存档兜底、屏幕方向回退等）在此按 [EXTRA_RPG_MAKER_VERSION] 版本门控注入，
 * 不影响 v0 路径。
 *
 * 本 Activity 位于 engine 模块的 com.core.rpgmaker 独立运行时包（不依赖 app 层），
 * 与 tyrano 宿主（com.core.tyrano，承载 v0）互不引用；v1/v2 专属运行时资产集中在
 * assets/rpgmaker/ 子目录，共享脚本（__rpg__.js、__rmmz__.js、触屏手柄、修改器等）
 * 仍以 engine assets 根为单份源头。用户偏好（UI 缩放、外网开关）直接读取共享的
 * tyranor_prefs，与 engine 内其他宿主同模式。
 */
class RpgMakerActivity : Activity() {
    private var webView: WebView? = null
    private var virtualMouseLayer: VirtualMouseLayer? = null
    private var gameDir: String? = null
    private var saveDirectory: File? = null
    private var gameUsesAsar = false
    private var webGameType = WebGameType.RPG_MV
    private var asarPath: String? = null
    private var asarArchive: AsarArchive? = null
    private var firstResume = true

    // WebView 回调线程（shouldInterceptRequest）会读取这两项，onCreate 主线程写入
    @Volatile
    private var localServer: RpgMakerLocalHttpServer? = null

    // 与 localServer 同一线程契约：shouldInterceptRequest 在 WebView 回调线程读取
    @Volatile
    private var allowExternalNetwork = false
    private var rpgMakerModEnabled = false
    private var rpgMakerModGameId = ""
    private var rpgMakerVersion: String? = null

    /** v1/v2 兼容会话（NW.js polyfill、加密资源回退等）；版本缺失的兜底会话按 v0 资源策略。 */
    @Volatile
    private var v12Session = false
    private val processExitScheduled = AtomicBoolean(false)

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(wrapContextForUiScale(newBase) ?: newBase)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // singleInstance 启动模式下切换游戏只走到这里：路径或行为相关 extras
        // （独立存档目录、修改器开关、运行时版本等）变化时整体重建，让 onCreate 按 Intent 重新解析
        val newDir = resolveGameDir(intent)
        if (newDir.isNullOrBlank()) return // 无法解析的意图不接管当前游戏，保留原 Intent
        val changed = newDir != gameDir || behaviorSignature(intent) != behaviorSignature(getIntent())
        setIntent(intent)
        if (changed && !isFinishing) {
            Log.i(TAG, "onNewIntent relaunch with changed intent; recreate")
            recreate()
        }
    }

    /** 影响引擎行为的 Intent extras 摘要，用于判断单游戏重启是否需要重建。 */
    private fun behaviorSignature(intent: Intent): String = listOf(
        resolveGameDir(intent),
        intent.getBooleanExtra(EXTRA_SCOPED_SAVE_DIR, false).toString(),
        intent.getStringExtra(EXTRA_SCOPED_SAVE_ROOT),
        intent.getBooleanExtra(EXTRA_RPG_MAKER_MOD_ENABLED, true).toString(),
        intent.getStringExtra(EXTRA_RPG_MAKER_MOD_GAME_ID),
        intent.getStringExtra(EXTRA_RPG_MAKER_VERSION),
        intent.getBooleanExtra(EXTRA_RPG_LEGACY_RENDERER, false).toString(),
    ).joinToString("\u0000")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enterFullscreen()
        // 多进程 WebView 数据目录隔离：:tyrano 与 :rpgmaker 都承载 WebView，API 28+
        // 起多进程共用默认数据目录会直接抛异常（PR review 意见）；必须在本进程任何
        // WebView 实例创建之前调用，且 :tyrano 侧保持默认目录，两侧目录互异
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching { WebView.setDataDirectorySuffix("rpgmaker") }
        }
        allowExternalNetwork = getSharedPreferences(EnginePrefs.APP_PREFS, Context.MODE_PRIVATE)
            .getBoolean(EnginePrefs.KEY_TYRANO_EXTERNAL_NETWORK, false)

        gameDir = resolveGameDir(intent)
        Log.i(TAG, "onCreate gameDir=$gameDir")
        val resolvedGameDir = gameDir
        if (resolvedGameDir.isNullOrBlank()) {
            failLaunch(getString(R.string.engine_rpgmaker_empty_game_directory))
            return
        }

        EngineSessionRegistry.record(this, EngineSessionRegistry.HOST_RPGMAKER, resolvedGameDir)

        val gameRoot = File(resolvedGameDir)

        val entry = findGameEntry(gameRoot, 0)
        if (entry == null) {
            val rootAsar = File(gameRoot, "app.asar")
            val resourcesAsar = File(File(gameRoot, "resources"), "app.asar")
            val index = File(gameRoot, "index.html")
            Log.e(TAG, "entry not found index=${index.absolutePath} app.asar=${rootAsar.absolutePath} resources/app.asar=${resourcesAsar.absolutePath} (searched subdirs: ${WEB_ENTRY_SUBDIRS.joinToString()})")
            failLaunch(getString(R.string.engine_rpgmaker_entry_not_found))
            return
        }
        val contentRoot = entry.contentRoot
        if (entry.asarPath != null) {
            gameUsesAsar = true
            asarPath = entry.asarPath
        }

        if (gameUsesAsar) {
            try {
                asarArchive = AsarArchive(File(requireNotNull(asarPath)))
            } catch (error: Throwable) {
                Log.e(TAG, "open asar failed", error)
                failLaunch(getString(R.string.engine_rpgmaker_asar_unreadable))
                return
            }
        }
        webGameType = detectWebGameType(intent.getStringExtra("type"), contentRoot, asarArchive)
        rpgMakerModEnabled = intent.getBooleanExtra(EXTRA_RPG_MAKER_MOD_ENABLED, true)
        rpgMakerModGameId = intent.getStringExtra(EXTRA_RPG_MAKER_MOD_GAME_ID)
            ?.takeIf(String::isNotBlank)
            ?: resolvedGameDir
        rpgMakerVersion = intent.getStringExtra(EXTRA_RPG_MAKER_VERSION)?.takeIf(String::isNotBlank)
        Log.i(TAG, "entry mode=${if (gameUsesAsar) "asar" else "dir"} type=${webGameType.intentValue} rpgMakerVersion=$rpgMakerVersion asar=$asarPath contentRoot=${contentRoot.absolutePath}")
        val saves = resolveSaveDirectory(intent, gameRoot)
        saveDirectory = saves
        if (!ensureWritableSaveDirectory(saves)) {
            failLaunch(getString(R.string.engine_rpgmaker_unwritable_save_directory))
            return
        }
        Log.i(TAG, "save directory=${saves?.absolutePath ?: "none"} scoped=${intent.getBooleanExtra(EXTRA_SCOPED_SAVE_DIR, false)}")

        val bundle = buildInjectionBundle(contentRoot) ?: return
        startLocalServer(bundle)
        if (localServer == null) return
        setupGameUi(saves, bundle)
    }

    /** 注入内容与本地服务器构造参数的一次性装配结果（onCreate 编排用）。 */
    private data class InjectionBundle(
        val contentRoot: File,
        val lateHook: ByteArray,
        val scriptAppends: Map<String, ByteArray>,
        val internalResources: Map<String, ByteArray>,
        val nwPolyfill: ByteArray?,
        val modHtml: String,
        val legacyRenderer: Boolean,
    )

    /**
     * 装配注入内容与服务器参数；失败时提示并返回 null（调用方应直接结束启动流程）。
     * 从 onCreate 拆出（PR review 意见：原方法圈复杂度 45 超阈值），按职责只负责
     * 版本门控判定与资产装配；服务器启动与视图装配分别由独立方法承担。
     */
    private fun buildInjectionBundle(contentRoot: File): InjectionBundle? {
        return try {
            val normalizedVersion = rpgMakerVersion?.trim()?.lowercase()
            // v2 = v1 的 NWJS 兼容层 + v0 的引擎文件策略（不覆盖核心脚本）
            val isRpgMvV1 = webGameType == WebGameType.RPG_MV && normalizedVersion == "v1"
            val isRpgMvV2 = webGameType == WebGameType.RPG_MV && normalizedVersion == "v2"
            val isRpgMzV2 = webGameType == WebGameType.RPG_MZ && normalizedVersion == "v2"
            v12Session = isRpgMvV1 || isRpgMvV2 || isRpgMzV2
            var useCoreScriptOverlay = isRpgMvV1
            if (!v12Session && normalizedVersion != null) {
                // 目前仅 MZ v1 为占位版本；缺版本（null）视为未配置的兜底会话，均回退 v0 资源策略
                Log.i(TAG, "rpgMakerVersion=$normalizedVersion has no dedicated runtime, falling back to v0 resources")
            }
            // v1 覆盖包要求核心文件齐全；任一缺失（打包回归）时整体降级为 v0 资源策略——
            // 连同 __rpg_v12.js hook 与 NWJS polyfill 一起放弃，避免“v12 补丁配游戏自带老核心”的混合态
            val v1Overlay: Map<String, ByteArray> = if (useCoreScriptOverlay) {
                val overlay = buildRpgMvV1Overlay(assets)
                if (overlay.isEmpty()) {
                    Log.w(TAG, "v1 overlay incomplete, downgrading session to v0 resources")
                    v12Session = false
                    useCoreScriptOverlay = false
                }
                overlay
            } else {
                emptyMap()
            }
            // 触屏手柄（issue #35）：MV/MZ 共用 __touch_pad.js，拼接进 hook 注入，
            // 独立于修改器开关。手柄代码零引擎依赖，MV/MZ 的 Input 均读 keyCode。
            // issue #30：游戏内可自定义按钮布局，逐游戏配置在此注入供 JS 读取。
            val touchPadConf = if (rpgMakerModGameId.isNotBlank()) {
                getSharedPreferences(EnginePrefs.GAME_OVERRIDES_PREFS, Context.MODE_PRIVATE)
                    .getString(rpgMakerModGameId, null)?.let { raw ->
                        runCatching { JSONObject(raw).optString(PER_GAME_TOUCH_PAD_KEY) }
                            .getOrNull()?.takeIf { it.isNotBlank() }
                    }
            } else {
                null
            }
            val touchPadConfigJs = touchPadConf?.takeIf { it.isNotBlank() }?.let {
                "window.__touchPadConfig=$it;"
            }.orEmpty()
            val touchPadThemeJs = run {
                val colors = EngineThemeColors.fromIntent(intent)
                "window.__touchPadTheme={primary:'${cssColor(colors.primary)}',onPrimary:'${cssColor(colors.onPrimary)}'};"
            }
            val touchPad = run {
                val pad = loadAssetOrNull(TOUCH_PAD_ASSET)?.toString(Charsets.UTF_8).orEmpty()
                (touchPadThemeJs + "\n" + touchPadConfigJs + "\n" + pad).toByteArray(Charsets.UTF_8)
            }
            // v1/v2 用带 PC 存档兜底的 MV hook（__rpg_v12.js，本模块资产）；
            // 版本缺失的兜底会话用 v0 的 __rpg__.js（engine 模块资产，与 v0 宿主一致）
            val hookAsset = when {
                webGameType == WebGameType.RPG_MZ -> RPG_MZ_HOOK_ASSET
                v12Session -> RPG_MV_V12_HOOK_ASSET
                else -> RPG_MV_HOOK_ASSET
            }
            val lateHook = (loadAssetOrNull(hookAsset) ?: ByteArray(0)) + touchPad
            val scriptAppends = if (webGameType == WebGameType.RPG_MZ) {
                mapOf(
                    "js/rmmz_core.js" to loadAsset(RPG_MZ_CORE_HOOK_ASSET),
                    "js/rmmz_managers.js" to loadAsset(RPG_MZ_MANAGERS_HOOK_ASSET),
                )
            } else {
                emptyMap()
            }
            val modResources = if (rpgMakerModEnabled) {
                mapOf(
                    RPG_MAKER_MOD_CORE_PATH to loadAsset(RPG_MAKER_MOD_CORE_ASSET),
                    RPG_MAKER_MOD_UI_PATH to loadAsset(RPG_MAKER_MOD_UI_ASSET),
                    RPG_MAKER_MOD_CSS_PATH to loadAsset(RPG_MAKER_MOD_CSS_ASSET),
                    RPG_MAKER_MOD_ICON_PATH to loadAsset(RPG_MAKER_MOD_ICON_ASSET),
                )
            } else {
                emptyMap()
            }
            val modHtml = if (rpgMakerModEnabled) buildRpgMakerModHtml() else ""
            // v1/v2 会话注入 NWJS 兼容层（earlyHook，</head> 处），v0 兜底会话不注入（与 v0 宿主一致）
            val nwPolyfill: ByteArray? = if (v12Session) {
                val base = loadAssetOrNull(NWJS_POLYFILL_ASSET)?.toString(Charsets.UTF_8)
                if (base == null) {
                    null
                } else {
                    // v1/v2 的 NWJS 兼容层兜底统一注入；v2 追加额外兼容层（仅 v2 生效）
                    val compatExtra = loadAssetOrNull(NWJS_POLYFILL_V1_EXTRA_ASSET)?.toString(Charsets.UTF_8).orEmpty()
                    val v2Extra = if (isRpgMvV2 || isRpgMzV2) {
                        loadAssetOrNull(NWJS_POLYFILL_V2_EXTRA_ASSET)?.toString(Charsets.UTF_8).orEmpty()
                    } else {
                        ""
                    }
                    (base + compatExtra + v2Extra).toByteArray(Charsets.UTF_8)
                }
            } else {
                null
            }
            val internalResources = modResources + v1Overlay
            Log.i(TAG, "asset loaded $hookAsset bytes=${lateHook.size} early=${nwPolyfill?.size ?: 0} scriptAppends=${scriptAppends.keys} v1Overlay=${v1Overlay.keys} rpgMakerVersion=$rpgMakerVersion v12Session=$v12Session useCoreScriptOverlay=$useCoreScriptOverlay")
            val legacyRenderer = intent.getBooleanExtra(EXTRA_RPG_LEGACY_RENDERER, false)
            InjectionBundle(contentRoot, lateHook, scriptAppends, internalResources, nwPolyfill, modHtml, legacyRenderer)
        } catch (error: Throwable) {
            Log.e(TAG, "build injection bundle failed", error)
            failLaunch(getString(R.string.engine_rpgmaker_server_failed))
            null
        }
    }

    /** 启动本地 HTTP 服务器；失败时提示并保持 localServer 为 null（调用方据此中止）。 */
    private fun startLocalServer(bundle: InjectionBundle) {
        try {
            localServer = if (gameUsesAsar) {
                RpgMakerLocalHttpServer(
                    bundle.contentRoot, asarArchive, bundle.lateHook, true, bundle.scriptAppends, bundle.modHtml, bundle.internalResources, bundle.nwPolyfill, v12Session,
                )
            } else {
                RpgMakerLocalHttpServer(
                    bundle.contentRoot, bundle.lateHook, true, bundle.scriptAppends, bundle.modHtml, bundle.internalResources, bundle.nwPolyfill, v12Session,
                )
            }.also { it.start() }
        } catch (error: Throwable) {
            Log.e(TAG, "start local server failed", error)
            failLaunch(getString(R.string.engine_rpgmaker_server_failed))
        }
    }

    /** 创建 WebView 与各 JS 桥、按 legacy 开关拼 URL 并装载游戏页。 */
    private fun setupGameUi(saves: File?, bundle: InjectionBundle) {
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            layoutParams = ViewGroup.LayoutParams(-1, -1)
        }
        val browser = WebView(this).apply {
            layoutParams = ViewGroup.LayoutParams(-1, -1)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) defaultFocusHighlightEnabled = false
        }
        webView = browser
        root.addView(browser)
        // 虚拟鼠标层（issue #25）：叠在 WebView 之上
        val layer = VirtualMouseLayer(this) { js ->
            webView?.let { v -> runCatching { v.evaluateJavascript(js, null) } }
        }
        virtualMouseLayer = layer
        root.addView(layer, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(root)

        configureWebView(browser)
        browser.addJavascriptInterface(RpgMakerSaveBridge(saves), RPG_MAKER_SAVE_BRIDGE_NAME)
        browser.addJavascriptInterface(
            TouchPadSaveBridge(rpgMakerModGameId),
            TOUCH_PAD_BRIDGE_NAME,
        )
        if (rpgMakerModEnabled) {
            browser.addJavascriptInterface(
                RpgMakerModBridge(rpgMakerModGameId),
                RPG_MAKER_MOD_BRIDGE_NAME,
            )
        }
        // PIXI legacy 兼容渲染（?android-legacy=1，__rpg_v12.js 的既定开关）：
        // 由设置页开关经 rpgLegacyRenderer extra 控制，规避部分 Android GPU
        // 上 WebGL 正常初始化却整屏渲染为黑的问题；默认关闭不影响既有行为。
        // 注意必须带 =1：__rpg_v12.js 的参数正则要求 key=value 格式，裸参数会被忽略
        val url = if (bundle.legacyRenderer) {
            "http://localhost:${requireNotNull(localServer).port}/index.html?android-legacy=1"
        } else {
            "http://localhost:${requireNotNull(localServer).port}/index.html"
        }
        Log.i(TAG, "loadUrl=$url")
        browser.loadUrl(url)
    }

    private fun failLaunch(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        finish()
    }

    private fun loadAsset(name: String): ByteArray = assets.open(name).buffered().use { it.readBytes() }

    private fun loadAssetOrNull(name: String): ByteArray? = try { loadAsset(name) } catch (_: Exception) { null }

    private fun cssColor(color: Int): String = String.format(Locale.US, "#%06X", color and 0xFFFFFF)

    /** 虚拟鼠标合成事件 API（懒加载缓存；见 assets/__tyranor_mouse.js）。 */
    private val mouseJs: String by lazy {
        runCatching { loadAsset(VIRTUAL_MOUSE_ASSET).toString(Charsets.UTF_8) }.getOrDefault("")
    }

    private fun buildRpgMakerModHtml(): String {
        val colors = EngineThemeColors.fromIntent(intent)
        return """
            <style>:root{--tm-primary:${cssColor(colors.primary)};--tm-on-primary:${cssColor(colors.onPrimary)};}</style>
            <link rel="stylesheet" href="/__tyranor__/rpgmaker_mod.css">
            <script src="/__tyranor__/rpgmaker_mod_core.js"></script>
            <script src="/__tyranor__/rpgmaker_mod_ui.js"></script>
        """.trimIndent()
    }

    private fun detectWebGameType(explicitType: String?, contentRoot: File, asar: AsarArchive?): WebGameType {
        if (asar != null) {
            fun has(vararg paths: String): Boolean = paths.any { asar.has(it) || asar.isDirectory(it) }
            return when {
                has("js/rpg_core.js", "www/js/rpg_core.js") -> WebGameType.RPG_MV
                has("js/rmmz_core.js", "www/js/rmmz_core.js") -> WebGameType.RPG_MZ
                else -> WebGameType.fromIntent(explicitType) ?: WebGameType.RPG_MV
            }
        }
        return when {
            File(contentRoot, "js/rpg_core.js").isFile -> WebGameType.RPG_MV
            File(contentRoot, "js/rmmz_core.js").isFile -> WebGameType.RPG_MZ
            else -> WebGameType.fromIntent(explicitType) ?: WebGameType.RPG_MV
        }
    }

    private fun configureWebView(browser: WebView) {
        browser.isHorizontalScrollBarEnabled = false
        browser.isVerticalScrollBarEnabled = false
        runCatching { browser.clearCache(true) }
        runCatching { browser.setLayerType(View.LAYER_TYPE_HARDWARE, null) }
        browser.setBackgroundColor(Color.BLACK)
        browser.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                return handleNavigation(request?.url?.toString(), request?.isForMainFrame != false)
            }

            @Suppress("DEPRECATION")
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean =
                handleNavigation(url, true)

            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                Log.i(TAG, "onPageStarted url=$url")
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                Log.i(TAG, "onPageFinished url=$url")
                // 虚拟鼠标合成事件 API（幂等，页面每次加载后重新注入）
                if (virtualMouseLayer != null && view != null) {
                    runCatching { view.evaluateJavascript(mouseJs, null) }
                }
            }

            override fun onReceivedError(
                view: WebView?,
                request: android.webkit.WebResourceRequest?,
                error: android.webkit.WebResourceError?,
            ) {
                super.onReceivedError(view, request, error)
                Log.e(TAG, "onReceivedError url=${request?.url} code=${error?.errorCode} desc=${error?.description}")
            }

            @Suppress("DEPRECATION")
            override fun onReceivedError(view: WebView?, errorCode: Int, description: String?, failingUrl: String?) {
                super.onReceivedError(view, errorCode, description, failingUrl)
                Log.e(TAG, "onReceivedError(code=$errorCode, url=$failingUrl, desc=$description)")
            }

            @Suppress("DEPRECATION")
            override fun onReceivedHttpError(
                view: WebView?,
                request: WebResourceRequest?,
                errorResponse: android.webkit.WebResourceResponse?,
            ) {
                super.onReceivedHttpError(view, request, errorResponse)
                Log.w(TAG, "onReceivedHttpError url=${request?.url} status=${errorResponse?.statusCode}")
            }

            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?,
            ): WebResourceResponse? = request?.url
                ?.takeUnless(::isAllowedGameResource)
                ?.let { blockedResponse() }

            @Suppress("DEPRECATION")
            override fun shouldInterceptRequest(view: WebView?, url: String?): WebResourceResponse? {
                val uri = url?.let(Uri::parse)
                return uri?.takeUnless(::isAllowedGameResource)?.let { blockedResponse() }
            }
        }
        browser.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: android.webkit.ConsoleMessage?): Boolean {
                if (consoleMessage != null) {
                    val level = Log.INFO
                    if (consoleMessage.messageLevel() == android.webkit.ConsoleMessage.MessageLevel.ERROR ||
                        consoleMessage.messageLevel() == android.webkit.ConsoleMessage.MessageLevel.WARNING
                    ) {
                        Log.w(TAG, "JS[${consoleMessage.messageLevel()}] ${consoleMessage.message()} (${consoleMessage.sourceId()}:${consoleMessage.lineNumber()})")
                    } else {
                        Log.i(TAG, "JS[${consoleMessage.messageLevel()}] ${consoleMessage.message()} (${consoleMessage.sourceId()}:${consoleMessage.lineNumber()})")
                    }
                }
                return super.onConsoleMessage(consoleMessage)
            }
        }
        // 仅 debug 构建开放 chrome://inspect 远程调试；release 保持关闭，避免任意 JS 注入面
        if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            runCatching { android.webkit.WebView.setWebContentsDebuggingEnabled(true) }
        }
        browser.settings.apply {
            userAgentString = "$userAgentString;tyranoplayer-android-1.0;tyranor-internal-rpgmaker"
            javaScriptEnabled = true
            allowContentAccess = false
            allowFileAccess = false
            allowFileAccessFromFileURLs = false
            allowUniversalAccessFromFileURLs = false
            domStorageEnabled = true
            databaseEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            javaScriptCanOpenWindowsAutomatically = false
            loadsImagesAutomatically = true
            blockNetworkImage = false
            mediaPlaybackRequiresUserGesture = false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                mixedContentMode = if (allowExternalNetwork) {
                    WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                } else {
                    WebSettings.MIXED_CONTENT_NEVER_ALLOW
                }
            }
        }
    }

    private fun handleNavigation(url: String?, mainFrame: Boolean): Boolean {
        if (url == null) return true
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return true
        if (isLocalGameUri(uri)) return false
        if (mainFrame) openExternalHttpUrl(uri)
        return true
    }

    private fun isLocalGameUri(uri: Uri?): Boolean {
        val server = localServer ?: return false
        if (!uri?.scheme.equals("http", ignoreCase = true)) return false
        if (!v12Session) {
            // v0 兜底会话：与 v0 宿主一致的严格回环判断
            return (uri?.host.equals("localhost", ignoreCase = true) || uri?.host == "127.0.0.1") &&
                uri?.port == server.port
        }
        // 兼容 localhost/127.0.0.1/host 为空（部分 WebView 对 localhost 归一化）的解析差异
        val host = uri?.host?.trim()?.lowercase(Locale.ROOT)
        val isLoopback = host.isNullOrEmpty() || host == "localhost" || host == "127.0.0.1" ||
            host == "0.0.0.0" || host == "[::1]"
        return isLoopback && (uri?.port == server.port)
    }

    private fun isAllowedGameResource(uri: Uri): Boolean = isLocalGameUri(uri) ||
        (allowExternalNetwork && (uri.scheme.equals("http", ignoreCase = true) ||
            uri.scheme.equals("https", ignoreCase = true))) ||
        uri.scheme.equals("data", ignoreCase = true) ||
        uri.scheme.equals("blob", ignoreCase = true) ||
        uri.scheme.equals("about", ignoreCase = true)

    private fun blockedResponse(): WebResourceResponse = WebResourceResponse(
        "text/plain",
        "UTF-8",
        403,
        "Forbidden",
        mapOf("Content-Length" to "0"),
        ByteArrayInputStream(ByteArray(0)),
    )

    private fun openExternalHttpUrl(uri: Uri?) {
        if (uri == null ||
            (!uri.scheme.equals("http", ignoreCase = true) &&
                !uri.scheme.equals("https", ignoreCase = true))
        ) return
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (error: Throwable) {
            Log.w(TAG, "open external URL failed: $uri", error)
        }
    }

    private fun resolveGameDir(source: Intent?): String? {
        source ?: return null
        val path = uriToFilePath(
            firstNonEmpty(
                source.getStringExtra("path"),
                source.getStringExtra("gamePath"),
                source.getStringExtra("projectRoot"),
                source.getStringExtra("gamedir"),
                source.getStringExtra("rootUri"),
            ),
        ) ?: return null
        val file = File(path).let { if (it.isFile) it.parentFile else it }
        return file?.absolutePath
    }

    private fun uriToFilePath(value: String?): String? {
        val raw = value?.trim() ?: return null
        if (raw.startsWith("file://")) return raw.removePrefix("file://")
        if (raw.startsWith("content://")) {
            val segment = Uri.parse(raw).lastPathSegment
            val colon = segment?.indexOf(':') ?: -1
            if (segment != null && colon >= 0) {
                val volume = segment.substring(0, colon)
                val relative = segment.substring(colon + 1)
                return if (volume.equals("primary", ignoreCase = true)) {
                    "/storage/emulated/0/$relative"
                } else {
                    "/storage/$volume/$relative"
                }
            }
        }
        return raw
    }

    private fun firstNonEmpty(vararg values: String?): String? =
        values.firstOrNull { !it.isNullOrBlank() }?.trim()

    override fun finish() {
        super.finish()
        if (processExitScheduled.compareAndSet(false, true)) {
            Handler(Looper.getMainLooper()).postDelayed({
                runCatching { android.os.Process.killProcess(android.os.Process.myPid()) }
            }, PROCESS_EXIT_DELAY_MS)
        }
    }

    @Deprecated("Deprecated in Android")
    override fun onBackPressed() {
        handleBackRequest()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP) handleBackRequest()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun handleBackRequest() {
        val browser = webView
        if (!rpgMakerModEnabled || browser == null) {
            DoubleBackExit.handleBack(this) { finish() }
            return
        }
        browser.evaluateJavascript(
            "(function(){if(window.TyranorModUI&&window.TyranorModUI.isOpen()){window.TyranorModUI.close();return true;}return false;})()",
        ) { handled ->
            if (!handled.equals("true", ignoreCase = true)) {
                DoubleBackExit.handleBack(this) { finish() }
            }
        }
    }

    override fun onPause() {
        virtualMouseLayer?.reset()
        // MV/MZ 的 WebAudio 主上下文挂起（WebView.onPause 不暂停 WebAudio，切后台会继续出声；
        // Html5Audio/视频旁路无法全局枚举，引擎主路径已覆盖）。v0 宿主的 _tyrano_player 挂起
        // 为 tyrano 专用，此处为 MV/MZ 等价实现。
        runCatching {
            webView?.evaluateJavascript(
                "try{if(window.WebAudio&&WebAudio._context&&WebAudio._context.state==='running'){WebAudio._context.suspend();}}catch(e){}",
                null,
            )
        }
        runCatching { webView?.onPause() }
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        enterFullscreen()
        if (firstResume) firstResume = false else {
            // 与 v0 宿主同构：首帧跳过恢复（页面尚未开始播放），前后台切换时恢复 WebAudio
            runCatching {
                webView?.evaluateJavascript(
                    "try{if(window.WebAudio&&WebAudio._context&&WebAudio._context.state==='suspended'){WebAudio._context.resume();}}catch(e){}",
                    null,
                )
            }
        }
        runCatching { webView?.onResume() }
    }

    override fun onDestroy() {
        EngineSessionRegistry.clear(this, EngineSessionRegistry.HOST_RPGMAKER)
        DoubleBackExit.clear(this)
        runCatching {
            webView?.stopLoading()
            webView?.loadUrl("about:blank")
            webView?.destroy()
        }
        webView = null
        runCatching { localServer?.stop() }
        localServer = null
        runCatching { asarArchive?.close() }
        asarArchive = null
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterFullscreen()
    }

    @Suppress("DEPRECATION")
    private fun enterFullscreen() {
        val flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        runCatching { window.decorView.systemUiVisibility = flags }
    }

    private fun resolveSaveDirectory(source: Intent?, gameRoot: File?): File? {
        if (source?.getBooleanExtra(EXTRA_SCOPED_SAVE_DIR, false) == true) {
            val explicit = source.getStringExtra(EXTRA_SCOPED_SAVE_ROOT)?.takeIf(String::isNotBlank)
                ?: return null
            return try {
                val external = getExternalFilesDir(null) ?: return null
                val namespace = File(File(external, "save"), "tyrano").canonicalFile
                File(explicit).canonicalFile.takeIf {
                    it.path.startsWith(namespace.path + File.separator)
                }
            } catch (_: Throwable) {
                // canonicalFile 解析失败时视为不在受控目录内，返回 null 走默认存档路径（边界兜底，§8）
                null
            }
        }
        return gameRoot?.let { File(it, "savedata") }
    }

    /** RPG Maker MV/MZ 的 StorageManager 兼容桥，接口名与旧项目保持一致。 */
    inner class RpgMakerSaveBridge(private val saveDirectory: File?) {
        @JavascriptInterface
        fun Save(key: String?, base64Data: String?) =
            RpgMakerStorage.write(saveDirectory, key, base64Data, RPG_MV_SAVE_EXTENSION)

        @JavascriptInterface
        fun Load(key: String?): String = RpgMakerStorage.read(saveDirectory, key, RPG_MV_SAVE_EXTENSION)

        @JavascriptInterface
        fun Exists(key: String?): Boolean = RpgMakerStorage.exists(saveDirectory, key, RPG_MV_SAVE_EXTENSION)

        @JavascriptInterface
        fun Remove(key: String?): Boolean = RpgMakerStorage.remove(saveDirectory, key, RPG_MV_SAVE_EXTENSION)
    }

    /** 修改器仅能读写当前游戏的布尔开关，不暴露文件系统或其他游戏的状态键。 */
    inner class RpgMakerModBridge(private val gameId: String) {
        private val preferences
            get() = getSharedPreferences(RPG_MAKER_MOD_PREFS, Context.MODE_PRIVATE)

        @JavascriptInterface
        fun getState(): String = preferences.getString(gameId, null).orEmpty()

        @JavascriptInterface
        fun setState(raw: String?) {
            val input = runCatching { JSONObject(raw.orEmpty()) }.getOrNull() ?: return
            val sanitized = JSONObject()
            RPG_MAKER_MOD_FLAGS.forEach { key ->
                if (input.has(key)) sanitized.put(key, input.optBoolean(key, false))
            }
            preferences.edit().putString(gameId, sanitized.toString()).apply()
        }
    }

    /** 触屏手柄游戏内布局保存桥（issue #30）：按游戏持久化自定义布局与预设。 */
    inner class TouchPadSaveBridge(private val gameId: String) {
        private val preferences
            get() = getSharedPreferences(EnginePrefs.GAME_OVERRIDES_PREFS, Context.MODE_PRIVATE)

        // touch_pad_config / touch_pad_presets 键名在本文件与 app 侧 GameOverridePartitions
        // 双处锚定（engine 不得反向依赖 app）：app 侧经 game_overrides 表 + prefs 镜像参与读写；
        // 与 PerGameSettingsStore 的其它引擎字段共存于同一条 JSON 记录，必须整条读改写以保留他人字段。
        // 已知限制：app 主线程的设置页写路径与本桥线程之间暂无跨层互斥，极端并发下存在丢更新窗口，
        // 后续如需彻底收口应把该记录的全部读写收敛到单一同步入口。
        private fun readField(key: String): String = try {
            preferences.getString(gameId, null)?.let { raw ->
                JSONObject(raw).optString(key)
            }.orEmpty()
        } catch (_: Throwable) {
            ""
        }

        private fun updateRecord(mutate: (JSONObject) -> Unit) {
            val existing = runCatching { preferences.getString(gameId, null)?.let { JSONObject(it) } }
                .getOrNull() ?: JSONObject()
            mutate(existing)
            preferences.edit().putString(gameId, existing.toString()).apply()
        }

        @JavascriptInterface
        fun getConfig(): String = readField(PER_GAME_TOUCH_PAD_KEY)

        @JavascriptInterface
        fun saveConfig(raw: String?) {
            if (gameId.isBlank() || raw.isNullOrBlank()) return
            try {
                val input = JSONObject(raw)
                updateRecord { record ->
                    if (input.length() == 0) {
                        record.remove(PER_GAME_TOUCH_PAD_KEY)
                    } else {
                        // 统一字符串形态落盘，与 PerGameSettingsStore.setStr 的包裹方式一致
                        record.put(PER_GAME_TOUCH_PAD_KEY, input.toString())
                    }
                }
            } catch (_: Throwable) {
                // 保留现有配置
            }
        }

        @JavascriptInterface
        fun getPresets(): String = readField(PER_GAME_TOUCH_PAD_PRESETS_KEY)

        @JavascriptInterface
        fun savePresets(raw: String?) {
            if (gameId.isBlank()) return
            try {
                updateRecord { record ->
                    if (raw.isNullOrBlank() || JSONObject(raw).length() == 0) {
                        record.remove(PER_GAME_TOUCH_PAD_PRESETS_KEY)
                    } else {
                        record.put(PER_GAME_TOUCH_PAD_PRESETS_KEY, JSONObject(raw).toString())
                    }
                }
            } catch (_: Throwable) {
                // 保留现有预设
            }
        }
    }

    /**
     * 游戏入口定位结果。
     *
     * @property contentRoot 包含 index.html 或 app.asar 的目录，将作为本地 HTTP 服务器的 root。
     * @property asarPath 命中的 app.asar 绝对路径；非空表示 asar 模式，空表示散文件模式。
     */
    private class GameEntry(val contentRoot: File, val asarPath: String?)

    /**
     * 递归查找游戏入口（index.html 或 app.asar）。
     *
     * 根目录优先匹配 app.asar / resources/app.asar / index.html；未命中时按
     * [WEB_ENTRY_SUBDIRS] 列表递归搜索子目录，与启动器侧的引擎特征探测子目录保持一致，
     * 避免扫描器识别成功但启动器找不到入口而闪退。
     *
     * @param dir 当前搜索目录。
     * @param depth 当前递归深度，根目录传入 0。
     * @return 入口定位结果；未找到返回 null。
     */
    private fun findGameEntry(dir: File, depth: Int): GameEntry? {
        // 当前目录的入口文件（保持原逻辑：asar 优先于 index.html）
        dir.resolve("app.asar").takeIf { it.isFile }?.let {
            return GameEntry(dir, it.absolutePath)
        }
        dir.resolve("resources/app.asar").takeIf { it.isFile }?.let {
            return GameEntry(dir, it.absolutePath)
        }
        dir.resolve("app.asar").takeIf { it.isDirectory && it.resolve("index.html").isFile }?.let {
            return GameEntry(it, null)
        }
        dir.resolve("resources/app.asar").takeIf { it.isDirectory && it.resolve("index.html").isFile }?.let {
            return GameEntry(it, null)
        }
        dir.resolve("index.html").takeIf { it.isFile }?.let {
            return GameEntry(dir, null)
        }
        // 达到最大深度后不再递归
        if (depth >= MAX_ENTRY_SEARCH_DEPTH) return null
        for (name in WEB_ENTRY_SUBDIRS) {
            val sub = dir.resolve(name)
            if (!sub.isDirectory) continue
            findGameEntry(sub, depth + 1)?.let { return it }
        }
        return null
    }

    private enum class WebGameType(val intentValue: String) {
        RPG_MV("RPG"),
        RPG_MZ("RMMZ");

        companion object {
            fun fromIntent(value: String?): WebGameType? = entries.firstOrNull {
                it.intentValue.equals(value, ignoreCase = true)
            }
        }
    }

    companion object {
        private const val TAG = "YukiRpgMaker"
        // 共享脚本资产在 engine assets 根；v1/v2 专属运行时资产统一在 rpgmaker/ 子目录
        private const val RPG_MV_HOOK_ASSET = "__rpg__.js"
        private const val RPG_MV_V12_HOOK_ASSET = "rpgmaker/__rpg_v12.js"
        private const val RPG_MZ_HOOK_ASSET = "__rmmz__.js"
        private const val TOUCH_PAD_ASSET = "__touch_pad.js"
        private const val NWJS_POLYFILL_ASSET = "rpgmaker/__nwjs_polyfill.js"
        private const val NWJS_POLYFILL_V1_EXTRA_ASSET = "rpgmaker/__nwjs_polyfill_v1.js"
        private const val NWJS_POLYFILL_V2_EXTRA_ASSET = "rpgmaker/__nwjs_polyfill_v2.js"
        private const val RPG_MZ_CORE_HOOK_ASSET = "__hook_rmmz_core.js"
        private const val RPG_MZ_MANAGERS_HOOK_ASSET = "__hook_rmmz_managers.js"
        private const val RPG_MAKER_SAVE_BRIDGE_NAME = "saveDataManager"
        private const val RPG_MAKER_MOD_BRIDGE_NAME = "TyranorModNative"
        private const val TOUCH_PAD_BRIDGE_NAME = "TyranorTouchPadNative"
        private const val RPG_MV_SAVE_EXTENSION = ".bin"
        private const val EXTRA_SCOPED_SAVE_DIR = "scopedSaveDir"
        private const val EXTRA_SCOPED_SAVE_ROOT = "scopedSaveRoot"
        private const val EXTRA_RPG_MAKER_MOD_ENABLED = "rpgMakerModEnabled"
        private const val EXTRA_RPG_MAKER_MOD_GAME_ID = "rpgMakerModGameId"
        private const val EXTRA_RPG_MAKER_VERSION = "rpgMakerVersion"
        private const val EXTRA_RPG_LEGACY_RENDERER = "rpgLegacyRenderer"
        private const val RPG_MAKER_MOD_PREFS = "tyranor_rpgmaker_mod_state"
        private const val PER_GAME_TOUCH_PAD_KEY = "touch_pad_config"
        private const val PER_GAME_TOUCH_PAD_PRESETS_KEY = "touch_pad_presets"
        private const val RPG_MAKER_MOD_CORE_ASSET = "__rpgmaker_mod_core.js"
        private const val RPG_MAKER_MOD_UI_ASSET = "__rpgmaker_mod_ui.js"
        private const val RPG_MAKER_MOD_CSS_ASSET = "__rpgmaker_mod.css"
        private const val RPG_MAKER_MOD_ICON_ASSET = "__rpgmaker_mod_icon.png"
        private const val VIRTUAL_MOUSE_ASSET = "__tyranor_mouse.js"
        private const val RPG_MV_V1_PREFIX = "rpgmaker/rpgmv-v1"
        private val RPG_MV_V1_FILES = arrayOf(
            "js/rpg_core.js",
            "js/rpg_managers.js",
            "js/rpg_objects.js",
            "js/rpg_scenes.js",
            "js/rpg_sprites.js",
            "js/rpg_windows.js",
            "js/libs/pixi.js",
            "js/libs/pixi-tilemap.js",
            "js/libs/pixi-picture.js",
            "js/libs/iphone-inline-video.browser.js",
            "js/libs/fpsmeter.js",
            "js/libs/lz-string.js",
        )

        // v1 覆盖：MV 1.6.1 corescript（与全局设置 RPG_MV_V1 = "v1" 对应）
        private fun buildRpgMvV1Overlay(manager: android.content.res.AssetManager): Map<String, ByteArray> {
            val out = mutableMapOf<String, ByteArray>()
            // 3959930_1.19 的 MPTPShowforActor.js 为单游戏特例，已由 __nwjs_polyfill.js 的 Window 兼容运行时兜底，不在此无条件覆盖
            var missing = false
            for (path in RPG_MV_V1_FILES) {
                val assetPath = RPG_MV_V1_PREFIX + "/" + path
                val bytes = runCatching { manager.open(assetPath).buffered().use { it.readBytes() } }.getOrNull()
                if (bytes != null && bytes.isNotEmpty()) {
                    out[path] = bytes
                    out["www/" + path] = bytes
                } else {
                    Log.w(TAG, "v1 overlay missing asset " + assetPath)
                    missing = true
                }
            }
            if (missing) {
                Log.w(TAG, "v1 overlay incomplete, falling back to v0 resources")
                return emptyMap()
            }
            return out
        }

        private const val RPG_MAKER_MOD_CORE_PATH = "__tyranor__/rpgmaker_mod_core.js"
        private const val RPG_MAKER_MOD_UI_PATH = "__tyranor__/rpgmaker_mod_ui.js"
        private const val RPG_MAKER_MOD_CSS_PATH = "__tyranor__/rpgmaker_mod.css"
        private const val RPG_MAKER_MOD_ICON_PATH = "__tyranor__/rpgmaker_mod_icon.png"
        private val RPG_MAKER_MOD_FLAGS = arrayOf(
            "godMode", "oneHit", "alwaysCrit", "noclip", "eventSpeed", "msgSkip",
        )
        private const val PROCESS_EXIT_DELAY_MS = 500L
        private const val MAX_ENTRY_SEARCH_DEPTH = 2
        private val WEB_ENTRY_SUBDIRS = arrayOf("www", "resources", "app.asar", "app", "tyrano", "data", "scenario", "system", "game")

        private const val KEY_UI_FONT_SCALE = "ui_font_scale"
        private const val KEY_UI_SCALE = "ui_scale"
        private const val DEFAULT_FONT_SCALE = 1.0f
        private const val MIN_FONT_SCALE = 0.85f
        private const val MAX_FONT_SCALE = 1.30f
        private const val DEFAULT_UI_SCALE = 1.0f
        private const val MIN_UI_SCALE = 0.70f
        private const val MAX_UI_SCALE = 1.50f

        private fun ensureWritableSaveDirectory(directory: File?): Boolean = try {
            directory != null &&
                (directory.exists() || directory.mkdirs()) &&
                directory.isDirectory &&
                directory.canWrite()
        } catch (_: Throwable) {
            // 目录探测/创建失败视为不可写，返回 false 由调用方回退（边界兜底，§8）
            false
        }

        /**
         * 通过 SharedPreferences 持久化的用户偏好创建自定义 Configuration 的 Context。
         *
         * 复刻 app 模块 UiScaleUtil.wrap 的语义：读取 tyranor_prefs 中的字体缩放与全局
         * UI 缩放，应用到 Configuration 后返回新的 Context。rpgmaker 模块不依赖 app 的
         * 工具类，此处保留独立的等价实现以避免反向依赖。
         */
        private fun wrapContextForUiScale(base: Context?): Context? {
            if (base == null) return null
            val prefs = base.getSharedPreferences(EnginePrefs.APP_PREFS, Context.MODE_PRIVATE)
            // NaN/Infinite 回落到各自默认值，与 app 模块 UiScaleUtil.clamp/clampUiScale 严格一致
            val fontScale = prefs.getFloat(KEY_UI_FONT_SCALE, DEFAULT_FONT_SCALE).let {
                if (it.isNaN() || it.isInfinite()) DEFAULT_FONT_SCALE else it.coerceIn(MIN_FONT_SCALE, MAX_FONT_SCALE)
            }
            val uiScale = prefs.getFloat(KEY_UI_SCALE, DEFAULT_UI_SCALE).let {
                if (it.isNaN() || it.isInfinite()) DEFAULT_UI_SCALE else it.coerceIn(MIN_UI_SCALE, MAX_UI_SCALE)
            }
            val config = Configuration(base.resources.configuration)
            config.fontScale = fontScale
            // 通过修改 densityDpi 实现全局 UI 缩放
            if (uiScale != 1.0f) {
                config.densityDpi = (config.densityDpi * uiScale).toInt()
            }
            return base.createConfigurationContext(config)
        }
    }
}
