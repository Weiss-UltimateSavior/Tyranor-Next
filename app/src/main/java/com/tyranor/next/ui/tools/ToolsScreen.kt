package com.tyranor.next.ui.tools

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.tyranor.next.R
import com.tyranor.next.core.game.launch.EngineLauncher
import com.tyranor.next.ui.archive.ArchiveUnpackActivity
import com.tyranor.next.theme.MiuixSettingsTheme
import com.tyranor.next.ui.common.SettingsEntryCard
import com.tyranor.next.ui.common.AppTopBar
import com.tyranor.next.ui.common.LaunchErrorDialog
import com.tyranor.next.ui.common.LaunchErrorState
import com.tyranor.next.ui.common.glassNavBottomInset
import com.tyranor.next.ui.common.motion.staggeredEntrance
import com.tyranor.next.ui.common.motion.startActivityWithPageTransition
import com.tyranor.next.ui.common.toErrorState
import com.tyranor.next.ui.runtime.EngineModuleManagementActivity
import com.tyranor.next.ui.save.WebDavSettingsActivity
import com.tyranor.next.ui.settings.InputSettingsActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class ToolEntry(
    @StringRes val title: Int,
    @DrawableRes val icon: Int,
    val onClick: () -> Unit,
)

@Composable
fun ToolsScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var launchError by remember { mutableStateOf<LaunchErrorState?>(null) }
    var launchingNative by remember { mutableStateOf(false) }
    val entries = listOf(
        ToolEntry(R.string.side_panel_kr2_native_title, R.drawable.ic_settings_engine) {
            if (!launchingNative) {
                launchingNative = true
                scope.launch {
                    try {
                        val result = withContext(Dispatchers.IO) {
                            EngineLauncher.launchNativeKirikiroidUi(context)
                        }
                        launchError = result.toErrorState(context)
                    } finally {
                        launchingNative = false
                    }
                }
            }
        },
        ToolEntry(R.string.side_panel_archive_tool_title, R.drawable.ic_sheet_archive) {
            startActivityWithPageTransition(context, ArchiveUnpackActivity.createIntent(context))
        },
        ToolEntry(R.string.webdav_title, R.drawable.ic_save_cloud) {
            startActivityWithPageTransition(context, WebDavSettingsActivity.createIntent(context))
        },
        ToolEntry(R.string.side_panel_input_tool_title, R.drawable.ic_input_gamepad) {
            startActivityWithPageTransition(context, InputSettingsActivity.createIntent(context))
        },
        ToolEntry(R.string.side_panel_engine_module_title, R.drawable.ic_engine_manage) {
            startActivityWithPageTransition(context, EngineModuleManagementActivity.createIntent(context))
        },
    )
    MiuixSettingsTheme {
        Column(modifier.fillMaxSize()) {
            AppTopBar(title = stringResource(R.string.nav_tools))
            LazyColumn(
                modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp + glassNavBottomInset()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                itemsIndexed(entries, key = { _, entry -> entry.title }) { index, entry ->
                    SettingsEntryCard(
                        title = stringResource(entry.title),
                        iconRes = entry.icon,
                        modifier = Modifier.staggeredEntrance(index),
                        enabled = index != 0 || !launchingNative,
                        onClick = entry.onClick,
                    )
                }
            }
        }
    }
    launchError?.let { state ->
        LaunchErrorDialog(state = state, onDismiss = { launchError = null })
    }
}
