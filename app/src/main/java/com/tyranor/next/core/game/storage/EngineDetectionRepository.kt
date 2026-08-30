package com.tyranor.next.core.game.storage

import android.content.Context
import android.util.Log
import com.core.engine.EnginePrefs
import com.tyranor.next.core.engine.EngineType
import com.tyranor.next.core.game.model.ScanGame
import com.tyranor.next.core.settings.EngineSettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * 自动引擎识别缓存仓库（迁移方案阶段 5，表结构见 4.5）：统一 Artemis / Ren'Py /
 * RPGM / mkxp-z 的识别结果与「记忆 + 指纹失效」逻辑。
 *
 * Artemis 成功版本由引擎子进程写回 prefs（engine 无法访问 Room），本仓库在查询时
 * consume-and-clear 消费归一到 DB，保持 DB 单一事实源；指纹变化即失效重识别。
 */
object EngineDetectionRepository {

    private const val TAG = "EngineDetectRepo"

    private const val ARTEMIS_MEMORY_PREFS = EnginePrefs.APP_PREFS
    private const val KEY_ARTEMIS_ENGINE_PREFIX = "artemis_engine."
    private const val KEY_ARTEMIS_ENGINE_SUCCESS_PREFIX = "artemis_engine_success."

    private const val RPGM_RUNTIME_MKXPZ = "internal.mkxp-z"

    /** Artemis 版本取值归一（原 EngineLauncher 逻辑随迁移收口至此，字面量与 ART_ENGINE_* 一致）。 */
    private fun normalizeArtemisVersion(value: String?): String? =
        when (value?.trim()) {
            EngineSettingsStore.ART_ENGINE_V1, "internal.artemis" -> EngineSettingsStore.ART_ENGINE_V1
            EngineSettingsStore.ART_ENGINE_V2, "internal.artemis.compat" -> EngineSettingsStore.ART_ENGINE_V2
            EngineSettingsStore.ART_ENGINE_V3, "internal.artemis.compat.v2" -> EngineSettingsStore.ART_ENGINE_V3
            EngineSettingsStore.ART_ENGINE_V4, "internal.artemis.v4" -> EngineSettingsStore.ART_ENGINE_V4
            EngineSettingsStore.ART_ENGINE_V5, "internal.artemis.v5" -> EngineSettingsStore.ART_ENGINE_V5
            else -> null
        }

    /**
     * Artemis 自动版本记忆查询：只读消费引擎子进程经 prefs 写回的成功版本（引擎无法访问
     * Room；不删除 prefs 键——app 进程对共享 prefs 的整文件写回会把引擎进程新写的其他键
     * 一并回滚，键残留无害且对回滚版本友好），再按指纹校验 DB 记忆；指纹不一致（游戏更新过）
     * 返回 null 触发重识别。fingerprint 为 null（目录不可读）时按无指纹历史处理。
     */
    suspend fun lookupArtemis(
        context: Context,
        gameUri: String,
        pathHash: String,
        fingerprint: String?,
    ): String? = withContext(Dispatchers.IO) {
        val dao = GameLibraryDatabase.get(context).gameLibraryDao()
        val prefs = context.getSharedPreferences(ARTEMIS_MEMORY_PREFS, Context.MODE_PRIVATE)
        val legacySuccess = prefs.getString(KEY_ARTEMIS_ENGINE_SUCCESS_PREFIX + pathHash, null)
        val legacyAttempt = prefs.getString(KEY_ARTEMIS_ENGINE_PREFIX + pathHash, null)
        val now = System.currentTimeMillis()
        val row0 = dao.getDetectionRow(gameUri)
        val legacySuccessNormalized = normalizeArtemisVersion(legacySuccess)
        // prefs 键不清除（避免 app 侧整文件写回覆盖引擎进程写入的其他键），改为条件导入：
        // 仅首次或引擎记录了与 DB 不同的成功版本（= 引擎刚在当前游戏文件上成功运行过，
        // 用当前指纹入库是安全的）时才导入；否则维持 DB 行，使指纹失效判定保持有效。
        val shouldImport = (legacySuccess != null || legacyAttempt != null) && (
            row0 == null ||
                (legacySuccessNormalized != null && row0.artemisSuccessVersion != legacySuccessNormalized)
            )
        if (shouldImport) {
            val attempt = normalizeArtemisVersion(legacyAttempt)
            dao.upsertDetectionRow(
                EngineDetectionEntity(
                    gameUri = gameUri,
                    engine = EngineType.ARTEMIS.name,
                    fingerprintHash = fingerprint ?: row0?.fingerprintHash,
                    artemisVersion = attempt ?: row0?.artemisVersion,
                    artemisSuccessVersion = legacySuccessNormalized ?: attempt ?: row0?.artemisSuccessVersion,
                    renpyVersion = row0?.renpyVersion,
                    rpgmRuntime = row0?.rpgmRuntime,
                    mkxpzSupported = row0?.mkxpzSupported,
                    confidence = row0?.confidence ?: 0,
                    reason = "engine success memory",
                    updatedAt = now,
                ),
            )
            Log.i(TAG, "Artemis engine memory imported from prefs game=$gameUri success=$legacySuccessNormalized attempt=$attempt")
        }
        val row: EngineDetectionEntity = if (shouldImport) {
            dao.getDetectionRow(gameUri) ?: return@withContext null
        } else {
            row0 ?: return@withContext null
        }
        val remembered = row.artemisSuccessVersion ?: row.artemisVersion ?: return@withContext null
        if (row.fingerprintHash == null || fingerprint == null || row.fingerprintHash == fingerprint) {
            remembered
        } else {
            Log.i(TAG, "Artemis memory invalidated by fingerprint change game=$gameUri")
            null
        }
    }

