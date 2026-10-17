package com.core.gl;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.WindowManager;
import com.core.engine.LaunchContract;
import com.core.engine.R;
import java.util.Locale;

/** Activity-attached analyzer. GPU counters are sampled at the configured interval. */
public final class ArtemisPerformanceOverlay {
    private static final String TAG = "GraphicsAnalyzer";
    private static final int MAX_ATTACH_ATTEMPTS = 60;
    private static final long ATTACH_RETRY_DELAY_MS = 16;
    public interface Source {
        ArtemisGraphicsStats stats();
        boolean isAnalyzing();
        int status();
    }
    private final Activity activity;
    private final Context textContext;
    private final Source source;
    private final int refreshIntervalMs;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean wanted;
    private int attachAttempts;
    private final Runnable attach = this::attachPanel;
    private GraphicsAnalyzerPanel analyzer;
    private WindowManager manager;
    private final Runnable update = new Runnable() {
        @Override public void run() {
            if (analyzer == null) return;
            if (!wanted || !isAnalyzing() || activity.isFinishing() || activity.isDestroyed()) { hide(); return; }
            ArtemisGraphicsStats.Snapshot snapshot = stats().snapshot(System.nanoTime());
            analyzer.update(snapshot, status() == GraphicsStatus.READY ? null : textContext.getString(statusResource()));
            handler.postDelayed(this, refreshIntervalMs);
        }
    };

    public ArtemisPerformanceOverlay(Activity activity, Source source) {
        if (source == null) throw new IllegalArgumentException("missing graphics source");
        this.activity = activity; this.source = source;
        refreshIntervalMs = GraphicsMonitorOptions.normalizeRefresh(activity.getIntent() == null
                ? GraphicsMonitorOptions.DEFAULT_REFRESH_MS : activity.getIntent().getIntExtra(
                        LaunchContract.GRAPHICS_FILTER_REFRESH_INTERVAL_MS, GraphicsMonitorOptions.DEFAULT_REFRESH_MS));
        String tag = activity.getIntent() == null ? null : activity.getIntent().getStringExtra(LaunchContract.UI_LANGUAGE_TAG);
        if (tag == null || tag.isEmpty()) textContext = activity;
        else {
            Configuration configuration = new Configuration(activity.getResources().getConfiguration());
            configuration.setLocale(Locale.forLanguageTag(tag));
            textContext = activity.createConfigurationContext(configuration);
        }
    }
    private ArtemisGraphicsStats stats() { return source.stats(); }
    private boolean isAnalyzing() { return source.isAnalyzing(); }
    private int status() { return source.status(); }

    public void show() {
        if (!wanted) attachAttempts = 0;
        wanted = true;
        attachPanel();
    }
    private void attachPanel() {
        if (!wanted || !isAnalyzing() || analyzer != null || activity.isFinishing() || activity.isDestroyed()) return;
        WindowManager.LayoutParams layout = params();
        if (layout.token == null) {
            handler.removeCallbacks(attach);
            if (++attachAttempts < MAX_ATTACH_ATTEMPTS) handler.postDelayed(attach, ATTACH_RETRY_DELAY_MS);
            return;
        }
        manager = activity.getWindowManager();
        try {
            int width;
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                android.view.WindowMetrics metrics = manager.getCurrentWindowMetrics();
                android.graphics.Rect bounds = metrics.getBounds();
                android.graphics.Insets insets = metrics.getWindowInsets().getInsetsIgnoringVisibility(
                        android.view.WindowInsets.Type.systemBars() | android.view.WindowInsets.Type.displayCutout());
                width = Math.max(1, bounds.width() - insets.left - insets.right);
            } else {
                android.graphics.Rect bounds = new android.graphics.Rect();
                activity.getWindow().getDecorView().getWindowVisibleDisplayFrame(bounds);
                width = Math.max(1, bounds.width());
            }
            int stripWidth = Math.max(1, width - Math.round(12 * activity.getResources().getDisplayMetrics().density));
            GraphicsAnalyzerPanel panel = new GraphicsAnalyzerPanel(textContext, stripWidth);
            manager.addView(panel, layout); analyzer = panel;
            handler.removeCallbacks(attach);
            attachAttempts = 0;
            // Sample a full interval; an immediate update can magnify a single startup frame into a huge FPS value.
            stats().resetInterval(System.nanoTime()); handler.postDelayed(update, refreshIntervalMs);
        } catch (WindowManager.BadTokenException | WindowManager.InvalidDisplayException
                | IllegalArgumentException | IllegalStateException | SecurityException failure) {
            Log.w(TAG, "Unable to attach analyzer to the host window", failure);
            hide();
        }
    }
    private WindowManager.LayoutParams params() {
        WindowManager.LayoutParams result = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_PANEL,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        boolean bottom = activity.getIntent() != null && "bottom".equals(
                activity.getIntent().getStringExtra(LaunchContract.GRAPHICS_FILTER_ANALYZER_POSITION));
        result.gravity = (bottom ? Gravity.BOTTOM : Gravity.TOP) | Gravity.CENTER_HORIZONTAL;
        result.token = activity.getWindow().getDecorView().getWindowToken();
        return result;
    }
    public void hide() {
        wanted = false;
        detachPanel();
    }
    private void detachPanel() {
        handler.removeCallbacks(attach); handler.removeCallbacks(update);
        if (analyzer != null && manager != null) {
            try { manager.removeViewImmediate(analyzer); }
            catch (IllegalArgumentException alreadyDetached) { /* Host window removal may precede focus loss. */ }
        }
        analyzer = null; manager = null;
    }

    private int statusResource() {
        switch (status()) {
            case GraphicsStatus.READY: return R.string.art_graphics_active;
            case GraphicsStatus.MISSING_ABI: return R.string.art_graphics_missing_abi;
            case GraphicsStatus.GLES3_UNAVAILABLE: return R.string.art_graphics_es3_unavailable;
            case GraphicsStatus.NATIVE_FAILED: return R.string.art_graphics_native_failed;
            case GraphicsStatus.FILTER_FAILED: return R.string.art_graphics_filter_failed;
            case GraphicsStatus.SIZE_UNAVAILABLE: return R.string.art_graphics_size_unavailable;
            default: return R.string.art_graphics_waiting;
        }
    }
}
