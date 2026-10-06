package com.tyranor.next.ui.home

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.tyranor.next.R
import com.tyranor.next.ui.common.NoRippleButton

/**
 * Web 首页：内置 WebView 展示用户所选网址（应用设置「首页样式切换」为网页时挂载）。
 *
 * 约定：
 * - 仅 http/https 在 WebView 内加载；主框架的非 http(s) 链接只对白名单 scheme（mailto/tel/sms 等）
 *   尝试交系统处理，其余（intent/file/data/blob/javascript）一律拦截；子框架非 http(s) 直接拦截；
 *   下载仅 http(s) 交系统浏览器处理（blob/data 无法外发，提示用户）；
 * - 不注入任何 `addJavascriptInterface`，禁用文件/content 访问（任意网址内容的安全底线）；
 *   混合内容用兼容模式（用户可自定义 http 站点，属有意取舍）；
 * - 四页常驻组合下首页切走后不会销毁：不在前台（[isActive] 为 false）或应用退后台时
 *   `onPause + pauseTimers + 暂停媒体 + 收键盘`，避免后台继续跑 JS/音视频；引擎 WebView 在独立进程，
 *   主进程已用 `WebView.setDataDirectorySuffix` 隔离数据目录（见 TyranorNextApplication）；
 * - `canGoBack()` 且首页在前台时，返回键先回退页面而不是退出应用；
 * - 已知限制（一期）：未挂 WebChromeClient，站点 alert/confirm/prompt 与 window.open 无效；
 *   配置变更/进程重建后不恢复历史与滚动（重新加载首页地址）。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun HomeWebView(
    url: String,
    isActive: Boolean,
    reloadSignal: Int,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var webView by remember { mutableStateOf<WebView?>(null) }
    // WebView 重建代号：渲染进程崩溃后必须销毁旧实例并新建
    var generation by remember { mutableIntStateOf(0) }
    var crashed by remember { mutableStateOf(false) }
    // 首页从未激活过时不创建 WebView（避免进程恢复到其它 Tab 时提前初始化 Chromium）
    var hasBeenActive by remember { mutableStateOf(isActive) }
    var loading by remember { mutableStateOf(true) }
    var loadFailed by remember { mutableStateOf(false) }
    var canGoBack by remember { mutableStateOf(false) }
    var appResumed by remember {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }
    // 已加载的地址 / 已处理的刷新序号：避免首页未激活时后台加载、切页往返重复加载；
    // handledReloadSignal 以当前信号初始化，避免样式切换重建后重放历史刷新
    var loadedUrl by remember { mutableStateOf<String?>(null) }
    var handledReloadSignal by remember { mutableIntStateOf(reloadSignal) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { appResumed = true }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { appResumed = false }
    LaunchedEffect(isActive) { if (isActive) hasBeenActive = true }

    BackHandler(enabled = isActive && canGoBack) { webView?.goBack() }

    Box(modifier.fillMaxSize()) {
        if (hasBeenActive) {
            key(generation) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT,
                            )
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.loadWithOverviewMode = true
                            settings.useWideViewPort = true
                            settings.setSupportZoom(false)
                            settings.builtInZoomControls = false
                            settings.displayZoomControls = false
                            // 不自动播放音视频：避免首页在后台/切页时出声
                            settings.mediaPlaybackRequiresUserGesture = true
                            // https 页面内的 http 子资源仍可加载（用户自定义网址可能混用 http，属有意取舍）
                            settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                            settings.allowFileAccess = false
                            settings.allowContentAccess = false
                            settings.javaScriptCanOpenWindowsAutomatically = false
                            settings.setSupportMultipleWindows(false)
                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                    val scheme = request.url.scheme?.lowercase()
                                    if (scheme == "http" || scheme == "https") return false
                                    // 子框架的非 http(s) 导航直接拦截，不拉起外部应用（防 drive-by deep link）
                                    if (!request.isForMainFrame) return true
                                    loading = false
                                    if (scheme in EXTERNAL_SCHEMES && !openExternally(context, request.url)) {
                                        toastOpenFailed(context)
                                    }
                                    return true
                                }

                                override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                                    loading = true
                                    loadFailed = false
                                }

                                override fun onPageFinished(view: WebView, url: String?) {
                                    loading = false
                                    canGoBack = view.canGoBack()
                                }

                                override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                                    canGoBack = view.canGoBack()
                                }

                                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                                    // 旧导航被新导航抢占时晚到的错误不得覆盖当前页面
                                    if (request.isForMainFrame && request.url.toString() == view.url) {
                                        loading = false
                                        loadFailed = true
                                    }
                                }

                                override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: android.net.http.SslError) {
                                    // 证书错误一律取消，绝不 proceed；默认不回调 onReceivedError，主框架需显式置失败态，
                                    // 子资源（如图片）证书失败不整页置失败
                                    handler.cancel()
                                    if (error.url == view.url) {
                                        loading = false
                                        loadFailed = true
                                    }
                                }

                                override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                                    // 不处理会连带杀死应用进程：清状态、递增代号触发 AndroidView 重建，
                                    // 旧实例交由 onRelease 销毁（不在此处 destroy，避免销毁后再次调用）
                                    crashed = true
                                    loadFailed = true
                                    loading = false
                                    canGoBack = false
                                    loadedUrl = null
                                    if (webView === view) webView = null
                                    generation++
                                    return true
                                }
                            }
                            // 下载不在应用内落地：http(s) 交系统浏览器/下载器处理
                            setDownloadListener { downloadUrl, _, _, _, _ ->
                                val uri = Uri.parse(downloadUrl)
                                val scheme = uri.scheme?.lowercase()
                                if (scheme == "http" || scheme == "https") {
                                    if (!openExternally(context, uri)) toastOpenFailed(context)
                                } else {
                                    Toast.makeText(context, R.string.home_web_download_unsupported, Toast.LENGTH_SHORT).show()
                                }
                            }
                            webView = this
                        }
                    },
                    onRelease = { view ->
                        // Compose 保证视图已不再需要：先停载/解绑再销毁（runCatching 容忍崩溃路径已失效的实例）
                        runCatching {
                            view.stopLoading()
                            view.webViewClient = WebViewClient()
                            view.setDownloadListener(null)
                        }
                        runCatching { view.destroy() }
                    },
                )
            }
        }

        val view = webView
        // 首次激活后加载；地址变化时重新加载（未激活/应用退后台时推迟到下次前台）；崩溃重建后等待手动重试
        LaunchedEffect(view, url, isActive, appResumed, crashed) {
            if (view == null || !isActive || !appResumed || crashed || loadedUrl == url) return@LaunchedEffect
            loadedUrl = url
            view.loadUrl(url)
        }
        // 顶栏刷新（每个信号只处理一次；非激活时忽略）
        LaunchedEffect(view, reloadSignal, isActive) {
            if (view == null || !isActive || reloadSignal <= handledReloadSignal) return@LaunchedEffect
            handledReloadSignal = reloadSignal
            view.reload()
        }
        // 前台可见性：首页不在前台或应用退后台时暂停 JS/媒体并收键盘，避免后台耗电
        LaunchedEffect(view, isActive, appResumed) {
            if (view == null) return@LaunchedEffect
            if (isActive && appResumed) {
                view.onResume()
                view.resumeTimers()
            } else {
                view.onPause()
                view.pauseTimers()
                view.clearFocus()
                (context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                    ?.hideSoftInputFromWindow(view.windowToken, 0)
                view.evaluateJavascript(PAUSE_MEDIA_JS, null)
            }
        }

        if (loading) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter),
            )
        }

        if (loadFailed) {
            Column(
                modifier = Modifier.align(Alignment.Center).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    stringResource(R.string.home_web_error),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                NoRippleButton(
                    text = stringResource(R.string.common_retry),
                    tonal = true,
                ) {
                    loadFailed = false
                    loading = true
                    if (crashed) {
                        // 崩溃路径：置回后由加载 effect 重建后的 WebView 重新加载
                        crashed = false
                    } else {
                        webView?.reload()
                    }
                }
            }
        }
    }
}

/** 允许交系统处理的外部 scheme（白名单，其余一律拦截）。 */
private val EXTERNAL_SCHEMES = setOf("mailto", "tel", "sms", "smsto", "geo", "market")

/** 暂停页面内所有媒体播放（pauseTimers 只停 JS 定时器，管不到已开始的媒体）。 */
private const val PAUSE_MEDIA_JS =
    "(function(){try{document.querySelectorAll('video,audio').forEach(function(m){try{m.pause()}catch(e){}})}catch(e){}})()"

/** 交系统处理链接；无可用应用返回 false。 */
private fun openExternally(context: Context, uri: Uri): Boolean = runCatching {
    context.startActivity(
        Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}.isSuccess

private fun toastOpenFailed(context: Context) {
    Toast.makeText(context, R.string.home_web_open_browser_failed, Toast.LENGTH_SHORT).show()
}
