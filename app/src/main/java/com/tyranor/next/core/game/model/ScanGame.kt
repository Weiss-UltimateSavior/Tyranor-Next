package com.tyranor.next.core.game.model

import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Immutable
import com.tyranor.next.core.engine.EngineType

/** 扫描产出的游戏候选。 */
@Immutable
data class ScanGame(
    val title: String,
    val uri: String,
    val engine: EngineType,
    val launchTarget: String,
    val coverUri: String? = null,
    val coverSource: String? = null,
    val vndbId: String? = null,
    val metadataTitle: String? = null,
    /** 外置 APK 模块内部别名（如 internal.rpgmxp），用于保留同一引擎下的子运行时；Ren'Py 由单游戏版本设置决定，不写该字段。 */
    val externalModuleAlias: String? = null,
    /** 用户通过“启动文件”手动指定的启动入口文件名（相对游戏目录）；null 表示自动。 */
    val launchFile: String? = null,
    /** 最近一次打开时间戳（毫秒），仅最近打开列表展示使用；0 表示未知。 */
    val openTime: Long = 0,
)

/** 简化描述，兼容 SharedPreferences 持久化所需的字段。 */
data class ScannedRoot(
    val uri: String,
    val name: String,
)

/** ScanGame ↔ Intent 序列化助手，供各详情页 Activity（引擎设置/存档/在线补丁）复用，避免重复实现。 */
object ScanGameIntents {
    private const val EXTRA_TITLE = "extra_title"
    private const val EXTRA_URI = "extra_uri"
    private const val EXTRA_ENGINE = "extra_engine"
    private const val EXTRA_LAUNCH_TARGET = "extra_launch_target"
    private const val EXTRA_COVER_URI = "extra_cover_uri"
    private const val EXTRA_COVER_SOURCE = "extra_cover_source"
    private const val EXTRA_VNDB_ID = "extra_vndb_id"
    private const val EXTRA_METADATA_TITLE = "extra_metadata_title"
    private const val EXTRA_EXTERNAL_MODULE_ALIAS = "extra_external_module_alias"

    fun putGame(intent: Intent, game: ScanGame): Intent = intent.apply {
        putExtra(EXTRA_TITLE, game.title)
        putExtra(EXTRA_URI, game.uri)
        putExtra(EXTRA_ENGINE, game.engine.name)
        putExtra(EXTRA_LAUNCH_TARGET, game.launchTarget)
        game.coverUri?.let { putExtra(EXTRA_COVER_URI, it) }
        game.coverSource?.let { putExtra(EXTRA_COVER_SOURCE, it) }
        game.vndbId?.let { putExtra(EXTRA_VNDB_ID, it) }
        game.metadataTitle?.let { putExtra(EXTRA_METADATA_TITLE, it) }
        game.externalModuleAlias?.let { putExtra(EXTRA_EXTERNAL_MODULE_ALIAS, it) }
    }

    fun getGame(intent: Intent): ScanGame? {
        val title = intent.getStringExtra(EXTRA_TITLE) ?: return null
        val uri = intent.getStringExtra(EXTRA_URI) ?: return null
        val engine = runCatching {
            EngineType.valueOf(intent.getStringExtra(EXTRA_ENGINE).orEmpty())
        }.getOrDefault(EngineType.UNKNOWN)
        return ScanGame(
            title = title,
            uri = uri,
            engine = engine,
            launchTarget = intent.getStringExtra(EXTRA_LAUNCH_TARGET).orEmpty(),
            coverUri = intent.getStringExtra(EXTRA_COVER_URI),
            coverSource = intent.getStringExtra(EXTRA_COVER_SOURCE),
            vndbId = intent.getStringExtra(EXTRA_VNDB_ID),
            metadataTitle = intent.getStringExtra(EXTRA_METADATA_TITLE),
            externalModuleAlias = intent.getStringExtra(EXTRA_EXTERNAL_MODULE_ALIAS),
        )
    }
}
