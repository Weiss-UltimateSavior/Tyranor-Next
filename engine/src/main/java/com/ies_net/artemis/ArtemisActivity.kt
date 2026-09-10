package com.ies_net.artemis

import android.app.NativeActivity
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.widget.Toast
import com.core.engine.LaunchContract
import com.core.engine.R

open class ArtemisActivity : NativeActivity() {
    companion object {
        init {
            System.loadLibrary("artemis_audio_bridge")
        }
    }

    external fun nativePauseAllSound(): Boolean
    external fun nativeResumeAllSound(): Boolean

    fun DownloadExpansionFiles(value: String) {}

    fun DownloadResource(a: String, b: String, c: String) {}

    external fun EmulateKeyEvent(keyCode: Int, action: Int)

    external fun ExecuteTag(tag: String)

    fun InAppBilling(a: String, b: String, c: Boolean, d: Boolean) {
        OnFinishPurchase(1, "", "", "", "", 1, "")
    }

    external fun OnFinishPurchase(result: Int, a: String, b: String, c: String, d: String, e: Int, f: String)

    external fun OnFinishVideo()

    external fun OnReadyPlayAssetDelivery(a: Int, b: Int, c: Int)

    fun PlayVideo(path: String, offset: Int, length: Int, volume: Int, skip: Int) {
        val intent = Intent(applicationContext, VideoViewActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
        intent.putExtra("PATH", path)
        intent.putExtra("OFFSET", offset)
        intent.putExtra("LENGTH", length)
        intent.putExtra("VOLUME", volume)
        intent.putExtra("SKIP", skip)
        startActivityForResult(intent, 1)
        overridePendingTransition(0, 0)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val keyCode = event.keyCode
        if (event.action == 0) {
            when (keyCode) {
                66 -> EmulateKeyEvent(13, 2)
                59, 60 -> EmulateKeyEvent(115, 2)
                113, 114 -> EmulateKeyEvent(140, 2)
                62 -> EmulateKeyEvent(32, 2)
                21 -> EmulateKeyEvent(37, 2)
                19 -> EmulateKeyEvent(38, 2)
                22 -> EmulateKeyEvent(39, 2)
                20 -> EmulateKeyEvent(40, 2)
                29 -> EmulateKeyEvent(143, 2)
                47 -> EmulateKeyEvent(83, 2)
                40 -> EmulateKeyEvent(76, 2)
                50 -> EmulateKeyEvent(86, 2)
                8 -> EmulateKeyEvent(112, 2)
                9 -> EmulateKeyEvent(113, 2)
                10 -> EmulateKeyEvent(114, 2)
                11 -> EmulateKeyEvent(115, 2)
                12 -> EmulateKeyEvent(116, 2)
                13 -> EmulateKeyEvent(117, 2)
                14 -> EmulateKeyEvent(118, 2)
                15 -> EmulateKeyEvent(119, 2)
                23 -> EmulateKeyEvent(13, 2)
                17 -> EmulateKeyEvent(122, 2)
                18 -> EmulateKeyEvent(140, 2)
                98, 100 -> EmulateKeyEvent(1, 2)
                96 -> EmulateKeyEvent(139, 2)
                97 -> EmulateKeyEvent(32, 2)
                99 -> EmulateKeyEvent(123, 2)
                101 -> EmulateKeyEvent(140, 1)
                102 -> EmulateKeyEvent(83, 2)
                103 -> EmulateKeyEvent(76, 2)
                105 -> EmulateKeyEvent(124, 2)
                106 -> EmulateKeyEvent(143, 2)
            }
        } else if (event.action == 1 && keyCode == 101) {
            EmulateKeyEvent(140, 0)
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, intent: Intent?) {
        if (requestCode == 1) OnFinishVideo()
    }

    override fun onCreate(bundle: Bundle?) {
        super.onCreate(bundle)
        window.addFlags(1024)
        window.addFlags(128)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        val old = this.intent
        if (old == null || intent == null) return
        val oldPath = old.getStringExtra(LaunchContract.PATH)
        val newPath = intent.getStringExtra(LaunchContract.PATH)
        if (oldPath == null || oldPath == newPath || newPath == null) return
        Toast.makeText(this, getString(R.string.engine_another_game_running), Toast.LENGTH_SHORT).show()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = 5894
    }
}
