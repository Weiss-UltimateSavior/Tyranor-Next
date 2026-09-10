package com.tyranor.next.core.cover

import android.content.Context
import com.tyranor.next.R
import com.tyranor.next.core.i18n.AppLocaleController
import com.tyranor.next.core.game.model.ScanGame
import com.tyranor.next.core.game.storage.GameLibraryFacade
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

data class CoverScrapeTaskState(
    val running: Boolean = false,
    val result: CoverScrapeResult? = null,
    val error: String? = null,
    val eventId: Long = 0L,
)

object CoverScrapeTaskManager {
    private val _state = MutableStateFlow(CoverScrapeTaskState())
    val state: StateFlow<CoverScrapeTaskState> = _state.asStateFlow()
    private val _gameUpdates = MutableSharedFlow<ScanGame>()
    val gameUpdates: SharedFlow<ScanGame> = _gameUpdates.asSharedFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private var job: Job? = null
    private var nextEventId = 0L

    fun start(context: Context, games: List<ScanGame>? = null): Boolean {
        val appContext = context.applicationContext
        synchronized(lock) {
            if (job?.isActive == true) return false
            _state.value = CoverScrapeTaskState(running = true)
            job = scope.launch {
                try {
                    val input = games ?: GameLibraryFacade.loadGames(appContext)
                    val result = CoverScraperService.scrapeLibraryCovers(appContext, input) { original, scraped ->
                        val persisted = withContext(NonCancellable + Dispatchers.IO) {
                            persistScrapedCover(appContext, original, scraped)
                        }
                        persisted?.let { _gameUpdates.emit(it) }
                    }
                    // 每张封面已通过 updateGameCover 单行落库（迁移方案阶段 2），
                    // 这里只读最新库作为结果快照，不再触发整库重写。
                    val mergedGames = withContext(NonCancellable + Dispatchers.IO) {
                        GameLibraryFacade.loadGames(appContext)
                    }
                    postFinished(result = result.copy(games = mergedGames), error = null)
                } catch (e: CancellationException) {
                    postFinished(result = null, error = appContext.getLocalizedString(R.string.cover_scrape_cancelled))
                    throw e
                } catch (e: Exception) {
                    postFinished(result = null, error = e.message ?: appContext.getLocalizedString(R.string.cover_scrape_failed))
                } finally {
                    synchronized(lock) {
                        job = null
                    }
                }
            }
        }
        return true
    }

    fun clearFinished(eventId: Long) {
        val current = _state.value
        if (!current.running && current.eventId == eventId) {
            _state.value = current.copy(result = null, error = null)
        }
    }

    private fun persistScrapedCover(context: Context, original: ScanGame, scraped: ScanGame): ScanGame? =
        GameLibraryFacade.updateGameCover(context, original.uri) { current ->
            mergeScrapedCover(current, original, scraped)
        }

    private suspend fun postFinished(result: CoverScrapeResult?, error: String?) {
        val eventId = synchronized(lock) {
            nextEventId += 1
            nextEventId
        }
        // NonCancellable：取消路径也要把终态写入，否则 running 永久停留在 true
        withContext(NonCancellable + Dispatchers.Main.immediate) {
            _state.value = CoverScrapeTaskState(
                running = false,
                result = result,
                error = error,
                eventId = eventId,
            )
        }
    }
}

/** 只在封面仍与任务启动快照一致时应用结果，避免覆盖用户同时进行的手动换封面。 */
internal fun mergeScrapedCover(current: ScanGame, original: ScanGame, scraped: ScanGame): ScanGame =
    if (current.coverUri == original.coverUri && current.coverSource == original.coverSource) {
        current.copy(
            coverUri = scraped.coverUri,
            coverSource = scraped.coverSource,
            // VNDB 元数据随封面结果一并合并（空串不得覆盖已有值）
            vndbId = scraped.vndbId?.takeIf { it.isNotBlank() } ?: current.vndbId,
            metadataTitle = scraped.metadataTitle?.takeIf { it.isNotBlank() } ?: current.metadataTitle,
        )
    } else {
        current
    }

private fun Context.getLocalizedString(id: Int): String =
    AppLocaleController.wrap(this).getString(id)
