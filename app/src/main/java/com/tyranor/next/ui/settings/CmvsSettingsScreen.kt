package com.tyranor.next.ui.settings

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.tyranor.next.R
import com.tyranor.next.core.settings.EngineSettingsStore
import com.tyranor.next.theme.PageTextColor
import com.tyranor.next.theme.MiuixSettingsTheme
import com.tyranor.next.ui.common.AppTopBar
import com.tyranor.next.ui.common.TopBarIcon
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.theme.MiuixTheme
import com.tyranor.next.ui.common.notice.AppNotice

@Composable
internal fun CmvsSettingsScreen() {
    val context = LocalContext.current
    var font by remember { mutableStateOf(EngineSettingsStore.getCmvsFont(context)) }
    var importing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null && !importing) {
            importing = true
            scope.launch {
                try {
                    val imported = FontImport.importToPrivate(context, uri)
                    if (imported != null) {
                        font = imported
                    } else {
                        AppNotice.makeText(context, R.string.launch_cmvs_font_unavailable, Toast.LENGTH_SHORT).show()
                    }
                } finally {
                    importing = false
                }
            }
        }
    }
    MiuixSettingsTheme {
        Scaffold(
            containerColor = Color.Transparent,
            contentWindowInsets = WindowInsets(0.dp),
            topBar = {
                AppTopBar(
                    title = stringResource(R.string.engine_settings_cmvs_title),
                    background = Color.Transparent,
                    contentColor = PageTextColor,
                    trailing = {
                        TopBarIcon(
                            painterResource(R.drawable.ic_save),
                            stringResource(R.string.engine_settings_save_content_description),
                            MiuixTheme.colorScheme.primary,
                        ) {
                            EngineSettingsStore.setCmvsFont(context, font)
                            AppNotice.makeText(context, R.string.engine_settings_saved, Toast.LENGTH_SHORT).show()
                        }
                    },
                )
            },
        ) { padding ->
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(top = padding.calculateTopPadding()),
                contentPadding = PaddingValues(16.dp),
            ) {
                item {
                    EngineCard("CMVS") {
                        FontPreference(
                            label = stringResource(R.string.cmvs_font_title),
                            value = font.substringAfterLast('/').ifBlank { stringResource(R.string.cmvs_font_default) },
                            followLabel = stringResource(R.string.cmvs_font_default),
                            onFollow = { if (!importing) font = "" },
                            onPick = { if (!importing) picker.launch("*/*") },
                            enabled = !importing,
                        )
                        Text(
                            stringResource(R.string.cmvs_font_note),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
            }
        }
    }
}
