package com.core.xsys4;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ConfigurationInfo;
import android.os.Bundle;
import android.util.Log;
import android.view.ViewGroup;
import android.widget.Toast;

import org.libsdl.app32.SDLActivity;
import org.tvp.kirikiri2.KrDialogStyle;

import com.core.engine.DoubleBackExit;
import com.core.engine.EngineSurfaceStartupController;
import com.core.engine.EngineUiText;
import com.core.engine.LaunchContract;
import com.core.engine.R;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Locale;

/**
 * System4（xsystem4）宿主 Activity。
 *
 * <p>与 {@code com.core.xsys35.Xs35Activity} 同架构：直接继承 vendored SDL2 2.32.10 Java 层
 * （包名 {@code org.libsdl.app32}，与 System35 共用 {@code libSDL2x.so}），由 SDL 原生主线程执行
 * {@code libxsystem4.so} 的 {@code SDL_main}。宿主负责预检（游戏目录、实际 ini、GLES 3.0、字体）、
 * 拼命令行参数（{@link #getArguments()}，位置参数为实际 ini 绝对路径）、承接自定义消息 {@code 0x8000}
 * 打开手册页、返回键与退出进程。
 *
 * <p>xsystem4 的正常退出与致命错误都是 {@code exit()} 整进程，宿主不依赖 {@code onDestroy} 收尾。
 * 进程隔离在 {@code :xsys4}；多开由 {@code singleInstance} 兜底提示。
 */
public final class Xs4Activity extends SDLActivity {

    private static final String TAG = "Xs4Activity";

    /** 引擎 {@code SystemService.c} 发出的手册打开命令（自定义消息）。 */
    private static final int COMMAND_OPEN_MANUAL = 0x8000;

    /** 引擎识别的游戏入口 ini 候选，顺序与引擎 {@code config_init_with_dir} 一致（先 System40.ini）。 */
    private static final String[] INI_NAMES = { "System40.ini", "AliceStart.ini" };

    /** OpenGL ES 3.0 的 {@code reqGlEsVersion} 编码值。 */
    private static final int GLES_3_0 = 0x30000;

    /**
     * SDL 线程经 native 回调读取 {@code nativeGetHintBoolean}，库未加载前不可调用；
     * 返回键兜底必须先经本守卫（同 {@code Xs35Activity}）。
     */
    private volatile boolean sdlReady;

