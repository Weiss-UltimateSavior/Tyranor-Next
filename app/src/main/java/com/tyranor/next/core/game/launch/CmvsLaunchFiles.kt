package com.tyranor.next.core.game.launch

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import com.tyranor.next.core.game.model.CmvsPaths
import com.tyranor.next.core.game.model.ScanGame
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * CMVS launch-side file contract.  The engine only reads the top-level
 * `filesDir/launch.cfg`; the App is the sole writer and uses an atomic rename.
 */
object CmvsLaunchFiles {
    private const val MAX_KEY_CFG_BYTES = 65537
    private const val TOKEN_PREFIX = "token="

    fun ensureFont(context: Context, configured: String?): String? =
        CmvsPaths.validatedFont(context.filesDir, configured.orEmpty())

    fun write(
        context: Context,
        game: ScanGame,
        gameDirectory: File,
        fontPath: String,
        fontFace: Int,
        token: String,
    ): Boolean {
        if (token.isBlank() || token.any { it == '\n' || it == '\r' }) return false
        val filesDir = context.filesDir
        val font = File(fontPath).canonicalFile
        if (!font.startsWith(filesDir.canonicalFile)) {
            Log.e("CmvsLaunch", "write: font outside filesDir font=${font.absolutePath}")
            return false
        }
        val relativeFont = font.relativeTo(filesDir.canonicalFile).invariantSeparatorsPath
        if (relativeFont.startsWith("../") || relativeFont.contains("..")) {
            Log.e("CmvsLaunch", "write: font not under filesDir relative=$relativeFont")
            return false
        }
        val gamePath = gameDirectory.absolutePath
        if (gamePath.any { it == '\n' || it == '\r' }) {
            Log.e("CmvsLaunch", "write: game path contains newline")
            return false
        }
        val user = CmvsPaths.userDataDir(filesDir, game.uri).absoluteFile
        if (!user.isDirectory && !user.mkdirs()) return false
        if (user.absolutePath.any { it == '\n' || it == '\r' }) return false
        if (fontFace !in 0..1024) return false
        val config = buildString {
            append("game=").append(gamePath).append('\n')
            append("user=").append(user.absolutePath).append('\n')
            append("font=").append(relativeFont).append('\n')
            append("face=").append(fontFace).append('\n')
            append(TOKEN_PREFIX).append(token).append('\n')
        }
        val target = CmvsPaths.launchConfig(filesDir)
        val temporary = File(filesDir, "launch.cfg.tmp")
        return try {
            temporary.writeText(config, Charsets.UTF_8)
            val installed = temporary.renameTo(target) || run {
                target.delete()
                temporary.renameTo(target)
            }
            if (!installed || !target.isFile || target.length() != config.toByteArray(StandardCharsets.UTF_8).size.toLong()) {
                Log.e("CmvsLaunch", "write: atomic rename failed target=$target")
                return false
            }
            File(filesDir, "launch-error.txt").delete()
            true
        } catch (t: Exception) {
            Log.e("CmvsLaunch", "write failed tmp=${temporary.absolutePath}", t)
            false
        } finally {
            temporary.delete()
        }
    }

    fun isRunning(context: Context): Boolean {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return false
        val expected = context.packageName + ":cmvs"
        return try {
            manager.runningAppProcesses.orEmpty().any {
                it.processName == expected && it.uid == android.os.Process.myUid()
            }
        } catch (_: Exception) {
            false
        }
    }
}

/** Launch-time validation that does not require the native engine. */
object CmvsPreflight {
    fun check(
        context: Context,
        game: ScanGame,
        gameDirectory: File,
        fontPath: String?,
        fontFace: Int,
    ): LaunchResult.Failure? {
        val data = File(gameDirectory, "data")
        val pack = File(data, "pack")
        if (!data.isDirectory || !pack.isDirectory) return LaunchResult.Failure.CmvsGameLayoutInvalid
        if (!File(pack, "script.cpz").isFile) return LaunchResult.Failure.CmvsGameLayoutInvalid
        val starts = listOf("start.ps3", "start.ps2").map { File(pack, it) }.filter { it.isFile }
        if (starts.size != 1) return LaunchResult.Failure.CmvsGameLayoutInvalid
        val keyConfig = File(gameDirectory, "key.cfg")
        if (!keyConfig.isFile) return LaunchResult.Failure.CmvsGameLayoutInvalid
        if (keyConfig.length() > 65537L) return LaunchResult.Failure.CmvsGameLayoutInvalid
        val validUtf8 = try {
            val bytes = keyConfig.readBytes()
            if (bytes.size > 65537) return LaunchResult.Failure.CmvsGameLayoutInvalid
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
            true
        } catch (_: Exception) {
            false
        }
        if (!validUtf8) return LaunchResult.Failure.CmvsGameLayoutInvalid
        if (fontPath.isNullOrBlank() || !File(fontPath).isFile) {
            return LaunchResult.Failure.CmvsFontUnavailable
        }
        if (fontFace !in 0..1024) return LaunchResult.Failure.CmvsFontUnavailable
        val user = CmvsPaths.userDataDir(context.filesDir, game.uri)
        if (!user.isDirectory && !user.mkdirs()) {
            return LaunchResult.Failure.CmvsUserDataDirUnavailable
        }
        val hasVulkan = context.packageManager.hasSystemFeature(
            PackageManager.FEATURE_VULKAN_HARDWARE_LEVEL,
        )
        if (!hasVulkan) return LaunchResult.Failure.CmvsVulkanUnavailable
        return null
    }
}
