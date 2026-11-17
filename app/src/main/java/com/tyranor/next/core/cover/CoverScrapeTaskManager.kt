package com.tyranor.next.core.cover

import android.content.Context
import android.util.Log
import com.tyranor.next.core.game.model.ScanGame
import com.tyranor.next.core.game.storage.GameLibraryFacade
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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
    /** 错误码而非文案；本地化由 UI 映射（`ui/cover/CoverErrorMessages.kt`）。 */
    val error: CoverErrorCode? = null,
    val eventId: Long = 0L,
)

/**
 * 批量刮削单条封面持久化通知：[game] 为落库后的行快照，四个修订号为「落库完成时刻」该行
 * 逐源绑定修订。收集方在应用回填前与实时修订号比对：任一来源推进（期间发生了绑定/解绑）
 * 即跳过该来源的缺失回填，防止迟到的批量刮削事件把用户刚解绑的条目 ID 重新写回 UI 状态
 * （DB 侧护栏见 [mergeScrapedCover]，UI 侧护栏见 `MainLibraryStateReducer.applyScrapedGame`）。
 */
data class ScrapedCoverUpdate(
    val game: ScanGame,
    val vndbRevision: Long,
    val bangumiRevision: Long,
    val hikarinagiRevision: Long,
    val nextmoeRevision: Long,
)

object CoverScrapeTaskManager {
    private const val TAG = "CoverScrapeTask"

    private val _state = MutableStateFlow(CoverScrapeTaskState())
    val state: StateFlow<CoverScrapeTaskState> = _state.asStateFlow()
    private val _gameUpdates = MutableSharedFlow<ScrapedCoverUpdate>()
    val gameUpdates: SharedFlow<ScrapedCoverUpdate> = _gameUpdates.asSharedFlow()
    // 最近一次被任一界面确认消费的完成事件：刮削设置页 clearFinished 后，主库侧据此不再
    // 把同一次 scrapeEventId/scrapeMessage 发布进 MainLibraryUiState，避免返回 GameScreen 重复弹提示
    private val _acknowledgedEventId = MutableStateFlow(0L)
    val acknowledgedEventId: StateFlow<Long> = _acknowledgedEventId.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private var job: Job? = null
    private var activeGeneration = 0L
    private var nextEventId = 0L

    fun start(context: Context, games: List<ScanGame>? = null): Boolean {
        val appContext = context.applicationContext
        synchronized(lock) {
            if (job?.isActive == true) return false
            val generation = ++activeGeneration
            _state.value = CoverScrapeTaskState(running = true)
            job = scope.launch {
                try {
                    val (input, initialRevisions) = withContext(Dispatchers.IO) {
                        GameLibraryFacade.loadGamesWithBindingRevisions(appContext, games)
                    }
                    val result = CoverScraperService.scrapeLibraryCovers(appContext, input) { original, scraped ->
                        val (persisted, revisions) = withContext(NonCancellable + Dispatchers.IO) {
                            val expectedRev = initialRevisions[original.uri]
                                ?: GameLibraryFacade.BindingRevisionSnapshot()
                            persistScrapedCover(appContext, original, scraped, expectedRev)
                        }
                        if (persisted != null && isCurrentGeneration(generation)) {
                            _gameUpdates.emit(
                                ScrapedCoverUpdate(
                                    game = persisted,
                                    vndbRevision = revisions.vndb,
                                    bangumiRevision = revisions.bangumi,
                                    hikarinagiRevision = revisions.hikarinagi,
                                    nextmoeRevision = revisions.nextmoe,
                                ),
                            )
                        }
                    }
                    // 每张封面已通过 updateGameCoverMetadata 单行落库（迁移方案阶段 2），
                    // 这里只读最新库作为结果快照，不再触发整库重写。
                    val mergedGames = withContext(NonCancellable + Dispatchers.IO) {
                        GameLibraryFacade.loadGames(appContext)
                    }
                    postFinished(generation = generation, result = result.copy(games = mergedGames), error = null)
                } catch (e: CancellationException) {
                    postFinished(generation = generation, result = null, error = CoverErrorCode.CANCELLED)
                    throw e
                } catch (e: Exception) {
                    // 底层 message 只作日志线索，不外泄给 UI（AGENTS.md 错误处理协议）
                    Log.w(TAG, "cover scrape task failed", e)
                    postFinished(generation = generation, result = null, error = CoverErrorCode.UNKNOWN)
                } finally {
                    synchronized(lock) {
                        if (generation == activeGeneration) {
                            job = null
                        }
                    }
                }
            }
        }
        return true
    }

    fun clearFinished(eventId: Long) {
        synchronized(lock) {
            val current = _state.value
            if (!current.running && current.eventId == eventId) {
                _state.value = current.copy(result = null, error = null)
                _acknowledgedEventId.value = eventId
            }
        }
    }

