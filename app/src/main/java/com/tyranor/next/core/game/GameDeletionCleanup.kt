package com.tyranor.next.core.game

import android.content.Context
import com.tyranor.next.core.cover.CoverImageCache
import com.tyranor.next.core.game.model.ScanGame
import com.tyranor.next.core.game.save.sync.SaveSyncService
import com.tyranor.next.core.game.storage.GameLibraryFacade
import com.tyranor.next.core.settings.PerGameSettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * 删除游戏后清理应用内关联数据（不触碰游戏目录）：存档同步状态、单游戏覆盖、
 * 最近打开 / 快捷启动记录，以及托管目录 `covers_remote/` 下的封面文件。
 * 各清理步骤相互独立，单项失败记录异常继续执行其余项，最终汇总抛出；
 * 当 UI 快照封面路径（[extraCoverUri]）与库内最新记录不同时仅补删额外封面文件，
 * 不重复执行存档或设置清理。
 *
 * 取舍（LOGIC-7）：全部步骤（含 [clearSaveSync]）统一纳入 [NonCancellable]——删除已被
 * 用户显式确认，VM 销毁窗口的协程取消不得让后续清理（覆盖行 / 最近与快捷记录 / 封面文件）
 * 被跳过而残留孤儿数据；调用方取消语义不受影响（清理完成后控制权交还，取消照常生效）。
 */
internal suspend fun cleanupDeletedGame(
    context: Context,
    target: ScanGame,
    extraCoverUri: String? = null,
    clearSaveSync: suspend (ScanGame) -> Unit = { SaveSyncService(context).clearGame(it) },
    clearPerGameSettings: suspend (String) -> Unit = { PerGameSettingsStore.clear(context, it) },
) {
    val failures = ArrayList<Exception>()
    suspend fun attempt(action: suspend () -> Unit) {
        try {
            action()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            failures += failure
        }
    }
    withContext(NonCancellable) {
        attempt { clearSaveSync(target) }
        attempt { clearPerGameSettings(target.uri) }
        attempt { GameLibraryFacade.removeRecentGame(context, target.uri) }
        attempt { GameLibraryFacade.removeQuickLaunch(context, target.uri) }
        attempt { CoverImageCache.deleteCachedCover(context, target.coverUri) }
        if (!extraCoverUri.isNullOrBlank() && extraCoverUri != target.coverUri) {
            attempt { CoverImageCache.deleteCachedCover(context, extraCoverUri) }
        }
    }
    if (failures.isNotEmpty()) {
        throw failures.first().also { first -> failures.drop(1).forEach(first::addSuppressed) }
    }
}
