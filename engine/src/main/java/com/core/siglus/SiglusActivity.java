package com.core.siglus;

import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Choreographer;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.core.engine.EnginePrefs;
import com.core.engine.R;
import com.core.engine.EngineUiText;
import com.core.engine.LaunchContract;

import java.io.File;

/**
 * SiglusEngine（siglus_rs）宿主 Activity。
 *
 * <p>宿主模型与上游一致：Java 持有 SurfaceView 生命周期与 Choreographer 主循环，Rust 引擎
 * 经 {@code siglus_android_*} C ABI 被逐步驱动。差异点：游戏路径/语言/回写键取自
 * {@link LaunchContract} extras，消息框复用 engine 既有文案资源，IME 与键事件按
 * TyranorNext 契约转发。
 */
public final class SiglusActivity extends AppCompatActivity implements
        SurfaceHolder.Callback,
        Choreographer.FrameCallback,
        View.OnTouchListener,
        SiglusTextInputView.Listener,
        NativeSiglus.MessageboxListener {

    private static final String TAG = "SiglusActivity";

    /** 引擎侧取消/返回键（Windows VK Escape）。 */
    private static final int VK_ESCAPE = 0x1B;
    private static final int VK_ENTER = 0x0D;
    private static final int VK_SPACE = 0x20;
    private static final int VK_BACK = 0x08;
    private static final int VK_DELETE = 0x2E;
    private static final int VK_TAB = 0x09;
    private static final int VK_SHIFT = 0x10;
    private static final int VK_CONTROL = 0x11;
    private static final int VK_ALT = 0x12;
    private static final int VK_META = 0x5B;

    private static final long BACK_EXIT_WINDOW_MS = 2000L;
    private static final int MAX_FRAME_DT_MS = 250;

    private FrameLayout rootLayout;
    private SurfaceView surfaceView;
    private SiglusTextInputView textInputView;
    private View loadingOverlay;

    private long handle = 0L;
    private boolean running = false;
    private volatile boolean surfaceReady = false;
    private long lastFrameNs = 0L;
    private boolean loadingOverlayVisible = true;
    private boolean imeVisible = false;

    private String gameRoot;
    private String gameDirName;
    private String pathHash;

    private long lastBackMs = 0L;

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
            Toast.makeText(this, uiString(R.string.engine_siglus_missing_game_dir),
                    Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        gameDirName = new File(gameRoot).getName();
        pathHash = getIntent().getStringExtra(LaunchContract.SIGLUS_PATH_HASH);

        NativeSiglus.setMessageboxListener(this);

        surfaceView.getHolder().addCallback(this);
        surfaceView.setOnTouchListener(this);
        surfaceView.setFocusable(true);
        surfaceView.setFocusableInTouchMode(true);
        surfaceView.setKeepScreenOn(true);

        applyImmersive();
        installBackHandling();
    }

    private View buildContentView() {
        rootLayout = new FrameLayout(this);
        rootLayout.setBackgroundColor(Color.BLACK);

        surfaceView = new SurfaceView(this);
        rootLayout.addView(surfaceView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        textInputView = new SiglusTextInputView(this, this);
        textInputView.setVisibility(View.VISIBLE);
        FrameLayout.LayoutParams inputParams = new FrameLayout.LayoutParams(1, 1);
        inputParams.gravity = Gravity.TOP | Gravity.START;
        rootLayout.addView(textInputView, inputParams);

        loadingOverlay = buildLoadingOverlay();
        rootLayout.addView(loadingOverlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return rootLayout;
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
        labelParams.topMargin = (int) (72 * getResources().getDisplayMetrics().density);
        overlay.addView(label, labelParams);
        return overlay;
    }

    private void hideLoadingOverlay() {
        if (loadingOverlayVisible && loadingOverlay != null) {
            loadingOverlayVisible = false;
            rootLayout.removeView(loadingOverlay);
            loadingOverlay = null;
        }
    }

    @Override
    protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        // singleInstance：游戏运行中再次收到启动请求时不叠加新引擎实例。
        Toast.makeText(this, uiString(R.string.engine_another_game_running),
                Toast.LENGTH_SHORT).show();
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
        stopFrameLoop();
        hideIme();
        destroyEngine();
        NativeSiglus.setMessageboxListener(null);
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
                    setEnabled(false);
                    getOnBackPressedDispatcher().onBackPressed();
                    return;
                }
                lastBackMs = now;
                if (handle != 0L) {
                    forwardKey(VK_ESCAPE, null, false);
                }
                Toast.makeText(SiglusActivity.this,
                        uiString(R.string.engine_siglus_back_again_to_exit),
                        Toast.LENGTH_SHORT).show();
            }
        });
    }

    // ---------- Surface ----------

    @Override
    public void surfaceCreated(@NonNull SurfaceHolder holder) {
        if (handle != 0L) {
            // 后台恢复：Android 换了新的 ANativeWindow，保留运行中的引擎并重挂窗口。
            Surface surface = holder.getSurface();
            int width = surfaceWidth(holder);
            int height = surfaceHeight(holder);
            surfaceReady = NativeSiglus.setSurface(handle, surface, width, height);
            if (!surfaceReady) {
                Toast.makeText(this, uiString(R.string.engine_launch_failed),
                        Toast.LENGTH_LONG).show();
                return;
            }
        } else {
            ensureEngine(holder);
        }
        maybeStartFrameLoop();
    }

    @Override
    public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int width, int height) {
        if (handle == 0L) {
            ensureEngine(holder);
        } else {
            NativeSiglus.resize(handle, Math.max(1, width), Math.max(1, height));
        }
    }

    @Override
    public void surfaceDestroyed(@NonNull SurfaceHolder holder) {
        // ANativeWindow 即将失效，但引擎必须存活：切后台不重启游戏，onDestroy 才销毁。
        surfaceReady = false;
        stopFrameLoop();
    }

    private int surfaceWidth(SurfaceHolder holder) {
        Rect frame = holder.getSurfaceFrame();
        int width = frame != null ? frame.width() : surfaceView.getWidth();
        return Math.max(1, width);
    }

    private int surfaceHeight(SurfaceHolder holder) {
        Rect frame = holder.getSurfaceFrame();
        int height = frame != null ? frame.height() : surfaceView.getHeight();
        return Math.max(1, height);
    }

    private void ensureEngine(@NonNull SurfaceHolder holder) {
        if (handle != 0L) {
            return;
        }
        NativeSiglus.nativeInitContext(getApplicationContext());

        String language = getIntent().getStringExtra(LaunchContract.SIGLUS_LANGUAGE);
        if (language != null && !language.trim().isEmpty()) {
            NativeSiglus.nativeSetLanguage(language.trim());
        }

        int width = surfaceWidth(holder);
        int height = surfaceHeight(holder);
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        double scale = metrics.density;

        long created = NativeSiglus.create(holder.getSurface(), width, height, scale, gameRoot);
        if (created == 0L) {
            Toast.makeText(this, uiString(R.string.engine_siglus_init_failed),
                    Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        handle = created;
        surfaceReady = true;
        NativeSiglus.setNativeMessageboxCallback(handle);
        writeBackGameTitleAsync();
    }

    private void destroyEngine() {
        if (handle != 0L) {
            NativeSiglus.destroy(handle);
            handle = 0L;
        }
    }

    // ---------- 帧循环 ----------

    private void maybeStartFrameLoop() {
        if (!running && handle != 0L && surfaceReady) {
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

        int status = NativeSiglus.step(handle, dtMs);
        if (status > 0) {
            finish();
            return;
        }
        if (status < 0) {
            // VM 错误不是正常退出：停帧循环保留最后一帧供 logcat 排查。
            running = false;
            return;
        }
        hideLoadingOverlay();
        updateImeState();
        Choreographer.getInstance().postFrameCallback(this);
    }

    // ---------- 输入 ----------

    @Override
    public boolean onTouch(View view, MotionEvent event) {
        if (handle == 0L || event == null) {
            return false;
        }
        int phase;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                phase = 0;
                break;
            case MotionEvent.ACTION_MOVE:
                phase = 1;
                break;
            case MotionEvent.ACTION_UP:
                phase = 2;
                break;
            case MotionEvent.ACTION_CANCEL:
                phase = 3;
                break;
            default:
                return false;
        }
        hideIme();
        NativeSiglus.touch(handle, phase, event.getX(), event.getY());
        return true;
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (handle != 0L && event != null) {
            int action = event.getAction();
            if (action == KeyEvent.ACTION_DOWN || action == KeyEvent.ACTION_MULTIPLE) {
                int keyCode = event.getKeyCode();
                int vk = vkCodeFor(keyCode);
                boolean repeat = event.getRepeatCount() > 0;
                if (vk != 0) {
                    forwardKey(vk, null, repeat);
                    return true;
                }
                int unicode = event.getUnicodeChar();
                if (unicode != 0) {
                    forwardKey(0, String.valueOf((char) unicode), repeat);
                    return true;
                }
            } else if (action == KeyEvent.ACTION_UP && vkCodeFor(event.getKeyCode()) != 0) {
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    private void forwardKey(int vk, @Nullable String text, boolean repeat) {
        if (handle == 0L) {
            return;
        }
        NativeSiglus.keyEvent(handle, vk, text, repeat);
    }

    /** Android KeyEvent → Windows 虚拟键码（与引擎 host.rs::vm_key_from_platform_code 对齐）。 */
    private static int vkCodeFor(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_ESCAPE:
                return VK_ESCAPE;
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                return VK_ENTER;
            case KeyEvent.KEYCODE_SPACE:
                return VK_SPACE;
            case KeyEvent.KEYCODE_DEL:
                return VK_BACK;
            case KeyEvent.KEYCODE_FORWARD_DEL:
                return VK_DELETE;
            case KeyEvent.KEYCODE_TAB:
                return VK_TAB;
            case KeyEvent.KEYCODE_SHIFT_LEFT:
            case KeyEvent.KEYCODE_SHIFT_RIGHT:
                return VK_SHIFT;
            case KeyEvent.KEYCODE_CTRL_LEFT:
            case KeyEvent.KEYCODE_CTRL_RIGHT:
                return VK_CONTROL;
            case KeyEvent.KEYCODE_ALT_LEFT:
            case KeyEvent.KEYCODE_ALT_RIGHT:
                return VK_ALT;
            case KeyEvent.KEYCODE_META_LEFT:
            case KeyEvent.KEYCODE_META_RIGHT:
                return VK_META;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                return 0x25;
            case KeyEvent.KEYCODE_DPAD_UP:
                return 0x26;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                return 0x27;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                return 0x28;
            case KeyEvent.KEYCODE_MOVE_HOME:
                return 0x24;
            case KeyEvent.KEYCODE_MOVE_END:
                return 0x23;
            default:
                break;
        }
        if (keyCode >= KeyEvent.KEYCODE_A && keyCode <= KeyEvent.KEYCODE_Z) {
            return 0x41 + (keyCode - KeyEvent.KEYCODE_A);
        }
        if (keyCode >= KeyEvent.KEYCODE_0 && keyCode <= KeyEvent.KEYCODE_9) {
            return 0x30 + (keyCode - KeyEvent.KEYCODE_0);
        }
        if (keyCode >= KeyEvent.KEYCODE_F1 && keyCode <= KeyEvent.KEYCODE_F12) {
            return 0x70 + (keyCode - KeyEvent.KEYCODE_F1);
        }
        return 0;
    }

    // ---------- IME ----------

    private void updateImeState() {
        if (handle == 0L) {
            return;
        }
        int[] area = new int[4];
        boolean needed = NativeSiglus.imeArea(handle, area);
        if (needed) {
            if (!imeVisible) {
                textInputView.requestFocus();
                InputMethodManager imm = (InputMethodManager)
                        getSystemService(Context.INPUT_METHOD_SERVICE);
                if (imm != null) {
                    imm.showSoftInput(textInputView, InputMethodManager.SHOW_IMPLICIT);
                }
                imeVisible = true;
            }
            textInputView.updateCursorAnchor(new Rect(area[0], area[1], area[0] + area[2], area[1] + area[3]));
        } else if (imeVisible) {
            hideIme();
        }
    }

    private void hideIme() {
        if (!imeVisible) {
            return;
        }
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.hideSoftInputFromWindow(textInputView.getWindowToken(), 0);
        }
        textInputView.clearFocus();
        imeVisible = false;
    }

    @Override
    public void onCommitText(String text) {
        if (handle != 0L && text != null && !text.isEmpty()) {
            NativeSiglus.textInput(handle, text);
        }
    }

    @Override
    public void onPreedit(String text, int cursorStart, int cursorEnd) {
        if (handle == 0L) {
            return;
        }
        NativeSiglus.imePreedit(handle, text, cursorStart, cursorEnd);
    }

    @Override
    public void onImeKeyEvent(int keyCode, boolean down) {
        if (handle == 0L || !down) {
            return;
        }
        int vk = vkCodeFor(keyCode);
        if (vk != 0) {
            forwardKey(vk, null, false);
        }
    }

    // ---------- 消息框 ----------

    @Override
    public void onMessagebox(long engineHandle, long requestId, int kind, String title, String message) {
        runOnUiThread(() -> showMessagebox(engineHandle, requestId, kind, title, message));
    }

    private void showMessagebox(long engineHandle, long requestId, int kind, String title, String message) {
        if (isFinishing() || isDestroyed()) {
            NativeSiglus.submitMessageboxResult(engineHandle, requestId, fallbackMessageboxValue(kind));
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(EngineUiText.localizeCommonDialogText(this, title));
        builder.setMessage(message == null ? "" : message);
        builder.setCancelable(true);
        switch (kind) {
            case 1: // OK / CANCEL
                builder.setPositiveButton(uiString(R.string.engine_ok),
                        (dialog, which) -> NativeSiglus.submitMessageboxResult(engineHandle, requestId, 0));
                builder.setNegativeButton(uiString(R.string.engine_cancel),
                        (dialog, which) -> NativeSiglus.submitMessageboxResult(engineHandle, requestId, 1));
                builder.setOnCancelListener(dialog ->
                        NativeSiglus.submitMessageboxResult(engineHandle, requestId, 1));
                break;
            case 2: // YES / NO
                builder.setPositiveButton(uiString(R.string.engine_yes),
                        (dialog, which) -> NativeSiglus.submitMessageboxResult(engineHandle, requestId, 0));
                builder.setNegativeButton(uiString(R.string.engine_no),
                        (dialog, which) -> NativeSiglus.submitMessageboxResult(engineHandle, requestId, 1));
                builder.setOnCancelListener(dialog ->
                        NativeSiglus.submitMessageboxResult(engineHandle, requestId, 1));
                break;
            case 3: // YES / NO / CANCEL
                builder.setPositiveButton(uiString(R.string.engine_yes),
                        (dialog, which) -> NativeSiglus.submitMessageboxResult(engineHandle, requestId, 0));
                builder.setNegativeButton(uiString(R.string.engine_no),
                        (dialog, which) -> NativeSiglus.submitMessageboxResult(engineHandle, requestId, 1));
                builder.setNeutralButton(uiString(R.string.engine_cancel),
                        (dialog, which) -> NativeSiglus.submitMessageboxResult(engineHandle, requestId, 2));
                builder.setOnCancelListener(dialog ->
                        NativeSiglus.submitMessageboxResult(engineHandle, requestId, 2));
                break;
            case 0: // OK
            default:
                builder.setPositiveButton(uiString(R.string.engine_ok),
                        (dialog, which) -> NativeSiglus.submitMessageboxResult(engineHandle, requestId, 0));
                builder.setOnCancelListener(dialog ->
                        NativeSiglus.submitMessageboxResult(engineHandle, requestId, 0));
                break;
        }
        builder.show();
    }

    private static long fallbackMessageboxValue(int kind) {
        switch (kind) {
            case 1:
            case 2:
                return 1;
            case 3:
                return 2;
            case 0:
            default:
                return 0;
        }
    }

    // ---------- 标题回写 ----------

    /**
     * 引擎启动成功后读取 Gameexe GAMENAME 并写入共享 prefs，App 侧在下一次入库时条件导入
     * （仅覆盖仍等于目录名的自动标题）。引擎进程无法访问 Room，故走 prefs 回写协议。
     */
    private void writeBackGameTitleAsync() {
        if (pathHash == null || pathHash.trim().isEmpty() || gameRoot == null) {
            return;
        }
        final String dir = gameRoot;
        final String hash = pathHash.trim();
        final String defaultTitle = gameDirName;
        new Thread(() -> {
            try {
                String name = NativeSiglus.gameNameFromDir(dir);
                if (name == null) {
                    return;
                }
                String title = name.trim();
                if (title.isEmpty() || title.equals(defaultTitle)) {
                    return;
                }
                SharedPreferences prefs = getSharedPreferences(EnginePrefs.APP_PREFS, Context.MODE_PRIVATE);
                prefs.edit().putString(EnginePrefs.KEY_SIGLUS_TITLE_PREFIX + hash, title).apply();
                Log.i(TAG, "siglus title written back: " + title);
            } catch (Throwable t) {
                Log.w(TAG, "title write-back failed", t);
            }
        }, "siglus-title").start();
    }

    // ---------- 工具 ----------

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
