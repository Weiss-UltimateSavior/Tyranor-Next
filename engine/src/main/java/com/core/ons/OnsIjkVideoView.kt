package com.core.ons

import android.content.Context
import android.graphics.SurfaceTexture
import android.util.Log
import android.view.Surface
import android.view.TextureView
import android.view.View
import tv.danmaku.ijk.media.player.IMediaPlayer
import tv.danmaku.ijk.media.player.IjkMediaPlayer
import java.io.FileDescriptor

/**
 * 基于 ijkplayer（ffmpeg 软解）的视频视图，用于播放 ONS 游戏视频。
 *
 * 为什么用 TextureView 而不是 SurfaceView：
 * ONScripter 里 SDL 已经占了一个 SurfaceView。实测两个 SurfaceView 并存时：
 *   1. z 序由系统决定，视频层会被 SDL 层盖住（表现为黑屏有声音）；
 *      setZOrderMediaOverlay 压不住先创建的普通 surface。
 *   2. 尝试隐藏 SDL 层后触发重新布局，视频 surface 拿到畸变尺寸
 *      （日志可见 setBuffersGeometry w=1278,h=959），缓冲区未填满
 *      而露出未初始化内存，表现为白边 / 紫边。
 * TextureView 走普通视图合成，不占独立 surface 层，因此不存在层级竞争，
 * 也不会有 surface 尺寸协商问题。代价是合成开销略高，
 * 但 ONS 视频普遍是 800x600 这类小尺寸，完全够用。
 *
 * 解码格式：ONS 游戏视频多为 MPEG-1 / MPEG-4，系统 MediaPlayer 不支持
 * MPEG-PS 容器（会返回 error(1,-2147483648)），所以固定走 ffmpeg 软解。
 *
 * 线程约定：回调来自 ijk 的消息线程，视图操作内部统一切回主线程。
 */
class OnsIjkVideoView(context: Context) : TextureView(context), TextureView.SurfaceTextureListener {

    /** 播放结束或失败的统一回调。 */
    interface Callback {
        /** 正常播完。 */
        fun onFinished()

        /** 播放失败，what/extra 为 ijk 原始错误码，仅用于日志。 */
        fun onFailed(what: Int, extra: Int)
    }

    private var player: IjkMediaPlayer? = null
    private var callback: Callback? = null
    private var surface: Surface? = null
    private var pendingFd: FileDescriptor? = null
    private var pendingPath: String? = null
    /** surface 就绪前收到的播放请求要缓存，等 onSurfaceTextureAvailable 再开始。 */
    private var surfaceReady = false
    private var startRequested = false
    private var released = false
    /** 保证回调只触发一次，避免上层被通知两次。 */
    private var notified = false
    /**
     * 宿主是否处于前台。只有主线程会写，但解码线程（ijk 消息线程）会在
     * preparedListener 里读取，因此用 @Volatile 保证可见性。
     */
    @Volatile
    private var hostForeground = true
    /** 当前播放器是否已 prepared 完成，供 resumePlayback 判断能否 start。 */
    @Volatile
    private var prepared = false
    private var videoWidth = 0
    private var videoHeight = 0

    init {
        // 不透明：视频铺满整个视图，开启透明合成只会白费性能。
        setOpaque(true)
        surfaceTextureListener = this
    }

    fun setCallback(cb: Callback?) {
        callback = cb
    }

    /** 用文件描述符播放，优先方式：不受 SAF / 作用域存储路径可见性影响。 */
    fun playFd(fd: FileDescriptor) {
        pendingFd = fd
        pendingPath = null
        startRequested = true
        maybeOpen()
    }

    /** 用真实路径播放，作为 fd 不可用时的退路。 */
    fun playPath(path: String) {
        pendingFd = null
        pendingPath = path
        startRequested = true
        maybeOpen()
    }

    private fun maybeOpen() {
        if (!surfaceReady || !startRequested || released) return
        startRequested = false
        openPlayer()
    }

