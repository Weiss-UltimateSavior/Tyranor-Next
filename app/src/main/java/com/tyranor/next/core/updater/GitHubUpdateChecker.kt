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

    /** 更新弹窗列表最多展示的版本数。 */
    private const val MAX_CANDIDATES = 3

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
                .map { json ->
                    GitHubRelease(
                        tagName = json.optString("tag_name"),
                        name = json.optString("name"),
                        htmlUrl = json.optString("html_url"),
                        prerelease = json.optBoolean("prerelease", false),
                        assets = json.optJSONArray("assets")
                            ?.let { assets ->
                                (0 until assets.length()).asSequence()
                                    .mapNotNull { index -> assets.optJSONObject(index) }
                                    .mapNotNull { asset ->
                                        val downloadUrl = asset.optString("browser_download_url")
                                        if (downloadUrl.isBlank()) return@mapNotNull null
                                        UpdateAsset(
                                            name = asset.optString("name"),
                                            downloadUrl = downloadUrl,
                                            size = asset.optLong("size", 0L),
                                            digest = asset.optString("digest").takeIf { it.isNotBlank() },
                                        )
                                    }
                                    .toList()
                            }
                            ?: emptyList(),
                    )
                }
                .filter { it.htmlUrl.isNotBlank() }
                .toList()

            // 弹窗列表固定展示最新三个版本（仓库发布顺序，新→旧，不按新旧过滤）；
            // 是否有更新与通知口径则严格取「比当前版本新」的 release，正式版优先。
            val toCandidate: (GitHubRelease) -> UpdateCandidate = { release ->
                UpdateCandidate(
                    latestVersion = versionFromRelease(release),
                    releaseName = release.name.ifBlank { release.tagName },
                    releaseUrl = release.htmlUrl,
                    apkAsset = selectApkAsset(release.assets),
                )
            }
            val newerStable = available.filter { release ->
                !release.prerelease && compareVersions(versionFromRelease(release), currentVersion) > 0
            }
            val newerPre = available.filter { release ->
                release.prerelease && compareVersions(versionFromRelease(release), currentVersion) > 0
            }

            if (newerStable.isEmpty() && newerPre.isEmpty()) {
                UpdateCheckResult.UpToDate(currentVersion)
            } else {
                UpdateCheckResult.UpdateAvailable(
                    currentVersion = currentVersion,
                    // 通知等单版本场景用 primary：最新的新正式版，无则最新的新 prerelease
                    primary = (newerStable.firstOrNull() ?: newerPre.first()).let(toCandidate),
                    candidates = available.take(MAX_CANDIDATES).map(toCandidate),
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

    /**
     * 从 release 的资产列表中选出适合当前设备的 APK：
     * 过滤 .apk → 优先文件名包含设备主 ABI → 次选 universal → 仅剩一个则直接使用。
     */
    private fun selectApkAsset(assets: List<UpdateAsset>): UpdateAsset? {
        val apks = assets.filter { it.name.endsWith(".apk", ignoreCase = true) && it.size > 0 }
        if (apks.isEmpty()) return null
        val primaryAbi = Build.SUPPORTED_ABIS.firstOrNull() ?: return apks.singleOrNull()
        return apks.firstOrNull { it.name.contains(primaryAbi, ignoreCase = true) }
            ?: apks.firstOrNull { it.name.contains("universal", ignoreCase = true) }
            ?: apks.singleOrNull()
    }

    private data class GitHubRelease(
        val tagName: String,
        val name: String,
        val htmlUrl: String,
        val prerelease: Boolean,
        val assets: List<UpdateAsset>,
    )
}

sealed interface UpdateCheckResult {
    data class UpToDate(val currentVersion: String) : UpdateCheckResult

    data class UpdateAvailable(
        val currentVersion: String,
        /** 通知等单版本场景使用的首选候选：最新的新正式版，无新正式版时为最新的新 prerelease。 */
        val primary: UpdateCandidate,
        /** 弹窗列表展示的候选版本（仓库发布顺序，新→旧），固定取最新 [GitHubUpdateChecker.MAX_CANDIDATES] 个。 */
        val candidates: List<UpdateCandidate>,
    ) : UpdateCheckResult

    data class Failed(val message: String) : UpdateCheckResult
}

/** 更新弹窗列表的单个候选版本。 */
data class UpdateCandidate(
    val latestVersion: String,
    val releaseName: String,
    val releaseUrl: String,
    /** 适合当前设备的 APK 资产；null 表示没有可用的 APK 资产，只能跳转浏览器下载。 */
    val apkAsset: UpdateAsset?,
)

/** GitHub Release 的单个资产文件（APK 下载所需的最小信息）。 */
data class UpdateAsset(
    val name: String,
    val downloadUrl: String,
    val size: Long,
    /** GitHub 提供的摘要，形如 "sha256:xxxx"，可能为 null。 */
    val digest: String?,
)
