package com.tyranor.next.ui.game

import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.annotation.VisibleForTesting
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items as lazyItems
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.tyranor.next.R
import com.tyranor.next.core.cover.CoverErrorCode
import com.tyranor.next.core.cover.CoverSearchCandidate
import com.tyranor.next.core.cover.CoverSearchResult
import com.tyranor.next.core.i18n.AppLocaleController
import com.tyranor.next.core.game.model.ScanGame
import com.tyranor.next.core.settings.AppSettingsStore
import com.tyranor.next.core.auth.BangumiAuthStore
import com.tyranor.next.core.auth.HikarinagiAuthStore
import com.tyranor.next.core.auth.NextMoeAuthStore
import com.tyranor.next.core.auth.VndbAuthStore
import com.tyranor.next.core.auth.VndbPermissionTier
import com.tyranor.next.core.auth.evaluateVndbPermissionTier
import com.tyranor.next.core.status.BangumiCollectTypes
import com.tyranor.next.core.status.HikarinagiStatuses
import com.tyranor.next.core.status.NextMoeWorkStates
import com.tyranor.next.core.status.PLAY_STATUS_LOG_TAG
import com.tyranor.next.core.status.PlayStatusError
import com.tyranor.next.core.status.PlayStatusException
import com.tyranor.next.core.status.PlayStatusService
import com.tyranor.next.core.status.VndbLabels
import com.tyranor.next.core.status.isHikarinagiStatus
import com.tyranor.next.core.status.isNextMoeWorkState
import com.tyranor.next.core.status.vndbLabelsToUnset
import com.tyranor.next.ui.common.AppAlertDialog
import com.tyranor.next.ui.common.AppNavItem
import com.tyranor.next.ui.common.AppSearchField
import com.tyranor.next.ui.common.DialogTextButton
import com.tyranor.next.ui.common.NoIndication
import com.tyranor.next.ui.common.motion.BlurCrossfade
import com.tyranor.next.ui.common.motion.ExpandVisibility
import com.tyranor.next.ui.common.motion.rememberLastNonNull
import com.tyranor.next.ui.common.motion.shakeOnTrigger
import com.tyranor.next.ui.common.notice.AppNotice
import com.tyranor.next.ui.cover.coverErrorMessage
import com.tyranor.next.ui.cover.coverSourceTitle
import com.tyranor.next.ui.cover.playStatusErrorMessage
import com.tyranor.next.ui.cover.playStatusError
import com.tyranor.next.theme.DialogItemSurface
import com.tyranor.next.theme.SecondaryTextColor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class PlayStatusOption(val code: String, val labelRes: Int)

private val bangumiStatusOptions = listOf(
    PlayStatusOption(BangumiCollectTypes.WISH.toString(), R.string.play_status_bangumi_wish),
    PlayStatusOption(BangumiCollectTypes.DONE.toString(), R.string.play_status_bangumi_done),
    PlayStatusOption(BangumiCollectTypes.DOING.toString(), R.string.play_status_bangumi_doing),
    PlayStatusOption(BangumiCollectTypes.ON_HOLD.toString(), R.string.play_status_bangumi_on_hold),
    PlayStatusOption(BangumiCollectTypes.DROPPED.toString(), R.string.play_status_bangumi_dropped),
)

private val vndbStatusOptions = listOf(
    PlayStatusOption(VndbLabels.PLAYING.toString(), R.string.play_status_vndb_playing),
    PlayStatusOption(VndbLabels.FINISHED.toString(), R.string.play_status_vndb_finished),
    PlayStatusOption(VndbLabels.STALLED.toString(), R.string.play_status_vndb_stalled),
    PlayStatusOption(VndbLabels.DROPPED.toString(), R.string.play_status_vndb_dropped),
)

private val hikarinagiStatusOptions = listOf(
    PlayStatusOption(HikarinagiStatuses.PLAN, R.string.play_status_hikarinagi_plan),
    PlayStatusOption(HikarinagiStatuses.COMPLETED, R.string.play_status_hikarinagi_completed),
    PlayStatusOption(HikarinagiStatuses.GOING, R.string.play_status_hikarinagi_going),
    PlayStatusOption(HikarinagiStatuses.ON_HOLD, R.string.play_status_hikarinagi_on_hold),
    PlayStatusOption(HikarinagiStatuses.DROPPED, R.string.play_status_hikarinagi_dropped),
)

private val nextmoeStatusOptions = listOf(
    PlayStatusOption(NextMoeWorkStates.WISH, R.string.play_status_nextmoe_wish),
    PlayStatusOption(NextMoeWorkStates.DONE, R.string.play_status_nextmoe_done),
    PlayStatusOption(NextMoeWorkStates.DOING, R.string.play_status_nextmoe_doing),
    PlayStatusOption(NextMoeWorkStates.ON_HOLD, R.string.play_status_nextmoe_on_hold),
    PlayStatusOption(NextMoeWorkStates.DROPPED, R.string.play_status_nextmoe_dropped),
)

private fun statusOptionText(context: Context, options: List<PlayStatusOption>, code: String?): String? =
    code?.let { target -> options.firstOrNull { it.code == target } }
        ?.let { AppLocaleController.wrap(context).getString(it.labelRes) }

internal fun boundIdForSource(game: ScanGame, source: String): String? = when (source) {
    AppSettingsStore.COVER_SOURCE_BANGUMI -> game.bangumiId
    AppSettingsStore.COVER_SOURCE_VNDB -> game.vndbId
    AppSettingsStore.COVER_SOURCE_HIKARINAGI -> game.hikarinagiId
    AppSettingsStore.COVER_SOURCE_NEXTMOE -> game.nextmoeId
    else -> null
}?.takeIf { it.isNotBlank() }

/**
 * 屏幕旋转重建后校验 rememberSaveable 恢复的二级弹窗目标来源：
 * 若异步解绑已落库并刷新 game，关闭过时的状态选择弹窗；若异步绑定已落库并刷新 game，关闭过时的搜索绑定弹窗。
 */
internal fun reconcilePlayStatusSubDialogSources(
    game: ScanGame,
    pickSource: String?,
    bindSource: String?,
): Pair<String?, String?> {
    val validPick = pickSource?.takeIf { boundIdForSource(game, it) != null }
    val validBind = bindSource?.takeIf { boundIdForSource(game, it) == null }
    return validPick to validBind
}

/**
 * 抽屉副文本缓存：按「游戏 + 绑定 + 逐源授权门 + locale」键控，TTL 5 分钟——避免每次打开
 * 抽屉都发起网络回读。授权状态变化（如登出）即换键重读，不会命中陈旧变体；缓存值为本地化
 * 文案，locale 入键（BK-41）：语言切换后按新键重读一次（与授权态换键同机制），TTL 内不再
 * 命中旧语言变体，旧键随 TTL 自然过期。
 */
internal object PlayStatusSummaryCache {
    private const val TTL_MS = 5 * 60_000L

    /** 容量上限：缓存只省一次回读，超限逐出无正确性影响。 */
    private const val MAX_ENTRIES = 32

    internal data class CacheKey(
        val uri: String,
        val bangumiId: String?,
        val vndbId: String?,
        val hikarinagiId: String?,
        val bangumiReady: Boolean,
        val vndbReady: Boolean,
        val hikarinagiReady: Boolean,
        val localeTag: String,
        val bangumiAuthEpoch: Long = 0L,
        val vndbAuthEpoch: Long = 0L,
        val hikarinagiAuthEpoch: Long = 0L,
        val nextmoeId: String? = null,
        val nextmoeReady: Boolean = false,
        val nextmoeAuthEpoch: Long = 0L,
    )