    /** 启动路径的同步门面：单行读/写 + 一次 prefs 消费，成本与旧版 prefs 查询相当。 */
    fun lookupArtemisBlocking(context: Context, gameUri: String, pathHash: String, fingerprint: String?): String? =
        runBlocking { lookupArtemis(context, gameUri, pathHash, fingerprint) }

    /**
     * 扫描结果入缓存（方案阶段 5 任务 3/4）：Ren'Py 扫描版本建议、RPGM 子运行时
     * （含 mkxp-z 支持判定）。合并写，不触碰同游戏已有的 Artemis 记忆字段。
     */
    suspend fun recordScanDetections(context: Context, games: List<ScanGame>) = withContext(Dispatchers.IO) {
        val dao = GameLibraryDatabase.get(context).gameLibraryDao()
        val now = System.currentTimeMillis()
        val rows = games.mapNotNull { game ->
            val existing = dao.getDetectionRow(game.uri)
            when (game.engine) {
                EngineType.RENPY -> if (game.detectedRenpyVersion == null && existing == null) {
                    null
                } else {
                    mergedRow(existing, game.uri, EngineType.RENPY.name, now, renpyVersion = game.detectedRenpyVersion)
                }
                EngineType.RPGMAKER -> if (game.externalModuleAlias == null && existing == null) {
                    null
                } else {
                    // alias 为 null（如 SAF 查询瞬时失败）时 mkxpz 判定传 null 保留既有值
                    mergedRow(
                        existing, game.uri, EngineType.RPGMAKER.name, now,
                        rpgmRuntime = game.externalModuleAlias,
                        mkxpzSupported = game.externalModuleAlias?.let { it == RPGM_RUNTIME_MKXPZ },
                    )
                }
                else -> null
            }
        }
        rows.forEach { dao.upsertDetectionRow(it) }
    }

    private fun mergedRow(
        existing: EngineDetectionEntity?,
        gameUri: String,
        engine: String,
        now: Long,
        renpyVersion: String? = null,
        rpgmRuntime: String? = null,
        mkxpzSupported: Boolean? = null,
    ): EngineDetectionEntity = EngineDetectionEntity(
        gameUri = gameUri,
        engine = engine,
        fingerprintHash = existing?.fingerprintHash,
        artemisVersion = existing?.artemisVersion,
        artemisSuccessVersion = existing?.artemisSuccessVersion,
        renpyVersion = renpyVersion ?: existing?.renpyVersion,
        rpgmRuntime = rpgmRuntime ?: existing?.rpgmRuntime,
        mkxpzSupported = mkxpzSupported ?: existing?.mkxpzSupported,
        confidence = existing?.confidence ?: 0,
        reason = if (existing == null) "scan" else existing.reason,
        updatedAt = now,
    )
}
