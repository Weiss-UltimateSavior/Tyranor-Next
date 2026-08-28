package com.tyranor.next.core.updater

import android.content.Context
import android.content.pm.PackageInfo
import android.os.Build
import com.tyranor.next.R
import com.tyranor.next.core.i18n.AppLocaleController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL

object GitHubUpdateChecker {
    private const val RELEASES_API =
        "https://api.github.com/repos/Weiss-UltimateSavior/Tyranor-Next/releases"

    suspend fun check(context: Context): UpdateCheckResult = withContext(Dispatchers.IO) {
        runCatching {
            val app = context.applicationContext
            val packageInfo = app.packageInfoCompat(app.packageName)
            val currentVersion = packageInfo.versionName ?: "0"
            val releases = fetchReleases(context)

            val available = (0 until releases.length())
                .asSequence()
                .mapNotNull { index -> releases.optJSONObject(index) }
                .filterNot { it.optBoolean("draft", false) }
                .map {
                    GitHubRelease(
                        tagName = it.optString("tag_name"),
                        name = it.optString("name"),
                        htmlUrl = it.optString("html_url"),
                        prerelease = it.optBoolean("prerelease", false),
                    )
                }
                .filter { it.htmlUrl.isNotBlank() }
                .toList()

            // 优先正式版；仅当没有更新的正式版时才考虑 prerelease（beta）。
            val latest = available.firstOrNull { release ->
                !release.prerelease && compareVersions(versionFromRelease(release), currentVersion) > 0
            } ?: available.firstOrNull { release ->
                release.prerelease && compareVersions(versionFromRelease(release), currentVersion) > 0
            }

            if (latest == null) {
                UpdateCheckResult.UpToDate(currentVersion)
            } else {
                UpdateCheckResult.UpdateAvailable(
                    currentVersion = currentVersion,
                    latestVersion = versionFromRelease(latest),
                    releaseName = latest.name.ifBlank { latest.tagName },
                    releaseUrl = latest.htmlUrl,
                    prerelease = latest.prerelease,
                )
            }
        }.getOrElse { error ->
            val localizedContext = AppLocaleController.wrap(context)
            UpdateCheckResult.Failed(error.message ?: localizedContext.getString(R.string.update_network_failed))
        }
    }

    private fun Context.packageInfoCompat(packageName: String): PackageInfo {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageInfo(packageName, android.content.pm.PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, 0)
        }
    }

    private fun fetchReleases(context: Context): JSONArray {
        val connection = (URL(RELEASES_API).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 10_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "TyranorNext")
        }
        return try {
            val stream = if (connection.responseCode in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream ?: connection.inputStream
            }
            stream.bufferedReader().use { reader ->
                val body = reader.readText()
                if (connection.responseCode !in 200..299) {
                    error(AppLocaleController.wrap(context).getString(R.string.update_github_request_failed, connection.responseCode))
                }
                JSONArray(body)
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun versionFromRelease(release: GitHubRelease): String {
        return release.tagName
            .removePrefix("refs/tags/")
            .removePrefix("beta-")
            .removePrefix("v")
            .ifBlank { release.name }
    }

    private fun compareVersions(left: String, right: String): Int {
        val leftParts = Regex("\\d+").findAll(left).map { it.value.toIntOrNull() ?: 0 }.toList()
        val rightParts = Regex("\\d+").findAll(right).map { it.value.toIntOrNull() ?: 0 }.toList()
        val max = maxOf(leftParts.size, rightParts.size)
        for (index in 0 until max) {
            val l = leftParts.getOrElse(index) { 0 }
            val r = rightParts.getOrElse(index) { 0 }
            if (l != r) return l.compareTo(r)
        }
        return 0
    }

    private data class GitHubRelease(
        val tagName: String,
        val name: String,
        val htmlUrl: String,
        val prerelease: Boolean,
    )
}

sealed interface UpdateCheckResult {
    data class UpToDate(val currentVersion: String) : UpdateCheckResult

    data class UpdateAvailable(
        val currentVersion: String,
        val latestVersion: String,
        val releaseName: String,
        val releaseUrl: String,
        val prerelease: Boolean,
    ) : UpdateCheckResult

    data class Failed(val message: String) : UpdateCheckResult
}
