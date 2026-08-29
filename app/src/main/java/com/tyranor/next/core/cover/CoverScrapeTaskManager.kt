package com.tyranor.next.core.cover

import android.content.Context
import com.tyranor.next.R
import com.tyranor.next.core.i18n.AppLocaleController
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import com.tyranor.next.core.game.model.ScanGame
import com.tyranor.next.core.game.scan.EngineScanner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext

data class CoverScrapeTaskState(
    val running: Boolean = false,
    val result: CoverScrapeResult? = null,
    val error: String? = null,
    val eventId: Long = 0L,
)

object CoverScrapeTaskManager {
    private val _state = mutableStateOf(CoverScrapeTaskState())
    val state: State<CoverScrapeTaskState> = _state
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
                    val input = games ?: EngineScanner.loadGames(appContext)
                    val result = CoverScraperService.scrapeLibraryCovers(appContext, input) { original, scraped ->
                        val persisted = withContext(NonCancellable + Dispatchers.IO) {
                            persistScrapedCover(appContext, original, scraped)
                        }
                        persisted?.let { _gameUpdates.emit(it) }
                    }
                    val mergedGames = withContext(NonCancellable + Dispatchers.IO) {
                        EngineScanner.updateGames(appContext) { currentGames ->
                            mergeWithCurrentLibrary(currentGames, input, result.games)
                        }
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

    private fun mergeWithCurrentLibrary(
        currentGames: List<ScanGame>,
        originalGames: List<ScanGame>,
        scrapedGames: List<ScanGame>,
    ): List<ScanGame> {
        if (currentGames.isEmpty()) return emptyList()
        val originalByUri = originalGames.associateBy { it.uri }
        val scrapedByUri = scrapedGames.associateBy { it.uri }
        return currentGames.map { current ->
            val original = originalByUri[current.uri] ?: return@map current
            val scraped = scrapedByUri[current.uri] ?: return@map current
            mergeScrapedCover(current, original, scraped)
        }
    }

    private fun persistScrapedCover(context: Context, original: ScanGame, scraped: ScanGame): ScanGame? {
        var persisted: ScanGame? = null
        EngineScanner.updateGames(context) { currentGames ->
            currentGames.map { current ->
                if (current.uri != original.uri) {
                    current
                } else {
                    mergeScrapedCover(current, original, scraped).also { merged ->
                        if (merged.coverUri != current.coverUri || merged.coverSource != current.coverSource) {
                            persisted = merged
                        }
                    }
                }
            }
        }
        return persisted
    }

    private suspend fun postFinished(result: CoverScrapeResult?, error: String?) {
        val eventId = synchronized(lock) {
            nextEventId += 1
            nextEventId
        }
        withContext(Dispatchers.Main.immediate) {
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
        )
    } else {
        current
    }

private fun Context.getLocalizedString(id: Int): String =
    AppLocaleController.wrap(this).getString(id)
