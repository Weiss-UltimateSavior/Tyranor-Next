package com.tyranor.next.ui.cover

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.tyranor.next.R
import com.tyranor.next.core.i18n.AppLocaleController
import com.tyranor.next.core.settings.AppSettingsStore

/**
 * 非组合上下文的 ASCII 来源显示名回退（不含 CJK 字面量，符合字符串检查规则；
 * 拥有 Context 的非组合路径优先通过 [coverSourceTitle] 解析）。
 */
internal fun coverSourceTitleRaw(source: String): String = when (source) {
    AppSettingsStore.COVER_SOURCE_HIKARINAGI -> "Hikarinagi"
    AppSettingsStore.COVER_SOURCE_BANGUMI -> "Bangumi"
    AppSettingsStore.COVER_SOURCE_STEAM -> "Steam"
    AppSettingsStore.COVER_SOURCE_VNDB -> "VNDB"
    AppSettingsStore.COVER_SOURCE_NEXTMOE -> "NextMoe"
    else -> source
}

/** 拥有 Context 的非组合路径统一来源标题解析（与组合路径 [coverSourceTitle] 一致）。 */
internal fun coverSourceTitle(context: Context, source: String): String = when (source) {
    AppSettingsStore.COVER_SOURCE_NEXTMOE -> AppLocaleController.wrap(context).getString(R.string.cover_source_nextmoe)
    AppSettingsStore.COVER_SOURCE_LOCAL -> AppLocaleController.wrap(context).getString(R.string.cover_source_local)
    AppSettingsStore.COVER_SOURCE_CUSTOM -> AppLocaleController.wrap(context).getString(R.string.cover_source_custom)
    else -> coverSourceTitleRaw(source)
}

@Composable
internal fun coverSourceTitle(source: String): String = when (source) {
    AppSettingsStore.COVER_SOURCE_NEXTMOE -> stringResource(R.string.cover_source_nextmoe)
    AppSettingsStore.COVER_SOURCE_LOCAL -> stringResource(R.string.cover_source_local)
    AppSettingsStore.COVER_SOURCE_CUSTOM -> stringResource(R.string.cover_source_custom)
    else -> coverSourceTitleRaw(source)
}
