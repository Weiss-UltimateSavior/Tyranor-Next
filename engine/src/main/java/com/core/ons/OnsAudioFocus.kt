package com.core.ons

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build

/**
 * 播过场视频时压制引擎音频。
 *
 * 为什么需要：上游 ONScripter_sound.cpp 的 Android 分支只调 playVideoAndroid() 就 return，
 * 没有像其他平台那样执行 Mix_HookMusic(NULL,NULL) + stopSMPEG()，播 op 时游戏 BGM 仍在响。
 * 这是引擎行为，不是集成层 bug。
 *
 * 试过但无效的方案：改 SDLAudioManager.mAudioTrack 音量。
 * 实测 0.7.7 的 SDL 走 AAudio 输出（日志有 AAudioStreamBuilder_openStream），
 * Java 侧那个 AudioTrack 字段是 null。
 *
 * 现在的做法只保留音频焦点这一层：
 * SDLActivity.nativePause() 确实能停掉引擎音频，但同时触发窗口 relayout
 * （实测 2414x1080 → 2161x959），视频层随之拿到畸变尺寸
 * （setBuffersGeometry w=1278,h=959），缓冲区填不满就出现白边紫边，所以不能用。
 */
object OnsAudioFocus {

    private const val TAG = "OnsAudioFocus"

    private var manager: AudioManager? = null
    /** API 26+ 的 AudioFocusRequest 持有引用，abandon 时需要。 */
    private var requestRef: Any? = null
    private var legacyListener: AudioManager.OnAudioFocusChangeListener? = null

    /** 播片开始：申请音频焦点。 */
    @JvmStatic
    fun acquire(context: Context?) {
        requestFocus(context)
    }

    /** 播片结束：释放音频焦点。 */
    @JvmStatic
    fun release() {
        abandonFocus()
    }

    private fun requestFocus(context: Context?) {
        try {
            val ctx = context?.applicationContext ?: return
            if (manager == null) {
                manager = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            }
            val am = manager ?: return

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (requestRef != null) return // 已持有
                val attrs = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build()
                val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(attrs)
                    .setWillPauseWhenDucked(false)
                    .build()
                val result = am.requestAudioFocus(req)
                requestRef = req
                android.util.Log.i(TAG, "audio focus requested, result=$result")
            } else {
                if (legacyListener != null) return
                val listener = AudioManager.OnAudioFocusChangeListener { }
                val result = am.requestAudioFocus(
                    listener,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK,
                )
                legacyListener = listener
                android.util.Log.i(TAG, "audio focus requested (legacy), result=$result")
            }
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "acquire failed", t)
        }
    }

    /** 释放焦点，引擎音频恢复正常音量。 */
    @Suppress("DEPRECATION")
    private fun abandonFocus() {
        try {
            val am = manager ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                (requestRef as? AudioFocusRequest)?.let {
                    am.abandonAudioFocusRequest(it)
                    android.util.Log.i(TAG, "audio focus released")
                }
                requestRef = null
            } else {
                legacyListener?.let {
                    am.abandonAudioFocus(it)
                    android.util.Log.i(TAG, "audio focus released (legacy)")
                }
                legacyListener = null
            }
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "release failed", t)
            requestRef = null
            legacyListener = null
        }
    }
}
