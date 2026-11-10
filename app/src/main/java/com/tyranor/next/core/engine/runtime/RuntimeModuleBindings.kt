package com.tyranor.next.core.engine.runtime

import android.content.Context
import com.tyranor.next.core.engine.EngineType
import com.tyranor.next.core.game.model.ScanGame
import com.tyranor.next.core.game.storage.GameLibraryFacade
import com.tyranor.next.core.settings.AppSettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 绑定游戏的卸载/禁用降级。
 *
 * 降级只改 `engine` 与 `runtimeModulePackage`，保留标题/封面/最近时间等既有字段；
 * 重扫只恢复已重新启用的原包名。发现查询失败时保留第三方绑定。
 */
object RuntimeModuleBindings {
    internal fun builtInSystemNnn(game: ScanGame): ScanGame =
        if (game.runtimeModulePackage == "com.tyranor.runtime.systemnnn" &&
            (game.engine == EngineType.RUNTIME_MODULE || game.engine == EngineType.UNKNOWN || game.engine == EngineType.SYSTEMNNN)
        ) {
            game.copy(engine = EngineType.SYSTEMNNN, runtimeModulePackage = null)
        } else game

    /** Read the old Emberfall identity as built-in until an explicit reconciliation persists it. */
    internal fun loadLibraryWithBuiltInSystemNnn(context: Context): List<ScanGame> =
        GameLibraryFacade.loadGames(context).map(::builtInSystemNnn)

    /**
     * 批量核对库中所有 [EngineType.RUNTIME_MODULE] 条目：
     * 模块不在已发现集合、或不在已启用集合、或包名为空 → 降级为 [EngineType.UNKNOWN]，
     * **保留最后已知的 `runtimeModulePackage`**（供重新启用/重装后重扫恢复，不作为启动依据）。
     * 重扫也能恢复使用内置 SystemNNN 回退的原绑定。
     *
     * 保留包名而不是清空，是为了让「模块描述暂时失效 / 发现查询失败 / 用户禁用到重装」期间
     * 重扫仍能识别这是运行时条目，不会把整条游戏记录当已删除而丢掉标题/封面/元数据。
     *
     * @return 本次降级的游戏数量；发现查询失败时只迁移已知的旧 Emberfall 内置身份。
     */
    suspend fun reconcile(context: Context, restoreReenabled: Boolean = false): Int = withContext(Dispatchers.IO) {
        val discovery = RuntimeModuleDiscovery.discover(context) as? RuntimeModuleDiscoveryResult.Success
        val discoveredPackages = discovery?.modules?.mapTo(HashSet()) { it.packageName }
        var degraded = 0
        var bindingChanged = false

        GameLibraryFacade.updateGames(context) { games ->
            val enabledPackages = if (discoveredPackages != null) AppSettingsStore.getEnabledRuntimeModules(context)
            else emptySet()
            games.map { game ->
                val updated = reconcileBinding(game, discoveredPackages, enabledPackages, restoreReenabled)
                if (updated !== game) {
                    bindingChanged = true
                    if (updated.engine == EngineType.UNKNOWN) degraded++
                }
                updated
            }
        }
        if (bindingChanged) GameLibraryFacade.notifyGamesChanged()
        degraded
    }

    internal fun reconcileBinding(
        game: ScanGame,
        discoveredPackages: Set<String>?,
        enabledPackages: Set<String>,
        restoreReenabled: Boolean,
    ): ScanGame {
        val builtIn = builtInSystemNnn(game)
        if (builtIn !== game || discoveredPackages == null) return builtIn
        val packageName = game.runtimeModulePackage
        val available = !packageName.isNullOrBlank() && packageName in enabledPackages && packageName in discoveredPackages
        return when {
            // Retain the last known package so a later scan can restore the binding.
            game.engine == EngineType.RUNTIME_MODULE && !available -> game.copy(engine = EngineType.UNKNOWN)
            restoreReenabled && available && (game.engine == EngineType.UNKNOWN || game.engine == EngineType.SYSTEMNNN) ->
                game.copy(engine = EngineType.RUNTIME_MODULE)
            else -> game
        }
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
