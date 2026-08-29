package com.core.tyrano

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.view.Gravity
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
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.core.engine.DoubleBackExit
import com.core.engine.EnginePrefs
import com.core.engine.EngineThemeColors
import com.core.engine.R
import java.io.ByteArrayInputStream
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject

/**
 * Tyrano WebView 宿主；资源服务与存档沙箱分别由独立组件负责。
 *
 * 本 Activity 隶属于 engine 模块，不依赖 app 层。用户偏好（UI 缩放、外网开关）
 * 直接读取共享的 yukihub_prefs，与 OnsSettings 同模式；确认对话框通过 Intent extras
 * 传入的 Launcher 主题色在 engine 内复刻 LauncherDialogFactory 的视觉风格，保持统一。
 */
class TyranoActivity : Activity() {
    private var webView: WebView? = null
    private var virtualMouseLayer: VirtualMouseLayer? = null
    private var gameDir: String? = null
    private var gameRootFile: File? = null
    private var saveDirectory: File? = null
    private var gameUsesAsar = false
    private var webGameType = WebGameType.TYRANO
    private var asarPath: String? = null
    private var asarArchive: AsarArchive? = null
    private var firstResume = true
    private var localServer: TyranoLocalHttpServer? = null
    private var allowExternalNetwork = false
    private var rpgMakerModEnabled = false
    private var rpgMakerModGameId = ""
    private var rpgMakerVersion: String? = null
    private val processExitScheduled = AtomicBoolean(false)

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(wrapContextForUiScale(newBase) ?: newBase)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enterFullscreen()
        allowExternalNetwork = getSharedPreferences(EnginePrefs.APP_PREFS, Context.MODE_PRIVATE)
            .getBoolean(EnginePrefs.KEY_TYRANO_EXTERNAL_NETWORK, false)

        gameDir = resolveGameDir(intent)
        Log.i(TAG, "onCreate gameDir=$gameDir")
        val resolvedGameDir = gameDir
        if (resolvedGameDir.isNullOrBlank()) {
            failLaunch(getString(R.string.engine_tyrano_empty_game_directory))
            return
        }

        val gameRoot = File(resolvedGameDir)
        gameRootFile = gameRoot