    private data class Entry(val line: String, val fetchedAt: Long)
    private val entries = mutableMapOf<CacheKey, Entry>()
    private val invalidationGenerations = mutableMapOf<String, Long>()

    fun key(
        uri: String,
        bangumiId: String?,
        vndbId: String?,
        hikarinagiId: String?,
        bangumiReady: Boolean,
        vndbReady: Boolean,
        hikarinagiReady: Boolean,
        localeTag: String,
        bangumiAuthEpoch: Long = 0L,
        vndbAuthEpoch: Long = 0L,
        hikarinagiAuthEpoch: Long = 0L,
        nextmoeId: String? = null,
        nextmoeReady: Boolean = false,
        nextmoeAuthEpoch: Long = 0L,
    ): CacheKey = CacheKey(
        uri = uri,
        bangumiId = bangumiId,
        vndbId = vndbId,
        hikarinagiId = hikarinagiId,
        bangumiReady = bangumiReady,
        vndbReady = vndbReady,
        hikarinagiReady = hikarinagiReady,
        localeTag = localeTag,
        bangumiAuthEpoch = bangumiAuthEpoch,
        vndbAuthEpoch = vndbAuthEpoch,
        hikarinagiAuthEpoch = hikarinagiAuthEpoch,
        nextmoeId = nextmoeId,
        nextmoeReady = nextmoeReady,
        nextmoeAuthEpoch = nextmoeAuthEpoch,
    )

    fun generation(uri: String): Long = synchronized(entries) { invalidationGenerations[uri] ?: 0L }

    fun get(key: CacheKey): String? = synchronized(entries) {
        val entry = entries[key] ?: return null
        if (System.currentTimeMillis() - entry.fetchedAt > TTL_MS) {
            entries.remove(key)
            return null
        }
        entry.line
    }

    fun put(key: CacheKey, line: String, fetchedAtMillis: Long = System.currentTimeMillis()) = synchronized(entries) {
        putLocked(key, line, fetchedAtMillis)
    }

    private fun putLocked(key: CacheKey, line: String, fetchedAtMillis: Long) {
        if (entries.size >= MAX_ENTRIES && key !in entries) {
            evictForCapacity(System.currentTimeMillis())
        }
        entries[key] = Entry(line, fetchedAtMillis)
    }

    /**
     * 仅当 [summary] 全部来源回读成功时写入缓存；失败文案即时展示、不入缓存
     * （网络恢复后下次打开抽屉立即重读）。若传入 [expectedGeneration]，写前确认
     * 期间未发生 [invalidate]，避免在途旧回读覆盖写/解绑后的失效标记。
     */
    fun putIfCacheable(
        key: CacheKey,
        summary: PlayStatusDrawerSummary,
        expectedGeneration: Long? = null,
    ) {
        if (!summary.cacheable) return
        val line = summary.line ?: return
        synchronized(entries) {
            if (expectedGeneration != null && (invalidationGenerations[key.uri] ?: 0L) != expectedGeneration) {
                return
            }
            putLocked(key, line, System.currentTimeMillis())
        }
    }

    /** 使某游戏的全部缓存变体失效（写操作改变远端状态后调用）。 */
    fun invalidate(uri: String) = synchronized(entries) {
        invalidationGenerations[uri] = (invalidationGenerations[uri] ?: 0L) + 1L
        entries.keys.removeAll { key -> key.uri == uri }
    }

    /** 容量逐出：先清过期变体，仍超限再按最旧逐出。 */
    private fun evictForCapacity(now: Long) {
        entries.keys.removeAll { key -> now - entries.getValue(key).fetchedAt > TTL_MS }
        while (entries.size >= MAX_ENTRIES) {
            val oldest = entries.entries.minByOrNull { it.value.fetchedAt } ?: return
            entries.remove(oldest.key)
        }
    }

    /**
     * JVM 测试隔离缝隙（与 SecureAuthStore.testCipherDelegate 同款 @VisibleForTesting 模式，
     * 生产路径零调用）：清空全部缓存条目。object 级缓存在测试类内共享，容量逐出按 fetchedAt
     * 取全局最旧——不隔离时用例间的人工时间戳会跨用例干扰逐出顺序（谁先谁后取决于执行顺序，
     * 非生产行为）。生产代码不得调用。
     */
    @VisibleForTesting
    internal fun clearForTest() = synchronized(entries) {
        entries.clear()
        invalidationGenerations.clear()
    }
}

