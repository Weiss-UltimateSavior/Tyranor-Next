package com.core.xsys4;

import android.app.Activity;
import android.content.pm.ActivityInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.ViewGroup;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;

/**
 * System4 游戏手册页：WebView 只读展示游戏 {@code Manual/index.html}（引擎自定义消息 0x8000 触发）。
 *
 * <p>安全约束：非导出；关闭 JavaScript；禁止 file 页面跨源读取（file→file、file→任意源）；
 * 导航与子资源只放行规范化后位于 {@code Manual/} 目录内的 file URL，其余（http/https、其它 file、
 * 路径穿越、符号链接逃逸）一律拦截。入参由 {@link Xs4Activity} 构造，不接受外部 Intent 传入任意 URL。
 */
public final class Xs4ManualActivity extends Activity {

    private static final String TAG = "Xs4ManualActivity";

    /** WebView 数据目录后缀：与主进程及其它引擎进程隔离（API 28+ 多进程共用默认目录会抛异常）。 */
    private static final String WEBVIEW_DATA_SUFFIX = "xsys4";

    static final String EXTRA_MANUAL_DIR = "xs4_manual_dir";
    static final String EXTRA_INDEX_PATH = "xs4_manual_index";

    private WebView webView;

    @Override
    public void setRequestedOrientation(int requestedOrientation) {
        super.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        File manualDir = absoluteFileExtra(EXTRA_MANUAL_DIR);
        File index = absoluteFileExtra(EXTRA_INDEX_PATH);
        if (!isValidEntry(manualDir, index)) {
            finish();
            return;
        }

        // 必须在本进程任何 WebView 实例创建之前调用（同 RpgMakerActivity）。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                WebView.setDataDirectorySuffix(WEBVIEW_DATA_SUFFIX);
            } catch (RuntimeException e) {
                Log.w(TAG, "webview data suffix not applied", e);
            }
        }

        webView = new WebView(this);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(false);
        settings.setAllowFileAccess(true);
        settings.setAllowFileAccessFromFileURLs(false);
        settings.setAllowUniversalAccessFromFileURLs(false);
        settings.setAllowContentAccess(false);
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return !isAllowedManualUrl(manualDir, request.getUrl().toString());
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                if (isAllowedManualUrl(manualDir, request.getUrl().toString())) {
                    return null;
                }
                return new WebResourceResponse("text/plain", "UTF-8", new ByteArrayInputStream(new byte[0]));
            }
        });
        setContentView(webView);
        webView.loadUrl(Uri.fromFile(index).toString());
    }

    @Override
    public void onBackPressed() {
        finish();
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            ViewGroup parent = (ViewGroup) webView.getParent();
            if (parent != null) {
                parent.removeView(webView);
            }
            webView.stopLoading();
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }

    /**
     * 判定 WebView 请求的 URL 是否允许加载：仅 file 协议（无 authority），且路径规范化后位于 manualDir 内。
     * 纯函数（不依赖 Android 类），便于 JVM 单测。
     */
    static boolean isAllowedManualUrl(File manualDir, String url) {
        if (manualDir == null || url == null) {
            return false;
        }
        try {
            URI uri = new URI(url);
            if (!"file".equalsIgnoreCase(uri.getScheme()) || uri.getRawAuthority() != null) {
                return false;
            }
            String path = uri.getPath();
            if (path == null || !path.startsWith("/")) {
                return false;
            }
            return isInsideManualDir(manualDir, new File(path));
        } catch (URISyntaxException | IOException | IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * 两者取规范路径（解析 {@code ..} 与符号链接）后，target 必须严格位于 manualDir 之下。
     * 前缀判定带路径分隔符，避免 {@code ManualX} 冒充 {@code Manual}；manualDir 本身不算「之下」。
     */
    static boolean isInsideManualDir(File manualDir, File target) throws IOException {
        return target.getCanonicalPath().startsWith(manualDir.getCanonicalPath() + File.separator);
    }

    private static boolean isValidEntry(File manualDir, File index) {
        if (manualDir == null || index == null || !index.isFile()) {
            return false;
        }
        try {
            return isInsideManualDir(manualDir, index);
        } catch (IOException e) {
            return false;
        }
    }

    private File absoluteFileExtra(String key) {
        String value = getIntent().getStringExtra(key);
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        File file = new File(value.trim());
        return file.isAbsolute() ? file : null;
    }
}