        val entry = findTyranoEntry(gameRoot, 0)
        if (entry == null) {
            val rootAsar = File(gameRoot, "app.asar")
            val resourcesAsar = File(File(gameRoot, "resources"), "app.asar")
            val index = File(gameRoot, "index.html")
            Log.e(TAG, "entry not found index=${index.absolutePath} app.asar=${rootAsar.absolutePath} resources/app.asar=${resourcesAsar.absolutePath} (searched subdirs: ${WEB_ENTRY_SUBDIRS.joinToString()})")
            failLaunch(getString(R.string.engine_tyrano_entry_not_found))
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
                failLaunch(getString(R.string.engine_tyrano_asar_unreadable))
                return
            }
        }
        webGameType = detectWebGameType(intent.getStringExtra("type"), contentRoot, asarArchive)
        rpgMakerModEnabled = intent.getBooleanExtra(EXTRA_RPG_MAKER_MOD_ENABLED, true) &&
            (webGameType == WebGameType.RPG_MV || webGameType == WebGameType.RPG_MZ)
        rpgMakerModGameId = intent.getStringExtra(EXTRA_RPG_MAKER_MOD_GAME_ID)
            ?.takeIf(String::isNotBlank)
            ?: resolvedGameDir
        rpgMakerVersion = intent.getStringExtra(EXTRA_RPG_MAKER_VERSION)?.takeIf(String::isNotBlank)
        Log.i(TAG, "entry mode=${if (gameUsesAsar) "asar" else "dir"} type=${webGameType.intentValue} rpgMakerVersion=$rpgMakerVersion asar=$asarPath contentRoot=${contentRoot.absolutePath}")
        val needsSaveBridge = webGameType == WebGameType.TYRANO ||
            webGameType == WebGameType.RPG_MV || webGameType == WebGameType.RPG_MZ
        val saves = if (needsSaveBridge) resolveSaveDirectory(intent, gameRoot) else null
        saveDirectory = saves
        if (needsSaveBridge && !ensureWritableSaveDirectory(saves)) {
            failLaunch(getString(R.string.engine_tyrano_unwritable_save_directory))
            return
        }
        Log.i(TAG, "save directory=${saves?.absolutePath ?: "none"} scoped=${intent.getBooleanExtra(EXTRA_SCOPED_SAVE_DIR, false)}")

        try {
            val hookAsset = when (webGameType) {
                WebGameType.TYRANO -> TYRANO_HOOK_ASSET
                WebGameType.RPG_MV -> RPG_MV_HOOK_ASSET
                WebGameType.RPG_MZ -> RPG_MZ_HOOK_ASSET
                WebGameType.VN, WebGameType.WEB_OTHER -> null
            }
            // 触屏手柄（issue #35）：MV/MZ 共用 __touch_pad.js，拼接进 hook 注入，
            // 独立于修改器开关。手柄代码零引擎依赖，MV/MZ 的 Input 均读 keyCode。
            // issue #30：游戏内可自定义按钮布局，逐游戏配置在此注入供 JS 读取。
            val isRpgWebGame = webGameType == WebGameType.RPG_MV || webGameType == WebGameType.RPG_MZ
            val touchPadConf = if (isRpgWebGame && rpgMakerModGameId.isNotBlank()) {
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
            val touchPadThemeJs = if (isRpgWebGame) {
                val colors = EngineThemeColors.fromIntent(intent)
                "window.__touchPadTheme={primary:'${cssColor(colors.primary)}',onPrimary:'${cssColor(colors.onPrimary)}'};"
            } else {
                ""
            }
            val touchPad =
                if (isRpgWebGame) {
                    val pad = try { String(loadAsset(TOUCH_PAD_ASSET), Charsets.UTF_8) } catch (_: Exception) { "" }
                    (touchPadThemeJs + "\n" + touchPadConfigJs + "\n" + pad).toByteArray(Charsets.UTF_8)
                } else {
                    ByteArray(0)
                }
            val nwPolyfill = if (webGameType == WebGameType.RPG_MV || webGameType == WebGameType.RPG_MZ) {
                try { loadAsset(NWJS_POLYFILL_ASSET) } catch (_: Exception) { ByteArray(0) }
            } else {
                ByteArray(0)
            }
            val lateHook = (hookAsset?.let { assets.open(it).buffered().use { input -> input.readBytes() } } ?: ByteArray(0)) +
                touchPad
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
            val normalizedVersion = rpgMakerVersion?.trim()?.lowercase()
            val isRpgMvV1 = webGameType == WebGameType.RPG_MV && normalizedVersion == "v1"
            if (webGameType == WebGameType.RPG_MZ && normalizedVersion == "v1") {
                android.util.Log.i(TAG, "MZ v1 is placeholder, falling back to v0 resources")
            }
            val v1Overlay: Map<String, ByteArray> = if (isRpgMvV1) {
                buildRpgMvV1Overlay(assets)
            } else {
                emptyMap()
            }
            val internalResources = modResources + v1Overlay
            val isRpgWebGameForInject = webGameType == WebGameType.RPG_MV || webGameType == WebGameType.RPG_MZ
            Log.i(TAG, "asset loaded ${hookAsset ?: "none"} bytes=${lateHook.size} early=${nwPolyfill.size} scriptAppends=${scriptAppends.keys} v1Overlay=${v1Overlay.keys} rpgMakerVersion=$rpgMakerVersion injectBeforeBody=${isRpgWebGameForInject}")
            localServer = if (gameUsesAsar) {
                TyranoLocalHttpServer(
                    contentRoot, asarArchive, lateHook, isRpgWebGameForInject, scriptAppends, modHtml, internalResources, nwPolyfill,
                )
            } else {
                TyranoLocalHttpServer(
                    contentRoot, lateHook, isRpgWebGameForInject, scriptAppends, modHtml, internalResources, nwPolyfill,
                )
            }.also { it.start() }
        } catch (error: Throwable) {
            Log.e(TAG, "start local server failed", error)
            failLaunch(getString(R.string.engine_tyrano_server_failed))
            return
        }

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
        // 虚拟鼠标层（issue #25）：仅 RPG Maker MV/MZ 需要，叠在 WebView 之上
        if (webGameType == WebGameType.RPG_MV || webGameType == WebGameType.RPG_MZ) {
            val layer = VirtualMouseLayer(this) { js ->
                webView?.let { v -> runCatching { v.evaluateJavascript(js, null) } }
            }
            virtualMouseLayer = layer
            root.addView(layer, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        setContentView(root)

        configureWebView(browser)
        when (webGameType) {
            WebGameType.RPG_MV, WebGameType.RPG_MZ -> {
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
            }
            WebGameType.TYRANO -> browser.addJavascriptInterface(TyranoJsBridge(saves), JS_BRIDGE_NAME)
            WebGameType.VN, WebGameType.WEB_OTHER -> Unit
        }
        val url = "http://localhost:${requireNotNull(localServer).port}/index.html"
        Log.i(TAG, "loadUrl=$url")
        browser.loadUrl(url)
    }

    private fun failLaunch(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        finish()
    }

    private fun loadAsset(name: String): ByteArray = assets.open(name).buffered().use { it.readBytes() }

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
                has("tyrano", "www/tyrano", "tyrano/tyrano.js", "www/tyrano/tyrano.js") -> WebGameType.TYRANO
                has("js/rpg_core.js", "www/js/rpg_core.js") -> WebGameType.RPG_MV
                has("js/rmmz_core.js", "www/js/rmmz_core.js") -> WebGameType.RPG_MZ
                has("globalData.vndata", "www/globalData.vndata") -> WebGameType.VN
                else -> WebGameType.entries.firstOrNull { it.intentValue.equals(explicitType, ignoreCase = true) } ?: WebGameType.WEB_OTHER
            }
        }
        return when {
            File(contentRoot, "tyrano").isDirectory -> WebGameType.TYRANO
            File(contentRoot, "js/rpg_core.js").isFile -> WebGameType.RPG_MV
            File(contentRoot, "js/rmmz_core.js").isFile -> WebGameType.RPG_MZ
            File(contentRoot, "globalData.vndata").isFile -> WebGameType.VN
            else -> WebGameType.fromIntent(explicitType)
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
        runCatching { android.webkit.WebView.setWebContentsDebuggingEnabled(true) }
        browser.settings.apply {
            userAgentString = "$userAgentString;tyranoplayer-android-1.0;yukihub-internal-tyrano"
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
        if (handleSpecialScheme(url)) return true
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return true
        if (isLocalGameUri(uri)) return false
        if (mainFrame) openExternalHttpUrl(uri)
        return true
    }

    private fun isLocalGameUri(uri: Uri?): Boolean {
        val server = localServer ?: return false
        if (!uri?.scheme.equals("http", ignoreCase = true)) return false
        return (uri?.host.equals("localhost", ignoreCase = true) || uri?.host == "127.0.0.1") &&
            uri?.port == server.port
    }

    private fun isAllowedGameResource(uri: Uri): Boolean = isLocalGameUri(uri) ||
        (allowExternalNetwork && (uri.scheme.equals("http", ignoreCase = true) ||
            uri.scheme.equals("https", ignoreCase = true))) ||
        uri.scheme.equals("data", ignoreCase = true) ||
        uri.scheme.equals("blob", ignoreCase = true) ||
        uri.scheme.equals("about", ignoreCase = true)

    private fun blockedResponse() = WebResourceResponse(
        "text/plain",
        "UTF-8",
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

    private fun handleSpecialScheme(url: String): Boolean {
        val lower = url.lowercase(Locale.ROOT)
        return try {
            when {
                lower.startsWith("tyranoplayer-save://") -> {
                    persistTyranoPlayerSave(url)
                    true
                }
                lower.startsWith("tyranoplayer-web://") -> {
                    val target = Uri.decode(
                        queryParam(url, "url")?.takeIf(String::isNotBlank)
                            ?: url.removePrefix("tyranoplayer-web://"),
                    )
                    target?.takeIf(String::isNotBlank)?.let { openExternalHttpUrl(Uri.parse(it.trim())) }
                    true
                }
                lower.startsWith("tyranoplayer-back://") -> {
                    runOnUiThread(::onBackPressed)
                    true
                }
                else -> false
            }
        } catch (error: Throwable) {
            Log.w(TAG, "handleSpecialScheme failed url=$url", error)
            true
        }
    }

    private fun persistTyranoPlayerSave(url: String) {
        try {
            TyranoStorage.write(saveDirectory, queryParam(url, "key"), queryParam(url, "data"))
        } catch (error: Throwable) {
            Log.w(TAG, "persistTyranoPlayerSave failed url=$url", error)
        }
    }

    private fun confirmReturnToTitle() {
        showEngineConfirm(
            getString(R.string.engine_return_to_title),
            getString(R.string.engine_return_to_title_message),
            getString(R.string.engine_confirm),
        ) {
            webView?.post { runCatching { webView?.reload() } }
        }
    }

    override fun finish() {
        super.finish()
        if (processExitScheduled.compareAndSet(false, true)) {
            Handler(Looper.getMainLooper()).postDelayed({
                runCatching { android.os.Process.killProcess(android.os.Process.myPid()) }
            }, PROCESS_EXIT_DELAY_MS)
        }
    }

    private fun queryParam(url: String?, key: String?): String? {
        if (url == null || key == null) return null
        val queryStart = url.indexOf('?')
        if (queryStart < 0 || queryStart + 1 >= url.length) return null
        for (pair in url.substring(queryStart + 1).split('&')) {
            val equals = pair.indexOf('=')
            val encodedKey = if (equals >= 0) pair.substring(0, equals) else pair
            if (key.equals(Uri.decode(encodedKey), ignoreCase = true)) {
                return if (equals >= 0) Uri.decode(pair.substring(equals + 1)) else ""
            }
        }
        return null
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
        runCatching { webView?.loadUrl("javascript:if(window._tyrano_player){_tyrano_player.pauseAllAudio();}") }
        runCatching { webView?.onPause() }
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        enterFullscreen()
        if (firstResume) firstResume = false else {
            runCatching { webView?.loadUrl("javascript:if(window._tyrano_player){_tyrano_player.resumeAllAudio();}") }
        }
        runCatching { webView?.onResume() }
    }

    override fun onDestroy() {
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

    /**
     * 与 Launcher 统一风格的确认对话框。
     *
     * engine 模块不依赖 app 的 LauncherDialogFactory/LauncherTheme，但 Launcher 通过
     * Intent extras 传入了主题色（primaryColor / themeColorCard / themeColorText 等，
     * 见 LauncherUiBridge.appendEngineThemeExtrasSafely）。此处用这些颜色在 engine 内复刻
     * LauncherDialogFactory.showConfirm 的视觉效果：圆角卡片背景 + 药丸形按钮，
     * 与 app 内其他确认弹窗保持一致。
     */
    private fun showEngineConfirm(
        title: String,
        message: String,
        confirmText: String,
        onConfirm: () -> Unit,
    ) {
        val dialog = AlertDialog.Builder(this).create()
        dialog.setCancelable(true)
        dialog.setCanceledOnTouchOutside(true)

        val density = resources.displayMetrics.density
        val dp = { value: Float -> (value * density + 0.5f).toInt() }
        val colors = EngineThemeColors.fromIntent(intent)

        // 根容器：圆角卡片，padding 22dp
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22f), dp(22f), dp(22f), dp(22f))
            background = GradientDrawable().apply {
                setColor(colors.card)
                cornerRadius = dp(20f).toFloat()
            }
        }

        // 标题：16sp bold 居中
        val titleView = TextView(this).apply {
            text = title
            setTextColor(colors.text)
            textSize = 16f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(-1, -2)
        }
        root.addView(titleView)

        // 消息：13sp 居中，topMargin 14dp
        val messageView = TextView(this).apply {
            text = message
            setTextColor(colors.textMuted)
            textSize = 13f
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = dp(14f)
            }
        }
        root.addView(messageView)

        // 按钮行：topMargin 22dp
        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = dp(22f)
            }
        }

        // 取消按钮：药丸形（card 底色 + primary 文字）
        val cancelBtn = TextView(this).apply {
            text = getString(R.string.engine_cancel)
            setTextColor(colors.primary)
            textSize = 13f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(colors.card)
                cornerRadius = dp(999f).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(0, dp(36f)).apply {
                weight = 1f
                marginEnd = dp(7f)
            }
        }
        buttonRow.addView(cancelBtn)

        // 确认按钮：药丸形（primary 底色 + onPrimary 文字）
        val confirmBtn = TextView(this).apply {
            text = confirmText
            setTextColor(colors.onPrimary)
            textSize = 13f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(colors.primary)
                cornerRadius = dp(999f).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(0, dp(36f)).apply {
                weight = 1f
                marginStart = dp(7f)
            }
        }
        buttonRow.addView(confirmBtn)
        root.addView(buttonRow)

        dialog.setView(root)
        dialog.show()

        // 窗口：透明背景 + 固定宽度 252dp，与 LauncherDialogFactory 一致
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(dp(252f), -2)
        }

        cancelBtn.setOnClickListener { dialog.dismiss() }
        confirmBtn.setOnClickListener {
            dialog.dismiss()
            onConfirm()
        }
    }

    /**
     * Tyrano 游戏入口定位结果。
     *
     * @property contentRoot 包含 index.html 或 app.asar 的目录，将作为本地 HTTP 服务器的 root。
     * @property asarPath 命中的 app.asar 绝对路径；非空表示 asar 模式，空表示散文件模式。
     */
    private class TyranoEntry(val contentRoot: File, val asarPath: String?)

    /**
     * 递归查找 Tyrano 游戏入口（index.html 或 app.asar）。
     *
     * 根目录优先匹配 app.asar / resources/app.asar / index.html；未命中时按
     * [WEB_ENTRY_SUBDIRS] 列表递归搜索子目录，与启动器侧的引擎特征探测子目录保持一致，
     * 避免扫描器识别成功但启动器找不到入口而闪退。
     *
     * @param dir 当前搜索目录。
     * @param depth 当前递归深度，根目录传入 0。
     * @return 入口定位结果；未找到返回 null。
     */
    private fun findTyranoEntry(dir: File, depth: Int): TyranoEntry? {
        // 当前目录的入口文件（保持原逻辑：asar 优先于 index.html）
        dir.resolve("app.asar").takeIf { it.isFile }?.let {
            return TyranoEntry(dir, it.absolutePath)
        }
        dir.resolve("resources/app.asar").takeIf { it.isFile }?.let {
            return TyranoEntry(dir, it.absolutePath)
        }
        dir.resolve("app.asar").takeIf { it.isDirectory && it.resolve("index.html").isFile }?.let {
            return TyranoEntry(it, null)
        }
        dir.resolve("resources/app.asar").takeIf { it.isDirectory && it.resolve("index.html").isFile }?.let {
            return TyranoEntry(it, null)
        }
        dir.resolve("index.html").takeIf { it.isFile }?.let {
            return TyranoEntry(dir, null)
        }
        // 达到最大深度后不再递归
        if (depth >= MAX_ENTRY_SEARCH_DEPTH) return null
        for (name in WEB_ENTRY_SUBDIRS) {
            val sub = dir.resolve(name)
            if (!sub.isDirectory) continue
            findTyranoEntry(sub, depth + 1)?.let { return it }
        }
        return null
    }

    inner class TyranoJsBridge(private val saveDirectory: File?) {
        @JavascriptInterface
        fun closeGame() = runOnUiThread(::onBackPressed)

        @JavascriptInterface
        fun finishGame() = runOnUiThread(::confirmReturnToTitle)

        @JavascriptInterface
        fun getStorage(key: String?): String = TyranoStorage.read(saveDirectory, key)

        @JavascriptInterface
        fun setStorage(key: String?, value: String?) = TyranoStorage.write(saveDirectory, key, value)

        @JavascriptInterface
        fun openUrl(url: String?) = runOnUiThread {
            try {
                openExternalHttpUrl(Uri.parse(url))
            } catch (error: Throwable) {
                Log.w(TAG, "invalid external URL", error)
            }
        }

        @JavascriptInterface fun stopMovie() = Unit
        @JavascriptInterface fun audio(value: String?) = Unit
    }

    /** RPG Maker MV/MZ 的 StorageManager 兼容桥，接口名与旧项目保持一致。 */
    inner class RpgMakerSaveBridge(private val saveDirectory: File?) {
        @JavascriptInterface
        fun Save(key: String?, base64Data: String?) =
            TyranoStorage.write(saveDirectory, key, base64Data, RPG_MV_SAVE_EXTENSION)

        @JavascriptInterface
        fun Load(key: String?): String = TyranoStorage.read(saveDirectory, key, RPG_MV_SAVE_EXTENSION)

        @JavascriptInterface
        fun Exists(key: String?): Boolean = TyranoStorage.exists(saveDirectory, key, RPG_MV_SAVE_EXTENSION)

        @JavascriptInterface
        fun Remove(key: String?): Boolean = TyranoStorage.remove(saveDirectory, key, RPG_MV_SAVE_EXTENSION)
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

        // touch_pad_config / touch_pad_presets 键目前仅 engine 侧读写（常量留在本文件）；
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

    private enum class WebGameType(val intentValue: String) {
        TYRANO("Tyrano"),
        RPG_MV("RPG"),
        RPG_MZ("RMMZ"),
        VN("VN"),
        WEB_OTHER("WebOther");

        companion object {
            fun fromIntent(value: String?): WebGameType = entries.firstOrNull {
                it.intentValue.equals(value, ignoreCase = true)
            } ?: TYRANO
        }
    }

    companion object {
        private const val TAG = "YukiTyrano"
        private const val TYRANO_HOOK_ASSET = "__tyrano__.js"
        private const val RPG_MV_HOOK_ASSET = "__rpg__.js"
        private const val RPG_MZ_HOOK_ASSET = "__rmmz__.js"
        private const val TOUCH_PAD_ASSET = "__touch_pad.js"
        private const val NWJS_POLYFILL_ASSET = "__nwjs_polyfill.js"
        private const val RPG_MZ_CORE_HOOK_ASSET = "__hook_rmmz_core.js"
        private const val RPG_MZ_MANAGERS_HOOK_ASSET = "__hook_rmmz_managers.js"
        private const val JS_BRIDGE_NAME = "appJsInterface"
        private const val RPG_MAKER_SAVE_BRIDGE_NAME = "saveDataManager"
        private const val RPG_MAKER_MOD_BRIDGE_NAME = "TyranorModNative"
        private const val TOUCH_PAD_BRIDGE_NAME = "TyranorTouchPadNative"
        private const val RPG_MV_SAVE_EXTENSION = ".bin"
        private const val EXTRA_SCOPED_SAVE_DIR = "scopedSaveDir"
        private const val EXTRA_SCOPED_SAVE_ROOT = "scopedSaveRoot"
        private const val EXTRA_RPG_MAKER_MOD_ENABLED = "rpgMakerModEnabled"
        private const val EXTRA_RPG_MAKER_MOD_GAME_ID = "rpgMakerModGameId"
        private const val EXTRA_RPG_MAKER_VERSION = "rpgMakerVersion"
        private const val RPG_MAKER_MOD_PREFS = "tyranor_rpgmaker_mod_state"
        private const val PER_GAME_TOUCH_PAD_KEY = "touch_pad_config"
        private const val PER_GAME_TOUCH_PAD_PRESETS_KEY = "touch_pad_presets"
        private const val RPG_MAKER_MOD_CORE_ASSET = "__rpgmaker_mod_core.js"
        private const val RPG_MAKER_MOD_UI_ASSET = "__rpgmaker_mod_ui.js"
        private const val RPG_MAKER_MOD_CSS_ASSET = "__rpgmaker_mod.css"
        private const val RPG_MAKER_MOD_ICON_ASSET = "__rpgmaker_mod_icon.png"
        private const val VIRTUAL_MOUSE_ASSET = "__tyranor_mouse.js"
        private const val RPG_MV_V1_PREFIX = "rpgmv-v1"
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

        // v1 覆盖：MV 1.6.1 核心（值契约来源：EngineSettingsStore.RPG_MV_V1 = "v1"）
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

        @JvmStatic
        @Throws(Exception::class)
        fun resolveStorageFile(directory: File?, key: String?): File? =
            TyranoStorage.resolveFile(directory, key)

        /**
         * 通过 SharedPreferences 持久化的用户偏好创建自定义 Configuration 的 Context。
         *
         * 复刻 app 模块 UiScaleUtil.wrap 的语义：读取 yukihub_prefs 中的字体缩放与全局
         * UI 缩放，应用到 Configuration 后返回新的 Context。engine 不依赖 app 的工具类，
         * 此处保留独立的等价实现以避免反向依赖。
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
