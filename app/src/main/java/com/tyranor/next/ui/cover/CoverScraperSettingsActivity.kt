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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
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
import com.tyranor.next.core.auth.HikarinagiAuthService
import com.tyranor.next.core.auth.HikarinagiAuthStore
import com.tyranor.next.core.i18n.AppLocaleController
import com.tyranor.next.core.settings.AppSettingsStore
import com.tyranor.next.theme.PageTextColor
import com.tyranor.next.theme.DialogItemSurface
import com.tyranor.next.theme.MiuixSettingsTheme
import com.tyranor.next.theme.PageGrey
import com.tyranor.next.theme.TextColor
import com.tyranor.next.theme.glassBorder
import com.tyranor.next.ui.common.AppScreenActivity
import com.tyranor.next.ui.common.AppTopBar
import com.tyranor.next.ui.common.NoRippleButton
import com.tyranor.next.ui.common.NoRippleTextButton
import com.tyranor.next.ui.common.motion.BlurCrossfade
import com.tyranor.next.ui.common.motion.ProvideEntrance
import com.tyranor.next.ui.common.motion.skipEntranceOnDrag
import com.tyranor.next.ui.common.motion.RollingNumberText
import com.tyranor.next.ui.common.motion.pressSquishGroup
import com.tyranor.next.ui.common.motion.staggeredEntrance
import com.tyranor.next.ui.auth.HikarinagiOAuthCallbackActivity
import com.tyranor.next.theme.AppComponentShape
import com.tyranor.next.theme.AppComponentCornerRadius
import top.yukonga.miuix.kmp.basic.Card as MiuixCard
import top.yukonga.miuix.kmp.basic.Scaffold as MiuixScaffold
import com.tyranor.next.ui.common.Switch
import com.tyranor.next.ui.common.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

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
    val settingsVersion by AppSettingsStore.coverScraperSettingsVersion.collectAsState()
    val scrapeTaskState by CoverScrapeTaskManager.state.collectAsState()
    var sources by remember(settingsVersion) { mutableStateOf(AppSettingsStore.getCoverScraperSourceOrder(ctx)) }
    var onlyMissing by remember(settingsVersion) { mutableStateOf(AppSettingsStore.isCoverScraperOnlyMissing(ctx)) }
    var vndbLargeCover by remember(settingsVersion) { mutableStateOf(AppSettingsStore.isVndbLargeCover(ctx)) }
    val scraping = scrapeTaskState.running
    val authStatus = remember(authVersion) { HikarinagiAuthStore.getStatus(ctx) }
    val batchScrapeRunningMessage = stringResource(R.string.game_batch_scraping_running)
    val scrapeResultMessage = scrapeTaskState.result?.let { result ->
        stringResource(R.string.cover_batch_result, result.updatedCount, result.skippedCount, result.failedCount)
    }

    LaunchedEffect(scrapeTaskState.eventId) {
        if (scrapeTaskState.eventId == 0L) return@LaunchedEffect
        scrapeResultMessage?.let { message ->
            Toast.makeText(
                ctx,
                message,
                Toast.LENGTH_SHORT,
            ).show()
        }
        scrapeTaskState.error?.let { error ->
            Toast.makeText(ctx, error.coverErrorMessage(ctx), Toast.LENGTH_SHORT).show()
        }
        CoverScrapeTaskManager.clearFinished(scrapeTaskState.eventId)
    }

    fun startBatchScrape() {
        if (!CoverScrapeTaskManager.start(ctx)) {
            Toast.makeText(ctx, batchScrapeRunningMessage, Toast.LENGTH_SHORT).show()
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
                                    authStatus = authStatus,
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
                                    onAuthClick = {
                                        if (source == AppSettingsStore.COVER_SOURCE_HIKARINAGI) {
                                            if (authStatus.authorized && !authStatus.needsReauth) {
                                                HikarinagiAuthStore.clear(ctx)
                                            } else {
                                                val error = activity?.let {
                                                    HikarinagiAuthService.startAuthorization(
                                                        it,
                                                        Intent(it, HikarinagiOAuthCallbackActivity::class.java),
                                                    )
                                                }
                                                if (error != null) Toast.makeText(ctx, error, Toast.LENGTH_SHORT).show()
                                            }
                                        }
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
                            NoRippleButton(
                                text = if (scraping) stringResource(R.string.cover_scraping_batch) else stringResource(R.string.cover_start_batch),
                                modifier = Modifier.fillMaxWidth(),
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

/**
 * 显示来源序号、启用开关和排序入口；仅 Hikarinagi 来源展示授权操作。
 * 序号与摘要各自执行内容转场，排序按钮在列表边界禁用，业务变更交由调用方写入。
 */
@Composable
private fun CoverSourceRow(
    index: Int,
    count: Int,
    source: String,
    enabled: Boolean,
    authStatus: com.tyranor.next.core.auth.HikarinagiAuthStatus,
    onEnabledChange: (Boolean) -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onAuthClick: () -> Unit,
) {
    Row(
        modifier = Modifier
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
            Text(
                coverSourceTitle(source),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = TextColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val summary = sourceSummary(source, authStatus)
            BlurCrossfade(targetState = summary) { text ->
                Text(
                    text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
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
                if (source == AppSettingsStore.COVER_SOURCE_HIKARINAGI) {
                    NoRippleTextButton(
                        text = if (authStatus.authorized && !authStatus.needsReauth) {
                            stringResource(R.string.cover_logout)
                        } else {
                            stringResource(R.string.cover_login)
                        },
                        onClick = onAuthClick,
                    )
                }
            }
        }
        Switch(checked = enabled, onCheckedChange = onEnabledChange)
    }
}

@Composable
private fun CoverScraperTopBar() {
    AppTopBar(
        title = stringResource(R.string.settings_cover_scraper),
        background = ComposeColor.Transparent,
        contentColor = PageTextColor,
    )
}

@Composable
private fun sourceSummary(source: String, authStatus: com.tyranor.next.core.auth.HikarinagiAuthStatus): String = when (source) {
    AppSettingsStore.COVER_SOURCE_HIKARINAGI -> when {
        authStatus.needsReauth -> stringResource(R.string.cover_hikarinagi_reauth_summary)
        authStatus.authorized -> stringResource(R.string.cover_hikarinagi_authorized_summary)
        else -> stringResource(R.string.cover_hikarinagi_guest_summary)
    }
    AppSettingsStore.COVER_SOURCE_BANGUMI -> stringResource(R.string.cover_bangumi_summary)
    AppSettingsStore.COVER_SOURCE_STEAM -> stringResource(R.string.cover_steam_summary)
    AppSettingsStore.COVER_SOURCE_VNDB -> stringResource(R.string.cover_vndb_summary)
    AppSettingsStore.COVER_SOURCE_NEXTMOE -> stringResource(R.string.cover_nextmoe_summary)
    else -> ""
}
