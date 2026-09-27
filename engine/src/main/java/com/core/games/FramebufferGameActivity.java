package com.core.games;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Choreographer;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.core.engine.EngineUiText;
import com.core.engine.LaunchContract;
import com.core.engine.R;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * 软件渲染引擎（RealLive / AVG32 / UK2）宿主 Activity。
 *
 * <p>引擎经 {@code game_fb_*} C ABI 被逐步驱动：每帧 {@code fbStep} 后把 RGBA 帧拷进
 * {@link Bitmap}（{@link NativeGames#fbCopyFrame}），由 {@link FramebufferView} 按
 * aspect-fit 绘制；触摸映射为鼠标（单指=左键、双指点按=右键、双指上下滑=滚轮），
 * 硬件按键映射见 {@link #mapKey(int)}，软键盘提交走 {@code game_fb_text}。
 * 进程隔离在 {@code :fbgames}。
 */
public final class FramebufferGameActivity extends AppCompatActivity
        implements Choreographer.FrameCallback, FramebufferTextInputView.Listener {

    private static final String TAG = "FramebufferGame";

    private static final long BACK_EXIT_WINDOW_MS = 2000L;
    private static final int MAX_FRAME_DT_MS = 250;
    private static final long LEFT_PRESS_DELAY_MS = 90L;
    private static final float WHEEL_STEP_PX = 48f;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private FrameLayout rootLayout;
    private FramebufferView fbView;
    private View loadingOverlay;
    private FramebufferTextInputView textInputView;
    private TextView imeButton;
    private TextView skipButton;

    private long handle = 0L;
    private boolean running = false;
    private boolean skipping = false;
    private boolean imeVisible = false;
    private long lastFrameNs = 0L;
    private long lastBackMs = 0L;

    private String gameRoot;
    private String engineId;
    private String nls;

    @Nullable
    private Bitmap bitmap;
    @Nullable
    private ByteBuffer buffer;
    private int frameW = 0;
    private int frameH = 0;

    // 触摸手势状态
    private boolean multiTouch = false;
    private boolean leftPressed = false;
    private boolean leftPressPending = false;
    private float wheelAnchorY = 0f;
    private float wheelAccum = 0f;
    private boolean wheelUsed = false;

    private final Runnable sendLeftPress = () -> {
        if (leftPressPending && handle != 0L) {
            leftPressPending = false;
            leftPressed = true;
            NativeGames.fbPointerButton(handle, NativeGames.BUTTON_LEFT, true);
        }
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setContentView(buildContentView());

        gameRoot = firstNonEmpty(
                getIntent().getStringExtra(LaunchContract.PATH),
                getIntent().getStringExtra(LaunchContract.GAME_PATH),
                getIntent().getStringExtra(LaunchContract.PROJECT_ROOT),
                getIntent().getStringExtra(LaunchContract.GAME_DIR));
        if (gameRoot == null || gameRoot.trim().isEmpty()) {
            Toast.makeText(this, uiString(R.string.engine_fb_missing_game_dir), Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        engineId = getIntent().getStringExtra(LaunchContract.GAMES_ENGINE);
        nls = getIntent().getStringExtra(LaunchContract.GAMES_NLS);

        applyImmersive();
        installBackHandling();
        // 音频后端（cpal/AAudio）依赖 ndk-context，且引擎日志也在此初始化。
        NativeGames.initAndroidContext(getApplicationContext());
        // 先让加载层画出来，再做可能阻塞的 open。
        fbView.post(this::openGame);
    }

    private View buildContentView() {
        rootLayout = new FrameLayout(this);
        rootLayout.setBackgroundColor(Color.BLACK);

        fbView = new FramebufferView(this);
        fbView.setOnTouchListener((v, event) -> onGameTouch(event));
        rootLayout.addView(fbView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        textInputView = new FramebufferTextInputView(this, this);
        FrameLayout.LayoutParams inputParams = new FrameLayout.LayoutParams(1, 1);
        inputParams.gravity = Gravity.TOP | Gravity.START;
        rootLayout.addView(textInputView, inputParams);

        rootLayout.addView(buildControlBar(), bottomEndParams());

        loadingOverlay = buildLoadingOverlay();
        rootLayout.addView(loadingOverlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return rootLayout;
    }

    private FrameLayout.LayoutParams bottomEndParams() {
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.gravity = Gravity.BOTTOM | Gravity.END;
        int margin = dp(10);
        params.setMargins(margin, margin, margin, margin);
        return params;
    }

    private View buildControlBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setAlpha(0.65f);

        imeButton = addBarButton(bar, uiString(R.string.engine_fb_ime_show), v -> toggleIme());
        skipButton = addBarButton(bar, uiString(R.string.engine_fb_skip), v -> toggleSkip());
        addBarButton(bar, uiString(R.string.engine_fb_menu), v -> rightClick());
        return bar;
    }

    private TextView addBarButton(LinearLayout bar, String label, View.OnClickListener listener) {
        TextView button = new TextView(this);
        button.setText(label);
        button.setTextColor(Color.WHITE);
        button.setTextSize(12f);
        button.setBackgroundColor(0x66000000);
        int padH = dp(10);
        int padV = dp(6);
        button.setPadding(padH, padV, padH, padV);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.leftMargin = dp(6);
        bar.addView(button, params);
        button.setOnClickListener(listener);
        return button;
    }

    private View buildLoadingOverlay() {
        FrameLayout overlay = new FrameLayout(this);
        overlay.setBackgroundColor(Color.BLACK);
        ProgressBar spinner = new ProgressBar(this);
        overlay.addView(spinner, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        TextView label = new TextView(this);
        label.setText(uiString(R.string.engine_starting_game));
        label.setTextColor(Color.LTGRAY);
        label.setTextSize(14.0f);
        label.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams labelParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        labelParams.topMargin = dp(72);
        overlay.addView(label, labelParams);
        return overlay;
    }

    private void hideLoadingOverlay() {
        if (loadingOverlay != null) {
            rootLayout.removeView(loadingOverlay);
            loadingOverlay = null;
        }
    }

    @Override
    protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        Toast.makeText(this, uiString(R.string.engine_another_game_running), Toast.LENGTH_SHORT).show();
    }

    // ---------- 引擎生命周期 ----------

    private void openGame() {
        if (handle != 0L) {
            return;
        }
        String title = getIntent().getStringExtra(LaunchContract.GAMES_TITLE);
        String[] error = new String[1];
        long created;
        try {
            created = NativeGames.fbOpen(gameRoot, engineId, nls, error);
        } catch (Throwable t) {
            created = 0L;
            error[0] = t.getMessage();
        }
        if (created == 0L) {
            String message = error[0] != null && !error[0].trim().isEmpty()
                    ? error[0]
                    : uiString(R.string.engine_fb_init_failed);
            new AlertDialog.Builder(this)
                    .setTitle(uiString(R.string.engine_launch_failed))
                    .setMessage(message)
                    .setPositiveButton(uiString(R.string.engine_ok), (d, w) -> finish())
                    .setOnCancelListener(d -> finish())
                    .show();
            return;
        }
        handle = created;
        String engineTitle = NativeGames.fbTitle(handle);
        if (engineTitle != null && !engineTitle.trim().isEmpty()) {
            setTitle(engineTitle);
        } else if (title != null && !title.trim().isEmpty()) {
            setTitle(title);
        }
        hideLoadingOverlay();
        maybeStartFrameLoop();
    }

    private void closeGame() {
        stopFrameLoop();
        handler.removeCallbacks(sendLeftPress);
        leftPressPending = false;
        leftPressed = false;
        if (handle != 0L) {
            long closing = handle;
            handle = 0L;
            NativeGames.fbClose(closing);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        applyImmersive();
        maybeStartFrameLoop();
    }

    @Override
    protected void onPause() {
        stopFrameLoop();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        hideIme();
        closeGame();
        super.onDestroy();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            applyImmersive();
        }
    }

    private void applyImmersive() {
        WindowInsetsControllerCompat controller =
                ViewCompat.getWindowInsetsController(getWindow().getDecorView());
        if (controller != null) {
            controller.hide(WindowInsetsCompat.Type.systemBars());
            controller.setSystemBarsBehavior(
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        }
    }

    private void installBackHandling() {
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                long now = SystemClock.uptimeMillis();
                if (lastBackMs != 0L && now - lastBackMs <= BACK_EXIT_WINDOW_MS) {
                    confirmExit();
                    return;
                }
                lastBackMs = now;
                rightClick();
                Toast.makeText(FramebufferGameActivity.this,
                        uiString(R.string.engine_fb_back_again_to_exit), Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void confirmExit() {
        new AlertDialog.Builder(this)
                .setTitle(uiString(R.string.engine_exit_game))
                .setMessage(uiString(R.string.engine_exit_game_message))
                .setPositiveButton(uiString(R.string.engine_ok), (d, w) -> finish())
                .setNegativeButton(uiString(R.string.engine_cancel), null)
                .show();
    }

    // ---------- 帧循环 ----------

    private void maybeStartFrameLoop() {
        if (!running && handle != 0L) {
            running = true;
            lastFrameNs = 0L;
            Choreographer.getInstance().postFrameCallback(this);
        }
    }

    private void stopFrameLoop() {
        if (running) {
            running = false;
            lastFrameNs = 0L;
            Choreographer.getInstance().removeFrameCallback(this);
        }
    }

    @Override
    public void doFrame(long frameTimeNanos) {
        if (!running || handle == 0L) {
            return;
        }
        if (lastFrameNs == 0L) {
            lastFrameNs = frameTimeNanos;
        }
        long dtNs = frameTimeNanos - lastFrameNs;
        lastFrameNs = frameTimeNanos;
        int dtMs = (int) (dtNs / 1_000_000L);
        if (dtMs < 0) {
            dtMs = 0;
        }
        if (dtMs > MAX_FRAME_DT_MS) {
            dtMs = MAX_FRAME_DT_MS;
        }

        int status = NativeGames.fbStep(handle, dtMs);
        if (status != 0) {
            closeGame();
            finish();
            return;
        }
        presentFrame();
        Choreographer.getInstance().postFrameCallback(this);
    }

    private void presentFrame() {
        int[] size = NativeGames.fbFrameSize(handle);
        if (size == null || size[0] <= 0 || size[1] <= 0) {
            return;
        }
        if (bitmap == null || size[0] != frameW || size[1] != frameH) {
            frameW = size[0];
            frameH = size[1];
            bitmap = Bitmap.createBitmap(frameW, frameH, Bitmap.Config.ARGB_8888);
            buffer = ByteBuffer.allocateDirect(frameW * frameH * 4).order(ByteOrder.nativeOrder());
        }
        if (buffer == null) {
            return;
        }
        buffer.rewind();
        if (NativeGames.fbCopyFrame(handle, buffer)) {
            buffer.rewind();
            // ARGB_8888 的内存字节序是 R,G,B,A，与引擎的 RGBA8 帧一致。
            bitmap.copyPixelsFromBuffer(buffer);
            fbView.setFrame(bitmap);
        }
    }

    // ---------- 输入 ----------

    private boolean onGameTouch(MotionEvent event) {
        if (handle == 0L || event == null) {
            return true;
        }
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                multiTouch = false;
                wheelUsed = false;
                movePointer(event.getX(), event.getY());
                leftPressPending = true;
                handler.postDelayed(sendLeftPress, LEFT_PRESS_DELAY_MS);
                break;
            }
            case MotionEvent.ACTION_POINTER_DOWN: {
                if (!multiTouch) {
                    multiTouch = true;
                    handler.removeCallbacks(sendLeftPress);
                    leftPressPending = false;
                    wheelAnchorY = averageY(event);
                    wheelAccum = 0f;
                }
                break;
            }
            case MotionEvent.ACTION_MOVE: {
                if (multiTouch) {
                    float y = averageY(event);
                    wheelAccum += y - wheelAnchorY;
                    wheelAnchorY = y;
                    while (Math.abs(wheelAccum) >= WHEEL_STEP_PX) {
                        boolean up = wheelAccum > 0;
                        NativeGames.fbWheel(handle, up);
                        wheelAccum += up ? -WHEEL_STEP_PX : WHEEL_STEP_PX;
                        wheelUsed = true;
                    }
                } else {
                    movePointer(event.getX(), event.getY());
                }
                break;
            }
            case MotionEvent.ACTION_UP: {
                if (multiTouch) {
                    if (leftPressed) {
                        leftPressed = false;
                        NativeGames.fbPointerButton(handle, NativeGames.BUTTON_LEFT, false);
                    }
                    if (!wheelUsed) {
                        rightClick();
                    }
                } else {
                    movePointer(event.getX(), event.getY());
                    if (leftPressPending) {
                        handler.removeCallbacks(sendLeftPress);
                        leftPressPending = false;
                        NativeGames.fbPointerButton(handle, NativeGames.BUTTON_LEFT, true);
                    }
                    leftPressed = false;
                    NativeGames.fbPointerButton(handle, NativeGames.BUTTON_LEFT, false);
                }
                multiTouch = false;
                break;
            }
            case MotionEvent.ACTION_CANCEL: {
                handler.removeCallbacks(sendLeftPress);
                leftPressPending = false;
                if (leftPressed) {
                    leftPressed = false;
                    NativeGames.fbPointerButton(handle, NativeGames.BUTTON_LEFT, false);
                }
                multiTouch = false;
                break;
            }
            default:
                break;
        }
        return true;
    }

    private static float averageY(MotionEvent event) {
        float sum = 0f;
        for (int i = 0; i < event.getPointerCount(); i++) {
            sum += event.getY(i);
        }
        return sum / Math.max(1, event.getPointerCount());
    }

    private void movePointer(float x, float y) {
        int[] point = fbView.toFrame(x, y);
        NativeGames.fbPointerMove(handle, point[0], point[1]);
    }

    private void rightClick() {
        if (handle == 0L) {
            return;
        }
        NativeGames.fbPointerButton(handle, NativeGames.BUTTON_RIGHT, true);
        NativeGames.fbPointerButton(handle, NativeGames.BUTTON_RIGHT, false);
    }

    private void toggleSkip() {
        if (handle == 0L) {
            return;
        }
        skipping = !skipping;
        NativeGames.fbKey(handle, NativeGames.KEY_CTRL, skipping);
        if (skipButton != null) {
            skipButton.setText(uiString(skipping ? R.string.engine_fb_skip_off : R.string.engine_fb_skip));
        }
    }

    private void toggleIme() {
        if (imeVisible) {
            hideIme();
        } else {
            showIme();
        }
    }

    private void showIme() {
        if (handle == 0L || textInputView == null) {
            return;
        }
        textInputView.requestFocus();
        textInputView.post(() -> {
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.showSoftInput(textInputView, 0);
            }
        });
        imeVisible = true;
        if (imeButton != null) {
            imeButton.setText(uiString(R.string.engine_fb_ime_hide));
        }
    }

    private void hideIme() {
        if (textInputView == null) {
            return;
        }
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.hideSoftInputFromWindow(textInputView.getWindowToken(), 0);
        }
        textInputView.clearFocus();
        imeVisible = false;
        if (imeButton != null) {
            imeButton.setText(uiString(R.string.engine_fb_ime_show));
        }
    }

    @Override
    public void onCommitText(String text) {
        if (handle != 0L && text != null && !text.isEmpty()) {
            NativeGames.fbText(handle, text);
        }
    }

    @Override
    public void onBackspace() {
        if (handle != 0L) {
            NativeGames.fbKey(handle, NativeGames.KEY_BACKSPACE, true);
            NativeGames.fbKey(handle, NativeGames.KEY_BACKSPACE, false);
        }
    }

    @Override
    public void onEnter() {
        if (handle != 0L) {
            NativeGames.fbKey(handle, NativeGames.KEY_ENTER, true);
            NativeGames.fbKey(handle, NativeGames.KEY_ENTER, false);
        }
    }

    private static int mapKey(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_BUTTON_A:
                return NativeGames.KEY_ENTER;
            case KeyEvent.KEYCODE_ESCAPE:
            case KeyEvent.KEYCODE_BUTTON_B:
                return NativeGames.KEY_ESCAPE;
            case KeyEvent.KEYCODE_SPACE:
                return NativeGames.KEY_SPACE;
            case KeyEvent.KEYCODE_DPAD_UP:
                return NativeGames.KEY_UP;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                return NativeGames.KEY_DOWN;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                return NativeGames.KEY_LEFT;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                return NativeGames.KEY_RIGHT;
            case KeyEvent.KEYCODE_PAGE_UP:
                return NativeGames.KEY_PAGE_UP;
            case KeyEvent.KEYCODE_PAGE_DOWN:
                return NativeGames.KEY_PAGE_DOWN;
            case KeyEvent.KEYCODE_MOVE_HOME:
                return NativeGames.KEY_HOME;
            case KeyEvent.KEYCODE_MOVE_END:
                return NativeGames.KEY_END;
            case KeyEvent.KEYCODE_DEL:
                return NativeGames.KEY_BACKSPACE;
            case KeyEvent.KEYCODE_TAB:
                return NativeGames.KEY_TAB;
            case KeyEvent.KEYCODE_CTRL_LEFT:
            case KeyEvent.KEYCODE_CTRL_RIGHT:
                return NativeGames.KEY_CTRL;
            case KeyEvent.KEYCODE_SHIFT_LEFT:
            case KeyEvent.KEYCODE_SHIFT_RIGHT:
                return NativeGames.KEY_SHIFT;
            default:
                if (keyCode >= KeyEvent.KEYCODE_F1 && keyCode <= KeyEvent.KEYCODE_F12) {
                    return NativeGames.KEY_F_BASE + (keyCode - KeyEvent.KEYCODE_F1 + 1);
                }
                return 0;
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        int code = mapKey(keyCode);
        if (handle != 0L && code != 0) {
            if (event.getRepeatCount() == 0 || code <= NativeGames.KEY_SHIFT) {
                NativeGames.fbKey(handle, code, true);
            }
            return true;
        }
        if (handle != 0L && event.getUnicodeChar() > 0 && !event.isCtrlPressed()) {
            NativeGames.fbText(handle, String.valueOf((char) event.getUnicodeChar()));
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        int code = mapKey(keyCode);
        if (handle != 0L && code != 0) {
            NativeGames.fbKey(handle, code, false);
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    // ---------- 工具 ----------

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private String uiString(int resourceId) {
        return EngineUiText.get(this, resourceId);
    }

    private static String firstNonEmpty(String... values) {
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                return value;
            }
        }
        return null;
    }
}
