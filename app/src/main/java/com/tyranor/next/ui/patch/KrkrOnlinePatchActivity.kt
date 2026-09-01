package com.tyranor.next.ui.patch

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tyranor.next.R
import com.tyranor.next.core.patch.KrkrOnlinePatchService
import com.tyranor.next.core.patch.KrkrPatchEntry
import com.tyranor.next.core.game.model.ScanGame
import com.tyranor.next.core.game.model.ScanGameIntents
import com.tyranor.next.core.settings.AppSettingsStore
import com.tyranor.next.theme.NavWhite
import com.tyranor.next.core.i18n.ProvideAppLocale
import com.tyranor.next.theme.TyranorNextTheme
import com.tyranor.next.ui.common.AppSearchField
import com.tyranor.next.ui.common.TimeFormats
import com.tyranor.next.ui.common.TopBarIcon
import com.tyranor.next.ui.common.WithoutPressIndication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class KrkrOnlinePatchActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val darkMode = AppSettingsStore.isDarkEffective(this)
        enableEdgeToEdge(
            statusBarStyle = if (darkMode) androidx.activity.SystemBarStyle.dark(Color.TRANSPARENT) else androidx.activity.SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = if (darkMode) androidx.activity.SystemBarStyle.dark(Color.TRANSPARENT) else androidx.activity.SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
        )

        val game = intent.readScanGame()
        if (game == null) {
            finish()
            return
        }

        setContent {
            ProvideAppLocale {
                TyranorNextTheme {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        WithoutPressIndication {
                            KrkrOnlinePatchScreen(game = game)
                        }
                    }
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    override fun finish() {
        super.finish()
        overridePendingTransition(R.anim.page_slide_in_from_top, R.anim.page_slide_out_to_bottom)
    }

    companion object {
        fun createIntent(context: Context, game: ScanGame): Intent =
            ScanGameIntents.putGame(Intent(context, KrkrOnlinePatchActivity::class.java), game)

        private fun Intent.readScanGame(): ScanGame? = ScanGameIntents.getGame(this)
    }
}

@Composable
private fun KrkrOnlinePatchScreen(game: ScanGame) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val patchInstalledCountFormat = stringResource(R.string.patch_installed_count)
    val patchWrittenToFormat = stringResource(R.string.patch_written_to)
    val patchInstallFailedMessage = stringResource(R.string.patch_install_failed)
    var keyword by remember { mutableStateOf(game.title) }
    var loading by remember { mutableStateOf(false) }
    var installing by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var entries by remember { mutableStateOf<List<KrkrPatchEntry>>(emptyList()) }

    fun loadIndex() {
        if (loading) return
        scope.launch {
            loading = true
            message = null
            val result = withContext(Dispatchers.IO) {
                runCatching { KrkrOnlinePatchService.fetchPatchIndex(context) }
            }
            entries = result.getOrDefault(emptyList())
            message = result.exceptionOrNull()?.message
            loading = false
        }
    }

    LaunchedEffect(Unit) {
        loadIndex()
    }

    val filtered = remember(entries, keyword) {
        KrkrOnlinePatchService.search(entries, keyword).take(80)
    }

    Column(Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background)) {
            Column(modifier = Modifier.fillMaxWidth().statusBarsPadding()) {
                Row(
                    modifier = Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.patch_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    TopBarIcon(painterResource(R.drawable.ic_refresh), stringResource(R.string.patch_refresh_content_description), MaterialTheme.colorScheme.primary) {
                        loadIndex()
                    }
                }
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
            contentPadding = PaddingValues(top = 16.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                AppSearchField(
                    query = keyword,
                    onQueryChange = { keyword = it },
                )
            }

            if (loading) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        CircularProgressIndicator(Modifier.size(28.dp))
                    }
                }
            }

            message?.let { text ->
                item {
                    Text(
                        text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
            }

            if (!loading && filtered.isEmpty() && message == null) {
                item {
                    Text(
                        stringResource(R.string.patch_no_match),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 12.dp),
                    )
                }
            }

            items(filtered, key = { "${it.timestamp}-${it.name}-${it.path}" }) { entry ->
                PatchEntryCard(
                    entry = entry,
                    installing = installing,
                    onInstall = { selected ->
                        scope.launch {
                            installing = true
                            message = null
                            val result = runCatching {
                                KrkrOnlinePatchService.downloadAndInstall(context, game, selected) {
                                    message = it
                                }
                            }
                            result.onSuccess {
                                Toast.makeText(context, patchInstalledCountFormat.format(it.installed.size), Toast.LENGTH_LONG).show()
                                message = patchWrittenToFormat.format(it.target)
                            }.onFailure {
                                message = it.message ?: patchInstallFailedMessage
                            }
                            installing = false
                        }
                    },
                )
            }

            item {
                Spacer(Modifier.navigationBarsPadding().height(4.dp))
            }
        }
    }
}

@Composable
private fun PatchEntryCard(
    entry: KrkrPatchEntry,
    installing: Boolean,
    onInstall: (List<String>) -> Unit,
) {
    val checked = remember(entry) {
        mutableStateMapOf<String, Boolean>().apply {
            entry.patches.forEach { put(it, true) }
        }
    }
    val selected = entry.patches.filter { checked[it] == true }

    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        colors = CardDefaults.cardColors(containerColor = NavWhite),
        shape = RoundedCornerShape(8.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Text(
                entry.name,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOf(entry.brand, TimeFormats.formatDate(entry.timestamp)).filter { it.isNotBlank() }.joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp),
            )
            Text(
                entry.path,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )

            Column(
                modifier = Modifier.padding(top = 10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                entry.patches.forEach { url ->
                    val name = url.substringAfterLast('/')
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable {
                            checked[url] = checked[url] != true
                        },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = checked[url] == true,
                            onCheckedChange = { checked[url] = it },
                        )
                        Text(
                            name,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }

            Button(
                onClick = { onInstall(selected) },
                enabled = selected.isNotEmpty() && !installing,
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
            ) {
                Icon(Icons.Filled.CloudDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(if (installing) stringResource(R.string.patch_processing) else stringResource(R.string.patch_download_install), modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}
