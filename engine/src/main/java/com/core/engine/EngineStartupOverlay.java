package com.core.engine;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

/** Native, theme-aware startup mask shared by engine host Activities. */
public final class EngineStartupOverlay extends FrameLayout {
    private enum State { LOADING, REVEALED, FAILED, DISPOSED }

    private static final String TAG = "EngineStartup";
    private final Activity activity;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final FrameLayout safeContent;
    private final TextView title;
    private final TextView hint;
    private final ProgressBar spinner;
    private volatile State state = State.LOADING;
    private WindowManager windowManager;
    private boolean attachedAsWindow;
    private int windowAttachAttempts;
    private final Runnable attachWindowRetry = this::attachWindowOnMain;

    public EngineStartupOverlay(Activity activity) {
        super(activity);
        if (activity == null) throw new IllegalArgumentException("activity must not be null");
        this.activity = activity;
        setBackgroundColor(Color.BLACK);
        setClickable(true);
        setFocusable(false);

        safeContent = new FrameLayout(activity);
        safeContent.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });
        addView(safeContent, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        EngineThemeColors.Palette palette = EngineThemeColors.fromIntent(activity.getIntent());
        LinearLayout panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setGravity(Gravity.CENTER_HORIZONTAL);
        panel.setPadding(dp(24), dp(20), dp(24), dp(20));

        title = new TextView(activity);
        title.setText(EngineUiText.get(activity, R.string.engine_starting_game));
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        title.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        if (android.os.Build.VERSION.SDK_INT >= 28) title.setAccessibilityHeading(true);
        panel.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        spinner = new ProgressBar(activity);
        spinner.setIndeterminate(true);
        spinner.setContentDescription(EngineUiText.get(activity, R.string.engine_game_loading));
        spinner.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(palette.getPrimary()));
        LinearLayout.LayoutParams spinnerParams = new LinearLayout.LayoutParams(dp(34), dp(34));
        spinnerParams.topMargin = dp(14);
        panel.addView(spinner, spinnerParams);

        hint = new TextView(activity);
        hint.setText(EngineUiText.get(activity, R.string.engine_preparing_game));
        hint.setTextColor(0xFFBDBDBD);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        hint.setGravity(Gravity.CENTER);
        hint.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        LinearLayout.LayoutParams hintParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hintParams.topMargin = dp(12);
        panel.addView(hint, hintParams);

        FrameLayout.LayoutParams panelParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        safeContent.addView(panel, panelParams);
        setVisibility(View.VISIBLE);
    }

    /** Adds this view above the host's ordinary content. */
    public void attachTo(ViewGroup parent) {
        if (!onMain(() -> attachTo(parent))) return;
        detachFromCurrentHost();
        if (state == State.DISPOSED || parent == null) return;
        windowAttachAttempts = 0;
        setVisibility(state == State.LOADING || state == State.FAILED ? View.VISIBLE : View.GONE);
        parent.addView(this, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        Log.i(TAG, "attach parent host=" + hostName());
    }

    /**
     * Attaches as a non-focusable application panel above a NativeActivity content window.
     * It does not receive Back or keyboard focus from the host window.
     */
    public void attachToWindow() {
        if (!onMain(this::attachToWindow)) return;
        detachFromCurrentHost();
        if (state == State.DISPOSED || activity.isFinishing() || activity.isDestroyed()) return;
        attachedAsWindow = true;
        windowAttachAttempts = 0;
        attachWindowOnMain();
    }

    private void attachWindowOnMain() {
        if (!attachedAsWindow || state == State.DISPOSED || activity.isFinishing()
                || activity.isDestroyed()) return;
        android.os.IBinder token = activity.getWindow().getDecorView().getWindowToken();
        if (token == null) {
            if (++windowAttachAttempts <= 20) main.postDelayed(attachWindowRetry, 50L);
            else {
                attachedAsWindow = false;
                Log.w(TAG, "attach window failed: host token unavailable host=" + hostName());
            }
            return;
        }
        if (windowManager == null) windowManager = activity.getWindowManager();
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_PANEL,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.OPAQUE);
        params.token = token;
        params.gravity = Gravity.TOP | Gravity.START;
        params.setTitle("EngineStartupOverlay:" + hostName());
        try {
            setVisibility(state == State.LOADING || state == State.FAILED ? View.VISIBLE : View.GONE);
            windowManager.addView(this, params);
            Log.i(TAG, "attach window host=" + hostName());
        } catch (WindowManager.BadTokenException | IllegalArgumentException | IllegalStateException failure) {
            attachedAsWindow = false;
            Log.w(TAG, "attach window failed host=" + hostName(), failure);
        }
    }

    /** Reveals engine content. The view remains attached and hidden for later host failures. */
    public void reveal() {
        if (!onMain(this::reveal)) return;
        if (state != State.LOADING) return;
        state = State.REVEALED;
        animate().cancel();
        setAlpha(1f);
        Log.i(TAG, "reveal host=" + hostName());
        animate().alpha(0f).setDuration(150L).withEndAction(() -> {
            if (state == State.REVEALED) {
                setAlpha(1f);
                setVisibility(View.GONE);
            }
        }).start();
    }

    /** Shows a failure message; also supports a renderer failure after an earlier reveal. */
    public void fail(CharSequence message) {
        if (!onMain(() -> fail(message))) return;
        if (state == State.FAILED || state == State.DISPOSED) return;
        state = State.FAILED;
        animate().cancel();
        setAlpha(1f);
        title.setText(EngineUiText.get(activity, R.string.engine_launch_failed));
        hint.setText(message == null || message.length() == 0
                ? EngineUiText.get(activity, R.string.engine_return_and_retry) : message);
        spinner.setVisibility(View.GONE);
        setVisibility(View.VISIBLE);
        Log.w(TAG, "fail host=" + hostName() + " messageLength=" + hint.length());
    }

    public void dispose() {
        if (!onMain(this::dispose)) return;
        if (state == State.DISPOSED) return;
        state = State.DISPOSED;
        animate().cancel();
        setAlpha(1f);
        main.removeCallbacks(attachWindowRetry);
        detachFromCurrentHost();
        Log.i(TAG, "dispose host=" + hostName());
    }

    public boolean isLoading() {
        return state == State.LOADING;
    }

    @Override
    public boolean onTouchEvent(android.view.MotionEvent event) {
        return true;
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }

    private void detachFromCurrentHost() {
        main.removeCallbacks(attachWindowRetry);
        if (attachedAsWindow && windowManager != null && isAttachedToWindow()) {
            try { windowManager.removeViewImmediate(this); }
            catch (IllegalArgumentException | IllegalStateException ignored) { /* already detached */ }
        } else if (getParent() instanceof ViewGroup) {
            ((ViewGroup) getParent()).removeView(this);
        }
        attachedAsWindow = false;
    }

    private String hostName() {
        return activity.getClass().getSimpleName();
    }

    private boolean onMain(Runnable action) {
        if (Looper.myLooper() == Looper.getMainLooper()) return true;
        main.post(action);
        return false;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
