package com.core.cmvs

import android.app.AlertDialog
import android.app.NativeActivity
import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Bundle
import android.os.Process
import android.text.InputType
import android.view.KeyEvent
import android.view.SurfaceHolder
import android.view.WindowManager
import android.widget.EditText
import androidx.annotation.Keep
import com.core.engine.DoubleBackExit
import com.core.engine.EngineSurfaceStartupController
import com.core.engine.LaunchContract
import com.core.engine.R
import com.core.nativeplugin.NativeRuntimePack
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * CMVS NativeActivity host.
 *
 * The App process writes the top-level `filesDir/launch.cfg` and starts this
 * Activity. The loader (`libcmvs_loader.so`) calls [shouldStartCmvsNative] and
 * [resolveCmvsEngineLibraryPath] from ANativeActivity_onCreate; the real
 * `libcmvs_android.so` is dlopen()ed only after both checks pass.
 */
class CmvsActivity : NativeActivity() {
    override fun setRequestedOrientation(requestedOrientation: Int) {
        super.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)
    }

    @Volatile private var startupAllowed = false
    @Volatile private var choosingFont = false
    private var startupController: EngineSurfaceStartupController? = null
    @Volatile private var startupSurfaceHolder: SurfaceHolder? = null

    @Keep
    @Synchronized
    fun resolveCmvsEngineLibraryPath(): String? =
        NativeRuntimePack.libraryPath(this, NativeRuntimePack.PACK_CMVS, "libcmvs_android.so")

    @Keep
    fun shouldStartCmvsNative(): Boolean = startupAllowed

    override fun onCreate(savedInstanceState: Bundle?) {
        val launchConfig = File(filesDir, "launch.cfg")
        val expectedToken = intent?.getStringExtra(LaunchContract.CMVS_LAUNCH_TOKEN).orEmpty()
        val actualToken = readLaunchToken()
        startupAllowed = launchConfig.isFile &&
            expectedToken.isNotEmpty() &&
            expectedToken == actualToken &&
            resolveCmvsEngineLibraryPath() != null
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (startupAllowed) {
            startupController = EngineSurfaceStartupController(this).also {
                it.attachToWindow()
                startupSurfaceHolder?.takeIf { holder -> holder.surface.isValid }
                    ?.let { holder -> it.onNativeSurfaceCreated(holder) }
            }
        }
        if (!startupAllowed) {
            // The App process reads launch-error.txt and surfaces the typed failure.
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        startupController?.onResume()
    }

    override fun onPause() {
        startupController?.onPause()
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        startupController?.onWindowFocusChanged(hasFocus)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        startupSurfaceHolder = holder
        super.surfaceCreated(holder)
        startupController?.onNativeSurfaceCreated(holder)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        startupSurfaceHolder = holder
        super.surfaceChanged(holder, format, width, height)
        startupController?.onNativeSurfaceChanged(holder)
    }

    override fun surfaceRedrawNeeded(holder: SurfaceHolder) {
        startupSurfaceHolder = holder
        super.surfaceRedrawNeeded(holder)
        startupController?.onNativeSurfaceRedrawNeeded(holder)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        if (startupSurfaceHolder === holder) startupSurfaceHolder = null
        super.surfaceDestroyed(holder)
        startupController?.onNativeSurfaceDestroyed(holder)
    }

    private fun readLaunchToken(): String? {
        val config = File(filesDir, "launch.cfg")
        if (!config.isFile || config.length() > 65537L) return null
        return try {
            config.readLines(StandardCharsets.UTF_8)
                .firstOrNull { it.startsWith("token=") }
                ?.substringAfter("token=")?.trim()?.takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            null
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                if (DoubleBackExit.shouldExit(this)) finish() else toastBackAgain()
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onBackPressed() {
        if (DoubleBackExit.shouldExit(this)) super.onBackPressed() else toastBackAgain()
    }

    private fun toastBackAgain() {
        android.widget.Toast.makeText(
            this,
            getString(R.string.engine_cmvs_back_again_to_exit),
            android.widget.Toast.LENGTH_SHORT,
        ).show()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        android.widget.Toast.makeText(
            this,
            getString(R.string.engine_another_game_running),
            android.widget.Toast.LENGTH_SHORT,
        ).show()
    }

    /** Called by the engine on the Java main thread for the in-game 0x213d font dialog. */
    @Keep
    fun chooseFont() {
        if (choosingFont || isFinishing || isDestroyed) return
        choosingFont = true
        val pick = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivityForResult(pick, REQUEST_FONT)
        } catch (_: Exception) {
            answer("cancel\n")
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_FONT) return
        if (isFinishing || isDestroyed) return
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) {
            answer("cancel\n")
            return
        }
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText("0")
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.engine_cmvs_font_index_title)
            .setMessage(R.string.engine_cmvs_font_index_message)
            .setView(input)
            .setNegativeButton(android.R.string.cancel) { _, _ -> answer("cancel\n") }
            .setOnCancelListener { answer("cancel\n") }
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val index = input.text.toString().toIntOrNull()
                if (index == null || index !in 0..1024) {
                    answer("cancel\n")
                } else {
                    copyFontAsync(uri, index)
                }
            }
            .show()
    }

    private fun copyFontAsync(uri: Uri, index: Int) {
        Thread({
            val path = try {
                importFont(uri)
            } catch (_: Exception) {
                null
            }
            answer(if (path == null) "cancel\n" else "$path\n$index\n")
        }, "cmvs-font-import").start()
    }

    private fun importFont(uri: Uri): String? {
        val directory = File(filesDir, "fonts")
        if (!directory.isDirectory && !directory.mkdirs()) return null
        val target = File(directory, "cmvs-${UUID.randomUUID()}.ttf")
        try {
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(target).use { output ->
                    val buffer = ByteArray(256 * 1024)
                    var total = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (total == 0L && !looksLikeFont(buffer, count)) {
                            target.delete()
                            return null
                        }
                        total += count
                        if (total > MAX_FONT_BYTES) throw IllegalArgumentException("font too large")
                        output.write(buffer, 0, count)
                    }
                    output.fd.sync()
                }
            } ?: return null
            return "fonts/${target.name}"
        } catch (_: Exception) {
            target.delete()
            return null
        }
    }

    /** Accept scaler tag 0x00010000 / 'true', 'ttcf' collections, and CFF/OpenType 'OTTO'. */
    private fun looksLikeFont(buffer: ByteArray, count: Int): Boolean {
        if (count < 4) return false
        return (buffer[0].toInt() == 0 && buffer[1].toInt() == 1 && buffer[2].toInt() == 0 && buffer[3].toInt() == 0) ||
            (buffer[0] == 't'.code.toByte() && buffer[1] == 'r'.code.toByte() &&
                buffer[2] == 'u'.code.toByte() && buffer[3] == 'e'.code.toByte()) ||
            (buffer[0] == 't'.code.toByte() && buffer[1] == 't'.code.toByte() &&
                buffer[2] == 'c'.code.toByte() && buffer[3] == 'f'.code.toByte()) ||
            (buffer[0] == 'O'.code.toByte() && buffer[1] == 'T'.code.toByte() &&
                buffer[2] == 'T'.code.toByte() && buffer[3] == 'O'.code.toByte())
    }

    private fun answer(value: String) {
        try {
            val temporary = File(filesDir, "font-result.tmp")
            temporary.writeText(value, StandardCharsets.UTF_8)
            val target = File(filesDir, "font-result.cfg")
            val installed = temporary.renameTo(target) || run {
                target.delete()
                temporary.renameTo(target)
            }
            if (!installed) {
                target.delete()
            }
        } catch (_: Exception) {
            // The engine treats a missing result as pending; process teardown cancels it.
        } finally {
            choosingFont = false
        }
    }

    override fun onDestroy() {
        choosingFont = false
        try {
            startupController?.dispose()
            startupController = null
            startupSurfaceHolder = null
            super.onDestroy()
        } finally {
            Process.killProcess(Process.myPid())
        }
    }

    private companion object {
        const val REQUEST_FONT = 0x434d
        const val MAX_FONT_BYTES = 32L * 1024L * 1024L
    }
}
