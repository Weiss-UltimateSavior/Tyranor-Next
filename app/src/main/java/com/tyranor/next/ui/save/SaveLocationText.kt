package com.tyranor.next.ui.save

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.tyranor.next.R
import com.tyranor.next.core.game.save.GameSaveManager

@Composable
internal fun GameSaveManager.SaveLocation.description(engineName: String): String = when (descriptionRes) {
    R.string.save_location_engine_game_dir,
    R.string.save_location_engine_scoped,
    R.string.save_location_engine_no_file_interface,
    -> stringResource(descriptionRes, engineName)
    else -> stringResource(descriptionRes)
}
