package com.tyranor.next.ui.cover

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tyranor.next.R
import androidx.compose.ui.graphics.Color as ComposeColor
import com.tyranor.next.core.cover.CoverScrapeTaskManager
import com.tyranor.next.core.auth.BangumiAuthService
import com.tyranor.next.core.auth.BangumiAuthStore
import com.tyranor.next.core.auth.BangumiTokenVerification
import com.tyranor.next.core.auth.HikarinagiAuthService
import com.tyranor.next.core.auth.HikarinagiAuthStore
import com.tyranor.next.core.auth.NextMoeAuthError
import com.tyranor.next.core.auth.NextMoeAuthService
import com.tyranor.next.core.auth.NextMoeAuthStatus
import com.tyranor.next.core.auth.NextMoeAuthStore
import com.tyranor.next.core.auth.NextMoeLoopbackPageText
import com.tyranor.next.core.auth.VndbAuthStore
import com.tyranor.next.core.auth.VndbPermissionTier
import com.tyranor.next.core.i18n.AppLocaleController
import com.tyranor.next.core.settings.AppSettingsStore
import com.tyranor.next.core.status.PlayStatusError
import com.tyranor.next.core.status.PlayStatusException
import com.tyranor.next.core.status.PlayStatusService
import com.tyranor.next.theme.PageTextColor
import com.tyranor.next.theme.DialogItemSurface
import com.tyranor.next.theme.MiuixSettingsTheme
import com.tyranor.next.theme.TextColor
import com.tyranor.next.theme.glassBorder
import com.tyranor.next.ui.common.AppScreenActivity
import com.tyranor.next.ui.common.AppSecondaryTopBar
import com.tyranor.next.ui.common.NoRippleButton
import com.tyranor.next.ui.common.NoRippleTextButton
import com.tyranor.next.ui.auth.BangumiOAuthCallbackActivity
import com.tyranor.next.ui.auth.authErrorMessage
import com.tyranor.next.ui.common.motion.BlurCrossfade
import com.tyranor.next.ui.common.motion.ExpandHorizontallyVisibility
import com.tyranor.next.ui.common.motion.reorderPlacement
import com.tyranor.next.ui.common.motion.ProvideEntrance
import com.tyranor.next.ui.common.motion.breathing
import com.tyranor.next.ui.common.motion.skipEntranceOnDrag
import com.tyranor.next.ui.common.motion.RollingNumberText
import com.tyranor.next.ui.common.motion.pressSquishGroup
import com.tyranor.next.ui.common.motion.staggeredEntrance
import com.tyranor.next.ui.auth.HikarinagiOAuthCallbackActivity
import com.tyranor.next.theme.AppComponentShape
import com.tyranor.next.theme.AppComponentCornerRadius
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card as MiuixCard
import top.yukonga.miuix.kmp.basic.Scaffold as MiuixScaffold
import com.tyranor.next.ui.common.Switch
import com.tyranor.next.ui.common.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import com.tyranor.next.ui.common.collectSettingAsState
import com.tyranor.next.ui.common.notice.AppNotice

/** 「授权中」标记的陈旧阈值：超过即认为授权流程被进程死亡打断，提示重试。 */
private const val AUTH_IN_PROGRESS_STALE_MS = 15 * 60_000L

/**
 * VNDB 站点对匿名/占位用户名的显示口径：设置页来源行不能把该占位值当作真实用户名
 * 拼进「来源 (用户名)」标题。
 */
private const val VNDB_ANONYMOUS_USERNAME = "VNDB"

class CoverScraperSettingsActivity : AppScreenActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setAppScreenContent {
            CoverScraperSettingsScreen()
        }
    }

    companion object {
        fun createIntent(context: Context): Intent =
            Intent(context, CoverScraperSettingsActivity::class.java)
    }
}

/**
 * 编辑封面来源优先级与抓取选项，订阅任务管理器展示批量结果并消费一次性完成事件。
 * 来源以自身身份参与组合，重排只改变序号与位置，不把授权或开关状态转交给其他来源。
 */