    private String gameRoot;
    private File iniFile;
    private String encoding;
    private Xs4Fonts.Resolved fonts;
    private EngineSurfaceStartupController startupController;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // 预检必须在 super.onCreate（进入 SDL 主循环）之前完成：任何失败都直接 finish，不触发 native 调用。
        // 注意：下面的早退发生在 super.onCreate 之前，Android 在 onCreate 未调用 super 时会抛
        // SuperNotCalledException（Xs35Activity 同款存量缺陷，本次不改）。因此 App 侧
        // EngineLauncher（Xs4Preflight）已在拉起本 Activity 之前预检 ini 与 GLES 3.0 并返回类型化失败；
        // 此处仅作防御（如预检后目录被移走/改名），正常不应触发。GLES 判定口径须与 Xs4Preflight.hasGles3 一致。
        // 与 Xs4Paths.resolveIniFile（app 侧）双向互指：游戏根取值、ini 候选顺序与大小写口径需同步修改。
        String rawGameRoot = firstNonEmpty(
                getIntent().getStringExtra(LaunchContract.PATH),
                getIntent().getStringExtra(LaunchContract.GAME_PATH),
                getIntent().getStringExtra(LaunchContract.PROJECT_ROOT),
                getIntent().getStringExtra(LaunchContract.GAME_DIR));
        // 游戏目录必须是绝对路径（同 Xs35Activity 的路径防御）。
        if (rawGameRoot == null || !new File(rawGameRoot.trim()).isAbsolute()) {
            Toast.makeText(this, uiString(R.string.engine_xsys4_missing_game_dir), Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        gameRoot = rawGameRoot.trim();

        // 引擎按精确大小写 file_exists 查找 ini，而 Windows 原版拷贝常有大小写偏差，故宿主大小写不敏感解析，
        // 并把实际文件的绝对路径作为位置参数传入（引擎对 .ini 位置参数取其目录为游戏目录）。
        iniFile = resolveIniFile(new File(gameRoot));
        if (iniFile == null) {
            Toast.makeText(this, uiString(R.string.engine_xsys4_init_failed), Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        if (!supportsGles3()) {
            Toast.makeText(this, uiString(R.string.engine_xsys4_gles3_required), Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        encoding = Xs4Fonts.normalizeEncoding(getIntent().getStringExtra(LaunchContract.XS4_ENCODING));
        fonts = Xs4Fonts.resolve(encoding,
                getIntent().getStringExtra(LaunchContract.XS4_FONT_GOTHIC_PATH),
                getIntent().getStringExtra(LaunchContract.XS4_FONT_MINCHO_PATH));
        for (String note : fonts.notes) {
            Log.w(TAG, "xsys4 " + note);
        }

        // 运行库由启动编排解压；缺失（或 stamp 与当前 APK 不一致）时不能在此 finish：
        // 本类是 SDL 宿主，super.onCreate 才加载运行库，未调用 super 就结束会抛
        // SuperNotCalledException。保持 null 交给 loadLibraries 抛 UnsatisfiedLinkError，
        // 由 SDL 的 mBrokenLibraries 错误路径收场。
        String sdl = com.core.nativeplugin.NativeRuntimePack.libraryPath(
                this, com.core.nativeplugin.NativeRuntimePack.PACK_XSYSTEM, "libSDL2x.so");
        String engine = com.core.nativeplugin.NativeRuntimePack.libraryPath(
                this, com.core.nativeplugin.NativeRuntimePack.PACK_XSYSTEM, "libxsystem4.so");
        if (sdl == null || engine == null) {
            Toast.makeText(this, uiString(R.string.engine_xsys4_runtime_pack_missing), Toast.LENGTH_LONG).show();
            Log.w(TAG, "xsystem runtime pack not ready: sdl=" + sdl + " engine=" + engine);
        }
        // 无条件赋值（含 null）：静态字段跨 Activity 实例保留，必须清掉上一次的旧路径。
        sSdlLibraryPath = sdl;
        sEngineLibraryPath = engine;
        super.onCreate(savedInstanceState);
        sdlReady = !SDLActivity.mBrokenLibraries;
        if (SDLActivity.mBrokenLibraries) {
            // SDL 已展示运行库加载错误框，不再设置依赖 native 的窗口样式。
            return;
        }

        // Workaround for https://github.com/libsdl-org/SDL/issues/8995 —— 必须 super.onCreate 之后调用，
        // 此时 SDL 单例已建立（sendCommand 依赖 mSingleton）。
        SDLActivity.setWindowStyle(true);

        Log.i(TAG, "xsys4 launch gamedir=" + gameRoot
                + " ini=" + iniFile.getName()
                + " scoped=" + getIntent().getBooleanExtra(LaunchContract.XS4_SCOPED_SAVE_DIR, false)
                + " savedir=" + getIntent().getStringExtra(LaunchContract.XS4_SAVE_DIR)
                + " encoding=" + encoding);
        attachStartupOverlay();
    }

    private void attachStartupOverlay() {
        if (SDLActivity.mBrokenLibraries || SDLActivity.mSurface == null || SDLActivity.mLayout == null) return;
        ViewGroup content = findViewById(android.R.id.content);
        startupController = new EngineSurfaceStartupController(this);
        startupController.attachTo(content != null ? content : SDLActivity.mLayout,
                SDLActivity.mSurface.getHolder());
    }

    @Override
    protected void onDestroy() {
        // 引擎原生库不可重入，与 Xs35Activity / Artemis / KRKR 宿主同策略：收尾后无条件自杀进程。
        try {
            if (startupController != null) {
                startupController.dispose();
                startupController = null;
            }
            super.onDestroy();
        } finally {
            android.os.Process.killProcess(android.os.Process.myPid());
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (startupController != null) startupController.onResume();
    }

    @Override
    protected void onPause() {
        if (startupController != null) startupController.onPause();
        super.onPause();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (startupController != null) startupController.onWindowFocusChanged(hasFocus);
    }

    @Override
    public void onBackPressed() {
        // sdlReady 守卫：native 库未加载时不得调用 nativeGetHintBoolean（同 Xs35Activity）。
        if (!sdlReady || isFinishing() || nativeGetHintBoolean("SDL_ANDROID_TRAP_BACK_BUTTON", false)) {
            return;
        }
        // 游戏内返回键由引擎自绘退出菜单处理（SDL 按键链消费）；本覆写仅覆盖系统直接调用
        // onBackPressed 的路径：单击提示，窗口期内再次返回才真正退出。
        if (DoubleBackExit.shouldExit(this)) {
            superOnBackPressed();
            return;
        }
        Toast.makeText(this, uiString(R.string.engine_xsys4_back_again_to_exit), Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // singleInstance：游戏运行中再次收到启动请求时不叠加新引擎实例。
        Toast.makeText(this, uiString(R.string.engine_another_game_running), Toast.LENGTH_SHORT).show();
    }

    // ---------- 屏幕方向 ----------

    /**
     * xsystem4 以可缩放窗口创建 SDL 窗口且未设置 SDL_HINT_ORIENTATIONS，上游 SDL 会请求
     * SCREEN_ORIENTATION_FULL_USER，设备锁定竖屏时游戏被信箱化。System4 只支持横屏，故始终请求
     * sensorLandscape（与 manifest 声明一致）。
     */
    @Override
    public void setOrientationBis(int w, int h, boolean resizable, String hint) {
        Log.v(TAG, "force sensorLandscape (w=" + w + ", h=" + h + ", resizable=" + resizable + ", hint=" + hint + ")");
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
    }

    // ---------- SDL 主循环参数 ----------

    // 引擎库从运行库包按绝对路径加载；libSDL2x.so 同包，先加载依赖。
    private static volatile String sSdlLibraryPath;
    private static volatile String sEngineLibraryPath;

    @Override
    public void loadLibraries() {
        String sdl = sSdlLibraryPath;
        String engine = sEngineLibraryPath;
        if (sdl == null || engine == null) throw new UnsatisfiedLinkError("xsystem runtime pack missing");
        System.load(sdl);
        System.load(engine);
    }

    @Override
    protected String getMainSharedObject() {
        String path = sEngineLibraryPath;
        // SDL 默认返回 nativeLibraryDir + 库名，这里换成运行库包解压后的绝对路径。
        return path != null ? path : super.getMainSharedObject();
    }

    @Override
    protected String[] getLibraries() {
        // 实际加载顺序由覆写的 loadLibraries() 决定（SDL2x → xsystem4），主库路径由
        // getMainSharedObject() 返回解压后的绝对路径；SDL2x 与 System35 共用，其余依赖均静态链入。
        return new String[] { "SDL2x" };
    }

    @Override
    protected String[] getArguments() {
        // 选项全部置于位置参数（游戏 ini）之前；位置参数必须最后。
        ArrayList<String> args = new ArrayList<>();

        // 存档目录必须由 App 下发且为绝对路径：缺省时引擎按 home 推导，Android 下 home 不可用。
        String saveDir = getIntent().getStringExtra(LaunchContract.XS4_SAVE_DIR);
        if (saveDir != null && !saveDir.trim().isEmpty()) {
            if (new File(saveDir.trim()).isAbsolute()) {
                args.add("--save-folder");
                args.add(saveDir.trim());
            } else {
                Log.w(TAG, "xsys4 ignore non-absolute save dir: " + saveDir);
            }
        }
        // 显式传 rsm：引擎默认虽已是 rsm，但 usage 文案过期，与壳保持一致。
        args.add("--save-format=rsm");

        args.add("--encoding");
        args.add(encoding);
        args.add("--font-gothic");
        args.add(fonts.gothic);
        args.add("--font-mincho");
        args.add(fonts.mincho);

        // 手柄：仅 on/off 下发，auto 交引擎默认。
        String joypad = getIntent().getStringExtra(LaunchContract.XS4_JOYPAD);
        if (joypad != null) {
            String value = joypad.trim().toLowerCase(Locale.ROOT);
            if ("on".equals(value) || "off".equals(value)) {
                args.add("--joypad=" + value);
            }
        }

        int msgSkipDelay = getIntent().getIntExtra(LaunchContract.XS4_MSGSKIP_DELAY, 0);
        if (msgSkipDelay > 0) {
            args.add("--msgskip-delay");
            args.add(String.valueOf(msgSkipDelay));
        }

        // 位置参数：实际 ini 绝对路径（引擎取其目录为游戏目录，解决 ini 名大小写不一致）。
        args.add(iniFile.getAbsolutePath());
        return args.toArray(new String[0]);
    }

    // ---------- 自定义消息（手册） ----------

    /**
     * 引擎发出 {@link #COMMAND_OPEN_MANUAL} 时打开游戏手册。手册缺失时静默不响应（不崩溃、不提示）；
     * 命令本身已被消费，返回 true。
     */
    @Override
    protected boolean onUnhandledMessage(int command, Object param) {
        if (command != COMMAND_OPEN_MANUAL) {
            return super.onUnhandledMessage(command, param);
        }
        openManual();
        return true;
    }

    private void openManual() {
        File manualDir = findChildIgnoreCase(new File(gameRoot), "Manual", true);
        File index = manualDir == null ? null : findChildIgnoreCase(manualDir, "index.html", false);
        if (index == null) {
            Log.i(TAG, "xsys4 manual not found under " + gameRoot);
            return;
        }
        try {
            File root = manualDir.getCanonicalFile();
            File page = index.getCanonicalFile();
            if (!Xs4ManualActivity.isInsideManualDir(root, page)) {
                Log.w(TAG, "xsys4 manual index escapes Manual/, ignored: " + page.getPath());
                return;
            }
            // 手册与宿主同 taskAffinity（com.core.Xsys4）：NEW_TASK 使其落入宿主任务，返回键回到游戏而非游戏库。
            // 需真机验证返回键行为。
            Intent intent = new Intent(this, Xs4ManualActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            intent.putExtra(Xs4ManualActivity.EXTRA_MANUAL_DIR, root.getPath());
            intent.putExtra(Xs4ManualActivity.EXTRA_INDEX_PATH, page.getPath());
            startActivity(intent);
        } catch (IOException e) {
            Log.w(TAG, "xsys4 manual path resolution failed", e);
        }
    }

    // ---------- SDL 系统消息框（引擎 sys_show_message_box） ----------

    /**
     * 覆盖 vendored SDL 的原生 AlertDialog 实现，统一到引擎层弹窗样式（{@link KrDialogStyle}）。
     * 引擎致命错误（ERROR / usage_error）与字体加载失败走此处呈现，是唯一可见的错误出口。
     *
     * <p>调用前提与上游一致：本方法由 {@code messageboxShowMessageBox} 经 {@code runOnUiThread} 投递，
     * native 线程阻塞在 {@code messageboxSelection.wait()}，因此必须在任何失败路径写入结果并唤醒。
     */
    @Override
    protected void messageboxCreateAndShow(Bundle args) {
        String title = args != null ? args.getString("title") : null;
        String message = args != null ? args.getString("message") : null;
        String[] buttonTexts = args != null ? args.getStringArray("buttonTexts") : null;
        int[] ids = args != null ? args.getIntArray("buttonIds") : null;
        // SDL 默认按钮为英文系统标签，映射到引擎侧公共字符串；自定义文案保持不变。
        if (buttonTexts != null) {
            for (int i = 0; i < buttonTexts.length; i++) {
                int resId = commonButtonTextResId(buttonTexts[i]);
                if (resId != 0) {
                    buttonTexts[i] = uiString(resId);
                }
            }
        }
        // 引擎未给按钮文案时兜底单个确定按钮（buttonIds 与 buttonTexts 按下标一一对应）。
        if (buttonTexts == null || buttonTexts.length == 0) {
            buttonTexts = new String[] { uiString(R.string.engine_ok) };
            ids = new int[] { 0 };
        }
        final int[] buttonIds = ids;
        try {
            KrDialogStyle.showMessageBox(this, title, message, buttonTexts, (which, text) -> {
                int id = (buttonIds != null && which >= 0 && which < buttonIds.length) ? buttonIds[which] : 0;
                synchronized (messageboxSelection) {
                    messageboxSelection[0] = id;
                    messageboxSelection.notifyAll();
                }
            });
        } catch (Throwable t) {
            // 弹窗构造失败也必须写入结果并唤醒，否则 native 线程永久阻塞在 messageboxSelection.wait()
            Log.e(TAG, "messageboxCreateAndShow failed", t);
            synchronized (messageboxSelection) {
                messageboxSelection[0] = 0;
                messageboxSelection.notifyAll();
            }
        }
    }

    /**
     * SDL 消息框默认按钮的系统英文标签（trim 后忽略大小写精确匹配）→ engine 公共字符串资源；
     * 无匹配（游戏自定义文案）返回 0，由调用方保留原文。
     */
    private static int commonButtonTextResId(String text) {
        if (text == null) {
            return 0;
        }
        switch (text.trim().toLowerCase(Locale.ROOT)) {
            case "ok":
                return R.string.engine_ok;
            case "cancel":
                return R.string.engine_cancel;
            case "yes":
                return R.string.engine_yes;
            case "no":
                return R.string.engine_no;
            default:
                return 0;
        }
    }

    // ---------- 预检工具 ----------

    /**
     * 解析游戏入口 ini：按 {@link #INI_NAMES} 顺序、大小写不敏感匹配普通文件，返回实际文件；均缺失返回 null。
     * 与 app 侧 {@code Xs4Paths.resolveIniFile} 双向互指：候选顺序与匹配口径需同步修改。
     */
    static File resolveIniFile(File root) {
        for (String name : INI_NAMES) {
            File found = findChildIgnoreCase(root, name, false);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    /** 目录内大小写不敏感地查找指定名称的子目录（{@code directory=true}）或普通文件。 */
    static File findChildIgnoreCase(File dir, String name, boolean directory) {
        File[] children = dir.listFiles();
        if (children == null) {
            return null;
        }
        for (File child : children) {
            if (child.getName().equalsIgnoreCase(name)
                    && (directory ? child.isDirectory() : child.isFile())) {
                return child;
            }
        }
        return null;
    }

    private boolean supportsGles3() {
        ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        ConfigurationInfo info = am != null ? am.getDeviceConfigurationInfo() : null;
        // reqGlEsVersion<=0 表示系统未报告版本（并非只支持 GLES 1），放行；与 App 侧 Xs4Preflight.hasGles3 同口径。
        return info == null || info.reqGlEsVersion <= 0 || info.reqGlEsVersion >= GLES_3_0;
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
