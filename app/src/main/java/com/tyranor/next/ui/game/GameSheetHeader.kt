package com.tyranor.next.ui.game

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tyranor.next.R
import com.tyranor.next.core.engine.EngineType
import com.tyranor.next.core.engine.runtime.RuntimeModuleBindings
import com.tyranor.next.core.game.model.ScanGame
import com.tyranor.next.core.game.playtime.GamePlaytimeRepository
import com.tyranor.next.core.game.playtime.GamePlaytimeStats
import com.tyranor.next.theme.AccentTagText
import com.tyranor.next.theme.AppComponentShape
import com.tyranor.next.theme.SecondaryTextColor
import com.tyranor.next.theme.TextColor
import com.tyranor.next.theme.glassBorder
import com.tyranor.next.ui.common.TimeFormats
import com.tyranor.next.ui.common.formatPlaytimeHours
import com.tyranor.next.ui.common.motion.BlurCrossfade
import com.tyranor.next.ui.common.motion.CoverSurface
import com.tyranor.next.ui.common.motion.rememberCoverReveal

/**
 * 档案卡数据：虚线分隔下三格——累计时长 / 上次游玩（今天、昨天、N 天前）/ 最近一次玩了多久。
 * 统计时长的引擎还没数据时显示「—」，不显示误导人的 0；外置运行的游戏（外置 APK 引擎模块、Winlator、
 * 外置模拟器、安卓游戏、第三方 APK 运行时模块）拿不到时长，换成两格：上次游玩 + 一格「外置运行，不计时」的说明，
 * 不摆两道永远空着的「—」。
 * 换成另一个游戏时各格数值模糊替换；在计时与不计时的游戏之间切换时整排一起模糊替换。
 */
@Composable
internal fun ShelfStatsStrip(
    game: ScanGame,
    stats: GamePlaytimeStats?,
    modifier: Modifier = Modifier,
) {
    val displayGame = remember(game) { RuntimeModuleBindings.builtInSystemNnn(game) }
    val none = stringResource(R.string.game_shelf_stat_none)
    val tracked = GamePlaytimeRepository.supports(displayGame.engine)
    val totalMillis = stats?.totalMillis?.takeIf { tracked && it > 0L }
    val sessionMillis = stats?.lastSessionMillis?.takeIf { tracked && it > 0L }
    val total = if (totalMillis != null) formatShelfDuration(totalMillis) else none
    val session = if (sessionMillis != null) formatShelfDuration(sessionMillis) else none
    val last = if (displayGame.openTime > 0L) formatShelfLastPlayed(displayGame.openTime) else stringResource(R.string.game_shelf_not_started)
    val untracked = stringResource(R.string.game_shelf_stat_untracked)
    val dividerColor = SecondaryTextColor.copy(alpha = 0.28f)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .drawBehind {
                // 档案卡式的虚线分隔
                drawLine(
                    dividerColor,
                    androidx.compose.ui.geometry.Offset(0f, 0f),
                    androidx.compose.ui.geometry.Offset(size.width, 0f),
                    strokeWidth = 1f * density,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(5f * density, 4f * density)),
                )
            }
            .padding(top = 10.dp),
    ) {
        BlurCrossfade(tracked, Modifier.fillMaxWidth()) { isTracked ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (isTracked) {
                    ShelfStatCell(stringResource(R.string.game_shelf_stat_total), total, Modifier.weight(1f))
                    ShelfStatCell(stringResource(R.string.game_shelf_stat_last), last, Modifier.weight(1f))
                    ShelfStatCell(stringResource(R.string.game_shelf_stat_session), session, Modifier.weight(1f))
                } else {
                    ShelfStatCell(stringResource(R.string.game_shelf_stat_last), last, Modifier.weight(1f))
                    // 说明而不是数据：同一字号（整排高度不变）、不加粗、次要色
                    ShelfStatCell(
                        stringResource(R.string.game_shelf_stat_total),
                        untracked,
                        Modifier.weight(2f),
                        note = true,
                    )
                }
            }
        }
    }
}

