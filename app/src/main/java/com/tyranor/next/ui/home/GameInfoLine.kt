package com.tyranor.next.ui.home

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.tyranor.next.R
import com.tyranor.next.core.engine.EngineType
import com.tyranor.next.core.game.model.ScanGame
import com.tyranor.next.ui.common.TimeFormats

/** 游戏信息行：引擎名 · 上次游玩时间（没玩过就只显示引擎名）。随海报标题一起滑动，见 [PosterCarousel]。 */
@Composable
internal fun gameInfoLine(game: ScanGame): String {
    val engineName = when (game.engine) {
        EngineType.UNKNOWN -> stringResource(R.string.engine_name_unknown)
        // 与游戏卡片一致用短名「Switch」
        EngineType.NINTENDO_SWITCH -> stringResource(R.string.engine_name_switch)
        else -> game.engine.displayName
    }
    return if (game.openTime > 0) {
        "$engineName · " + stringResource(R.string.home_last_played, TimeFormats.formatDateTime(game.openTime))
    } else {
        engineName
    }
}