    /**
     * 单行落库刮削封面并回收被替换的旧图；返回「落库后的行（null = 未采纳）」与「落库完成
     * 时刻的逐源绑定修订快照」（见 [GameLibraryFacade.updateGameCoverMetadataWithRevisions]）。
     */
    internal fun persistScrapedCover(
        context: Context,
        original: ScanGame,
        scraped: ScanGame,
        expectedRevision: GameLibraryFacade.BindingRevisionSnapshot = GameLibraryFacade.BindingRevisionSnapshot(),
    ): Pair<ScanGame?, GameLibraryFacade.BindingRevisionSnapshot> {
        val (persisted, revisionsAfter) = GameLibraryFacade.updateGameCoverMetadataWithRevisions(context, original.uri) { current ->
            val currentRevision = GameLibraryFacade.bindingRevision(current.uri)
            mergeScrapedCover(
                current = current,
                original = original,
                scraped = scraped,
                vndbRevisionUnchanged = currentRevision.vndb == expectedRevision.vndb,
                bangumiRevisionUnchanged = currentRevision.bangumi == expectedRevision.bangumi,
                hikarinagiRevisionUnchanged = currentRevision.hikarinagi == expectedRevision.hikarinagi,
                nextmoeRevisionUnchanged = currentRevision.nextmoe == expectedRevision.nextmoe,
            )
        }
        if (persisted != null) {
            // 换封面成功后清理被替换的旧图。mergeScrapedCover 仅在 current 与任务快照一致时生效，
            // 因此此时 original.coverUri 就是落库前的旧封面；deleteCachedCover 只在 covers_remote
            // 目录内删除，并自动跳过与新封面相同的文件（重复刮削同一 URL 时新旧为同一文件）。
            CoverImageCache.deleteCachedCover(context, original.coverUri, exceptUri = persisted.coverUri)
            return persisted to revisionsAfter
        } else if (!scraped.coverUri.isNullOrBlank() && scraped.coverUri != original.coverUri) {
            // 并发手动换封面或删游导致本次刮削封面未被采纳：回收新下载的 covers_remote 孤儿文件（跳过仍被库内引用的 URI）
            val currentGames = GameLibraryFacade.loadGames(context)
            if (currentGames.none { it.coverUri == scraped.coverUri }) {
                val currentCover = currentGames.firstOrNull { it.uri == original.uri }?.coverUri
                CoverImageCache.deleteCachedCover(context, scraped.coverUri, exceptUri = currentCover)
            }
        }
        return null to revisionsAfter
    }

    private fun isCurrentGeneration(generation: Long): Boolean = synchronized(lock) {
        generation == activeGeneration
    }

    @androidx.annotation.VisibleForTesting
    internal fun resetForTest() {
        val activeJob = synchronized(lock) {
            activeGeneration += 1L
            val current = job
            job = null
            nextEventId = 0L
            _state.value = CoverScrapeTaskState()
            _acknowledgedEventId.value = 0L
            current
        }
        if (activeJob != null) {
            runBlocking { activeJob.cancelAndJoin() }
        }
    }

    private suspend fun postFinished(generation: Long, result: CoverScrapeResult?, error: CoverErrorCode?) {
        // NonCancellable：取消路径也要把终态写入，否则 running 永久停留在 true；
        // 按任务代次隔离，resetForTest() 后滞后退出的旧任务不再回写 _state。
        withContext(NonCancellable) {
            synchronized(lock) {
                if (generation != activeGeneration) return@withContext
                job = null
                nextEventId += 1
                val eventId = nextEventId
                _state.value = CoverScrapeTaskState(
                    running = false,
                    result = result,
                    error = error,
                    eventId = eventId,
                )
            }
        }
    }
}

/** 只在封面仍与任务启动快照一致时应用结果，避免覆盖用户同时进行的手动换封面。 */
internal fun mergeScrapedCover(
    current: ScanGame,
    original: ScanGame,
    scraped: ScanGame,
    vndbRevisionUnchanged: Boolean = true,
    bangumiRevisionUnchanged: Boolean = true,
    hikarinagiRevisionUnchanged: Boolean = true,
    nextmoeRevisionUnchanged: Boolean = true,
): ScanGame =
    if (current.coverUri == original.coverUri && current.coverSource == original.coverSource) {
        current.copy(
            coverUri = scraped.coverUri,
            coverSource = scraped.coverSource,
            // 已有绑定优先（仅在为空时以刮削命中填充）：游玩状态绑定不写封面列、必过本护栏，
            // 「刮削值优先」会让标题首条候选静默改写手工绑定，后续状态写入打到错误的远端条目。
            // 回填另须与任务快照及逐源绑定修订号一致（BK-40）：解绑进行中、或任务期间曾绑定后
            // 又解绑使 current 回到 null 时均不回填，否则批量刮削会把刚解绑的绑定静默接回去；
            // 正常绑定窗口（未修改过绑定且 current 与 original 一致为空）仍回填，既有行为不变
            vndbId = current.vndbId?.takeIf { it.isNotBlank() }
                ?: scraped.vndbId?.takeIf {
                    it.isNotBlank() && vndbRevisionUnchanged && current.vndbId == original.vndbId
                },
            bangumiId = current.bangumiId?.takeIf { it.isNotBlank() }
                ?: scraped.bangumiId?.takeIf {
                    it.isNotBlank() && bangumiRevisionUnchanged && current.bangumiId == original.bangumiId
                },
            hikarinagiId = current.hikarinagiId?.takeIf { it.isNotBlank() }
                ?: scraped.hikarinagiId?.takeIf {
                    it.isNotBlank() && hikarinagiRevisionUnchanged && current.hikarinagiId == original.hikarinagiId
                },
            nextmoeId = current.nextmoeId?.takeIf { it.isNotBlank() }
                ?: scraped.nextmoeId?.takeIf {
                    it.isNotBlank() && nextmoeRevisionUnchanged && current.nextmoeId == original.nextmoeId
                },
            metadataTitle = current.metadataTitle?.takeIf { it.isNotBlank() }
                ?: scraped.metadataTitle?.takeIf { it.isNotBlank() },
        )
    } else {
        current
    }