/** 阻塞状态请求统一包装：取消透传，类型化失败保留错误码。 */
private inline fun <T> statusResult(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (e: PlayStatusException) {
        Result.failure(e)
    } catch (t: Throwable) {
        Result.failure(t)
    }

private class PlayStatusSourceHandle<T>(
    private val resultState: MutableState<Result<T>?>,
    private val opEpochState: MutableIntState,
    private val refreshNonceState: MutableIntState,
) {
    var result: Result<T>?
        get() = resultState.value
        set(value) { resultState.value = value }
    var opEpoch: Int
        get() = opEpochState.intValue
        set(value) { opEpochState.intValue = value }
    var refreshNonce: Int
        get() = refreshNonceState.intValue
        set(value) { refreshNonceState.intValue = value }
}

/**
 * 游玩状态单源回读与状态容器：按登录会话代次分键（同账号后台刷新令牌时不清除已读状态），
 * 仅在绑定条目、会话代次或刷新序号变更时执行 IO 回读，并在读失败时立即失效抽屉摘要缓存。
 */
@Composable
private fun <T> rememberPlayStatusSourceRead(
    context: Context,
    gameUri: String,
    boundId: String?,
    sessionEpoch: Long,
    readStatus: (String) -> T,
): PlayStatusSourceHandle<T> {
    val resultState = remember(gameUri, boundId, sessionEpoch) { mutableStateOf<Result<T>?>(null) }
    val opEpochState = remember(gameUri) { mutableIntStateOf(0) }
    val refreshNonceState = remember(gameUri) { mutableIntStateOf(0) }
    val handle = remember(resultState, opEpochState, refreshNonceState) {
        PlayStatusSourceHandle(resultState, opEpochState, refreshNonceState)
    }
    LaunchedEffect(gameUri, boundId, sessionEpoch, handle.refreshNonce) {
        val opEpoch = ++handle.opEpoch
        val id = boundId?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
        // 仅初次加载或从失败态恢复时清为加载中；写后确认回读保留当前状态防闪烁
        if (handle.result == null || handle.result?.isFailure == true) {
            handle.result = null
        }
        val loaded = withContext(Dispatchers.IO) {
            statusResult { readStatus(id) }.also { result ->
                if (result.isFailure) PlayStatusSummaryCache.invalidate(gameUri)
            }
        }
        if (handle.opEpoch == opEpoch) {
            handle.result = loaded
        }
    }
    return handle
}

/**
 * 游玩状态同步弹窗（游戏操作抽屉「游玩状态」条目）：Bangumi 收藏、VNDB ulist、Hikarinagi 评分与
 * 未萌 NextMoe 作品状态四区，各自独立绑定/回读/写入；所有写入都是用户显式操作，且会出现在站点公开主页。
 *
 * 域归位注记（BK-64）：游玩状态域的文件分居 ui/game（本弹窗与 [PlayStatusSummaryCache]）与
 * ui/cover（`PlayStatusMessages.kt` 错误文案映射）两包为既有归位——文案映射最早随封面刮削域
 * 落地、与 `CoverErrorMessages` 同域同构，弹窗属游戏抽屉域；本文件跨包引用与同包
 * `CoverScraperSettingsActivity` 共用 `playStatusErrorMessage` 为既有约定。若按规范迁
 * `ui/common`（有 LaunchErrorMessages 先例），需连带两处 import 与 core/status/PlayStatusErrors.kt
 * 的 KDoc 指引同步改写，触碰时评估。
 */
@Composable
internal fun PlayStatusDialog(
    game: ScanGame,
    onDismiss: () -> Unit,
    onBindingUpdated: (String, ScanGame) -> Unit,
) {
    val context = LocalContext.current
    val latestGame by rememberUpdatedState(game)
    val bangumiSessionEpoch by BangumiAuthStore.sessionEpoch.collectAsStateWithLifecycle()
    val vndbSessionEpoch by VndbAuthStore.sessionEpoch.collectAsStateWithLifecycle()
    val hikarinagiSessionEpoch by HikarinagiAuthStore.sessionEpoch.collectAsStateWithLifecycle()
    val nextmoeSessionEpoch by NextMoeAuthStore.sessionEpoch.collectAsStateWithLifecycle()
    // null = 加载中；Result.failure = 回读失败（展示错误文案）
    // Bangumi 读回（GET /v0/users/{username}/collections/{id}）与三源共用同一回读容器：
    // 弹窗打开/重开、绑定或换号时自动回读远端状态，写成功仍乐观更新（写后确认回读见
    // triggerBangumiReread）
    val bangumiState = rememberPlayStatusSourceRead(context, game.uri, game.bangumiId, bangumiSessionEpoch) { id ->
        PlayStatusService.readBangumiStatus(context, id)
    }
    val vndbState = rememberPlayStatusSourceRead(context, game.uri, game.vndbId, vndbSessionEpoch) { id ->
        PlayStatusService.readVndbStatus(context, id)
    }
    val hikarinagiState = rememberPlayStatusSourceRead(context, game.uri, game.hikarinagiId, hikarinagiSessionEpoch) { id ->
        PlayStatusService.readHikarinagiStatus(context, id)
    }
    val nextmoeState = rememberPlayStatusSourceRead(context, game.uri, game.nextmoeId, nextmoeSessionEpoch) { id ->
        PlayStatusService.readNextMoeStatus(context, id)
    }
    var pickSource by rememberSaveable(game.uri) { mutableStateOf<String?>(null) }
    var bindSource by rememberSaveable(game.uri) { mutableStateOf<String?>(null) }
    val (activePickSource, activeBindSource) = reconcilePlayStatusSubDialogSources(game, pickSource, bindSource)
    LaunchedEffect(game, pickSource, bindSource) {
        if (pickSource != activePickSource) pickSource = activePickSource
        if (bindSource != activeBindSource) bindSource = activeBindSource
    }
    // writingSource 有意不用 rememberSaveable（BK-35）：Activity 重建时写协程随 scope 取消中断，
    // 远端写为幂等操作（PUT / 不存在则创建的 POST / 显式 set+unset 的 PATCH），重建后上方
    // 回读 LaunchedEffect 重读收敛；若存留 writingSource，scope 已死、写入态永不回落，
    // 反而呈现假「写入中」
    var writingSource by remember(game.uri) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun writeStatus(source: String, code: String) {
        if (writingSource != null) return
        // 空绑定 id 前置防御：选择弹窗仅对已绑定来源开放，走到这里 id 为空说明快照已漂移
        // （如绑定被并发解绑）——按「未绑定条目」反馈并关闭选择弹窗，不得静默 return，
        // 也不得向远端发空 id 请求（服务端写路径另有 require 兜底）。
        // 与 unbindSource / 写后决策同口径读取 rememberUpdatedState 的最新 game，
        // 不使用组合期捕获快照（退场动画窗口内的旧 lambda 可能携带过期绑定态）
        val currentGame = latestGame
        val boundId = boundIdForSource(currentGame, source)
        if (boundId.isNullOrBlank()) {
            AppNotice.makeText(
                context,
                AppLocaleController.wrap(context).getString(R.string.game_play_status_unbound),
                Toast.LENGTH_SHORT,
                AppNotice.Kind.Limit,
            ).show()
            pickSource = null
            return
        }
        val targetUri = currentGame.uri
        val sourceSessionEpoch = when (source) {
            AppSettingsStore.COVER_SOURCE_BANGUMI -> bangumiSessionEpoch
            AppSettingsStore.COVER_SOURCE_VNDB -> vndbSessionEpoch
            AppSettingsStore.COVER_SOURCE_HIKARINAGI -> hikarinagiSessionEpoch
            AppSettingsStore.COVER_SOURCE_NEXTMOE -> nextmoeSessionEpoch
            else -> 0L
        }
        val sourceCredentialEpoch = when (source) {
            AppSettingsStore.COVER_SOURCE_BANGUMI -> BangumiAuthStore.credentialEpoch.value
            AppSettingsStore.COVER_SOURCE_VNDB -> VndbAuthStore.credentialEpoch.value
            AppSettingsStore.COVER_SOURCE_HIKARINAGI -> HikarinagiAuthStore.credentialEpoch.value
            AppSettingsStore.COVER_SOURCE_NEXTMOE -> NextMoeAuthStore.credentialEpoch.value
            else -> 0L
        }
        // 防御性纵深守卫（与 UI 层 optionsEnabled = !nextmoeUnavailable 协同）：
        // 初始回读未完成或失败前禁止写 NextMoe，避免 PUT 不带已有 completion 清掉网页端完成度。
        // 守卫命中（正常不可达，仅「弹窗打开后回读失败」快照漂移窗口可触发）与空绑定分支
        // 同口径：给 Limit 提示并关闭选择弹窗，不得静默 return 让弹窗无反馈地悬留（LOGIC-6）。
        if (source == AppSettingsStore.COVER_SOURCE_NEXTMOE && nextmoeState.result?.isSuccess != true) {
            AppNotice.makeText(
                context,
                AppLocaleController.wrap(context).getString(R.string.game_play_status_nextmoe_read_pending),
                Toast.LENGTH_SHORT,
                AppNotice.Kind.Limit,
            ).show()
            pickSource = null
            return
        }
        val preservedNextMoeCompletion = if (source == AppSettingsStore.COVER_SOURCE_NEXTMOE) {
            nextmoeState.result?.getOrNull()?.completion
        } else {
            null
        }
        writingSource = source
        scope.launch {
            val failure = try {
                withContext(Dispatchers.IO) {
                    try {
                        when (source) {
                            AppSettingsStore.COVER_SOURCE_BANGUMI ->
                                PlayStatusService.writeBangumiStatus(context, boundId, code.toInt(), sourceSessionEpoch)
                            AppSettingsStore.COVER_SOURCE_VNDB ->
                                PlayStatusService.writeVndbStatus(context, boundId, code.toInt(), sourceSessionEpoch)
                            AppSettingsStore.COVER_SOURCE_HIKARINAGI ->
                                PlayStatusService.writeHikarinagiStatus(context, boundId, code, sourceSessionEpoch)
                            AppSettingsStore.COVER_SOURCE_NEXTMOE ->
                                PlayStatusService.writeNextMoeStatus(
                                    context,
                                    boundId,
                                    code,
                                    preservedCompletion = preservedNextMoeCompletion,
                                    expectedSessionEpoch = sourceSessionEpoch,
                                )
                        }
                        // 远端写成功后立即在 IO 段使抽屉摘要缓存失效，即使用户随后旋转屏幕取消 UI 协程也能保证下次刷新
                        PlayStatusSummaryCache.invalidate(targetUri)
                        null
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (t: Throwable) {
                        t.playStatusError()
                    }
                }
            } finally {
                writingSource = null
            }
            if (failure == null) {
                // 按登录会话代次区分「换号/登出」与「同账号令牌刷新」，同账号刷新后的写入成功仍应更新界面并关窗
                val decision = evaluatePlayStatusWriteDecision(
                    source = source,
                    targetUri = targetUri,
                    boundId = boundId,
                    sourceSessionEpoch = sourceSessionEpoch,
                    sourceCredentialEpoch = sourceCredentialEpoch,
                    latestGame = latestGame,
                )
                if (decision.applyOptimisticResult) {
                    when (source) {
                        AppSettingsStore.COVER_SOURCE_BANGUMI -> {
                            ++bangumiState.opEpoch
                            bangumiState.result =
                                Result.success(PlayStatusService.BangumiStatus(exists = true, type = code.toInt()))
                            if (decision.triggerBangumiReread) {
                                // 写入期间发生了同账号令牌刷新：触发一次写后回读与服务端最终状态对齐
                                ++bangumiState.refreshNonce
                            }
                        }
                        AppSettingsStore.COVER_SOURCE_VNDB -> {
                            val vndbCode = code.toInt()
                            val previous = vndbState.result?.getOrNull()?.labelIds.orEmpty()
                            // 写后递增单源代次，阻止较早的在途初始回读覆盖刚确认的写入结果
                            ++vndbState.opEpoch
                            // labels_set/unset 语义：预置四态被新值替换，自定义标签不受影响
                            vndbState.result = Result.success(
                                PlayStatusService.VndbStatus(
                                    previous.filterNot { it in vndbLabelsToUnset(vndbCode) }.toSet() + vndbCode,
                                ),
                            )
                        }
                        AppSettingsStore.COVER_SOURCE_HIKARINAGI -> {
                            ++hikarinagiState.opEpoch
                            hikarinagiState.result = Result.success(PlayStatusService.HikarinagiStatus(exists = true, status = code))
                            if (decision.triggerHikarinagiReread) {
                                // 写入期间发生了同账号令牌刷新：触发一次写后回读与服务端最终状态对齐
                                ++hikarinagiState.refreshNonce
                            }
                        }
                        AppSettingsStore.COVER_SOURCE_NEXTMOE -> {
                            ++nextmoeState.opEpoch
                            val nextCompletion = if (code == NextMoeWorkStates.WISH) null else preservedNextMoeCompletion
                            nextmoeState.result = Result.success(
                                PlayStatusService.NextMoeStatus(exists = true, state = code, completion = nextCompletion),
                            )
                            if (decision.triggerNextMoeReread) {
                                ++nextmoeState.refreshNonce
                            }
                        }
                    }
                    pickSource = null
                } else {
                    pickSource = null
                    val stillBoundSameId = latestGame.uri == targetUri &&
                        boundIdForSource(latestGame, source) == boundId
                    val noticeRes = if (stillBoundSameId) {
                        R.string.game_play_status_write_session_changed
                    } else {
                        R.string.game_play_status_write_binding_changed
                    }
                    AppNotice.makeText(
                        context,
                        AppLocaleController.wrap(context).getString(noticeRes),
                        Toast.LENGTH_SHORT,
                        AppNotice.Kind.Info,
                    ).show()
                }
            } else {
                AppNotice.makeText(context, failure.playStatusErrorMessage(context), Toast.LENGTH_SHORT, AppNotice.Kind.Failure).show()
            }
        }
    }

    fun unbindSource(source: String) {
        if (writingSource != null) return
        val targetGame = latestGame
        writingSource = source
        scope.launch {
            val updated = try {
                withContext(Dispatchers.IO) {
                    try {
                        PlayStatusService.unbindSubject(context, targetGame, source)
                            ?.also { PlayStatusSummaryCache.invalidate(targetGame.uri) }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (t: Throwable) {
                        // 解绑失败按既有语义静默吞掉（UI 已提示失败），但留日志便于排障
                        Log.w(PLAY_STATUS_LOG_TAG, "unbind subject failed", t)
                        null
                    }
                }
            } finally {
                writingSource = null
            }
            when (updated) {
                null -> AppNotice.makeText(
                    context,
                    AppLocaleController.wrap(context).getString(R.string.game_play_status_unbind_failed),
                    Toast.LENGTH_SHORT,
                    AppNotice.Kind.Failure,
                ).show()
                else -> {
                    pickSource = null
                    onBindingUpdated(source, updated)
                    AppNotice.makeText(
                        context,
                        AppLocaleController.wrap(context).getString(R.string.game_play_status_unbound_done),
                        Toast.LENGTH_SHORT,
                        AppNotice.Kind.Success,
                    ).show()
                }
            }
        }
    }

    /**
     * 绑定 VNDB 条目后检查 token 权限并中性提示（非错误）：只读 token 可预览游玩状态，
     * 仅在缺 listwrite 时说明写入不可用；权限齐全不打扰。未配置 token 或查询失败不弹提示。
     */
    fun checkVndbPermissionsAfterBind() {
        scope.launch {
            val permissions = withContext(Dispatchers.IO) {
                PlayStatusService.checkVndbPermissions(context)
            } ?: return@launch
            // 权限层级（均为中性提示，非错误）：未勾选 = 只读公开；仅 Access private items =
            // 可读（含私密）；勾选 Add/remove/edit items = 读写齐全。仅缺写时提示。
            when (evaluateVndbPermissionTier(permissions)) {
                VndbPermissionTier.WRITE -> return@launch
                VndbPermissionTier.READ_PUBLIC -> AppNotice.makeText(
                    context,
                    AppLocaleController.wrap(context).getString(R.string.game_vndb_bind_perm_none),
                    Toast.LENGTH_LONG,
                    AppNotice.Kind.Info,
                ).show()
                VndbPermissionTier.READ_PRIVATE -> AppNotice.makeText(
                    context,
                    AppLocaleController.wrap(context).getString(R.string.game_vndb_bind_perm_read),
                    Toast.LENGTH_LONG,
                    AppNotice.Kind.Info,
                ).show()
            }
        }
    }

    AppAlertDialog(
        onDismissRequest = onDismiss,
        textScrollEnabled = true,
        title = { Text(stringResource(R.string.game_play_status_title), style = MaterialTheme.typography.titleMedium) },
        text = {
            PlayStatusSourceList(
                context = context,
                game = game,
                bangumiResult = bangumiState.result,
                vndbResult = vndbState.result,
                hikarinagiResult = hikarinagiState.result,
                nextmoeResult = nextmoeState.result,
                busy = writingSource != null,
                onPickSource = { pickSource = it },
                onBindSource = { bindSource = it },
            )
        },
        confirmButton = { DialogTextButton(text = stringResource(R.string.common_cancel), onClick = onDismiss) },
    )

    activePickSource?.let { source ->
        PlayStatusPickHost(
            context = context,
            game = game,
            source = source,
            bangumiResult = bangumiState.result,
            vndbState = vndbState,
            hikarinagiState = hikarinagiState,
            nextmoeState = nextmoeState,
            writing = writingSource == source,
            onDismiss = { pickSource = null },
            onPick = { code -> writeStatus(source, code) },
            onUnbind = { unbindSource(source) },
        )
    }

    activeBindSource?.let { source ->
        SubjectBindDialog(
            game = game,
            source = source,
            onDismiss = { bindSource = null },
            onBound = { updated ->
                bindSource = null
                onBindingUpdated(source, updated)
                AppNotice.makeText(
                    context,
                    AppLocaleController.wrap(context).getString(R.string.game_bind_subject_bound),
                    Toast.LENGTH_SHORT,
                    AppNotice.Kind.Success,
                ).show()
                if (source == AppSettingsStore.COVER_SOURCE_VNDB) {
                    // 首次绑定即检查 token 权限：只读 token 可预览，缺 listwrite 时中性提示（非错误）
                    checkVndbPermissionsAfterBind()
                }
            },
        )
    }
}

@Composable
private fun PlayStatusSourceList(
    context: Context,
    game: ScanGame,
    bangumiResult: Result<PlayStatusService.BangumiStatus>?,
    vndbResult: Result<PlayStatusService.VndbStatus>?,
    hikarinagiResult: Result<PlayStatusService.HikarinagiStatus>?,
    nextmoeResult: Result<PlayStatusService.NextMoeStatus>?,
    busy: Boolean,
    onPickSource: (String) -> Unit,
    onBindSource: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            stringResource(R.string.game_play_status_public_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = SecondaryTextColor,
        )
        val rows = listOf(
            Triple(AppSettingsStore.COVER_SOURCE_BANGUMI, game.bangumiId, bangumiStatusLine(context, game.bangumiId, bangumiResult)),
            Triple(AppSettingsStore.COVER_SOURCE_VNDB, game.vndbId, vndbStatusLine(context, game.vndbId, vndbResult)),
            Triple(AppSettingsStore.COVER_SOURCE_HIKARINAGI, game.hikarinagiId, hikarinagiStatusLine(context, game.hikarinagiId, hikarinagiResult)),
            Triple(AppSettingsStore.COVER_SOURCE_NEXTMOE, game.nextmoeId, nextmoeStatusLine(context, game.nextmoeId, nextmoeResult)),
        )
        rows.forEach { (source, boundId, statusLine) ->
            PlaySourceRow(
                sourceTitle = coverSourceTitle(source),
                boundId = boundId,
                statusLine = statusLine,
                // 跨源写互斥：任一来源写入期间四行整体置灰禁点，跨源点击不再静默 no-op
                busy = busy,
                onClick = if (boundId.isNullOrBlank()) {
                    { onBindSource(source) }
                } else {
                    { onPickSource(source) }
                },
            )
        }
    }
}

@Composable
private fun PlayStatusPickHost(
    context: Context,
    game: ScanGame,
    source: String,
    bangumiResult: Result<PlayStatusService.BangumiStatus>?,
    vndbState: PlayStatusSourceHandle<PlayStatusService.VndbStatus>,
    hikarinagiState: PlayStatusSourceHandle<PlayStatusService.HikarinagiStatus>,
    nextmoeState: PlayStatusSourceHandle<PlayStatusService.NextMoeStatus>,
    writing: Boolean,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
    onUnbind: () -> Unit,
) {
    val vndbResult = vndbState.result
    val hikarinagiResult = hikarinagiState.result
    val nextmoeResult = nextmoeState.result
    val nextmoeUnavailable = source == AppSettingsStore.COVER_SOURCE_NEXTMOE && nextmoeResult?.isSuccess != true
    val statusHint = when (source) {
        AppSettingsStore.COVER_SOURCE_NEXTMOE ->
            if (nextmoeUnavailable) nextmoeStatusLine(context, game.nextmoeId, nextmoeResult) else null
        AppSettingsStore.COVER_SOURCE_VNDB ->
            if (vndbResult?.isFailure == true) vndbStatusLine(context, game.vndbId, vndbResult) else null
        AppSettingsStore.COVER_SOURCE_HIKARINAGI ->
            if (hikarinagiResult?.isFailure == true) hikarinagiStatusLine(context, game.hikarinagiId, hikarinagiResult) else null
        else -> null
    }
    val statusHintIsError = when (source) {
        AppSettingsStore.COVER_SOURCE_NEXTMOE -> nextmoeUnavailable && nextmoeResult?.isFailure == true
        AppSettingsStore.COVER_SOURCE_VNDB -> vndbResult?.isFailure == true
        AppSettingsStore.COVER_SOURCE_HIKARINAGI -> hikarinagiResult?.isFailure == true
        else -> false
    }
    val onRetry: (() -> Unit)? = when {
        source == AppSettingsStore.COVER_SOURCE_NEXTMOE && statusHintIsError -> ({ nextmoeState.refreshNonce++ })
        source == AppSettingsStore.COVER_SOURCE_VNDB && statusHintIsError -> ({ vndbState.refreshNonce++ })
        source == AppSettingsStore.COVER_SOURCE_HIKARINAGI && statusHintIsError -> ({ hikarinagiState.refreshNonce++ })
        else -> null
    }
    PlayStatusPickDialog(
        sourceTitle = coverSourceTitle(source),
        options = when (source) {
            AppSettingsStore.COVER_SOURCE_BANGUMI -> bangumiStatusOptions
            AppSettingsStore.COVER_SOURCE_HIKARINAGI -> hikarinagiStatusOptions
            AppSettingsStore.COVER_SOURCE_NEXTMOE -> nextmoeStatusOptions
            else -> vndbStatusOptions
        },
        optionText = { labelRes -> stringResource(labelRes) },
        selected = when (source) {
            AppSettingsStore.COVER_SOURCE_BANGUMI -> bangumiResult?.getOrNull()?.type?.toString()
            AppSettingsStore.COVER_SOURCE_HIKARINAGI -> hikarinagiResult?.getOrNull()?.status
            AppSettingsStore.COVER_SOURCE_NEXTMOE -> nextmoeResult?.getOrNull()?.state
            else -> vndbResult?.getOrNull()?.labelIds?.firstOrNull { it in VndbLabels.ALL }?.toString()
        },
        writing = writing,
        optionsEnabled = !nextmoeUnavailable,
        statusHint = statusHint,
        statusHintIsError = statusHintIsError,
        onRetry = onRetry,
        onDismiss = onDismiss,
        onPick = onPick,
        onUnbind = onUnbind,
    )
}

@Composable
private fun PlaySourceRow(
    sourceTitle: String,
    boundId: String?,
    statusLine: String,
    busy: Boolean,
    onClick: () -> Unit,
) {
    AppNavItem(
        title = sourceTitle,
        summary = when {
            boundId.isNullOrBlank() -> stringResource(R.string.game_play_status_unbound)
            // 四源已绑定行的 statusLine 恒非空（加载中/错误/未收藏/状态词），「已绑定」兜底
            // 随 Bangumi 读回接入移除——重开弹窗显示的是远端真实状态而非绑定回显
            else -> statusLine + " · " + boundId
        },
        summaryMaxLines = Int.MAX_VALUE,
        onClick = if (busy) null else onClick,
        leadingIcon = R.drawable.ic_sheet_play_status,
        // 弹窗条目自足禁用按压反馈（AGENTS.md 弹窗条目约定）
        indication = null,
        showArrow = true,
        containerColor = DialogItemSurface,
    )
}

internal fun bangumiStatusLine(
    context: Context,
    boundId: String?,
    result: Result<PlayStatusService.BangumiStatus>?,
): String {
    if (boundId.isNullOrBlank()) return ""
    // 读回容器复用三源口径：result == null 表示回读进行中，展示加载文案
    if (result == null) return AppLocaleController.wrap(context).getString(R.string.common_loading)
    val status = result.getOrNull()
    return when {
        status == null -> (result.exceptionOrNull()?.playStatusError() ?: PlayStatusError.UNKNOWN)
            .playStatusErrorMessage(context)
        // 读回 404 = 远端未收藏（私有收藏按官方 spec 同样 404，一并归一为未收藏）
        !status.exists -> AppLocaleController.wrap(context).getString(R.string.game_play_status_bangumi_not_collected)
        status.type == null -> AppLocaleController.wrap(context).getString(R.string.game_play_status_unknown_state)
        else -> statusOptionText(context, bangumiStatusOptions, status.type.toString())
            ?: AppLocaleController.wrap(context).getString(R.string.game_play_status_unknown_state)
    }
}

internal fun vndbStatusLine(
    context: Context,
    boundId: String?,
    result: Result<PlayStatusService.VndbStatus>?,
): String {
    if (boundId.isNullOrBlank()) return ""
    if (result == null) return AppLocaleController.wrap(context).getString(R.string.common_loading)
    val status = result.getOrNull()
    return when {
        status == null -> (result.exceptionOrNull()?.playStatusError() ?: PlayStatusError.UNKNOWN)
            .playStatusErrorMessage(context)
        status.labelIds.none { it in VndbLabels.ALL } -> {
            AppLocaleController.wrap(context).getString(R.string.game_play_status_not_listed)
        }
        else -> status.labelIds.filter { it in VndbLabels.ALL }
            .mapNotNull { statusOptionText(context, vndbStatusOptions, it.toString()) }
            .joinToString(" / ")
            .ifBlank { AppLocaleController.wrap(context).getString(R.string.game_play_status_not_listed) }
    }
}

internal fun hikarinagiStatusLine(
    context: Context,
    boundId: String?,
    result: Result<PlayStatusService.HikarinagiStatus>?,
): String {
    if (boundId.isNullOrBlank()) return ""
    if (result == null) return AppLocaleController.wrap(context).getString(R.string.common_loading)
    val rate = result.getOrNull()
    return when {
        rate == null -> (result.exceptionOrNull()?.playStatusError() ?: PlayStatusError.UNKNOWN)
            .playStatusErrorMessage(context)
        !rate.exists || rate.status.isNullOrBlank() ->
            AppLocaleController.wrap(context).getString(R.string.game_play_status_hikarinagi_not_marked)
        !isHikarinagiStatus(rate.status) ->
            AppLocaleController.wrap(context).getString(R.string.game_play_status_unknown_state)
        else -> statusOptionText(context, hikarinagiStatusOptions, rate.status)
            ?: AppLocaleController.wrap(context).getString(R.string.game_play_status_unknown_state)
    }
}

internal fun nextmoeStatusLine(
    context: Context,
    boundId: String?,
    result: Result<PlayStatusService.NextMoeStatus>?,
): String {
    if (boundId.isNullOrBlank()) return ""
    if (result == null) return AppLocaleController.wrap(context).getString(R.string.common_loading)
    val state = result.getOrNull()
    return when {
        state == null -> (result.exceptionOrNull()?.playStatusError() ?: PlayStatusError.UNKNOWN)
            .playStatusErrorMessage(context)
        !state.exists || state.state.isNullOrBlank() ->
            AppLocaleController.wrap(context).getString(R.string.game_play_status_nextmoe_not_marked)
        !isNextMoeWorkState(state.state) ->
            AppLocaleController.wrap(context).getString(R.string.game_play_status_unknown_state)
        else -> statusOptionText(context, nextmoeStatusOptions, state.state)
            ?: AppLocaleController.wrap(context).getString(R.string.game_play_status_unknown_state)
    }
}

/**
 * 抽屉「游玩状态」条目副文本聚合结果：[line] 为展示文案；[cacheable] 表示所有回读来源均成功
 * （任一来源失败则整行即时展示、不入 TTL 缓存）。
 */
internal data class PlayStatusDrawerSummary(val line: String?, val cacheable: Boolean)

/**
 * 抽屉「游玩状态」条目副文本：绑定且已授权来源的远端状态一行汇总。逐源授权门：
 * 已绑定但未授权的来源既不发起回读、也不产出文案行；Bangumi 经单条收藏 GET 读回远端
 * 收藏状态（与三源同构，404 归一未收藏）。须在 Dispatchers.IO 调用。
 * 来源名统一走本地化 [coverSourceTitle(context, …)]（同口径，LOGIC-5：缓存键含 localeTag，
 * 本地化安全；VNDB/Hikarinagi/Bangumi 无本地化键时与 ASCII 原名一致）。
 */
internal fun playStatusDrawerSummary(
    context: Context,
    game: ScanGame,
    bangumiAuthorized: Boolean,
    vndbAuthorized: Boolean,
    hikarinagiAuthorized: Boolean,
    nextmoeAuthorized: Boolean = false,
): PlayStatusDrawerSummary {
    val parts = mutableListOf<String>()
    var cacheable = true
    if (vndbAuthorized) {
        game.vndbId?.takeIf { it.isNotBlank() }?.let { id ->
            val result = statusResult { PlayStatusService.readVndbStatus(context, id) }
            if (result.isFailure) cacheable = false
            parts.add(coverSourceTitle(context, AppSettingsStore.COVER_SOURCE_VNDB) + " · " + vndbStatusLine(context, id, result))
        }
    }
    if (hikarinagiAuthorized) {
        game.hikarinagiId?.takeIf { it.isNotBlank() }?.let { id ->
            val result = statusResult { PlayStatusService.readHikarinagiStatus(context, id) }
            if (result.isFailure) cacheable = false
            parts.add(coverSourceTitle(context, AppSettingsStore.COVER_SOURCE_HIKARINAGI) + " · " + hikarinagiStatusLine(context, id, result))
        }
    }
    if (nextmoeAuthorized) {
        game.nextmoeId?.takeIf { it.isNotBlank() }?.let { id ->
            val result = statusResult { PlayStatusService.readNextMoeStatus(context, id) }
            if (result.isFailure) cacheable = false
            parts.add(coverSourceTitle(context, AppSettingsStore.COVER_SOURCE_NEXTMOE) + " · " + nextmoeStatusLine(context, id, result))
        }
    }
    if (bangumiAuthorized) {
        game.bangumiId?.takeIf { it.isNotBlank() }?.let { id ->
            val result = statusResult { PlayStatusService.readBangumiStatus(context, id) }
            if (result.isFailure) cacheable = false
            parts.add(
                coverSourceTitle(context, AppSettingsStore.COVER_SOURCE_BANGUMI) + " · " +
                    bangumiStatusLine(context, id, result),
            )
        }
    }
    return PlayStatusDrawerSummary(parts.joinToString(" · ").ifBlank { null }, cacheable)
}

/**
 * 状态选择：单选列表，点选即写入（写入期间禁点）；底部提供「解绑条目」。
 * 等待语义（BK-56 文档固化）：写入期间遮罩/返回/取消钮/条目点选一致禁用——底层为阻塞 IO、
 * 单请求最坏 ~35s（connect 15s + read 20s，core/net HttpJson 超时），无中途取消途径；
 * 与凭据弹窗（CredentialTokenDialog）同口径，防半保存状态。
 */
@Composable
private fun PlayStatusPickDialog(
    sourceTitle: String,
    options: List<PlayStatusOption>,
    optionText: @Composable (Int) -> String,
    selected: String?,
    writing: Boolean,
    optionsEnabled: Boolean = true,
    statusHint: String? = null,
    statusHintIsError: Boolean = false,
    onRetry: (() -> Unit)? = null,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
    onUnbind: () -> Unit,
) {
    AppAlertDialog(
        onDismissRequest = { if (!writing) onDismiss() },
        dismissEnabled = !writing,
        title = { Text(stringResource(R.string.game_play_status_pick_title, sourceTitle), style = MaterialTheme.typography.titleMedium) },
        text = {
            val displayHint = rememberLastNonNull(statusHint?.takeIf { it.isNotBlank() })
            val displayRetry = rememberLastNonNull(onRetry)
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ExpandVisibility(visible = !statusHint.isNullOrBlank()) {
                    if (displayHint != null) {
                        BlurCrossfade(
                            targetState = displayHint to statusHintIsError,
                            label = "playStatusPickHint",
                        ) { (hintText, isError) ->
                            Text(
                                hintText,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (isError) MaterialTheme.colorScheme.error else SecondaryTextColor,
                            )
                        }
                    }
                }
                ExpandVisibility(visible = onRetry != null) {
                    if (displayRetry != null) {
                        AppNavItem(
                            title = stringResource(R.string.common_retry),
                            // 退场动画窗口内 onRetry 已置 null（displayRetry 仍保留上一帧供渲染）：
                            // 一并禁点，防止退场期连点重复递增 nextmoeRefreshNonce
                            onClick = if (writing || onRetry == null) null else displayRetry,
                            leadingIcon = R.drawable.ic_refresh,
                            indication = null,
                            showArrow = false,
                            containerColor = DialogItemSurface,
                        )
                    }
                }
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(max = 320.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    lazyItems(options, key = { it.code }, contentType = { "play_status_option" }) { option ->
                        AppNavItem(
                            title = optionText(option.labelRes),
                            summary = if (selected == option.code) {
                                stringResource(R.string.game_play_status_current)
                            } else {
                                null
                            },
                            onClick = if (writing || !optionsEnabled) null else ({ onPick(option.code) }),
                            leadingIcon = R.drawable.ic_sheet_play_status,
                            // 弹窗条目自足禁用按压反馈（AGENTS.md 弹窗条目约定）
                            indication = null,
                            // 点选即写入的「执行动作」条目：隐藏跳转箭头（AGENTS.md 功能跳转条目规范）
                            showArrow = false,
                            containerColor = DialogItemSurface,
                        )
                    }
                    item(key = "unbind", contentType = "play_status_unbind") {
                        AppNavItem(
                            title = stringResource(R.string.game_play_status_unbind),
                            // 大字体窄屏下与候选条目同口径允许两行（候选条目见 titleMaxLines = 2）
                            titleMaxLines = 2,
                            onClick = if (writing) null else ({ onUnbind() }),
                            leadingIcon = R.drawable.ic_sheet_delete,
                            leadingIconTint = MaterialTheme.colorScheme.error,
                            titleColor = MaterialTheme.colorScheme.error,
                            // 弹窗条目自足禁用按压反馈（AGENTS.md 弹窗条目约定）
                            indication = null,
                            // 破坏性动作条目：隐藏跳转箭头（AGENTS.md 功能跳转条目规范）
                            showArrow = false,
                            containerColor = DialogItemSurface,
                        )
                    }
                }
            }
        },
        confirmButton = {
            DialogTextButton(
                text = stringResource(R.string.common_cancel),
                enabled = !writing,
                // 与遮罩/返回一致：写期间不允许取消（写协程后台继续）——底层单请求最坏 ~35s
                // （connect 15s + read 20s，HttpJson 超时），无中途取消途径，等待语义与凭据
                // 弹窗同口径（BK-56），防半保存状态
                onClick = { if (!writing) onDismiss() },
            )
        },
    )
}

private sealed interface SubjectBindContentState {
    data object Idle : SubjectBindContentState
    data object Searching : SubjectBindContentState
    data class Error(val error: CoverErrorCode) : SubjectBindContentState
    data object Empty : SubjectBindContentState
    data class Results(val candidates: List<CoverSearchCandidate>) : SubjectBindContentState
}

/**
 * 条目绑定：关键词搜索 Bangumi / VNDB / Hikarinagi / 未萌 NextMoe 候选，点选即绑定（只写绑定 id，不动封面）。
 * 搜索与封面刮削共用候选映射，但不受封面来源启用开关约束。等待语义（BK-56 文档固化）：
 * 绑定进行中遮罩/返回/取消钮/候选点选一致禁用，搜索与绑定均为阻塞 IO、单请求最坏 ~35s
 * （HttpJson 超时），无中途取消途径，与状态选择弹窗/凭据弹窗同口径。
 */
@Composable
private fun SubjectBindDialog(
    game: ScanGame,
    source: String,
    onDismiss: () -> Unit,
    onBound: (ScanGame) -> Unit,
) {
    val context = LocalContext.current
    val latestGame by rememberUpdatedState(game)
    var keyword by rememberSaveable(source, game.uri) { mutableStateOf(game.title) }
    var searching by remember(source, game.uri) { mutableStateOf(false) }
    var noMatch by rememberSaveable(source, game.uri) { mutableStateOf(false) }
    var errorCode by rememberSaveable(source, game.uri) { mutableStateOf<CoverErrorCode?>(null) }
    var candidates by rememberSaveable(source, game.uri, stateSaver = CoverSearchCandidatesSaver) {
        mutableStateOf(emptyList<CoverSearchCandidate>())
    }
    var binding by remember(source, game.uri) { mutableStateOf(false) }
    var searchRejects by remember(source, game.uri) { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()

    fun search() {
        if (searching || binding) return
        val query = keyword.trim()
        if (query.isEmpty()) {
            searchRejects++
            return
        }
        searching = true
        noMatch = false
        errorCode = null
        candidates = emptyList()
        scope.launch {
            try {
                when (
                    val result = withContext(Dispatchers.IO) {
                        PlayStatusService.searchBindingCandidates(context, source, query)
                    }
                ) {
                    is CoverSearchResult.Success -> {
                        // 同 source+id 候选去重：列表 key 以 source:id 唯一，
                        // 上游重复条目会使 LazyColumn 因重复 key 崩溃
                        candidates = result.candidates.distinctBy { it.source + ":" + it.id }
                        noMatch = candidates.isEmpty()
                    }
                    is CoverSearchResult.Failure -> errorCode = result.error
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                errorCode = CoverErrorCode.UNKNOWN
            } finally {
                searching = false
            }
        }
    }

    fun bind(candidate: CoverSearchCandidate) {
        if (searching || binding) return
        val targetGame = latestGame
        binding = true
        scope.launch {
            val updated = try {
                withContext(Dispatchers.IO) {
                    try {
                        PlayStatusService.bindSubject(context, targetGame, source, candidate.id, candidate.metadataTitle)
                            ?.also { PlayStatusSummaryCache.invalidate(targetGame.uri) }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (t: Throwable) {
                        // 绑定失败按既有语义提示 AppNotice，留日志便于排障
                        Log.w(PLAY_STATUS_LOG_TAG, "bind subject failed", t)
                        null
                    }
                }
            } finally {
                binding = false
            }
            if (updated != null) {
                onBound(updated)
            } else {
                AppNotice.makeText(
                    context,
                    AppLocaleController.wrap(context).getString(R.string.game_bind_subject_failed),
                    Toast.LENGTH_SHORT,
                    AppNotice.Kind.Failure,
                ).show()
            }
        }
    }

    AppAlertDialog(
        onDismissRequest = { if (!binding) onDismiss() },
        dismissEnabled = !binding,
        title = {
            Text(
                stringResource(R.string.game_bind_subject_title, coverSourceTitle(source)),
                style = MaterialTheme.typography.titleMedium,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                // 输入区内禁用全部点击/按压反馈（AGENTS.md 弹窗点击反馈统一规范）
                CompositionLocalProvider(LocalIndication provides NoIndication) {
                    AppSearchField(
                        query = keyword,
                        onQueryChange = { keyword = it },
                        onSearch = { search() },
                        imeAction = ImeAction.Search,
                        leadingIcon = painterResource(R.drawable.ic_game_search),
                        iconContentDescription = stringResource(R.string.game_bind_subject_search),
                        textStyle = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.shakeOnTrigger(searchRejects, amplitude = 6.dp),
                    )
                }
                val contentState = when {
                    searching -> SubjectBindContentState.Searching
                    errorCode != null -> SubjectBindContentState.Error(errorCode!!)
                    noMatch -> SubjectBindContentState.Empty
                    candidates.isNotEmpty() -> SubjectBindContentState.Results(candidates)
                    else -> SubjectBindContentState.Idle
                }
                val activeContent = contentState.takeIf { it != SubjectBindContentState.Idle }
                val shownContent = rememberLastNonNull(activeContent)
                ExpandVisibility(
                    visible = activeContent != null,
                    modifier = Modifier.fillMaxWidth().weight(1f, fill = false).clipToBounds(),
                    label = "subjectBindExpand",
                ) {
                    BlurCrossfade(
                        targetState = shownContent ?: SubjectBindContentState.Idle,
                        modifier = Modifier.fillMaxWidth(),
                        label = "subjectBindContent",
                    ) { state ->
                        when (state) {
                            SubjectBindContentState.Idle -> Unit
                            SubjectBindContentState.Searching -> Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(22.dp))
                            }
                            is SubjectBindContentState.Error -> Text(
                                state.error.coverErrorMessage(context),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error,
                            )
                            SubjectBindContentState.Empty -> Text(
                                stringResource(R.string.game_bind_subject_no_match),
                                style = MaterialTheme.typography.bodyMedium,
                                color = SecondaryTextColor,
                            )
                            is SubjectBindContentState.Results -> LazyColumn(
                                modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                lazyItems(
                                    state.candidates,
                                    key = { it.source + ":" + it.id },
                                    contentType = { "bind_candidate" },
                                ) { candidate ->
                                    AppNavItem(
                                        title = candidate.title,
                                        titleMaxLines = 2,
                                        summary = candidate.detail.ifBlank { candidate.subtitle },
                                        onClick = if (searching || binding) null else ({ bind(candidate) }),
                                        leadingIcon = R.drawable.ic_cover_source,
                                        // 弹窗条目自足禁用按压反馈（AGENTS.md 弹窗条目约定）
                                        indication = null,
                                        // 点选即绑定的「执行动作」条目：隐藏跳转箭头（AGENTS.md 功能跳转条目规范）
                                        showArrow = false,
                                        containerColor = DialogItemSurface,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        dismissButton = {
            DialogTextButton(
                text = stringResource(R.string.common_cancel),
                enabled = !binding,
                // 与遮罩/返回键一致：绑定进行中不允许取消（绑定协程后台继续，同状态选择弹窗口径；
                // BK-56：单请求最坏 ~35s 无中途取消途径，等待语义与凭据弹窗同口径）
                onClick = { if (!binding) onDismiss() },
            )
        },
        confirmButton = {
            val canSearch = keyword.trim().isNotEmpty() && !searching && !binding
            BlurCrossfade(
                targetState = searching,
                contentAlignment = Alignment.Center,
                label = "subjectBindSearchButton",
            ) { isSearching ->
                DialogTextButton(
                    text = if (isSearching) stringResource(R.string.game_searching) else stringResource(R.string.game_search),
                    enabled = canSearch,
                    onClick = { search() },
                )
            }
        },
    )
}

/**
 * 写操作回落到 UI 时的状态决策：
 * - [applyOptimisticResult]：目标绑定与登录会话代次（区别于后台刷新递增的 credentialEpoch）均仍一致时为 true；
 * - [triggerBangumiReread]：同登录会话期间 Bangumi 发生了令牌轮换时为 true，写后追加一次确认回读；
 * - [triggerHikarinagiReread]：同登录会话期间 Hikarinagi 发生了令牌轮换时为 true，写后追加一次确认回读；
 * - [triggerNextMoeReread]：同登录会话期间未萌 NextMoe 发生了令牌轮换时为 true，写后追加一次确认回读。
 */
internal data class PlayStatusWriteDecision(
    val applyOptimisticResult: Boolean,
    val triggerHikarinagiReread: Boolean,
    val triggerNextMoeReread: Boolean = false,
    val triggerBangumiReread: Boolean = false,
)

internal fun evaluatePlayStatusWriteDecision(
    source: String,
    targetUri: String,
    boundId: String,
    sourceSessionEpoch: Long,
    sourceCredentialEpoch: Long,
    latestGame: ScanGame,
    liveBangumiSessionEpoch: Long = BangumiAuthStore.sessionEpoch.value,
    liveVndbSessionEpoch: Long = VndbAuthStore.sessionEpoch.value,
    liveHikarinagiSessionEpoch: Long = HikarinagiAuthStore.sessionEpoch.value,
    liveHikarinagiCredentialEpoch: Long = HikarinagiAuthStore.credentialEpoch.value,
    liveNextMoeSessionEpoch: Long = NextMoeAuthStore.sessionEpoch.value,
    liveNextMoeCredentialEpoch: Long = NextMoeAuthStore.credentialEpoch.value,
    liveBangumiCredentialEpoch: Long = BangumiAuthStore.credentialEpoch.value,
): PlayStatusWriteDecision {
    val stillSameSession = latestGame.uri == targetUri && when (source) {
        AppSettingsStore.COVER_SOURCE_BANGUMI ->
            latestGame.bangumiId == boundId && liveBangumiSessionEpoch == sourceSessionEpoch
        AppSettingsStore.COVER_SOURCE_VNDB ->
            latestGame.vndbId == boundId && liveVndbSessionEpoch == sourceSessionEpoch
        AppSettingsStore.COVER_SOURCE_HIKARINAGI ->
            latestGame.hikarinagiId == boundId && liveHikarinagiSessionEpoch == sourceSessionEpoch
        AppSettingsStore.COVER_SOURCE_NEXTMOE ->
            latestGame.nextmoeId == boundId && liveNextMoeSessionEpoch == sourceSessionEpoch
        else -> false
    }
    val triggerBangumiReread = stillSameSession &&
        source == AppSettingsStore.COVER_SOURCE_BANGUMI &&
        liveBangumiCredentialEpoch != sourceCredentialEpoch
    val triggerHikarinagiReread = stillSameSession &&
        source == AppSettingsStore.COVER_SOURCE_HIKARINAGI &&
        liveHikarinagiCredentialEpoch != sourceCredentialEpoch
    val triggerNextMoeReread = stillSameSession &&
        source == AppSettingsStore.COVER_SOURCE_NEXTMOE &&
        liveNextMoeCredentialEpoch != sourceCredentialEpoch
    return PlayStatusWriteDecision(
        applyOptimisticResult = stillSameSession,
        triggerHikarinagiReread = triggerHikarinagiReread,
        triggerNextMoeReread = triggerNextMoeReread,
        triggerBangumiReread = triggerBangumiReread,
    )
}
