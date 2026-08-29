package com.tyranor.next.ui.cover

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.tyranor.next.R
import com.tyranor.next.core.settings.AppSettingsStore

@Composable
internal fun coverSourceTitle(source: String): String = when (source) {
    AppSettingsStore.COVER_SOURCE_HIKARINAGI -> "Hikarinagi"
    AppSettingsStore.COVER_SOURCE_BANGUMI -> "Bangumi"
    AppSettingsStore.COVER_SOURCE_STEAM -> "Steam"
    AppSettingsStore.COVER_SOURCE_VNDB -> "VNDB"
    AppSettingsStore.COVER_SOURCE_LOCAL -> stringResource(R.string.cover_source_local)
    AppSettingsStore.COVER_SOURCE_CUSTOM -> stringResource(R.string.cover_source_custom)
    else -> source
}
