package com.core.ons

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.RelativeLayout
import android.widget.TextView

import com.core.engine.R

import java.io.File

/**
 * ONS 游戏视频的「窗口内覆盖播放」控制器。
 *
 * 为什么不用独立 Activity：
 * ONScripter 声明为 launchMode="singleInstance" 且有独立 taskAffinity，
 * 启动另一个 Activity 播视频会导致 task 前后台切换，实测后果是
 * SDL 收到 onStop()、播完 finish 后回到的是 MainActivity 而不是游戏本身。
 * 而 native 侧 playVideoAndroid 是 fire-and-forget（ONScripter_sound.cpp
 * 调用后立即返回继续执行脚本），并不需要独立页面来「阻塞等待」。
 *
 * 所以正确做法是把播放器直接 addContentView 盖在 ONScripter 自己的窗口上：
 * 零 Activity 切换、SDL 生命周期不受干扰、播完移除视图即可回到游戏画面。
 *
 * 线程约定：所有公开方法都必须在主线程调用，内部回调也会切回主线程。
 */
class OnsVideoOverlay(private val host: Activity) {

    private val main = Handler(Looper.getMainLooper())

    /**
     * 跳过提示的淡出任务。
     * 必须持有引用：方法引用 `::fadeOutSkipHint` 每次求值都生成新对象，
     * `removeCallbacks(::fadeOutSkipHint)` 无法移除已入队的那个。
     */
    private val fadeOutRunnable = Runnable { fadeOutSkipHint() }

    private var container: FrameLayout? = null
    private var videoView: OnsIjkVideoView? = null
    private var skipHint: TextView? = null
    private var pfd: ParcelFileDescriptor? = null
    private var skippable = true
    /** 防止重复清理。 */
    private var dismissed = false

    /** 当前是否正在播放，供宿主决定按键/触摸事件是否该交给视频层。 */
    fun isPlaying(): Boolean = container != null && !dismissed

