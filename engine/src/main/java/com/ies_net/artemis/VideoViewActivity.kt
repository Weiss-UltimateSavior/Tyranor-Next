package com.ies_net.artemis

import tv.danmaku.ijk.media.player.IMediaPlayer
import tv.danmaku.ijk.media.player.widget.IjkVideoView
import android.app.Activity
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.AssetFileDescriptor
import android.graphics.Color
import android.media.AudioManager
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.LinearLayout
import java.io.File
import java.io.RandomAccessFile

class VideoViewActivity : Activity(), IMediaPlayer.OnCompletionListener, IMediaPlayer.OnErrorListener {
    private val TAG = "ArtemisVideo"
    private var assetFileDescriptor: AssetFileDescriptor? = null
    private var randomAccessFile: RandomAccessFile? = null
    private var pausedBySystem = false
    private var skip = 0
    private var videoView: IjkVideoView? = null

    override fun setRequestedOrientation(requestedOrientation: Int) {
        super.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event != null && isSystemVolumeKey(event.keyCode)) return super.dispatchKeyEvent(event)
        if (event != null && event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_BACK && skip == 0) return true
        return super.dispatchKeyEvent(event)
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(0, 0)
    }

    override fun onCompletion(player: IMediaPlayer?) {
        Log.i(TAG, "IJK onCompletion")
        closeSources()
        setResult(RESULT_OK, Intent())
        finish()
    }

    override fun onError(player: IMediaPlayer?, what: Int, extra: Int): Boolean {
        Log.e(TAG, "IJK onError what=$what extra=$extra")
        closeSources()
        setResult(RESULT_CANCELED, Intent())
        finish()
        return true
    }

    override fun onCreate(bundle: Bundle?) {
        super.onCreate(bundle)
        // IJK 三件套来自运行库包；缺失时直接结束，避免在播放器构造期抛 UnsatisfiedLinkError。
        if (!com.core.nativeplugin.IjkRuntime.load(this)) {
            finish()
            return
        }
        setVolumeControlStream(AudioManager.STREAM_MUSIC)
        enterFullscreen()
        pausedBySystem = false
        val intent = this.intent
        val rawPath = intent?.getStringExtra("PATH")
        val gameDir = intent?.getStringExtra("GAME_DIR")
        val offset = intent?.getIntExtra("OFFSET", intent.getIntExtra("A", 0)) ?: 0
        val length = intent?.getIntExtra("LENGTH", intent.getIntExtra("B", 0)) ?: 0
        val volume = intent?.getIntExtra("VOLUME", intent.getIntExtra("C", 0)) ?: 0
        skip = intent?.getIntExtra("SKIP", intent.getIntExtra("D", 0)) ?: 0
        val path = resolvePath(rawPath, gameDir)
        Log.i(TAG, "IJK VideoViewActivity onCreate rawPath=$rawPath path=$path offset=$offset length=$length volume=$volume skip=$skip")
        if (path == null || path.trim().isEmpty()) {
            setResult(RESULT_CANCELED, Intent())
            finish()
            return
        }
        val root = LinearLayout(this)
        root.setBackgroundColor(Color.rgb(0, 0, 0))
        root.gravity = 17
        setContentView(root, WindowManager.LayoutParams(-1, -1))
        videoView = IjkVideoView(this)
        videoView?.setZOrderOnTop(true)
        videoView?.requestFocus()
        videoView?.setOnCompletionListener(this)
        videoView?.setOnErrorListener(this)
        root.addView(videoView)
        try {
            openVideo(path, offset, length, volume)
            videoView?.start()
        } catch (t: Throwable) {
            Log.e(TAG, "IJK openVideo failed", t)
            setResult(RESULT_CANCELED, Intent())
            finish()
        }
    }

    private fun openVideo(path: String, offset: Int, length: Int, volume: Int) {
        closeSources()
        try {
            val file = File(path)
            randomAccessFile = RandomAccessFile(file, "r")
            val fd = randomAccessFile!!.fd
            Log.i(TAG, "IJK open file exists=${file.exists()} size=${file.length()} offset=$offset length=$length")
            videoView?.setDataSource(fd, volume)
        } catch (fileError: Throwable) {
            Log.w(TAG, "IJK open as file failed, try assets: $path", fileError)
            closeSources()
            assetFileDescriptor = assets.openFd(path)
            val fd = assetFileDescriptor!!.fileDescriptor
            videoView?.setDataSource(fd, volume)
        }
    }

    private fun resolvePath(raw: String?, gameDir: String?): String? {
        if (raw == null) return null
        var p = raw.trim()
        if (p.startsWith("file://")) p = p.substring("file://".length)
        if (p.startsWith("content://")) return p
        var f = File(p)
        if (!f.isAbsolute && gameDir != null && gameDir.trim().isNotEmpty()) f = File(gameDir, p)
        return f.path
    }

    override fun onPause() {
        super.onPause()
        pausedBySystem = true
        try { videoView?.pause() } catch (_: Throwable) {}
    }

    override fun onResume() {
        super.onResume()
        enterFullscreen()
        if (pausedBySystem) {
            pausedBySystem = false
            setResult(RESULT_CANCELED, Intent())
            finish()
        }
    }

    override fun onDestroy() {
        closeSources()
        super.onDestroy()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (skip == 0) return super.onTouchEvent(event)
        val action = event.action
        if ((action == MotionEvent.ACTION_DOWN && skip <= 1) || (action == MotionEvent.ACTION_POINTER_DOWN && skip <= 2)) {
            onCompletion(null)
        }
        return super.onTouchEvent(event)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterFullscreen()
    }

    private fun closeSources() {
        try { randomAccessFile?.close() } catch (_: Throwable) {}
        randomAccessFile = null
        try { assetFileDescriptor?.close() } catch (_: Throwable) {}
        assetFileDescriptor = null
    }

    private fun isSystemVolumeKey(keyCode: Int): Boolean {
        return keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_MUTE || keyCode == KeyEvent.KEYCODE_MUTE
    }

    private fun enterFullscreen() {
        try {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = 5894
        } catch (_: Throwable) {}
    }
}