@Composable
internal fun CoverScraperSettingsScreen() {
    val ctx = LocalContext.current
    val activity = AppLocaleController.findActivity(ctx) as? ComponentActivity
    val authVersion by HikarinagiAuthStore.statusVersion.collectAsState()
    val bangumiAuthVersion by BangumiAuthStore.statusVersion.collectAsState()
    val vndbAuthVersion by VndbAuthStore.statusVersion.collectAsState()
    val nextmoeAuthVersion by NextMoeAuthStore.statusVersion.collectAsState()
    val nextmoeAuthEvent by NextMoeAuthService.authEvent.collectAsState()
    val settingsVersion by AppSettingsStore.coverScraperSettingsVersion.collectSettingAsState()
    val scrapeTaskState by CoverScrapeTaskManager.state.collectAsState()
    var sources by remember(settingsVersion) { mutableStateOf(AppSettingsStore.getCoverScraperSourceOrder(ctx)) }
    var onlyMissing by remember(settingsVersion) { mutableStateOf(AppSettingsStore.isCoverScraperOnlyMissing(ctx)) }
    var vndbLargeCover by remember(settingsVersion) { mutableStateOf(AppSettingsStore.isVndbLargeCover(ctx)) }
    var showVndbTokenDialog by remember { mutableStateOf(false) }
    var showBangumiTokenDialog by remember { mutableStateOf(false) }
    val scraping = scrapeTaskState.running
    // 以下四次 remember 内的 getStatus 为 Keystore 解密读取，在组合期（主线程）调用为既有契约
    // （毫秒级开销，与凭据弹窗 CredentialTokenDialog / 游戏抽屉授权门 GameScreen 同模式）：
    // 真机出现可感卡顿或 StrictMode 违规时按「分点下沉 IO」统一改造（produceState + 各自
    // statusVersion 键，BK-01 施工图在案），消费面（往下传参）不变（BK-01B 观察注记）
    val authStatus = remember(authVersion) { HikarinagiAuthStore.getStatus(ctx) }
    val bangumiAuthStatus = remember(bangumiAuthVersion) { BangumiAuthStore.getStatus(ctx) }
    val vndbAuthStatus = remember(vndbAuthVersion) { VndbAuthStore.getStatus(ctx) }
    val nextmoeAuthStatus = remember(nextmoeAuthVersion) { NextMoeAuthStore.getStatus(ctx) }
    // 发起授权后进程死亡会让回调永不抵达：陈旧的「授权中」标记提示用户重试。
    // 陈旧性只在授权状态版本变化时重算（BK-02：随 bangumiAuthStatus 记忆化，墙钟读取收敛进
    // remember 计算块，不再每次重组执行）；长时间停留页面不重算为既有取舍——authInProgress
    // 标记仅在授权发起/回调时写，停留期间无新事件可驱动重算
    val bangumiAuthStale = remember(bangumiAuthStatus) {
        !bangumiAuthStatus.authorized && !bangumiAuthStatus.needsReauth &&
            bangumiAuthStatus.authInProgressAtMillis > 0L &&
            System.currentTimeMillis() - bangumiAuthStatus.authInProgressAtMillis > AUTH_IN_PROGRESS_STALE_MS
    }
    val nextmoeAuthStale = remember(nextmoeAuthStatus) {
        !nextmoeAuthStatus.authorized && !nextmoeAuthStatus.needsReauth &&
            nextmoeAuthStatus.authInProgressAtMillis > 0L &&
            System.currentTimeMillis() - nextmoeAuthStatus.authInProgressAtMillis > AUTH_IN_PROGRESS_STALE_MS
    }
    val batchScrapeRunningMessage = stringResource(R.string.game_batch_scraping_running)
    val scrapeResultMessage = scrapeTaskState.result?.let { result ->
        stringResource(R.string.cover_batch_result, result.updatedCount, result.skippedCount, result.failedCount)
    }

    LaunchedEffect(authStatus.authorized, authStatus.needsReauth, authStatus.username) {
        if (authStatus.authorized && !authStatus.needsReauth && authStatus.username.isBlank()) {
            withContext(Dispatchers.IO) {
                HikarinagiAuthService.ensureUsernameLoaded(ctx)
            }
        }
    }

    LaunchedEffect(bangumiAuthStatus.authorized, bangumiAuthStatus.needsReauth, bangumiAuthStatus.username) {
        if (bangumiAuthStatus.authorized && !bangumiAuthStatus.needsReauth && bangumiAuthStatus.username.isBlank()) {
            withContext(Dispatchers.IO) {
                BangumiAuthService.ensureIdentityLoaded(ctx)
            }
        }
    }

    LaunchedEffect(nextmoeAuthStatus.authorized, nextmoeAuthStatus.needsReauth, nextmoeAuthStatus.username) {
        if (nextmoeAuthStatus.authorized && !nextmoeAuthStatus.needsReauth && nextmoeAuthStatus.username.isBlank()) {
            withContext(Dispatchers.IO) {
                NextMoeAuthService.ensureUsernameLoaded(ctx)
            }
        }
    }

    LaunchedEffect(nextmoeAuthEvent?.eventId) {
        val event = nextmoeAuthEvent ?: return@LaunchedEffect
        val message = if (event.success) {
            AppLocaleController.wrap(ctx).getString(R.string.auth_nextmoe_success)
        } else {
            (event.error ?: NextMoeAuthError.FAILED).authErrorMessage(ctx, event.detail)
        }
        val kind = when {
            event.success -> AppNotice.Kind.Success
            event.error == NextMoeAuthError.CANCELLED -> AppNotice.Kind.Info
            else -> AppNotice.Kind.Failure
        }
        AppNotice.makeText(ctx, message, Toast.LENGTH_SHORT, kind).show()
        NextMoeAuthService.clearAuthEvent(event.eventId)
    }

    // 预热 Bangumi/Hikarinagi 授权服务：消除点击「授权」时的 AuthorizationService 构造卡顿
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            BangumiAuthService.prewarm(ctx)
            HikarinagiAuthService.prewarm(ctx)
        }
    }

    LaunchedEffect(scrapeTaskState.eventId) {
        if (scrapeTaskState.eventId == 0L) return@LaunchedEffect
        scrapeResultMessage?.let { message ->
            AppNotice.makeText(
                ctx,
                message,
                Toast.LENGTH_SHORT, AppNotice.Kind.Info,
            ).show()
        }
        scrapeTaskState.error?.let { error ->
            AppNotice.makeText(ctx, error.coverErrorMessage(ctx), Toast.LENGTH_SHORT, AppNotice.Kind.Failure).show()
        }
        CoverScrapeTaskManager.clearFinished(scrapeTaskState.eventId)
    }

    fun startBatchScrape() {
        if (!CoverScrapeTaskManager.start(ctx)) {
            AppNotice.makeText(ctx, batchScrapeRunningMessage, Toast.LENGTH_SHORT, AppNotice.Kind.Limit).show()
        }
    }

    MiuixSettingsTheme {
      ProvideEntrance {
        MiuixScaffold(
            modifier = Modifier.fillMaxSize().skipEntranceOnDrag(),
            containerColor = ComposeColor.Transparent,
            contentWindowInsets = WindowInsets(0.dp),
            topBar = { CoverScraperTopBar() },
        ) { innerPadding ->
            LazyColumn(
                // 顶栏透明：列表整体垫在顶栏下方（持久 padding），避免滚动时内容穿过顶栏
                modifier = Modifier.fillMaxSize()
                    .padding(horizontal = 12.dp)
                    .padding(top = innerPadding.calculateTopPadding()),
                contentPadding = PaddingValues(
                    top = 12.dp,
                    bottom = 24.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(),
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    val rowState = CoverSourceRowState(
                        hikarinagiStatus = authStatus,
                        bangumiStatus = bangumiAuthStatus,
                        bangumiAuthStale = bangumiAuthStale,
                        vndbStatus = vndbAuthStatus,
                        nextmoeStatus = nextmoeAuthStatus,
                        nextmoeAuthStale = nextmoeAuthStale,
                        onHikarinagiAuthClick = {
                            if (authStatus.authorized && !authStatus.needsReauth) {
                                HikarinagiAuthStore.clear(ctx)
                            } else if (activity == null) {
                                AppNotice.makeText(
                                    ctx,
                                    com.tyranor.next.core.auth.HikarinagiAuthError.LAUNCH_FAILED.authErrorMessage(ctx),
                                    Toast.LENGTH_SHORT,
                                    AppNotice.Kind.Failure,
                                ).show()
                            } else {
                                val error = HikarinagiAuthService.startAuthorization(
                                    activity,
                                    Intent(activity, HikarinagiOAuthCallbackActivity::class.java),
                                )
                                if (error != null) {
                                    AppNotice.makeText(ctx, error.authErrorMessage(ctx), Toast.LENGTH_SHORT, AppNotice.Kind.Failure).show()
                                }
                            }
                        },
                        onBangumiAuthClick = {
                            if (bangumiAuthStatus.authorized && !bangumiAuthStatus.needsReauth) {
                                BangumiAuthStore.clear(ctx)
                            } else if (activity == null) {
                                AppNotice.makeText(
                                    ctx,
                                    com.tyranor.next.core.auth.BangumiAuthError.LAUNCH_FAILED.authErrorMessage(ctx),
                                    Toast.LENGTH_SHORT,
                                    AppNotice.Kind.Failure,
                                ).show()
                            } else {
                                val error = BangumiAuthService.startAuthorization(
                                    activity,
                                    Intent(activity, BangumiOAuthCallbackActivity::class.java),
                                )
                                if (error != null) {
                                    AppNotice.makeText(ctx, error.authErrorMessage(ctx), Toast.LENGTH_SHORT, AppNotice.Kind.Failure).show()
                                }
                            }
                        },
                        onBangumiTokenClick = { showBangumiTokenDialog = true },
                        onVndbAuthClick = {
                            when (vndbAuthRowAction(vndbAuthStatus.authorized, vndbAuthStatus.needsReauth)) {
                                VndbAuthRowAction.LOG_OUT -> VndbAuthStore.clear(ctx)
                                VndbAuthRowAction.SHOW_TOKEN_DIALOG -> showVndbTokenDialog = true
                            }
                        },
                        onNextMoeAuthorizeClick = {
                            if (activity == null) {
                                AppNotice.makeText(
                                    ctx,
                                    NextMoeAuthError.LAUNCH_FAILED.authErrorMessage(ctx),
                                    Toast.LENGTH_SHORT,
                                    AppNotice.Kind.Failure,
                                ).show()
                            } else {
                                val localized = AppLocaleController.wrap(ctx)
                                val error = NextMoeAuthService.startAuthorization(
                                    activity,
                                    NextMoeLoopbackPageText(
                                        title = localized.getString(R.string.auth_nextmoe_loopback_page_title),
                                        successMessage = localized.getString(R.string.auth_nextmoe_loopback_success_body),
                                        cancelledMessage = localized.getString(R.string.auth_nextmoe_loopback_cancelled_body),
                                        errorMessage = localized.getString(R.string.auth_nextmoe_loopback_error_body),
                                    ),
                                )
                                if (error != null) {
                                    AppNotice.makeText(ctx, error.authErrorMessage(ctx), Toast.LENGTH_SHORT, AppNotice.Kind.Failure).show()
                                }
                            }
                        },
                        onNextMoeClearClick = { NextMoeAuthStore.clear(ctx) },
                    )
                    MiuixCard(modifier = Modifier.pressSquishGroup().staggeredEntrance(0).fillMaxWidth().glassBorder(), cornerRadius = AppComponentCornerRadius) {
                        Column(Modifier.padding(vertical = 4.dp)) {
                            sources.forEachIndexed { index, source ->
                              // 以来源为 key：重排时行状态跟着来源走，序号数字滚动
                              key(source) {
                                CoverSourceRow(
                                    index = index,
                                    count = sources.size,
                                    source = source,
                                    enabled = AppSettingsStore.isCoverScraperSourceEnabled(ctx, source),
                                    rowState = rowState,
                                    onEnabledChange = { enabled ->
                                        AppSettingsStore.setCoverScraperSourceEnabled(ctx, source, enabled)
                                    },
                                    onMoveUp = {
                                        AppSettingsStore.moveCoverScraperSource(ctx, source, -1)
                                        sources = AppSettingsStore.getCoverScraperSourceOrder(ctx)
                                    },
                                    onMoveDown = {
                                        AppSettingsStore.moveCoverScraperSource(ctx, source, 1)
                                        sources = AppSettingsStore.getCoverScraperSourceOrder(ctx)
                                    },
                                )
                              }
                            }
                        }
                    }
                }

                item {
                    MiuixCard(modifier = Modifier.pressSquishGroup().staggeredEntrance(1).fillMaxWidth().glassBorder(), cornerRadius = AppComponentCornerRadius) {
                        Column(Modifier.padding(vertical = 4.dp)) {
                            SwitchPreference(
                                title = stringResource(R.string.cover_only_missing_title),
                                summary = stringResource(R.string.cover_only_missing_summary),
                                checked = onlyMissing,
                                onCheckedChange = { checked ->
                                    onlyMissing = checked
                                    AppSettingsStore.setCoverScraperOnlyMissing(ctx, checked)
                                },
                            )
                            SwitchPreference(
                                title = stringResource(R.string.cover_vndb_large_cover_title),
                                summary = stringResource(R.string.cover_vndb_large_cover_summary),
                                checked = vndbLargeCover,
                                onCheckedChange = { checked ->
                                    vndbLargeCover = checked
                                    AppSettingsStore.setVndbLargeCover(ctx, checked)
                                },
                            )
                        }
                    }
                }

                item {
                    MiuixCard(modifier = Modifier.pressSquishGroup().staggeredEntrance(2).fillMaxWidth().glassBorder(), cornerRadius = AppComponentCornerRadius) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text(
                                stringResource(R.string.cover_write_content_title),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = TextColor,
                            )
                            Text(
                                stringResource(R.string.cover_write_content_summary),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            // 无按压反馈：Material Button 内部走 ripple()，不经过 LocalIndication
                            // 「开始批量抓取」↔「抓取中」整颗按钮模糊替换（文案与可用态底色一起过渡）；
                            // 抓取进行中按钮缓缓明灭
                            BlurCrossfade(
                                targetState = scraping,
                                modifier = Modifier.fillMaxWidth(),
                                label = "coverBatchButton",
                            ) { shownScraping ->
                                NoRippleButton(
                                    text = if (shownScraping) stringResource(R.string.cover_scraping_batch) else stringResource(R.string.cover_start_batch),
                                    modifier = Modifier.fillMaxWidth().breathing(shownScraping),
                                    // 可用态取实时状态：退场中的旧按钮也不能重复启动
                                    enabled = !scraping,
                                    onClick = { startBatchScrape() },
                                )
                            }
                        }
                    }
                }
            }
        }
      }
    }

    if (showVndbTokenDialog) {
        VndbTokenDialog(onDismiss = { showVndbTokenDialog = false })
    }

    if (showBangumiTokenDialog) {
        BangumiTokenDialog(onDismiss = { showBangumiTokenDialog = false })
    }
}

