package com.tyranor.next.ui.home

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tyranor.next.R
import com.tyranor.next.core.game.playtime.GamePlaytimeRepository
import com.tyranor.next.core.game.playtime.GamePlaytimeStats

/** Cumulative hours for a built-in game's recent-list card. Refreshing belongs to the page. */
@Composable
internal fun RecentGamePlaytime(gameUri: String, color: Color, textStyle: TextStyle) {
    val context = LocalContext.current.applicationContext
    val statsFlow = remember(context, gameUri) { GamePlaytimeRepository.observe(context, gameUri) }
    val stats: GamePlaytimeStats? by statsFlow.collectAsStateWithLifecycle(initialValue = null)
    val duration = formatPlaytimeHours(stats?.totalMillis ?: 0L)
    if (duration == "0h") return
    val description = stringResource(R.string.game_playtime_total_accessibility, duration)
    Text(
        text = duration,
        style = textStyle,
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.semantics { contentDescription = description },
    )
}
