package com.core.xsys35;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;

import org.libsdl.app32.SDLActivity;
import org.tvp.kirikiri2.KrDialogStyle;

import com.core.engine.DoubleBackExit;
import com.core.engine.EnginePrefs;
import com.core.engine.EngineUiText;
import com.core.engine.LaunchContract;
import com.core.engine.R;

import java.io.File;

/**
 * System3.x（xsystem35）宿主 Activity。
 *
 * <p>与上游 {@code io.github.kichikuou.xsystem35.GameActivity} 同架构：直接继承 vendored
 * SDL2 2.32.10 Java 层（包名 {@code org.libsdl.app32}），由 SDL 原生主线程执行
 * {@code libxsystem35.so} 的 {@code SDL_main}。宿主只做四件事：拼命令行参数
 * （{@link #getArguments()}，{@code -gamedir} 必须成对置于首位）、启动预检场景档
 * （{@code S*<字母>.ALD} / {@code adisk.ald}）、承接 native 反射回调（输入弹窗 / MIDI 播放）、
 * 返回键与退出进程。
 *
 * <p>进程隔离在 {@code :xsys35}；多开由 {@code singleInstance} 兜底提示。
 */
public final class Xs35Activity extends SDLActivity {

    private static final String TAG = "Xs35Activity";

    /** 渲染后端取值：auto 表示不传 {@code -renderer}，交由 SDL 自动探测。 */
    private static final String RENDERER_AUTO = "auto";

    /** SDL 线程经反射调用 MIDI 回调，UI 线程在 onCreate/onDestroy 读写，故 volatile。 */
    private volatile Xs35MidiPlayer midi;

    /**
     * SDL 单例是否已建立（{@code super.onCreate} 之后为 true）。{@code nativeGetHintBoolean}
     * 等 native 调用只有在库加载后才可用，返回键兜底必须先经本守卫。
     */
    private volatile boolean sdlReady;

    private String gameRoot;
    private String pathHash;

