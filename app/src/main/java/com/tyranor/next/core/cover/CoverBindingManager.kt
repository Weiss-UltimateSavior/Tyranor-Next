package com.tyranor.next.core.cover

import android.content.Context
import com.tyranor.next.core.game.model.ScanGame
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * 封面绑定任务：搜索弹窗关闭后继续下载，并在原游戏封面显示进度圈。
 *
 * 这里没有「暂停」：中途只能等它完成，失败后由封面上的「重新下载」重新发起一次绑定。
 * 线程约定：任务表与 [states] 的读改写都在 [lock] 内完成，进度回调只在「自己仍是当前任务」时落盘，
 * 避免替换任务后旧协程把过期进度或失败态写回来。
 */
object CoverBindingManager {

    /** [failed] 为 true 表示这次绑定已结束但没拿到封面，UI 据此显示「重新下载」。 */
    data class State(val progress: Float = 0f, val failed: Boolean = false)

    private class Run(val cancel: CoverDownloadCancel) {
        var job: Job? = null
    }

    private class Task(
        val context: Context,
        val game: ScanGame,
        val candidate: CoverSearchCandidate,
        val onUpdated: (ScanGame) -> Unit,
    ) {
        var run: Run? = null
    }

    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _states = MutableStateFlow<Map<String, State>>(emptyMap())
    val states: StateFlow<Map<String, State>> = _states

    private val tasks = ConcurrentHashMap<String, Task>()

    fun bind(
        context: Context,
        game: ScanGame,
        candidate: CoverSearchCandidate,
        onUpdated: (ScanGame) -> Unit,
    ) {
        val task = Task(context.applicationContext, game, candidate, onUpdated)
        val previous = synchronized(lock) { tasks.put(game.uri, task) }
        previous?.run?.let { run ->
            run.cancel.cancel()
            run.job?.cancel()
        }
        start(task)
    }

    /** 失败后重试：沿用上次选中的候选重新下载；进行中的任务忽略。 */
    fun retry(uri: String) {
        val task = tasks[uri] ?: return
        if (_states.value[uri]?.failed != true) return
        start(task)
    }

    private fun start(task: Task) {
        val run = Run(CoverDownloadCancel())
        synchronized(lock) {
            task.run = run
            _states.value = _states.value + (task.game.uri to State(progress = 0f, failed = false))
        }
        run.job = scope.launch {
            var lastEmit = 0L
            val updated = try {
                CoverScraperService.bindCoverCandidate(
                    task.context,
                    task.game,
                    task.candidate,
                    cancel = run.cancel,
                    onProgress = { progress ->
                        // 进度按 100ms 节流落到 StateFlow，避免每个读块都触发一次 UI 重组。
                        val now = System.currentTimeMillis()
                        if (now - lastEmit >= 100L || progress >= 0.95f) {
                            lastEmit = now
                            synchronized(lock) {
                                // 换任务后旧协程仍可能回调，只有自己还是当前 run 才写入。
                                if (task.run !== run) return@synchronized
                                _states.value = _states.value +
                                    (task.game.uri to State(progress = progress, failed = false))
                            }
                        }
                    },
                )
            } catch (cancelled: CancellationException) {
                // 取消不是失败：原样向上抛，交给协程取消语义处理。
                throw cancelled
            } catch (_: Throwable) {
                null
            }
            if (run.cancel.isCancelled) return@launch
            withContext(Dispatchers.Main.immediate) {
                val owned = synchronized(lock) {
                    if (tasks[task.game.uri] !== task || task.run !== run) {
                        false
                    } else {
                        task.run = null
                        true
                    }
                }
                if (!owned) return@withContext
                if (updated != null) {
                    synchronized(lock) {
                        tasks.remove(task.game.uri, task)
                        _states.value = _states.value - task.game.uri
                    }
                    task.onUpdated(updated)
                } else {
                    // 失败保留任务与候选，封面上的「重新下载」用它重试。
                    synchronized(lock) {
                        val progress = _states.value[task.game.uri]?.progress ?: 0f
                        _states.value = _states.value + (task.game.uri to State(progress, failed = true))
                    }
                }
            }
            // 旧封面删除移出绑定关键路径：UI 已拿到新封面后再清理缓存中的旧图。
            if (updated != null) {
                withContext(Dispatchers.IO) {
                    CoverImageCache.deleteCachedCover(task.context, task.game.coverUri, updated.coverUri)
                }
            }
        }
    }

    /**
     * 外部途径改过封面（自定义图片、批量抓取等）后清掉该游戏保留的失败任务与状态，
     * 避免封面上的「重新下载」用旧候选覆盖刚设置的新封面；其他游戏不受影响。
     */
    fun invalidate(uri: String) {
        val task = synchronized(lock) { tasks.remove(uri) } ?: return
        task.run?.let { run ->
            run.cancel.cancel()
            run.job?.cancel()
        }
        synchronized(lock) { _states.value = _states.value - uri }
    }
}
