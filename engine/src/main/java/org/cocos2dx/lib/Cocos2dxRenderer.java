package org.cocos2dx.lib;
import android.opengl.EGL14;
import android.opengl.GLSurfaceView;
import android.util.Log;
import java.util.concurrent.locks.LockSupport;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

public class Cocos2dxRenderer implements GLSurfaceView.Renderer {
    private static final String TAG = "Cocos2dxRenderer";

    public interface RendererListener {
        void onNativeReady();
        void onFrameRendered();
    }

    private static volatile long sAnimationInterval = 16666666L;
    /** 下一帧的绝对截止时间（System.nanoTime 基准） */
    private long mNextFrameDeadline;
    /** eglSwapInterval 是否成功激活。
     *  false 时 onDrawFrame 在 60fps 场景下回退到 parkNanos 兜底，避免 swap 不阻塞导致紧密循环。
     *  volatile：GLSurfaceView detach → re-attach 时新 GLThread 可能读到 stale 值。
     *  onSurfaceCreated 入口重置为 false，成功调用 eglSwapInterval 后才置 true。 */
    private volatile boolean mEglSwapIntervalActive = false;
    /** eglSwapInterval 失败时仅记录首次告警，避免日志刷屏；成功调用后重置以允许下次失败再次记录。
     *  volatile：GLSurfaceView detach → re-attach 时新 GLThread 可能读到 stale 值（仅影响日志）。
     *  每个 Renderer 实例独立跟踪，避免 Activity 重建后跨实例残留告警状态。 */
    private volatile boolean mEglSwapIntervalWarned = false;
    private boolean mNativeInitCompleted = false;
    private int mScreenWidth;
    private int mScreenHeight;
    private final RendererListener mListener;

    public Cocos2dxRenderer() {
        this(null);
    }

    public Cocos2dxRenderer(RendererListener listener) {
        mListener = listener;
    }

    private static native void nativeInit(int w, int h);
    private static native void nativeOnSurfaceChanged(int w, int h);
    private static native void nativeRender();
    private static native void nativeOnPause();
    private static native void nativeOnResume();
    private static native boolean nativeKeyEvent(int keyCode, boolean isPressed);
    private static native void nativeTouchesBegin(int id, float x, float y);
    private static native void nativeTouchesMove(int[] ids, float[] xs, float[] ys);
    private static native void nativeTouchesEnd(int id, float x, float y);
    private static native void nativeTouchesCancel(int[] ids, float[] xs, float[] ys);
    private static native void nativeInsertText(String text);
    private static native void nativeDeleteBackward();
    private static native String nativeGetContentText();

    public static void setAnimationInterval(float interval) {
        sAnimationInterval = (long) (interval * 1000000000.0f);
    }

    public void setScreenWidthAndHeight(int w, int h) {
        mScreenWidth = w;
        mScreenHeight = h;
    }

    public String getContentText() { return nativeGetContentText(); }
    public void handleInsertText(String text) { nativeInsertText(text); }
    public void handleDeleteBackward() { nativeDeleteBackward(); }
    public void handleKeyDown(int keyCode) { nativeKeyEvent(keyCode, true); }
    public void handleKeyUp(int keyCode) { nativeKeyEvent(keyCode, false); }
    public void handleActionDown(int id, float x, float y) { nativeTouchesBegin(id, x, y); }
    public void handleActionMove(int[] ids, float[] xs, float[] ys) { nativeTouchesMove(ids, xs, ys); }
    public void handleActionUp(int id, float x, float y) { nativeTouchesEnd(id, x, y); }
    public void handleActionCancel(int[] ids, float[] xs, float[] ys) { nativeTouchesCancel(ids, xs, ys); }

    public void handleOnPause() {
        if (mNativeInitCompleted) {
            try { Cocos2dxHelper.onEnterBackground(); } catch (Throwable ignored) { }
            nativeOnPause();
        }
    }

    public void handleOnResume() {
        Cocos2dxHelper.onEnterForeground();
        nativeOnResume();
    }

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        // 显式设置 EGL swap interval = 1，确保 eglSwapBuffers 对齐 VSync。
        // 部分设备/驱动默认为 0（无 VSync），导致 60fps 场景下帧率不受控、耗电增加。
        // 收窄到 Exception 以避免吞掉 Error（如 UnsatisfiedLinkError）；
        // 预先判空 display 避免 IllegalArgumentException；
        // 检查返回值并仅记录首次失败，便于排查同时避免日志刷屏，成功时重置告警标记。
        // 成功激活时 mEglSwapIntervalActive=true，onDrawFrame 在 60fps 下依赖 eglSwapBuffers 阻塞；
        // 失败/异常/无 display 时 mEglSwapIntervalActive=false，回退到 parkNanos 兜底避免紧密循环。
        mEglSwapIntervalActive = false;
        android.opengl.EGLDisplay display = EGL14.eglGetCurrentDisplay();
        if (display != null) {
            try {
                boolean ok = EGL14.eglSwapInterval(display, 1);
                if (ok) {
                    mEglSwapIntervalActive = true;
                    mEglSwapIntervalWarned = false;
                } else if (!mEglSwapIntervalWarned) {
                    mEglSwapIntervalWarned = true;
                    Log.w(TAG, "eglSwapInterval(1) returned false, EGL error=0x"
                            + Integer.toHexString(EGL14.eglGetError()));
                }
            } catch (Exception e) {
                if (!mEglSwapIntervalWarned) {
                    mEglSwapIntervalWarned = true;
                    Log.w(TAG, "eglSwapInterval unavailable", e);
                }
            }
        }