/** 收敛各来源授权状态与行级动作回调的参数对象（BK-15B 重构）。 */
private data class CoverSourceRowState(
    val hikarinagiStatus: com.tyranor.next.core.auth.HikarinagiAuthStatus,
    val bangumiStatus: com.tyranor.next.core.auth.BangumiAuthStatus,
    val bangumiAuthStale: Boolean,
    val vndbStatus: com.tyranor.next.core.auth.VndbAuthStatus,
    val nextmoeStatus: NextMoeAuthStatus,
    val nextmoeAuthStale: Boolean,
    val onHikarinagiAuthClick: () -> Unit,
    val onBangumiAuthClick: () -> Unit,
    val onBangumiTokenClick: () -> Unit,
    val onVndbAuthClick: () -> Unit,
    val onNextMoeAuthorizeClick: () -> Unit,
    val onNextMoeClearClick: () -> Unit,
)

@Composable
private fun CoverSourceRow(
    index: Int,
    count: Int,
    source: String,
    enabled: Boolean,
    rowState: CoverSourceRowState,
    onEnabledChange: (Boolean) -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
) {
    Row(
        modifier = Modifier
            // 上移 / 下移后行从旧位置弹簧滑到新位置（必须在链最外层，读取的是父布局给的位置）
            .reorderPlacement()
            // 卡片内各来源行跟在卡片之后依次轻轻升起
            .staggeredEntrance(index, distance = 12.dp)
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(AppComponentShape)
                // 序号徽标底色：玻璃风格用亮玻璃面，避免透明不可见
                .background(DialogItemSurface),
            contentAlignment = Alignment.Center,
        ) {
            RollingNumberText(
                "${index + 1}",
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                color = MiuixTheme.colorScheme.primary,
            )
        }
        Column(
            modifier = Modifier.padding(start = 12.dp).weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            val baseTitle = coverSourceTitle(source)
            // 来源行标题统一走单一拼装（含 VNDB 占位用户名排除），不再各写一份
            val title = when (source) {
                AppSettingsStore.COVER_SOURCE_HIKARINAGI -> authorizedSourceTitle(
                    baseTitle,
                    rowState.hikarinagiStatus.authorized,
                    rowState.hikarinagiStatus.username,
                )
                AppSettingsStore.COVER_SOURCE_VNDB -> authorizedSourceTitle(
                    baseTitle,
                    rowState.vndbStatus.authorized &&
                        !rowState.vndbStatus.username.equals(VNDB_ANONYMOUS_USERNAME, ignoreCase = true),
                    rowState.vndbStatus.username,
                )
                AppSettingsStore.COVER_SOURCE_BANGUMI -> bangumiSourceTitle(baseTitle, rowState.bangumiStatus)
                AppSettingsStore.COVER_SOURCE_NEXTMOE -> authorizedSourceTitle(
                    baseTitle,
                    rowState.nextmoeStatus.authorized,
                    rowState.nextmoeStatus.username,
                )
                else -> baseTitle
            }
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = TextColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val summary = sourceSummary(source, rowState)
            BlurCrossfade(targetState = summary) { text ->
                Text(
                    text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                NoRippleTextButton(
                    text = stringResource(R.string.cover_move_up),
                    enabled = index > 0,
                    onClick = onMoveUp,
                )
                NoRippleTextButton(
                    text = stringResource(R.string.cover_move_down),
                    enabled = index < count - 1,
                    onClick = onMoveDown,
                )
                when (source) {
                    AppSettingsStore.COVER_SOURCE_HIKARINAGI -> {
                        val liveSignedIn = rowState.hikarinagiStatus.authorized && !rowState.hikarinagiStatus.needsReauth
                        BlurCrossfade(
                            targetState = liveSignedIn,
                            label = "coverAuthAction",
                        ) { signedIn ->
                            NoRippleTextButton(
                                text = if (signedIn) {
                                    stringResource(R.string.cover_logout)
                                } else {
                                    stringResource(R.string.cover_authorize)
                                },
                                enabled = signedIn == liveSignedIn,
                                onClick = rowState.onHikarinagiAuthClick,
                            )
                        }
                    }
                    AppSettingsStore.COVER_SOURCE_BANGUMI -> {
                        val liveSignedIn = rowState.bangumiStatus.authorized && !rowState.bangumiStatus.needsReauth
                        BlurCrossfade(
                            targetState = liveSignedIn,
                            label = "bangumiAuthAction",
                        ) { signedIn ->
                            NoRippleTextButton(
                                text = if (signedIn) {
                                    stringResource(R.string.cover_logout)
                                } else {
                                    stringResource(R.string.cover_authorize)
                                },
                                enabled = signedIn == liveSignedIn,
                                onClick = rowState.onBangumiAuthClick,
                            )
                        }
                        ExpandHorizontallyVisibility(
                            visible = !liveSignedIn,
                            label = "bangumiTokenAction",
                        ) {
                            NoRippleTextButton(
                                text = stringResource(R.string.cover_token),
                                enabled = !liveSignedIn,
                                onClick = rowState.onBangumiTokenClick,
                            )
                        }
                    }
                    AppSettingsStore.COVER_SOURCE_VNDB -> {
                        val liveSignedIn = rowState.vndbStatus.authorized && !rowState.vndbStatus.needsReauth
                        BlurCrossfade(
                            targetState = liveSignedIn,
                            label = "vndbAuthAction",
                        ) { signedIn ->
                            NoRippleTextButton(
                                text = if (signedIn) {
                                    stringResource(R.string.cover_logout)
                                } else {
                                    stringResource(R.string.cover_authorize)
                                },
                                enabled = signedIn == liveSignedIn,
                                onClick = rowState.onVndbAuthClick,
                            )
                        }
                    }
                    AppSettingsStore.COVER_SOURCE_NEXTMOE -> {
                        val actions = nextMoeAuthRowActions(
                            authorized = rowState.nextmoeStatus.authorized,
                            needsReauth = rowState.nextmoeStatus.needsReauth,
                        )
                        ExpandHorizontallyVisibility(
                            visible = actions.showAuthorize,
                            label = "nextmoeAuthorizeAction",
                        ) {
                            NoRippleTextButton(
                                text = stringResource(R.string.cover_authorize),
                                enabled = actions.showAuthorize,
                                onClick = rowState.onNextMoeAuthorizeClick,
                            )
                        }
                        ExpandHorizontallyVisibility(
                            visible = actions.showClear,
                            label = "nextmoeClearAction",
                        ) {
                            NoRippleTextButton(
                                text = stringResource(R.string.cover_logout),
                                enabled = actions.showClear,
                                onClick = rowState.onNextMoeClearClick,
                            )
                        }
                    }
                }
            }
        }
        Switch(checked = enabled, onCheckedChange = onEnabledChange)
    }
}

