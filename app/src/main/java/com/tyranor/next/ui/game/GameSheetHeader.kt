package com.tyranor.next.ui.game

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tyranor.next.R
import com.tyranor.next.core.game.model.ScanGame
import com.tyranor.next.core.game.playtime.GamePlaytimeRepository
import com.tyranor.next.core.game.playtime.GamePlaytimeStats
import com.tyranor.next.theme.AccentTagText
import com.tyranor.next.theme.AppComponentShape
import com.tyranor.next.theme.SecondaryTextColor
import com.tyranor.next.theme.TextColor
import com.tyranor.next.theme.glassBorder
import com.tyranor.next.ui.common.motion.BlurCrossfade
import com.tyranor.next.ui.common.motion.CoverSurface
import com.tyranor.next.ui.common.motion.rememberCoverReveal
import com.tyranor.next.ui.home.formatPlaytimeHours

/**
 * 存档卡数据：虚线分隔下三格——累计时长 / 上次游玩（今天、昨天、N 天前）/ 最近一次玩了多久。
 * 统计时长的引擎还没数据时显示「—」，不显示误导人的 0；外置运行的游戏（外置模块、Winlator、外置模拟器、安卓游戏、
 * 第三方运行模块）根本拿不到时长，换成两格：上次游玩 + 一格「外置运行，不计时」的说明，不摆两道永远空着的「—」。
 * 换成另一个游戏时各格数值模糊替换；在计时与不计时的游戏之间切换时整排一起模糊替换。
 */
@Composable
internal fun ShelfStatsStrip(
    game: ScanGame,
    stats: GamePlaytimeStats?,
    modifier: Modifier = Modifier,
    divider: Boolean = true,
    /** 标签与数值的字体；null 时用界面字体。 */
    labelFont: FontFamily? = null,
    valueFont: FontFamily? = null,
) {
    val none = stringResource(R.string.game_shelf_stat_none)
    val tracked = GamePlaytimeRepository.supports(game.engine)
    val totalMillis = stats?.totalMillis?.takeIf { tracked && it > 0L }
    val sessionMillis = stats?.lastSessionMillis?.takeIf { tracked && it > 0L }
    val total = if (totalMillis != null) formatShelfDuration(totalMillis) else none
    val session = if (sessionMillis != null) formatShelfDuration(sessionMillis) else none
    val last = if (game.openTime > 0L) formatShelfLastPlayed(game.openTime) else stringResource(R.string.game_shelf_not_started)
    val untracked = stringResource(R.string.game_shelf_stat_untracked)
    val dividerColor = SecondaryTextColor.copy(alpha = 0.28f)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .drawBehind {
                // 存档卡式的虚线分隔
                if (divider) drawLine(
                    dividerColor,
                    androidx.compose.ui.geometry.Offset(0f, 0f),
                    androidx.compose.ui.geometry.Offset(size.width, 0f),
                    strokeWidth = 1f * density,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(5f * density, 4f * density)),
                )
            }
            .padding(top = if (divider) 10.dp else 0.dp),
    ) {
        BlurCrossfade(tracked, Modifier.fillMaxWidth()) { isTracked ->
            Row(Modifier.fillMaxWidth()) {
                if (isTracked) {
                    ShelfStatCell(stringResource(R.string.game_shelf_stat_total), total, labelFont, valueFont, Modifier.weight(1f))
                    ShelfStatCell(stringResource(R.string.game_shelf_stat_last), last, labelFont, valueFont, Modifier.weight(1f))
                    ShelfStatCell(stringResource(R.string.game_shelf_stat_session), session, labelFont, valueFont, Modifier.weight(1f))
                } else {
                    ShelfStatCell(stringResource(R.string.game_shelf_stat_last), last, labelFont, valueFont, Modifier.weight(1f))
                    // 说明而不是数据：同一字号（整排高度不变）、不加粗、次要色
                    ShelfStatCell(
                        stringResource(R.string.game_shelf_stat_total),
                        untracked,
                        labelFont,
                        labelFont,
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
    labelFont: FontFamily?,
    valueFont: FontFamily?,
    modifier: Modifier = Modifier,
    note: Boolean = false,
) {
    Column(modifier.semantics(mergeDescendants = true) { }) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium.let { if (labelFont != null) it.copy(fontFamily = labelFont) else it },
            color = SecondaryTextColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        BlurCrossfade(value) { text ->
            Text(
                text,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = if (note) FontWeight.Normal else FontWeight.Bold).let {
                    if (valueFont != null) it.copy(fontFamily = valueFont) else it
                },
                color = if (note) SecondaryTextColor else TextColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * 游戏操作抽屉的头部「档案卡」：左边是这款游戏的封面（与游戏列表里的封面同一种画法——同样的圆角 [coverShape]、引擎色底、
 * 无封面时的「Tyranor + 引擎名」占位、玻璃外观下的描边），右边引擎名牌、游戏名与三格数据（累计时长 / 上次游玩 / 最近一次）。
 * 所有卡片风格与首页的操作抽屉共用这一个头部。
 */
@Composable
internal fun GameSheetHeader(game: ScanGame, coverShape: Shape, modifier: Modifier = Modifier) {
    val cover by rememberCoverBitmap(game.coverUri)
    val bitmap = cover
    val reveal = rememberCoverReveal(bitmap != null, CoverSurface.Grid)
    val context = LocalContext.current.applicationContext
    val statsFlow = remember(context, game.uri) { GamePlaytimeRepository.observe(context, game.uri) }
    val stats: GamePlaytimeStats? by statsFlow.collectAsStateWithLifecycle(initialValue = null)
    val accent = MaterialTheme.colorScheme.primary
    Row(modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .width(SHEET_COVER_WIDTH)
                .aspectRatio(3f / 4f)
                .clip(coverShape)
                .background(game.engine.coverColor())
                .glassBorder(shape = coverShape),
            contentAlignment = Alignment.Center,
        ) {
            // 与列表一致：已知封面存在、只是还没解码好时先留底色，不闪占位文字
            if (bitmap == null && !isCoverKnown(game.coverUri)) GameCoverPlaceholder(gameCardEngineName(game))
            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = game.title,
                    contentScale = ContentScale.Crop,
                    modifier = reveal.image(Modifier.fillMaxSize()),
                )
            }
        }
        Column(Modifier.weight(1f).padding(start = 16.dp)) {
            Text(
                game.engine.displayName,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                color = AccentTagText,
                maxLines = 1,
                modifier = Modifier
                    .background(accent, AppComponentShape)
                    .padding(horizontal = 10.dp, vertical = 2.dp),
            )
            Text(
                game.title,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = TextColor,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp),
            )
            ShelfStatsStrip(game, stats, Modifier.padding(top = 8.dp))
        }
    }
}

/** 时长：不满一小时按分钟（至少 1 分钟），否则按小时（保留一位小数）。 */
@Composable
internal fun formatShelfDuration(millis: Long): String {
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
        else -> com.tyranor.next.ui.common.TimeFormats.formatDate(openTime)
    }
}

/** 操作抽屉头部封面的宽度（3:4）。 */
private val SHEET_COVER_WIDTH = 78.dp
