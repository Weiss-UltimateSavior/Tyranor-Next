package com.core.engine;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.ContentResolver;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ImageView;
import android.widget.TextView;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;

/** Native, theme-aware startup mask shared by engine host Activities. */
public final class EngineStartupOverlay extends FrameLayout {
    private enum State { LOADING, READY, REVEALING, REVEALED, FAILED, DISPOSED }

    private static final String TAG = "EngineStartup";
    private static final int COVER_SIZE_DP = 64;
    private static final long PAGE_TURN_MS = 420L;
    private static final long FADE_MS = 150L;
    private static final PathInterpolator PAGE_TURN_EASING = new PathInterpolator(0.4f, 0f, 0.2f, 1f);
    private static final ExecutorService COVER_LOADER = Executors.newSingleThreadExecutor();
    private final Activity activity;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final FrameLayout page;
    private final View loadingPanel;
    private final View failurePanel;
    private final TextView failureHint;
    private final StartupSpectrumView spectrum;
    private final boolean animateSpectrum;
    private final boolean turnPage;
    private final ImageView cover;
    private final Uri coverUri;
    private final int appIcon;
    private Future<?> coverRequest;
    private Bitmap coverBitmap;
    private final Runnable revealWhenReady = this::revealNow;
    private volatile State state = State.LOADING;
    private WindowManager windowManager;
    private boolean attachedAsWindow;
    private int windowAttachAttempts;
    private final Runnable attachWindowRetry = this::attachWindowOnMain;