/**
 * 来源行标题统一拼装：已授权且用户名非空（去除首尾空白）时「来源 (用户名)」，否则原标题。
 * 四来源（Hikarinagi / VNDB / Bangumi / 未萌NextMoe）共用此唯一实现；调用方在 [authorizedSourceTitle] 之上
 * 自行附加来源特有条件（如 VNDB 占位用户名排除）。
 */
private fun authorizedSourceTitle(baseTitle: String, authorized: Boolean, username: String?): String {
    val user = username?.trim().orEmpty()
    return if (authorized && user.isNotBlank()) "$baseTitle ($user)" else baseTitle
}

/** Bangumi 行标题：[authorizedSourceTitle] 的 Bangumi 形态（既有 JVM 测试直接钉住该入口）。 */
internal fun bangumiSourceTitle(
    baseTitle: String,
    status: com.tyranor.next.core.auth.BangumiAuthStatus,
): String = authorizedSourceTitle(baseTitle, status.authorized, status.username)

/** VNDB 授权条目点击行为（纯决策，JVM 可测）。 */
internal enum class VndbAuthRowAction {
    /** 已授权且未失效：直接登出（与 Bangumi / Hikarinagi 的行级登出行为对齐）。 */
    LOG_OUT,

    /** 未授权或授权已失效：打开令牌粘贴弹窗（重新授权）。 */
    SHOW_TOKEN_DIALOG,
}

