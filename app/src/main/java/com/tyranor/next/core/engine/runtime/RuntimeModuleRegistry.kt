package com.tyranor.next.core.engine.runtime

import android.content.Context
import com.tyranor.next.core.settings.AppSettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 发现结果的进程内快照。
 *
 * [modules] 带上本次快照的启用状态；[lastError] 只在最近一次刷新失败时非空，
 * 失败时 [modules] 保留上一次成功结果。
 */
data class RuntimeModuleSnapshot(
    val modules: List<RuntimeModuleDescriptor>,
    val lastError: String? = null,
)

/**
 * 第三方运行时模块注册表：发现、启用状态与扫描索引的唯一门面。
 *
 * 只读缓存的方法（[find] / [labelOf]）不会触发 PackageManager 查询；需要最新数据时显式 [refresh]。
 * 刷新与启用状态变更经同一把 [Mutex] 串行，避免并发刷新把旧快照写回。
 */
object RuntimeModuleRegistry {

    private val refreshMutex = Mutex()

    private val _state = MutableStateFlow(
        RuntimeModuleSnapshot(modules = emptyList()),
    )
    val state: StateFlow<RuntimeModuleSnapshot> = _state.asStateFlow()

    /** 重新发现模块并合并持久化启用状态；失败时保留旧模块并只记录 [RuntimeModuleSnapshot.lastError]。 */
    suspend fun refresh(context: Context): RuntimeModuleDiscoveryResult = refreshMutex.withLock {
        refreshLocked(context)
    }

    /** 构建扫描索引：先刷新，成功后只取「已启用 + 有扫描描述」的模块；刷新失败返回空索引。 */
    suspend fun scanIndex(context: Context): RuntimeModuleIndex = refreshMutex.withLock {
        when (refreshLocked(context)) {
            is RuntimeModuleDiscoveryResult.Success -> RuntimeModuleIndex.of(
                _state.value.modules.filter { it.enabled && it.scan != null },
            )

            is RuntimeModuleDiscoveryResult.Failure -> RuntimeModuleIndex.EMPTY
        }
    }

    /**
     * 写入启用状态；关闭时先降级绑定该模块的游戏，再刷新快照。
     * 返回本次降级的游戏数量（启用恒为 0）。
     */
    suspend fun setEnabled(context: Context, packageName: String, enabled: Boolean): Int =
        refreshMutex.withLock {
            val target = packageName.trim()
            if (target.isEmpty()) return@withLock 0
            withContext(Dispatchers.IO) {
                AppSettingsStore.setRuntimeModuleEnabled(context, target, enabled)
            }
            val degraded = if (enabled) 0 else RuntimeModuleBindings.reconcile(context)
            refreshLocked(context)
            degraded
        }

    /** 只读缓存；模块未发现或已卸载时返回 null。 */
    fun find(packageName: String?): RuntimeModuleDescriptor? {
        val target = packageName?.takeIf { it.isNotBlank() } ?: return null
        return _state.value.modules.firstOrNull { it.packageName == target }
    }

    private suspend fun refreshLocked(context: Context): RuntimeModuleDiscoveryResult =
        withContext(Dispatchers.IO) {
            val result = RuntimeModuleDiscovery.discover(context)
            when (result) {
                is RuntimeModuleDiscoveryResult.Success -> {
                    val enabledPackages = AppSettingsStore.getEnabledRuntimeModules(context)
                    val modules = result.modules.map { it.copy(enabled = it.packageName in enabledPackages) }
                    _state.value = RuntimeModuleSnapshot(
                        modules = modules,
                        lastError = null,
                    )
                    RuntimeModuleDiscoveryResult.Success(modules)
                }

                is RuntimeModuleDiscoveryResult.Failure -> {
                    _state.value = _state.value.copy(
                        lastError = result.detail ?: "runtime module discovery failed",
                    )
                    result
                }
            }
        }
}
