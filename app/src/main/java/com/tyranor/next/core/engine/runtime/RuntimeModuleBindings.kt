package com.tyranor.next.core.engine.runtime

import android.content.Context
import com.tyranor.next.core.engine.EngineType
import com.tyranor.next.core.game.storage.GameLibraryFacade
import com.tyranor.next.core.settings.AppSettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 绑定游戏的卸载/禁用降级。
 *
 * 降级只改 `engine` 与 `runtimeModulePackage`，保留标题/封面/最近时间等既有字段；
 * 不删除条目、不自动重绑。发现查询失败时绝不降级，避免把全部游戏误打成 UNKNOWN。
 */
object RuntimeModuleBindings {

    /**
     * 批量核对库中所有 [EngineType.RUNTIME_MODULE] 条目：
     * 模块不在已发现集合、或不在已启用集合、或包名为空 → 降级为 [EngineType.UNKNOWN]，
     * **保留最后已知的 `runtimeModulePackage`**（供重新启用/重装后重扫恢复，不作为启动依据）。
     *
     * 保留包名而不是清空，是为了让「模块描述暂时失效 / 发现查询失败 / 用户禁用到重装」期间
     * 重扫仍能识别这是运行时条目，不会把整条游戏记录当已删除而丢掉标题/封面/元数据。
     *
     * @return 本次降级的游戏数量；发现查询失败返回 0。
     */
    suspend fun reconcile(context: Context): Int = withContext(Dispatchers.IO) {
        val discovery = RuntimeModuleDiscovery.discover(context)
        if (discovery !is RuntimeModuleDiscoveryResult.Success) return@withContext 0

        val enabledPackages = AppSettingsStore.getEnabledRuntimeModules(context)
        val discoveredPackages = discovery.modules.asSequence().map { it.packageName }.toSet()
        var degraded = 0

        GameLibraryFacade.updateGames(context) { games ->
            var count = 0
            val updated = games.map { game ->
                val packageName = game.runtimeModulePackage
                val unavailable = packageName == null ||
                    packageName.isBlank() ||
                    packageName !in enabledPackages ||
                    packageName !in discoveredPackages
                if (game.engine == EngineType.RUNTIME_MODULE && unavailable) {
                    count++
                    // 只降级引擎，保留最后已知包名；重扫合并会据此保留条目。
                    game.copy(engine = EngineType.UNKNOWN)
                } else {
                    game
                }
            }
            degraded = count
            updated
        }
        if (degraded > 0) GameLibraryFacade.notifyGamesChanged()
        degraded
    }

    /**
     * 只降级指定 URI 的游戏：`engine = UNKNOWN`，保留 `runtimeModulePackage` 作为最后已知绑定。
     * 只处理仍为 [EngineType.RUNTIME_MODULE] 且（可选）包名匹配的条目，避免竞态误伤已改绑的游戏。
     *
     * 缓存未命中时会阻塞读库，调用方应确保在后台线程执行。
     *
     * @return 库中存在该 URI 并已执行降级时返回 true；找不到/已不是运行时条目返回 false。
     */
    fun degradeGame(
        context: Context,
        gameUri: String,
        expectedPackage: String? = null,
    ): Boolean {
        var found = false
        GameLibraryFacade.updateGames(context) { games ->
            games.map { game ->
                val matches = game.uri == gameUri &&
                    game.engine == EngineType.RUNTIME_MODULE &&
                    (expectedPackage == null || game.runtimeModulePackage == expectedPackage)
                if (matches) {
                    found = true
                    game.copy(engine = EngineType.UNKNOWN)
                } else {
                    game
                }
            }
        }
        if (found) GameLibraryFacade.notifyGamesChanged()
        return found
    }
}
