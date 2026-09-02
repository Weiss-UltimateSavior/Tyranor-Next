package org.tvp.krkrsdl3;

import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.res.AssetManager;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;

import com.core.engine.KrkrStartupDialogPolicy;
import org.libsdl3.app.SDLActivity;

import java.util.ArrayList;
import java.util.Objects;

public class KRKRActivity extends SDLActivity {
    private static final String TAG = "KRKRActivity";
    /** 引擎 argv 协议键：启动器经该 extra 传入游戏启动参数列表（首项为启动文件绝对路径）。 */
    public static final String SHAREDPREF_GAMECONFIG = "gameargs";
    private ArrayList<String> m_gameargs;
    private boolean skipStartupDialogs;
    private long launchStartElapsedMs = -1L;

    // override sdl functions
    static {
        System.loadLibrary("SDL3");
        System.loadLibrary("krkrsdl3");
    }

    @Override
    protected String[] getLibraries() {
        return new String[] {
                "SDL3",
                "krkrsdl3"
        };
    }

    @Override
    protected String[] getArguments() {
        if (m_gameargs == null)
            m_gameargs = readGameArgs(getIntent());
        if (m_gameargs != null && !m_gameargs.isEmpty())
            return m_gameargs.toArray(new String[0]);
        return new String[] { "" };
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        m_gameargs = readGameArgs(getIntent());
        Intent launchIntent = getIntent();
        skipStartupDialogs = launchIntent != null
                && launchIntent.getBooleanExtra(KrkrStartupDialogPolicy.EXTRA_ENABLED, false);
        launchStartElapsedMs = SystemClock.elapsedRealtime();
        Log.i(TAG, "launch args=" + m_gameargs);
        super.onCreate(savedInstanceState);
        setNativeAssetManager(getAssets());
        this.fullscreen();
    }

    private ArrayList<String> readGameArgs(Intent intent) {
        if (intent == null)
            return null;
        ArrayList<String> args = intent.getStringArrayListExtra(SHAREDPREF_GAMECONFIG);
        if (args != null)
            return args;
        String[] arr = intent.getStringArrayExtra(SHAREDPREF_GAMECONFIG);
        if (arr == null)
            return null;
        args = new ArrayList<>();
        for (String item : arr) {
            if (item != null)
                args.add(item);
        }
        return args;
    }

    @Override
    protected void onResume() {
        super.onResume();
        this.fullscreen();
    }

    @Override
    protected void onDestroy() {
        // 输入弹窗未确认时销毁宿主：解除 native 线程 WaitInputResult 阻塞，
        // 防止 SDL3 onDestroy 的 mSDLThread.join 死锁/线程泄漏。
        KRKRCall.cancelPendingInput();
        skipStartupDialogs = false;
        launchStartElapsedMs = -1L;
        super.onDestroy();
    }

    @Override
    protected boolean shouldAutoSkipMessageBox(
            int flags,
            String title,
            String message,
            int[] buttonFlags,
            int[] buttonIds,
            String[] buttonTexts) {
        return buttonIds != null
                && buttonIds.length == 1
                && KrkrStartupDialogPolicy.shouldAutoConfirm(
                        skipStartupDialogs,
                        launchStartElapsedMs,
                        SystemClock.elapsedRealtime(),
                        title,
                        message,
                        buttonTexts);
    }

    public void onWindowFocusChanged (boolean hasFocus) {
        if(hasFocus) this.fullscreen();
    }

    /** 全屏沉浸入口：默认隐藏系统栏并强制横屏；Rinne 集成子类可覆盖以定制沉浸式布局。 */
    protected void fullscreen() {
        View decorView = getWindow().getDecorView();
        int uiOptions = View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN ;
        decorView.setSystemUiVisibility(uiOptions);
        try {
            Objects.requireNonNull(this.getSupportActionBar()).hide();
        }
        catch (NullPointerException ignored){}
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
    }

    public native void setNativeAssetManager(AssetManager assetManager);
}
