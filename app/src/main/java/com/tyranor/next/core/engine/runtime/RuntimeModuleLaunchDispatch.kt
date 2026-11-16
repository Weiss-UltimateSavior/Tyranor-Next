package com.tyranor.next.core.engine.runtime

import kotlin.coroutines.cancellation.CancellationException

/** 当前游戏的精确 SAF tree 优先；授权不可用时保留路径入口，沿用启动结果协议。 */
internal object RuntimeModuleLaunchDispatch {
    data class SafSource<S : Any>(
        val treeUri: S,
        val authority: String,
        val treeDocumentId: String,
        val rootDocumentId: String,
    )

    fun <S : Any> launch(
        folderPath: String?,
        safSource: SafSource<S>?,
        persistedSafSources: () -> Sequence<SafSource<S>> = { emptySequence() },
        canReadSafSource: (SafSource<S>) -> Boolean,
        dispatch: (String?, SafSource<S>?) -> RuntimeModuleLaunchResult,
    ): RuntimeModuleLaunchResult {
        val path = folderPath?.takeIf { it.isNotBlank() }
        val readableSource = try {
            when {
                safSource == null -> null
                safSource.rootDocumentId == safSource.treeDocumentId -> safSource.takeIf(canReadSafSource)
                else -> persistedSafSources().firstOrNull { candidate ->
                    candidate.authority == safSource.authority &&
                        candidate.treeDocumentId == safSource.rootDocumentId &&
                        candidate.rootDocumentId == candidate.treeDocumentId &&
                        canReadSafSource(candidate)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: SecurityException) {
            null
        } catch (e: Exception) {
            return RuntimeModuleLaunchResult.StartFailed(e.message)
        }
        if (path == null && readableSource == null) {
            return if (safSource == null) {
                RuntimeModuleLaunchResult.PathUnresolved
            } else {
                RuntimeModuleLaunchResult.SecurityDenied
            }
        }

        val result = dispatch(path, readableSource)
        // 预检后权限仍可能失效；仅系统拒绝携带 SAF 的启动时允许一次纯路径回退。
        return if (result == RuntimeModuleLaunchResult.SecurityDenied && readableSource != null && path != null) {
            dispatch(path, null)
        } else {
            result
        }
    }
}