    /**
     * 开始播放。
     *
     * @param path      视频真实路径
     * @param skippable 是否允许点击/按键跳过
     * @return true 表示已经接管播放；false 表示无法播放，调用方应当忽略本次请求
     */
    fun play(path: String?, skippable: Boolean): Boolean {
        if (host.isFinishing || host.isDestroyed) return false
        if (path.isNullOrEmpty()) return false

        // 上一段还没结束就来了新的（脚本连播），先收掉旧的再开始。
        if (isPlaying()) dismiss()

        val file = File(path)
        if (!file.isFile || !file.canRead()) {
            Log.w(TAG, "video not readable: $path")
            return false
        }

        this.skippable = skippable
        this.dismissed = false

        return try {
            val root = FrameLayout(host).apply {
                setBackgroundColor(Color.BLACK)
                // 吃掉落在视频层上的触摸，避免穿透到下面的 SDL surface
                // 让游戏在播片时误收到推进文本的点击。
                isClickable = true
                isFocusable = true
            }

            val view = OnsIjkVideoView(host)
            val videoLp = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.CENTER,
            )
            view.setCallback(object : OnsIjkVideoView.Callback {
                override fun onFinished() {
                    Log.i(TAG, "video finished")
                    postDismiss()
                }

                override fun onFailed(what: Int, extra: Int) {
                    // 播不了就直接收场回到游戏，绝不停在黑屏上。
                    Log.w(TAG, "video failed what=$what extra=$extra")
                    postDismiss()
                }
            })
            root.addView(view, videoLp)
            videoView = view

            if (skippable) root.addView(buildSkipHint(), buildSkipHintLp())

            root.setOnTouchListener { _: View, e: MotionEvent ->
                if (this.skippable && e.action == MotionEvent.ACTION_DOWN) {
                    Log.i(TAG, "skipped by touch")
                    postDismiss()
                }
                true
            }

            // 与虚拟按键层同理：SDL 的 content root 是 RelativeLayout，
            // 传泛型 ViewGroup.LayoutParams 会被兜底成 WRAP_CONTENT，
            // 视频层拿不到全屏尺寸。
            host.addContentView(
                root,
                RelativeLayout.LayoutParams(
                    RelativeLayout.LayoutParams.MATCH_PARENT,
                    RelativeLayout.LayoutParams.MATCH_PARENT,
                ),
            )
            container = root

            // 不再隐藏 SDL 的 surface：视频层现在是 TextureView，走普通视图合成，
            // 天然盖在 SDL 的 SurfaceView 之上，没有层级竞争。
            // 之前隐藏 SDL surface 反而触发重新布局，导致视频 surface 拿到畸变尺寸
            // （setBuffersGeometry w=1278,h=959），缓冲区未填满而出现白边/紫边。

            // 优先用 fd：与作用域存储/SAF 场景保持一致，路径不可直接 open 时仍可用。
            val fd = try {
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            } catch (t: Throwable) {
                Log.w(TAG, "open fd failed, fallback to path", t)
                null
            }
            if (fd != null) {
                pfd = fd
                view.playFd(fd.fileDescriptor)
            } else {
                view.playPath(path)
            }
            Log.i(TAG, "overlay playing $path skippable=$skippable")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "start overlay failed", t)
            dismiss()
            false
        }
    }

    /** 用户按键跳过时由宿主调用。 */
    fun skipByKey() {
        if (!isPlaying() || !skippable) return
        Log.i(TAG, "skipped by key")
        postDismiss()
    }

    /** 宿主进入后台时调用：暂停解码，避免无谓耗电与音频抢占。 */
    fun onHostPause() {
        videoView?.pausePlayback()
    }

    /** 宿主回到前台时调用：恢复此前因切后台而暂停的播放。 */
    fun onHostResume() {
        videoView?.resumePlayback()
    }

    /** 移除覆盖层并释放播放器，回到游戏画面。 */
    fun dismiss() {
        if (dismissed) return
        dismissed = true

        // 取消挂起的淡出回调：脚本连播时，上一段遗留的回调会在新提示显示不足
        // 3.5 秒时触发，把新提示提前淡掉。持有 Runnable 引用才能正确移除。
        main.removeCallbacks(fadeOutRunnable)

        videoView?.let {
            try {
                it.release()
            } catch (_: Throwable) {
            }
        }
        videoView = null

        container?.let { root ->
            try {
                (root.parent as? ViewGroup)?.removeView(root)
            } catch (t: Throwable) {
                Log.w(TAG, "remove container failed", t)
            }
        }
        container = null
        skipHint = null

        pfd?.let {
            try {
                it.close()
            } catch (_: Throwable) {
            }
        }
        pfd = null
        Log.i(TAG, "overlay dismissed")
    }

    /**
     * ijk 的回调可能来自解码线程，视图操作必须切回主线程。
     */
    private fun postDismiss() {
        main.post {
            if (host.isFinishing || host.isDestroyed) return@post
            dismiss()
        }
    }

    // ==================== 跳过提示 ====================

    private fun buildSkipHint(): TextView {
        val tv = TextView(host)
        // 引擎模块同样维护 values / values-en / values-ja 三套文案，
        // 不能在这里写死中文（英文/日文环境也要显示对应语言）。
        tv.text = host.getString(R.string.ons_video_skip_hint)
        tv.setTextColor(Color.argb(200, 255, 255, 255))
        tv.textSize = 13f
        val padH = dp(12)
        val padV = dp(6)
        tv.setPadding(padH, padV, padH, padV)
        val bg = GradientDrawable().apply {
            cornerRadius = dp(14).toFloat()
            setColor(Color.argb(110, 0, 0, 0))
        }
        tv.background = bg
        skipHint = tv
        // 播放几秒后淡出，避免一直压在画面上影响观看。
        main.postDelayed(fadeOutRunnable, SKIP_HINT_FADE_DELAY_MS)
        return tv
    }

    private fun buildSkipHintLp(): FrameLayout.LayoutParams {
        val lp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.END or Gravity.BOTTOM,
        )
        lp.rightMargin = dp(18)
        lp.bottomMargin = dp(18)
        return lp
    }

    private fun fadeOutSkipHint() {
        val tv = skipHint ?: return
        if (dismissed) return
        try {
            tv.animate()
                .alpha(0f)
                .setDuration(SKIP_HINT_FADE_DURATION_MS)
                .withEndAction {
                    if (tv.parent != null) tv.visibility = View.GONE
                }
                .start()
        } catch (t: Throwable) {
            tv.visibility = View.GONE
        }
    }

    private fun dp(v: Int): Int = Math.round(v * host.resources.displayMetrics.density)

    private companion object {
        const val TAG = "OnsVideoOverlay"
        const val SKIP_HINT_FADE_DELAY_MS = 3500L
        const val SKIP_HINT_FADE_DURATION_MS = 600L
    }
}