        nativeInit(mScreenWidth, mScreenHeight);
        // EGL 上下文重建：Anime4K 的 GL 句柄随旧上下文失效，通知丢弃
        if (com.core.gl.Anime4kRuntime.isEnabled()) {
            com.core.gl.Anime4kPostProcessor.getInstance().onSurfaceCreated();
        }
        mNextFrameDeadline = System.nanoTime() + sAnimationInterval;
        // 跨 EGL 生命周期兜底：60fps + eglSwapInterval 成功场景下 onDrawFrame 不进入 if 块，
        // Thread.interrupted() 永不被调用；此处消费可能残留的中断标志，避免跨 EGL 重建透传。
        Thread.interrupted();
        mNativeInitCompleted = true;
        if (mListener != null) mListener.onNativeReady();
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int width, int height) {
        nativeOnSurfaceChanged(width, height);
        // 尺寸变化 / 上下文重建后（重建路径必然伴随 surfaceChanged）重建 Anime4K GL 资源
        if (com.core.gl.Anime4kRuntime.isEnabled()) {
            com.core.gl.Anime4kPostProcessor.getInstance().onSurfaceChanged(width, height);
        }
    }

    @Override
    public void onDrawFrame(GL10 gl) {
        long interval = sAnimationInterval;

        // 帧率限制：仅在目标帧率低于显示器刷新率时主动等待。
        // 60fps（默认）时由 EGL swap interval=1 对齐 VSync，无需额外等待；
        // 但若 eglSwapInterval 失败（mEglSwapIntervalActive=false，部分 GPU 驱动 / EGL_BAD_MATCH），
        // swap 不再阻塞，RENDERMODE_CONTINUOUSLY 下 GLThread 紧密循环——回退到 parkNanos 兜底。
        if (interval > 16666666L || !mEglSwapIntervalActive) {
            long now = System.nanoTime();
            long waitNanos = mNextFrameDeadline - now;
            boolean interruptedEarly = false;
            while (waitNanos > 0) {
                // LockSupport.parkNanos 精度通常优于 Thread.sleep（ART 实现无绝对保证），
                // 且不抛 InterruptedException（但会响应中断立即返回）。
                // 循环重 park 以应对 spurious wakeup，确保严格对齐 deadline，
                // 避免提前返回导致下一帧 deadline 提前、帧率漂移。
                // 中断检查使用 Thread.interrupted() 重置标志，保留"中断后渲染本帧"语义，
                // 同时避免 isInterrupted() 不重置导致后续每帧永久跳过等待。
                // 不设迭代上限：parkNanos 自带超时，spurious wakeup 仅导致少量重 park 不会失控。
                if (Thread.interrupted()) { interruptedEarly = true; break; }
                LockSupport.parkNanos(waitNanos);
                long after = System.nanoTime();
                if (after >= mNextFrameDeadline) {
                    // parkNanos 可能因中断返回且标志未清；此处 deadline 已到，等待目的已达成，
                    // 主动消费残留标志避免下一帧被误判为 interruptedEarly=true。
                    // 不改变中断语义：合法中断（parkNanos 之前或期间但 deadline 未到）仍从 line 148 进入分支；
                    // 此处仅消除"deadline 已到但标志残留"的边缘场景。
                    Thread.interrupted();
                    break;
                }
                waitNanos = mNextFrameDeadline - after;
            }
            // 帧跳过：如果落后超过 1 帧（如 GC 停顿、系统调度延迟），
            // 重置 deadline 避免"死亡螺旋"（连续多帧无等待追赶导致画面撕裂）。
            // 中断提前退出（interruptedEarly）也走重置分支：afterWait << mNextFrameDeadline 时
            // 若仍走 += interval，下一帧 waitNanos ≈ 原 waitNanos + interval，多等约一个 waitNanos，
            // 造成"一帧过早 + 一帧过晚"的抖动；重新锚定到 afterWait + interval 保持逻辑自洽。
            long afterWait = System.nanoTime();
            if (interruptedEarly || afterWait - mNextFrameDeadline > interval) {
                mNextFrameDeadline = afterWait + interval;
            } else {
                mNextFrameDeadline += interval;
            }
        }

        nativeRender();
        // Anime4K 后处理：引擎帧完成后、GLSurfaceView 隐式 swap 前执行超分链。
        // 未启用时为一次 volatile 读，零 GL 开销；内部失败后自动退化为空操作。
        if (com.core.gl.Anime4kRuntime.isEnabled()) {
            com.core.gl.Anime4kPostProcessor.getInstance().drawFrame();
        }
        if (mListener != null) mListener.onFrameRendered();
    }
}