    public EngineStartupOverlay(Activity activity) {
        super(activity);
        if (activity == null) throw new IllegalArgumentException("activity must not be null");
        this.activity = activity;
        setClickable(true);
        setFocusable(false);

        page = new FrameLayout(activity);
        page.setBackgroundColor(Color.BLACK);
        page.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });
        addView(page, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        Intent intent = activity.getIntent();
        EngineThemeColors.Palette palette = EngineThemeColors.fromIntent(intent);
        animateSpectrum = ValueAnimator.areAnimatorsEnabled();
        turnPage = intent == null || intent.getBooleanExtra(LaunchContract.STARTUP_ENHANCED_MOTION, true);
        spectrum = new StartupSpectrumView(activity, palette.getPrimary(), animateSpectrum, this::scheduleReveal);
        coverUri = readCoverUri(intent);
        cover = new ImageView(activity);
        cover.setBackgroundColor(0xFF17171A);
        cover.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        appIcon = intent != null ? intent.getIntExtra(LaunchContract.STARTUP_APP_ICON, 0) : 0;
        if (coverUri == null) showAppIcon();

        LinearLayout panel = new LinearLayout(activity) {
            @Override protected void onMeasure(int widthSpec, int heightSpec) {
                int availableWidth = MeasureSpec.getSize(widthSpec);
                int preferredWidth = Math.max(dp(320), Math.round(availableWidth * 0.46f));
                int width = Math.min(Math.min(preferredWidth, dp(480)), Math.max(1, availableWidth - dp(40)));
                spectrum.getLayoutParams().height = Math.min(Math.round(width * 0.23f),
                        Math.round(MeasureSpec.getSize(heightSpec) * 0.30f));
                super.onMeasure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), heightSpec);
            }
        };
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.addView(spectrum, new LinearLayout.LayoutParams(-1, dp(90)));

        LinearLayout row = new LinearLayout(activity);
        row.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        row.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        row.addView(cover, new LinearLayout.LayoutParams(dp(COVER_SIZE_DP), dp(COVER_SIZE_DP)));

        LinearLayout labels = new LinearLayout(activity);
        labels.setOrientation(LinearLayout.VERTICAL);
        String[] quotes = EngineUiText.localizedContext(activity).getResources().getStringArray(R.array.engine_startup_quotes);
        String quote = quotes[ThreadLocalRandom.current().nextInt(quotes.length)];
        TextView title = label(quote, 23, Color.WHITE, true);
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        labels.addView(title);
        String gameName = intent == null ? null : intent.getStringExtra(LaunchContract.GAMES_TITLE);
        if (TextUtils.isEmpty(gameName) && intent != null) {
            gameName = intent.getStringExtra(LaunchContract.RUNTIME_MODULE_GAME_TITLE);
        }
        if (!TextUtils.isEmpty(gameName)) {
            TextView game = label(gameName, 15, 0xFFBDBDBD, false);
            game.setMaxLines(2);
            game.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams gameParams = new LinearLayout.LayoutParams(-1, -2);
            gameParams.topMargin = dp(3);
            labels.addView(game, gameParams);
        }
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(0, -2, 1f);
        labelParams.leftMargin = dp(16);
        row.addView(labels, labelParams);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(-1, -2);
        rowParams.topMargin = dp(16);
        panel.addView(row, rowParams);
        loadingPanel = panel;
        page.addView(panel, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));

        LinearLayout error = new LinearLayout(activity);
        error.setOrientation(LinearLayout.VERTICAL);
        error.setPadding(dp(24), dp(20), dp(24), dp(20));
        TextView failureTitle = label(EngineUiText.get(activity, R.string.engine_launch_failed), 16, Color.WHITE, true);
        failureTitle.setGravity(Gravity.CENTER);
        error.addView(failureTitle, new LinearLayout.LayoutParams(-1, -2));
        failureHint = label(null, 14, 0xFFBDBDBD, false);
        failureHint.setGravity(Gravity.CENTER);
        failureHint.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        LinearLayout.LayoutParams hintParams = new LinearLayout.LayoutParams(-1, -2);
        hintParams.topMargin = dp(12);
        error.addView(failureHint, hintParams);
        error.setVisibility(View.GONE);
        failurePanel = error;
        page.addView(error, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER));
    }

    private TextView label(CharSequence text, int size, int color, boolean heading) {
        TextView view = new TextView(activity);
        view.setText(text);
        view.setTextColor(color);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, size);
        view.setGravity(Gravity.START);
        if (heading) {
            view.setTypeface(null, android.graphics.Typeface.BOLD);
            view.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
            if (android.os.Build.VERSION.SDK_INT >= 28) view.setAccessibilityHeading(true);
        }
        return view;
    }

    private static Uri readCoverUri(Intent intent) {
        if (intent == null) return null;
        String value = intent.getStringExtra(LaunchContract.GAME_COVER_URI);
        if (TextUtils.isEmpty(value)) return null;
        if (value.startsWith("/")) return Uri.fromFile(new File(value));
        Uri uri = Uri.parse(value);
        String scheme = uri.getScheme();
        return ContentResolver.SCHEME_FILE.equals(scheme) || ContentResolver.SCHEME_CONTENT.equals(scheme)
                || ContentResolver.SCHEME_ANDROID_RESOURCE.equals(scheme) ? uri : null;
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (coverUri == null || coverBitmap != null || (state != State.LOADING && state != State.READY)) return;
        ContentResolver resolver = activity.getContentResolver();
        int maxSize = dp(COVER_SIZE_DP * 2);
        coverRequest = COVER_LOADER.submit(() -> {
            Bitmap decoded = decodeCover(resolver, coverUri, maxSize);
            main.post(() -> {
                if (isAttachedToWindow() && (state == State.LOADING || state == State.READY) && coverBitmap == null) {
                    if (decoded == null) showAppIcon();
                    else {
                        coverBitmap = decoded;
                        cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
                        cover.setImageBitmap(decoded);
                    }
                } else if (decoded != null) {
                    decoded.recycle();
                }
            });
        });
    }

    @Override protected void onDetachedFromWindow() {
        cancelCoverRequest();
        if (state == State.REVEALING) {
            page.animate().cancel();
            finishReveal();
        }
        super.onDetachedFromWindow();
    }

    private void showAppIcon() {
        cover.setScaleType(ImageView.ScaleType.FIT_CENTER);
        if (appIcon != 0) cover.setImageResource(appIcon);
        else cover.setImageDrawable(activity.getApplicationInfo().loadIcon(activity.getPackageManager()));
    }

    private static Bitmap decodeCover(ContentResolver resolver, Uri uri, int maxSize) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        try {
            try (InputStream input = resolver.openInputStream(uri)) {
                if (input == null) return null;
                BitmapFactory.decodeStream(input, null, options);
            }
            if (options.outWidth <= 0 || options.outHeight <= 0) return null;
            int longestSide = Math.max(options.outWidth, options.outHeight);
            options.inSampleSize = 1;
            while (longestSide / options.inSampleSize > maxSize) options.inSampleSize *= 2;
            options.inJustDecodeBounds = false;
            try (InputStream input = resolver.openInputStream(uri)) {
                return input == null ? null : BitmapFactory.decodeStream(input, null, options);
            }
        } catch (IOException | RuntimeException ignored) {
            return null;
        }
    }

    private void cancelCoverRequest() {
        if (coverRequest == null) return;
        coverRequest.cancel(true);
        coverRequest = null;
    }

    private void releaseCover() {
        cancelCoverRequest();
        cover.setImageDrawable(null);
        coverBitmap = null;
    }

    /** Adds this view above the host's ordinary content. */
    public void attachTo(ViewGroup parent) {
        if (!onMain(() -> attachTo(parent))) return;
        detachFromCurrentHost();
        if (state == State.DISPOSED || parent == null) return;
        windowAttachAttempts = 0;
        setVisibility(state == State.REVEALED ? View.GONE : View.VISIBLE);
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
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT);
        params.token = token;
        params.gravity = Gravity.TOP | Gravity.START;
        params.setTitle("EngineStartupOverlay:" + hostName());
        try {
            setVisibility(state == State.REVEALED ? View.GONE : View.VISIBLE);
            windowManager.addView(this, params);
            Log.i(TAG, "attach window host=" + hostName());
        } catch (WindowManager.BadTokenException | IllegalArgumentException | IllegalStateException failure) {
            attachedAsWindow = false;
            Log.w(TAG, "attach window failed host=" + hostName(), failure);
        }
    }

    /** Records engine readiness and keeps the overlay visible through at least the first cycle. */
    public void reveal() {
        if (!onMain(this::reveal)) return;
        if (state != State.LOADING) return;
        state = State.READY;
        scheduleReveal();
    }

    private void scheduleReveal() {
        if (state != State.READY) return;
        long endsAt = spectrum.firstCycleEndsAt();
        if (animateSpectrum && endsAt < 0) return;
        main.removeCallbacks(revealWhenReady);
        main.postAtTime(revealWhenReady, Math.max(SystemClock.uptimeMillis(), endsAt));
    }

    private void revealNow() {
        if (state != State.READY) return;
        state = State.REVEALING;
        spectrum.stop();
        cancelCoverRequest();
        Log.i(TAG, "reveal host=" + hostName());
        if (!ValueAnimator.areAnimatorsEnabled() || !isAttachedToWindow() || page.getWidth() <= 0) {
            finishReveal();
            return;
        }
        if (turnPage) {
            page.setPivotX(0f);
            page.setPivotY(page.getHeight() * 0.5f);
            page.setCameraDistance(page.getWidth() * 3f);
            page.animate().rotationY(90f).setDuration(PAGE_TURN_MS)
                    .setInterpolator(PAGE_TURN_EASING).withEndAction(this::finishReveal).start();
        } else {
            page.animate().alpha(0f).setDuration(FADE_MS).withEndAction(this::finishReveal).start();
        }
    }

    private void finishReveal() {
        if (state != State.REVEALING) return;
        state = State.REVEALED;
        setVisibility(View.GONE);
        resetPageTransform();
        releaseCover();
    }

    private void resetPageTransform() {
        page.setRotationY(0f);
        page.setAlpha(1f);
    }

    /** Shows a failure message; also supports a renderer failure after an earlier reveal. */
    public void fail(CharSequence message) {
        if (!onMain(() -> fail(message))) return;
        if (state == State.FAILED || state == State.DISPOSED) return;
        state = State.FAILED;
        spectrum.stop();
        page.animate().cancel();
        resetPageTransform();
        main.removeCallbacks(revealWhenReady);
        releaseCover();
        loadingPanel.setVisibility(View.GONE);
        failureHint.setText(message == null || message.length() == 0
                ? EngineUiText.get(activity, R.string.engine_return_and_retry) : message);
        failurePanel.setVisibility(View.VISIBLE);
        setVisibility(View.VISIBLE);
        Log.w(TAG, "fail host=" + hostName() + " messageLength=" + failureHint.length());
    }

    public void dispose() {
        if (!onMain(this::dispose)) return;
        if (state == State.DISPOSED) return;
        state = State.DISPOSED;
        spectrum.stop();
        page.animate().cancel();
        resetPageTransform();
        main.removeCallbacks(attachWindowRetry);
        main.removeCallbacks(revealWhenReady);
        releaseCover();
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
