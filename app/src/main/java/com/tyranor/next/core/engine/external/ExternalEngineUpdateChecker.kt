package com.tyranor.next.core.engine.external

import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import com.tyranor.next.core.net.httpJson
import com.tyranor.next.core.net.optNonBlankString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/** GitHub 项目及正式发布的标签前缀，标签去掉前缀后须与 APK 的数字版本名一致。 */
data class ExternalEngineReleaseSource(val repository: String, val tagPrefix: String = "") {
    val releasesUrl: String get() = "https://github.com/$repository/releases"
}

data class ExternalEngineUpdate(
    val installedVersion: String,
    val latestVersion: String,
    val releasesUrl: String,
)

object ExternalEngineUpdateChecker {
    private const val RELEASE_CACHE_MS = 30 * 60_000L
    private const val RETRY_DELAY_MS = 60_000L
    private val numericVersion = Regex("[0-9]+(?:\\.[0-9]+)*")
    private val cache = ConcurrentHashMap<ExternalEngineReleaseSource, CachedRelease>()

    private class CachedRelease {
        val lock = Mutex()
        var version: String? = null
        var refreshAfter = 0L
    }

    /** 返回已安装模块可下载的较新正式版。安装状态每次重读，发布结果由所有入口共享。 */
    suspend fun check(context: Context, module: ExternalEngineModule): ExternalEngineUpdate? {
        val source = module.updateSource ?: return null
        return withContext(Dispatchers.IO) {
            val installed = try {
                ExternalEngineLauncher.packageInfo(context, module).versionName
            } catch (_: PackageManager.NameNotFoundException) {
                return@withContext null
            } ?: return@withContext null
            val latest = latestVersion(source) ?: return@withContext null
            if (isNewerVersion(latest, installed)) {
                ExternalEngineUpdate(installed, latest, source.releasesUrl)
            } else null
        }
    }

    private suspend fun latestVersion(source: ExternalEngineReleaseSource): String? {
        val cached = cache.computeIfAbsent(source) { CachedRelease() }
        return cached.lock.withLock {
            if (SystemClock.elapsedRealtime() >= cached.refreshAfter) {
                val release = httpJson(
                    "https://api.github.com/repos/${source.repository}/releases/latest",
                    headers = mapOf("Accept" to "application/vnd.github+json"),
                )
                if (release != null) cached.version = versionFromRelease(source, release)
                val delay = if (release == null) RETRY_DELAY_MS else RELEASE_CACHE_MS
                cached.refreshAfter = SystemClock.elapsedRealtime() + delay
            }
            cached.version
        }
    }

    internal fun versionFromRelease(source: ExternalEngineReleaseSource, release: JSONObject): String? {
        if (release.optBoolean("draft") || release.optBoolean("prerelease")) return null
        val tag = release.optNonBlankString("tag_name") ?: return null
        if (!tag.startsWith(source.tagPrefix)) return null
        val version = tag.removePrefix(source.tagPrefix)
        if (!numericVersion.matches(version)) return null
        val assets = release.optJSONArray("assets") ?: return null
        return version.takeIf {
            (0 until assets.length()).any { index ->
                assets.optJSONObject(index)?.optNonBlankString("name")?.endsWith(".apk", ignoreCase = true) == true
            }
        }
    }

    internal fun isNewerVersion(candidate: String, installed: String): Boolean {
        val latest = versionParts(candidate) ?: return false
        val current = versionParts(installed) ?: return false
        for (index in 0 until maxOf(latest.size, current.size)) {
            val difference = (latest.getOrNull(index) ?: 0).compareTo(current.getOrNull(index) ?: 0)
            if (difference != 0) return difference > 0
        }
        return false
    }

    private fun versionParts(version: String): List<Int>? {
        if (!numericVersion.matches(version)) return null
        return version.split('.').map { it.toIntOrNull() ?: return null }
    }
}