/** VNDB 授权条目点击行为决策：已授权且未失效 → 直接登出；其余 → 打开令牌粘贴弹窗。 */
internal fun vndbAuthRowAction(authorized: Boolean, needsReauth: Boolean): VndbAuthRowAction =
    if (authorized && !needsReauth) VndbAuthRowAction.LOG_OUT else VndbAuthRowAction.SHOW_TOKEN_DIALOG

/** NextMoe 行级动作显隐（纯决策，JVM 可测）。 */
internal data class NextMoeAuthRowActions(
    val showAuthorize: Boolean,
    val showClear: Boolean,
)

/** NextMoe 清除入口仅取决于本地是否存有凭据，与授权有效性（needsReauth）解耦。 */
internal fun nextMoeAuthRowActions(authorized: Boolean, needsReauth: Boolean): NextMoeAuthRowActions =
    NextMoeAuthRowActions(
        showAuthorize = !authorized || needsReauth,
        showClear = authorized,
    )

@Composable
private fun CoverScraperTopBar() {
    AppSecondaryTopBar(
        title = stringResource(R.string.settings_cover_scraper),
        background = ComposeColor.Transparent,
        contentColor = PageTextColor,
    )
}

@Composable
private fun sourceSummary(
    source: String,
    rowState: CoverSourceRowState,
): String {
    val authStatus = rowState.hikarinagiStatus
    val bangumiAuthStatus = rowState.bangumiStatus
    val bangumiAuthStale = rowState.bangumiAuthStale
    val vndbAuthStatus = rowState.vndbStatus
    val nextmoeAuthStatus = rowState.nextmoeStatus
    val nextmoeAuthStale = rowState.nextmoeAuthStale
    return when (source) {
        AppSettingsStore.COVER_SOURCE_HIKARINAGI -> when {
            authStatus.needsReauth -> stringResource(R.string.cover_hikarinagi_reauth_summary)
            authStatus.permissionDenied -> stringResource(R.string.play_error_hikarinagi_token_permission)
            authStatus.authorized -> stringResource(R.string.cover_hikarinagi_authorized_summary)
            else -> stringResource(R.string.cover_hikarinagi_guest_summary)
        }
        AppSettingsStore.COVER_SOURCE_BANGUMI -> when {
            bangumiAuthStatus.needsReauth -> when {
                bangumiAuthStatus.lastErrorKind == com.tyranor.next.core.auth.BangumiAuthError.EXPIRED ->
                    stringResource(R.string.cover_bangumi_reauth_summary)
                bangumiAuthStatus.isSecureStorageError ->
                    stringResource(R.string.cover_bangumi_secure_storage_summary)
                else -> stringResource(R.string.cover_bangumi_secure_storage_summary)
            }
            bangumiAuthStatus.authorized -> {
                if (bangumiAuthStatus.isPersonalToken) {
                    stringResource(R.string.cover_bangumi_authorized_personal_summary)
                } else {
                    stringResource(R.string.cover_bangumi_authorized_write_summary)
                }
            }
            bangumiAuthStale -> stringResource(R.string.auth_bangumi_auth_stale)
            else -> stringResource(R.string.cover_bangumi_summary)
        }
        AppSettingsStore.COVER_SOURCE_STEAM -> stringResource(R.string.cover_steam_summary)
        AppSettingsStore.COVER_SOURCE_VNDB -> when {
            vndbAuthStatus.needsReauth -> when {
                vndbAuthStatus.lastErrorKind == com.tyranor.next.core.auth.VndbAuthError.PERMISSION ->
                    stringResource(R.string.play_error_vndb_token_permission)
                vndbAuthStatus.isSecureStorageError ->
                    stringResource(R.string.cover_vndb_secure_storage_summary)
                else -> stringResource(R.string.play_error_vndb_token_invalid)
            }
            vndbAuthStatus.authorized -> when (vndbAuthStatus.permissionTier) {
                VndbPermissionTier.WRITE -> stringResource(R.string.cover_vndb_authorized_summary)
                VndbPermissionTier.READ_PRIVATE,
                VndbPermissionTier.READ_PUBLIC ->
                    stringResource(R.string.cover_vndb_authorized_read_only_summary)
            }
            else -> stringResource(R.string.cover_vndb_summary)
        }
        AppSettingsStore.COVER_SOURCE_NEXTMOE -> when {
            nextmoeAuthStatus.needsReauth -> when {
                nextmoeAuthStatus.lastErrorKind == NextMoeAuthError.EXPIRED ->
                    stringResource(R.string.cover_nextmoe_reauth_summary)
                nextmoeAuthStatus.isSecureStorageError ->
                    stringResource(R.string.cover_nextmoe_secure_storage_summary)
                else -> stringResource(R.string.cover_nextmoe_secure_storage_summary)
            }
            nextmoeAuthStatus.permissionDenied -> stringResource(R.string.play_error_nextmoe_token_permission)
            nextmoeAuthStatus.authorized -> stringResource(R.string.cover_nextmoe_authorized_summary)
            nextmoeAuthStale -> stringResource(R.string.auth_nextmoe_auth_stale)
            else -> stringResource(R.string.cover_nextmoe_summary)
        }
        else -> ""
    }
}

