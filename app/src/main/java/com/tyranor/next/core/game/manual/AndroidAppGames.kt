package com.tyranor.next.core.game.manual

import android.content.Context
import android.content.Intent
import android.util.Log
import com.tyranor.next.core.engine.EngineType
import com.tyranor.next.core.game.model.ScanGame
import java.util.Locale

/**
 * 手动添加的安卓游戏（[EngineType.ANDROID_APP]）：不参与扫描，从游戏页顶栏「添加游戏 →
 * 添加安卓游戏」选择已安装应用入库，启动时按包名直接跳转该应用。
 *
 * 数据模型零迁移（与 PC 手动添加同思路）：`uri = androidapp://<packageName>` 作为库内唯一键，
 * 包名复用 `launchTarget` 承载（现有列/序列化协议无需变更）。
 */
object AndroidAppGames {

    private const val TAG = "AndroidAppGames"

    /** 库内 uri 前缀；仅用于手动条目识别与查重。 */
    const val URI_PREFIX = "androidapp://"

    /** 设备上带启动入口的应用（应用名 + 包名）。 */
    data class InstalledApp(val label: String, val packageName: String)

    fun uriFor(packageName: String): String = URI_PREFIX + packageName

    /**
     * 是否为安卓游戏条目：以 engine 为准，engine 字段损坏（回退 UNKNOWN）时按 uri 前缀兜底识别，
     * 保证重扫保留的条目在启动/存档判定等处行为一致。
     */
    fun isAndroidApp(game: ScanGame): Boolean =
        game.engine == EngineType.ANDROID_APP || game.uri.startsWith(URI_PREFIX)

    /**
     * 读取设备上所有带启动入口的应用（排除本应用），按应用名排序。
     * 查询失败返回 null（与「设备上确实没有可启动应用」区分，由 UI 决定错误文案）。
     * 依赖 Manifest 的 `<queries>` MAIN/LAUNCHER 可见性声明（Android 11+）。
     */
    fun listLaunchableApps(context: Context): List<InstalledApp>? = runCatching {
        val packageManager = context.packageManager
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        packageManager.queryIntentActivities(launcherIntent, 0)
            .asSequence()
            .mapNotNull { it.activityInfo?.applicationInfo }
            .filter { it.packageName != context.packageName }
            .distinctBy { it.packageName }
            .map {
                InstalledApp(
                    label = packageManager.getApplicationLabel(it).toString().ifBlank { it.packageName },
                    packageName = it.packageName,
                )
            }
            .sortedWith(compareBy({ it.label.lowercase(Locale.getDefault()) }, { it.packageName }))
            .toList()
    }.onFailure {
        Log.w(TAG, "query launchable apps failed", it)
    }.getOrNull()

    /**
     * 从游戏记录解析包名（`launchTarget` 承载）；为空时回退从 `androidapp://` uri 解析，
     * 避免历史/损坏数据导致启动直接失败。
     */
    fun packageNameOf(game: ScanGame): String? =
        game.launchTarget.trim().takeIf { it.isNotEmpty() }
            ?: game.uri.removePrefix(URI_PREFIX).takeIf { it != game.uri && it.isNotBlank() }

    /** 由应用标签与包名构造库记录（标题默认应用名，可后续在详情重命名）。 */
    fun toScanGame(label: String, packageName: String): ScanGame = ScanGame(
        title = label,
        uri = uriFor(packageName),
        engine = EngineType.ANDROID_APP,
        launchTarget = packageName,
    )
}