@Composable
private fun ShelfStatCell(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    note: Boolean = false,
) {
    Column(modifier.semantics(mergeDescendants = true) { }) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = SecondaryTextColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        BlurCrossfade(value) { text ->
            Text(
                text,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = if (note) FontWeight.Normal else FontWeight.Bold),
                color = if (note) SecondaryTextColor else TextColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 档案卡引擎名牌显示名：对 UNKNOWN / RUNTIME_MODULE 走本地化文案，其余保留引擎名称。 */
@Composable
private fun gameSheetEngineName(game: ScanGame): String = when (game.engine) {
    EngineType.UNKNOWN -> stringResource(R.string.engine_name_unknown)
    EngineType.RUNTIME_MODULE -> stringResource(R.string.engine_name_runtime_module)
    else -> game.engine.displayName
}

/**
 * 游戏操作抽屉的头部「档案卡」：左边是这款游戏的封面（与游戏列表里的封面同一种画法——同样的圆角 [coverShape]、引擎色底、
 * 无封面时的「Tyranor + 引擎名」占位、玻璃外观下的描边），右边引擎名牌、游戏名与三格数据（累计时长 / 上次游玩 / 最近一次）。
 * 所有卡片风格与首页的操作抽屉共用这一个头部。
 */
@Composable
internal fun GameSheetHeader(game: ScanGame, coverShape: Shape, modifier: Modifier = Modifier) {
    val displayGame = remember(game) { RuntimeModuleBindings.builtInSystemNnn(game) }
    val tracked = GamePlaytimeRepository.supports(displayGame.engine)
    // 头部封面只有 78dp 宽，GRID 档（384×512）在任何密度的手机上都已经过采样；
    // 刻意不跟列表的 forDisplay 档位走，避免为一张小图常驻一份更大的解码结果。
    val cover by rememberCoverBitmap(displayGame.coverUri)
    val bitmap = cover
    val reveal = rememberCoverReveal(bitmap != null, CoverSurface.Grid)
    val context = LocalContext.current.applicationContext
    val statsFlow = remember(context, displayGame.uri, tracked) {
        if (tracked) GamePlaytimeRepository.observe(context, displayGame.uri) else null
    }
    val stats: GamePlaytimeStats? = if (statsFlow != null) {
        statsFlow.collectAsStateWithLifecycle(initialValue = null).value
    } else {
        null
    }
    val accent = MaterialTheme.colorScheme.primary
    Row(modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        // 封面为装饰性配图，右侧已由名牌与标题提供完整语义，避免读屏重复播报
        Box(
            Modifier
                .width(SHEET_COVER_WIDTH)
                .aspectRatio(3f / 4f)
                .clip(coverShape)
                .background(displayGame.engine.coverColor())
                .glassBorder(shape = coverShape)
                .clearAndSetSemantics { },
            contentAlignment = Alignment.Center,
        ) {
            // 与列表一致：已知封面存在、只是还没解码好时先留底色，不闪占位文字
            if (bitmap == null && !isCoverKnown(displayGame.coverUri)) GameCoverPlaceholder(gameCardEngineName(displayGame))
            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = reveal.image(Modifier.fillMaxSize()),
                )
            }
        }
        Column(Modifier.weight(1f).padding(start = 16.dp)) {
            Text(
                gameSheetEngineName(displayGame),
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                color = AccentTagText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .background(accent, AppComponentShape)
                    .padding(horizontal = 10.dp, vertical = 2.dp),
            )
            Text(
                displayGame.title,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = TextColor,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp),
            )
            ShelfStatsStrip(displayGame, stats, Modifier.padding(top = 8.dp))
        }
    }
}

/**
 * 时长：非正值返回「—」；不满一小时按分钟（至少 1 分钟），否则按小时（保留一位小数）。
 */
@Composable
internal fun formatShelfDuration(millis: Long): String {
    if (millis <= 0L) return stringResource(R.string.game_shelf_stat_none)
    val minutes = millis / 60_000L
    return if (minutes < 60L) {
        stringResource(R.string.game_shelf_minutes, minutes.coerceAtLeast(1L).toInt())
    } else {
        formatPlaytimeHours(millis)
    }
}

/** 上次游玩：按自然日算今天 / 昨天 / N 天前，超过一年显示日期。 */
@Composable
private fun formatShelfLastPlayed(openTime: Long): String {
    val zone = java.time.ZoneId.systemDefault()
    val opened = java.time.Instant.ofEpochMilli(openTime).atZone(zone).toLocalDate()
    val days = java.time.temporal.ChronoUnit.DAYS.between(opened, java.time.LocalDate.now(zone)).coerceAtLeast(0L)
    return when {
        days == 0L -> stringResource(R.string.game_shelf_today)
        days == 1L -> stringResource(R.string.game_shelf_yesterday)
        days < 365L -> stringResource(R.string.game_shelf_days_ago, days.toInt())
        else -> TimeFormats.formatDate(openTime)
    }
}

/** 操作抽屉头部封面的宽度（3:4）。 */
private val SHEET_COVER_WIDTH = 78.dp