    private fun openPlayer() {
        releasePlayer()
        // p 在 setDataSource 之后才交给 player 字段；此处保留局部引用，
        // 保证任一步骤抛异常时能释放这个已构建的实例（否则只会释放 null）。
        var created: IjkMediaPlayer? = null
        try {
            val p = IjkMediaPlayer()
            created = p

            // 锁定 ffmpeg 软解：MPEG-1 等老容器硬解普遍不支持。
            p.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec", 0L)
            p.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-auto-rotate", 0L)
            p.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-handle-resolution-change", 0L)
            p.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "opensles", 0L)
            // 本地文件不需要网络重连逻辑。
            p.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "reconnect", 0L)
            // 老片源常有轻微损坏，允许丢帧而不是直接报错退出。
            p.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "framedrop", 5L)
            p.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "start-on-prepared", 0L)
            p.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "fflags", "fastseek")
            // 只放开本地协议，避免脚本里塞 URL 造成意外外联。
            p.setOption(
                IjkMediaPlayer.OPT_CATEGORY_FORMAT,
                "protocol_whitelist",
                "file,pipe,fd,crypto,cache,async,data",
            )

            p.setOnPreparedListener(preparedListener)
            p.setOnCompletionListener(completionListener)
            p.setOnErrorListener(errorListener)
            p.setOnVideoSizeChangedListener(videoSizeListener)
            if (surface != null) p.setSurface(surface)
            p.setScreenOnWhilePlaying(true)
            p.setVolume(1f, 1f)

            when {
                pendingFd != null -> p.setDataSource(pendingFd)
                !pendingPath.isNullOrEmpty() -> p.setDataSource(pendingPath)
                else -> {
                    Log.w(TAG, "no data source")
                    notifyFailed(-1, 0)
                    return
                }
            }

            player = p
            // 所有权已转移给 player 字段，异常路径无需再单独释放。
            created = null
            p.prepareAsync()
            Log.i(TAG, "ijk prepareAsync, fd=${pendingFd != null} path=$pendingPath")
        } catch (t: Throwable) {
            Log.e(TAG, "open ijk player failed", t)
            releasePlayerInstance(created)
            notifyFailed(-2, 0)
        }
    }

    private val preparedListener = IMediaPlayer.OnPreparedListener { mp ->
        try {
            val w = mp.videoWidth
            val h = mp.videoHeight
            Log.i(TAG, "ijk prepared ${w}x$h")
            prepared = true
            // 回调来自 ijk 的消息线程，视图操作必须回主线程。
            post { applyVideoSize(w, h) }
            // 宿主已切到后台时不启动：prepareAsync 的完成时机不可控，
            // 无条件 start 会让视频在后台开始播放。等 resumePlayback 再恢复。
            if (hostForeground) {
                mp.start()
            } else {
                Log.i(TAG, "prepared while host is in background; defer start")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "start after prepared failed", t)
            notifyFailed(-3, 0)
        }
    }

    private val completionListener = IMediaPlayer.OnCompletionListener {
        Log.i(TAG, "ijk completed")
        notifyFinished()
    }

    private val errorListener = IMediaPlayer.OnErrorListener { _: IMediaPlayer?, what: Int, extra: Int ->
        Log.e(TAG, "ijk error what=$what extra=$extra")
        notifyFailed(what, extra)
        true
    }

    private val videoSizeListener =
        IMediaPlayer.OnVideoSizeChangedListener { _: IMediaPlayer?, w: Int, h: Int, _: Int, _: Int ->
            post { applyVideoSize(w, h) }
        }

    /** 在主线程更新视频尺寸并重新布局。 */
    private fun applyVideoSize(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        if (w == videoWidth && h == videoHeight) return
        videoWidth = w
        videoHeight = h
        requestLayout()
    }

    /**
     * 宿主进入后台：记录状态并暂停当前播放器。
     * 若此时播放器还在 prepareAsync，preparedListener 会依据 hostForeground 延后 start。
     */
    fun pausePlayback() {
        hostForeground = false
        try {
            val p = player
            if (p != null && p.isPlaying) p.pause()
        } catch (t: Throwable) {
            Log.w(TAG, "pause failed", t)
        }
    }

    /**
     * 宿主回到前台：只恢复已经 prepared 完成的播放器。
     * 未准备完成时不能 start，否则会被 ijk 忽略或抛异常；等 preparedListener 处理。
     */
    fun resumePlayback() {
        hostForeground = true
        try {
            val p = player
            if (prepared && p != null && !p.isPlaying) p.start()
        } catch (t: Throwable) {
            Log.w(TAG, "resume failed", t)
        }
    }

    fun release() {
        released = true
        // 只释放播放器，不主动释放 Surface。
        //
        // 原因：TextureView 的 Surface 是包在 SurfaceTexture 上的，而 SurfaceTexture
        // 归 HWUI 渲染线程所有。在这里主动 release() 会让渲染线程操作已销毁的对象，
        // 实测导致退出游戏时崩溃：
        //   FORTIFY: pthread_mutex_lock called on a destroyed mutex
        //   Fatal signal 6 (SIGABRT) in tid xxxxx (hwuiTask1)
        // 正确时机是 onSurfaceTextureDestroyed 回调——那时系统已保证渲染线程不再使用它。
        releasePlayer()
        // 这里**不能**把 surface 置空：真正的释放发生在 onSurfaceTextureDestroyed，
        // 置空会让该回调拿不到这个包装对象，只能等 GC 才释放 native 资源。
        surfaceReady = false
        // 清掉不再需要的引用，避免本视图被上层长期持有时留着已消费的数据源与回调。
        pendingFd = null
        pendingPath = null
        callback = null
    }

    private fun releasePlayer() {
        val p = player
        player = null
        prepared = false
        releasePlayerInstance(p)
    }

    /** 释放指定实例（可能是尚未交给 [player] 字段的局部实例）。 */
    private fun releasePlayerInstance(p: IjkMediaPlayer?) {
        if (p == null) return
        try {
            p.setOnPreparedListener(null)
            p.setOnCompletionListener(null)
            p.setOnErrorListener(null)
            p.setOnVideoSizeChangedListener(null)
            p.setSurface(null)
            p.reset()
            p.release()
        } catch (t: Throwable) {
            Log.w(TAG, "release player failed", t)
        }
    }

    private fun notifyFinished() {
        if (notified) return
        notified = true
        callback?.onFinished()
    }

    private fun notifyFailed(what: Int, extra: Int) {
        if (notified) return
        notified = true
        callback?.onFailed(what, extra)
    }

    // ==================== TextureView.SurfaceTextureListener ====================

    override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
        surface = Surface(st)
        surfaceReady = true
        Log.i(TAG, "surface texture available ${width}x$height")
        try {
            player?.setSurface(surface)
        } catch (t: Throwable) {
            Log.w(TAG, "attach surface failed", t)
        }
        maybeOpen()
    }

    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {
        // TextureView 尺寸变化由视图系统负责缩放，播放器无需干预。
    }

    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
        surfaceReady = false
        try {
            player?.setSurface(null)
        } catch (t: Throwable) {
            Log.w(TAG, "detach surface failed", t)
        }
        // 这里才是释放 Surface 的正确时机：系统保证渲染线程已不再使用该 SurfaceTexture。
        val s = surface
        surface = null
        if (s != null) {
            try {
                s.release()
            } catch (t: Throwable) {
                Log.w(TAG, "release surface failed", t)
            }
        }
        // 返回 true 表示由系统释放 SurfaceTexture。
        return true
    }

    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {
        // 每帧都会回调，这里不需要做任何事。
    }

    // ==================== 等比缩放 ====================

    /**
     * 按视频原始宽高比做 letterbox，避免 4:3 老片源被拉成全屏变形。
     * 视频尺寸未知时退化为使用父容器给的尺寸。
     */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        var width = View.getDefaultSize(videoWidth, widthMeasureSpec)
        var height = View.getDefaultSize(videoHeight, heightMeasureSpec)
        if (videoWidth > 0 && videoHeight > 0 && width > 0 && height > 0) {
            val videoRatio = videoWidth.toLong() * height
            val viewRatio = width.toLong() * videoHeight
            if (videoRatio > viewRatio) {
                height = width * videoHeight / videoWidth
            } else if (videoRatio < viewRatio) {
                width = height * videoWidth / videoHeight
            }
        }
        setMeasuredDimension(width, height)
    }

    private companion object {
        const val TAG = "OnsIjkVideoView"
    }
}