    /** 标题回写只做一次（SDL 每次 setWindowTitle 都会回调 setTitle）。 */
    private volatile boolean titleWrittenBack = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // 先解析 Intent：缺失游戏目录或缺少场景档时必须尽早失败并结束，不进入 SDL 主循环。
        String rawGameRoot = firstNonEmpty(
                getIntent().getStringExtra(LaunchContract.PATH),
                getIntent().getStringExtra(LaunchContract.GAME_PATH),
                getIntent().getStringExtra(LaunchContract.PROJECT_ROOT),
                getIntent().getStringExtra(LaunchContract.GAME_DIR));
        // 路径防御（审核 5-安全-2.1）：游戏目录必须是绝对路径；相对路径（异常 Intent / 外部调用）
        // 与缺失同样按「缺少游戏目录」处理，避免把引擎 chdir 到进程当前目录下的任意位置。
        if (rawGameRoot == null || rawGameRoot.trim().isEmpty()
                || !new File(rawGameRoot.trim()).isAbsolute()) {
            Toast.makeText(this, uiString(R.string.engine_xsys35_missing_game_dir),
                    Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        gameRoot = rawGameRoot.trim();

        // 标题回写定位哈希由 App 计算并下发（协议见 LaunchContract.XS35_PATH_HASH，
        // 与 Siglus 同款）；宿主不自行推导，非空才启用回写。
        pathHash = getIntent().getStringExtra(LaunchContract.XS35_PATH_HASH);

        // 引擎硬性要求：游戏目录（= chdir 后的 CWD）必须存在场景档 S*.ALD（或 adisk.ald），
        // 否则 native main() 会打印 usage 退出、表现为 SDL 线程结束后的黑白屏。宿主在进入主循环前兜底。
        if (!containsScenarioScript(gameRoot)) {
            Toast.makeText(this, uiString(R.string.engine_xsys35_init_failed),
                    Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        super.onCreate(savedInstanceState);
        sdlReady = true;

        midi = new Xs35MidiPlayer();

        // Workaround for https://github.com/libsdl-org/SDL/issues/8995 —— 必须 super.onCreate 之后调用，
        // 此时 SDL 单例已建立（sendCommand 依赖 mSingleton）。
        SDLActivity.setWindowStyle(true);

        // 启动参数日志：契约字段 XS35_SAVE_DIR 的生效值由 App 解析（禁止宿主推导），
        // XS35_SCOPED_SAVE_DIR 仅作诊断信息，在此消费并打印，便于排查存档落点。
        String scopedSaveDir = getIntent().getStringExtra(LaunchContract.XS35_SAVE_DIR);
        Log.i(TAG, "xsys35 launch gamedir=" + gameRoot
                + " scoped=" + getIntent().getBooleanExtra(LaunchContract.XS35_SCOPED_SAVE_DIR, false)
                + " savedir=" + scopedSaveDir);
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (midi != null) {
            midi.onActivityStop();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (midi != null) {
            midi.onActivityResume();
        }
    }

    @Override
    protected void onDestroy() {
        // 上游顺序：先让 SDLActivity.onDestroy 收尾（停 SDL 线程 / nativeQuit），再释放 MIDI，
        // 最后无条件自杀进程（引擎原生库不可重入，与 Artemis/KRKR 宿主同策略）。
        try {
            super.onDestroy();
        } finally {
            try {
                if (midi != null) {
                    midi.release();
                    midi = null;
                }
            } finally {
                android.os.Process.killProcess(android.os.Process.myPid());
            }
        }
    }

    @Override
    public void onBackPressed() {
        // sdlReady 守卫（审核 3-IMPORTANT-2）：native 库未加载时不得调用 nativeGetHintBoolean，
        // 否则在引擎启动早期（onCreate 失败路径等）会触发未捕获的 UnsatisfiedLinkError。
        if (!sdlReady || isFinishing() || nativeGetHintBoolean("SDL_ANDROID_TRAP_BACK_BUTTON", false)) {
            return;
        }
        // 上游 SDL 2.32.10 的按键链会在 dispatchKeyEvent 里把返回键转发给 native 并消费，
        // 游戏内实际行为是引擎自绘退出菜单（event.c：AC_BACK → menu_quitmenu_open）。
        // 本覆写仅覆盖系统直接调用 onBackPressed 的路径（引擎启动早期等）：
        // 对齐其它宿主，单击提示、窗口期内再次返回才真正退出（与既有 2.26.3 接入同款安全兜底）。
        if (DoubleBackExit.shouldExit(this)) {
            superOnBackPressed();
            return;
        }
        Toast.makeText(this, uiString(R.string.engine_xsys35_back_again_to_exit),
                Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // singleInstance：游戏运行中再次收到启动请求时不叠加新引擎实例。
        Toast.makeText(this, uiString(R.string.engine_another_game_running),
                Toast.LENGTH_SHORT).show();
    }

    // ---------- 屏幕方向 ----------

    /**
     * xsystem35 以可缩放窗口创建 SDL 窗口且未设置 SDL_HINT_ORIENTATIONS，上游 SDL 在
     * 「无 hint + resizable」时会请求 SCREEN_ORIENTATION_FULL_USER（尊重系统旋转锁），
     * 设备锁定竖屏时活动会以竖屏渲染、游戏被信箱化。System3.x 游戏必须横屏，故覆盖
     * 该扩展点始终请求 sensorLandscape（与 manifest 声明一致，不受旋转锁影响）。
     */
    @Override
    public void setOrientationBis(int w, int h, boolean resizable, String hint) {
        Log.v(TAG, "force sensorLandscape (w=" + w + ", h=" + h + ", resizable=" + resizable + ", hint=" + hint + ")");
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
    }

    // ---------- SDL 主循环参数 ----------

    @Override
    protected String[] getLibraries() {
        // 顺序即加载顺序，末项为主库（getMainSharedObject / getMainFunction 由 SDLActivity 默认值给出）。
        // SDL2_mixer / freetype / libwebp 经 DT_NEEDED 自动解析，无需显式加载。
        return new String[] { "SDL2x", "xsystem35" };
    }

    @Override
    protected String[] getArguments() {
        // 上游 main() 无 argc 校验且无条件解引用 argv[1]：-gamedir <目录> 必须成对置于首位。
        java.util.ArrayList<String> args = new java.util.ArrayList<>();
        args.add("-gamedir");
        args.add(gameRoot);

        String saveDir = getIntent().getStringExtra(LaunchContract.XS35_SAVE_DIR);
        if (saveDir != null && !saveDir.trim().isEmpty()) {
            // 路径防御（审核 5-安全-2.1）：契约约定存档目录必须是绝对路径；
            // 非绝对路径（异常 Intent / 外部调用）只记日志并忽略，不传 -savedir（引擎回退游戏目录内）。
            if (new File(saveDir.trim()).isAbsolute()) {
                args.add("-savedir");
                args.add(saveDir.trim());
            } else {
                Log.w(TAG, "xsys35 ignore non-absolute savedir: " + saveDir);
            }
        }

        String renderer = getIntent().getStringExtra(LaunchContract.XS35_RENDERER);
        if (renderer != null && !renderer.trim().isEmpty()
                && !RENDERER_AUTO.equalsIgnoreCase(renderer.trim())) {
            args.add("-renderer");
            args.add(renderer.trim());
        }

        if (getIntent().getBooleanExtra(LaunchContract.XS35_INTEGER_SCALE, false)) {
            args.add("-integerscale");
        }
        if (getIntent().getBooleanExtra(LaunchContract.XS35_MUTE_UNFOCUS, false)) {
            args.add("-mute_on_unfocus");
        }
        return args.toArray(new String[0]);
    }

    // ---------- SDL 系统消息框（引擎 sys_show_message_box） ----------

    /**
     * 覆盖 vendored SDL 的原生 AlertDialog 实现，统一到引擎层弹窗样式
     * （{@link KrDialogStyle}，与 KRKR/Artemis 同款卡片与按钮）。
     *
     * <p>调用前提与上游一致：本方法由 {@code messageboxShowMessageBox} 经
     * {@code runOnUiThread} 投递，UI 线程执行时 native 线程正阻塞等待
     * {@code messageboxSelection}。{@code KrDialogStyle} 不可取消、且 dismiss 先于回调，
     * 故不挂 OnDismissListener——其 notify 会早于按钮结果写入，使 native 线程提前醒来读到 -1。</p>
     */
    @Override
    protected void messageboxCreateAndShow(Bundle args) {
        String title = args != null ? args.getString("title") : null;
        String message = args != null ? args.getString("message") : null;
        String[] buttonTexts = args != null ? args.getStringArray("buttonTexts") : null;
        int[] ids = args != null ? args.getIntArray("buttonIds") : null;
        // SDL 默认按钮为英文系统标签，映射到引擎侧公共字符串；自定义文案保持不变。
        // （只处理英文默认标签：中文系统标签由 KrDialogStyle 在渲染时二次本地化。）
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
     * 无匹配（游戏自定义文案）返回 0，由调用方保留原文，避免误伤。
     */
    private static int commonButtonTextResId(String text) {
        if (text == null) {
            return 0;
        }
        switch (text.trim().toLowerCase(java.util.Locale.ROOT)) {
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

    // ---------- native 反射回调（SDL 线程调用，方法名/签名必须与引擎 C 侧一致） ----------

    /**
     * {@code src/input_modal.c} 文本输入：阻塞 SDL 线程，直到 UI 线程的弹窗关闭。
     * 取消/关闭时返回 null（native 侧视为空输入）。
     */
    public String inputString(String msg, String oldVal, int maxLen) {
        final String[] result = new String[1];
        final Object lock = new Object();
        final boolean[] done = new boolean[1];
        // 引擎取值异常时收敛到合法区间（负数按 0 处理），避免 substring 越界
        final int pickMaxLen = Math.max(0, maxLen);
        final Runnable show = () -> {
            if (isFinishing() || isDestroyed()) {
                synchronized (lock) {
                    done[0] = true;
                    lock.notifyAll();
                }
                return;
            }
            try {
                // 统一到引擎层弹窗样式（标题固定「提示」，确定/取消文案走 engine 资源）
                KrDialogStyle.showInputBox(this, uiString(R.string.engine_prompt), msg,
                        oldVal != null ? oldVal : "",
                        new String[] { uiString(R.string.engine_ok), uiString(R.string.engine_cancel) },
                        false, (which, text) -> {
                            if (which == 0) {
                                String value = text == null ? "" : text;
                                // 与上游一致：超出上限时截断而不是拒绝。
                                result[0] = value.length() <= pickMaxLen
                                        ? value : value.substring(0, pickMaxLen);
                            }
                            // which != 0：保持 result[0] == null（取消语义，native 侧视为空输入）
                            synchronized (lock) {
                                done[0] = true;
                                lock.notifyAll();
                            }
                        });
            } catch (RuntimeException e) {
                // 弹窗构造失败也必须唤醒 native 线程，否则 SDL 线程永远阻塞在 wait()
                Log.e(TAG, "inputString dialog failed", e);
                synchronized (lock) {
                    done[0] = true;
                    lock.notifyAll();
                }
            }
        };
        runOnUiThread(show);

        synchronized (lock) {
            // 条件等待（防虚假唤醒）：done 由用户确认/取消、弹窗构造失败或 Activity 已结束置位
            while (!done[0]) {
                try {
                    lock.wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        return result[0];
    }

    /** {@code src/input_modal.c} 数值输入：语义同 {@link #inputString}，取消返回 -1。 */
    public int inputNumber(String msg, int min, int max, int initial) {
        final int[] result = new int[] { -1 };
        final Object lock = new Object();
        final boolean[] done = new boolean[1];
        // 引擎取值异常时收敛区间与预填值到合法范围（确认解析失败的回退值也与之一致）；
        // showNumberBox 内部同样会规范化 min/max 并钳制输入，避免异常值导致 native 线程等不到结果。
        final int pickMin = Math.min(min, max);
        final int pickMax = Math.max(min, max);
        final int pickInitial = Math.min(Math.max(initial, pickMin), pickMax);
        final Runnable show = () -> {
            if (isFinishing() || isDestroyed()) {
                synchronized (lock) {
                    done[0] = true;
                    lock.notifyAll();
                }
                return;
            }
            try {
                // 统一到引擎层弹窗样式；数值区间在 showNumberBox 内再次规范化并钳制。
                KrDialogStyle.showNumberBox(this, uiString(R.string.engine_prompt), msg,
                        pickInitial, pickMin, pickMax,
                        new String[] { uiString(R.string.engine_ok), uiString(R.string.engine_cancel) },
                        false, (which, text) -> {
                            if (which == 0) {
                                // showNumberBox 已解析并钳制，这里只做字符串 → 整数还原；
                                // 解析失败（理论不可达）回退 initial。
                                int value = pickInitial;
                                if (text != null && !text.isEmpty()) {
                                    try {
                                        value = Integer.parseInt(text);
                                    } catch (NumberFormatException ignored) {
                                        // 保持 pickInitial
                                    }
                                }
                                result[0] = value;
                            }
                            // which != 0：保持 result[0] == -1（取消语义，与上游一致）
                            synchronized (lock) {
                                done[0] = true;
                                lock.notifyAll();
                            }
                        });
            } catch (RuntimeException e) {
                Log.e(TAG, "inputNumber dialog failed", e);
                synchronized (lock) {
                    done[0] = true;
                    lock.notifyAll();
                }
            }
        };
        runOnUiThread(show);

        synchronized (lock) {
            // 条件等待（防虚假唤醒）：done 由用户确认/取消、弹窗构造失败或 Activity 已结束置位
            while (!done[0]) {
                try {
                    lock.wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        return result[0];
    }

    /** {@code src/midi.android.c}：播放 {@code SDL_AndroidGetInternalStoragePath()/tmp.mid}。 */
    public void midiStart(String path, boolean loop) {
        if (midi != null) {
            midi.start(path, loop);
        }
    }

    public void midiStop() {
        if (midi != null) {
            midi.stop();
        }
    }

    public int midiCurrentPosition() {
        return midi == null ? 0 : midi.currentPosition();
    }

    // ---------- 标题回写（复用 Siglus 协议） ----------

    @Override
    public void setTitle(CharSequence title) {
        super.setTitle(title);
        if (titleWrittenBack || pathHash == null) {
            return;
        }
        writeBackTitle(title == null ? null : title.toString());
    }

    /**
     * native 经 SDL 的 {@code setActivityTitle} 回传 {@code "XSystem35 Version <ver>:<标题>"}，
     * 取冒号后段；首次非空即写共享 prefs（{@code xsys35_title.<pathHash>}），App 侧下次入库时
     * 条件导入（仅覆盖仍等于目录名的自动标题）。引擎进程访问不到 Room，故走 prefs 回写协议。
     */
    private void writeBackTitle(String rawTitle) {
        if (rawTitle == null) {
            return;
        }
        String resolved = afterFirstColon(rawTitle);
        if (resolved.isEmpty()) {
            return;
        }
        titleWrittenBack = true;
        SharedPreferences prefs = getSharedPreferences(EnginePrefs.APP_PREFS, Context.MODE_PRIVATE);
        String key = EnginePrefs.KEY_XSYS35_TITLE_PREFIX + pathHash;
        if (prefs.contains(key)) {
            return;
        }
        prefs.edit().putString(key, resolved).apply();
        Log.i(TAG, "xsys35 title written back: " + resolved);
    }

    // ---------- 工具 ----------

    /** 取第一个冒号后的内容（上游标题格式 {@code "XSystem35 Version <ver>:<标题>"}）。 */
    private static String afterFirstColon(String text) {
        int index = text.indexOf(':');
        if (index < 0) {
            return "";
        }
        return text.substring(index + 1).trim();
    }

    /**
     * 游戏目录（非递归）是否含引擎接受的场景档：`adisk.ald` 特例，或 `S` + 字母（`a..z`）的
     * `*.ALD`（文件名以 {@code .ald} 结尾、长度 ≥ 6、倒数第 6 位为 S/s、倒数第 5 位为字母，
     * 对齐上游 gameresource.c 的 {@code toupper(x) - 'A'} 有效范围）。
     * 与 app 侧 {@code com.tyranor.next.core.game.model.Xs35Paths.hasScenarioAld} 同口径（双向互指）。
     */
    private static boolean containsScenarioScript(String dir) {
        File[] files = new File(dir).listFiles();
        if (files == null || files.length == 0) {
            return false;
        }
        for (File file : files) {
            if (file.isDirectory()) continue;
            String name = file.getName().toLowerCase();
            if (name.equals("adisk.ald")) {
                return true;
            }
            if (name.endsWith(".ald") && name.length() >= 6
                    && name.charAt(name.length() - 6) == 's'
                    && name.charAt(name.length() - 5) >= 'a' && name.charAt(name.length() - 5) <= 'z') {
                return true;
            }
        }
        return false;
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