/** VNDB token 粘贴弹窗：确认时经 /authinfo 校验后入库（VNDB 无 OAuth）。校验成功后直接关闭弹窗返回父级。 */
@Composable
private fun VndbTokenDialog(onDismiss: () -> Unit) {
    CredentialTokenDialog(
        titleRes = R.string.auth_vndb_token_title,
        instructionsRes = R.string.auth_vndb_token_instructions,
        missingRes = R.string.auth_vndb_token_missing,
        savedRes = R.string.auth_vndb_token_saved,
        readStoredToken = VndbAuthStore::getToken,
        hasStoredCredential = { VndbAuthStore.getStatus(it).authorized },
        clearCredential = VndbAuthStore::clear,
        confirmToken = { context, token -> verifyAndSaveVndbToken(context, token) },
        onDismiss = onDismiss,
    )
}

internal suspend fun verifyAndSaveVndbToken(
    context: Context,
    token: String,
    verify: suspend (String) -> PlayStatusService.VndbAuthInfo = { PlayStatusService.verifyVndbToken(it) },
    saveToken: (Context, String, PlayStatusService.VndbAuthInfo) -> Boolean = { ctx, tok, auth ->
        VndbAuthStore.saveToken(ctx, tok, auth.userId, auth.username, auth.permissions)
    },
): CredentialDialogOutcome {
    var failure: PlayStatusError? = null
    var info: PlayStatusService.VndbAuthInfo? = null
    try {
        info = verify(token)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (t: Throwable) {
        failure = (t as? PlayStatusException)?.error ?: PlayStatusError.UNKNOWN
    }
    return when {
        info != null -> {
            // 校验往返期间若对话框关闭或发起新提交导致协程被取消，落盘前中止，避免写回过期凭据
            currentCoroutineContext().ensureActive()
            // saveToken 返回 Boolean（true = 全部加密落盘成功；false = 任一步失败，
            // store 已置「secure storage unavailable」哨兵）。false 时不再显示「已保存」，
            // 改在弹窗内展示失败文案
            if (saveToken(context, token, info)) {
                CredentialDialogOutcome.Saved
            } else {
                CredentialDialogOutcome.Failed(
                    AppLocaleController.wrap(context).getString(R.string.cover_vndb_save_failed),
                )
            }
        }
        failure != null -> CredentialDialogOutcome.Failed(failure.playStatusErrorMessage(context))
        else -> CredentialDialogOutcome.Failed(
            AppLocaleController.wrap(context).getString(R.string.play_error_unknown),
        )
    }
}

/** Bangumi 个人令牌粘贴弹窗：无需注册 OAuth 应用（next.bgm.tv/demo/access-token），/v0/me 校验后入库。校验成功后直接关闭弹窗返回父级。 */
@Composable
private fun BangumiTokenDialog(onDismiss: () -> Unit) {
    CredentialTokenDialog(
        titleRes = R.string.auth_bangumi_token_title,
        instructionsRes = R.string.auth_bangumi_token_instructions,
        missingRes = R.string.auth_bangumi_token_missing,
        savedRes = R.string.auth_bangumi_token_saved,
        readStoredToken = BangumiAuthStore::getAccessToken,
        hasStoredCredential = { BangumiAuthStore.getStatus(it).authorized },
        clearCredential = BangumiAuthStore::clear,
        confirmToken = { context, token -> verifyAndSaveBangumiToken(context, token) },
        onDismiss = onDismiss,
    )
}

internal suspend fun verifyAndSaveBangumiToken(
    context: Context,
    token: String,
    verify: suspend (String) -> BangumiTokenVerification = { BangumiAuthService.verifyPersonalToken(it) },
    readGeneration: (Context) -> Long = BangumiAuthStore::readCredentialGeneration,
    saveToken: (Context, String, BangumiAuthService.BangumiTokenIdentity, Long) -> Boolean = { ctx, tok, id, expectedGen ->
        // 个人令牌无 refresh、不过期：expiresAt=0 走长期令牌路径；校验开始前快照凭据代次，
        // 防止并发完成的 OAuth 授权被随后返回的旧个人令牌校验静默覆盖
        val savedEpoch = BangumiAuthStore.saveTokensIfGenerationMatches(ctx, expectedGen, tok, "", 0L)
        if (savedEpoch == null) {
            false
        } else {
            // 身份已在校验时解析，落库供游玩状态写入与真实用户名路由使用（明文非敏感字段）
            BangumiAuthStore.saveUserIdentityIfEpochMatches(ctx, id.id, id.username, savedEpoch)
            true
        }
    },
    formatString: (Context, Int) -> String = { ctx, resId -> AppLocaleController.wrap(ctx).getString(resId) },
): CredentialDialogOutcome {
    // 校验开始前捕获凭据代次，与最终提交同一把 storeLock 下的 generation 校验闭环
    val expectedGeneration = readGeneration(context)
    // 校验失败分档：令牌被拒与网络/服务异常分开提示，离线不再误报「令牌无效」
    var failureMessage: String? = null
    var identity: BangumiAuthService.BangumiTokenIdentity? = null
    try {
        when (val result = verify(token)) {
            is BangumiTokenVerification.Success -> identity = result.identity
            is BangumiTokenVerification.Failure ->
                failureMessage = result.error.authErrorMessage(context)
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Throwable) {
        failureMessage = formatString(context, R.string.play_error_unknown)
    }
    return when {
        identity != null -> {
            // 校验往返期间若对话框关闭或发起新提交导致协程被取消，落盘前中止，避免写回过期凭据
            currentCoroutineContext().ensureActive()
            if (saveToken(context, token, identity, expectedGeneration)) {
                CredentialDialogOutcome.Saved
            } else if (readGeneration(context) != expectedGeneration) {
                CredentialDialogOutcome.Failed(formatString(context, R.string.auth_bangumi_credential_superseded))
            } else {
                // 令牌落盘失败：不写身份字段，避免留下「无令牌却有身份」的部分状态；
                // store 已置「secure storage unavailable」哨兵，弹窗内提示失败
                CredentialDialogOutcome.Failed(formatString(context, R.string.cover_bangumi_save_failed))
            }
        }
        failureMessage != null -> CredentialDialogOutcome.Failed(failureMessage)
        else -> CredentialDialogOutcome.Failed(formatString(context, R.string.play_error_unknown))
    }
}
