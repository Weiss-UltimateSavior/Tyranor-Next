package com.tyranor.next.ui.pages

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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tyranor.next.R
import com.tyranor.next.scanner.KrkrOnlinePatchService
import com.tyranor.next.scanner.KrkrPatchEntry
import com.tyranor.next.scanner.ScanGame
import com.tyranor.next.scanner.ScanGameIntents
import com.tyranor.next.theme.NavWhite
import com.tyranor.next.theme.TyranorNextTheme
import com.tyranor.next.ui.common.TopBarIcon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class KrkrOnlinePatchActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge(
            statusBarStyle = androidx.activity.SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = androidx.activity.SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
        )

        val game = intent.readScanGame()
        if (game == null) {
            finish()
            return
        }

        setContent {
            TyranorNextTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    KrkrOnlinePatchScreen(game = game)
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    override fun finish() {
        super.finish()
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
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
                runCatching { KrkrOnlinePatchService.fetchPatchIndex() }
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
                        "在线补丁",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    TopBarIcon(painterResource(R.drawable.ic_refresh), "刷新", MaterialTheme.colorScheme.primary) {
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
                OutlinedTextField(
                    value = keyword,
                    onValueChange = { keyword = it },
                    singleLine = true,
                    label = { Text("搜索游戏名或品牌") },
                    modifier = Modifier.fillMaxWidth(),
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
                        "未找到匹配补丁",
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
                                Toast.makeText(context, "已安装 ${it.installed.size} 个补丁", Toast.LENGTH_LONG).show()
                                message = "已写入：${it.target}"
                            }.onFailure {
                                message = it.message ?: "补丁安装失败"
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
                listOf(entry.brand, formatPatchDate(entry.timestamp)).filter { it.isNotBlank() }.joinToString(" · "),
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
                Text(if (installing) "处理中" else "下载并安装", modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}

private fun formatPatchDate(timestamp: Long): String {
    if (timestamp <= 0L) return ""
    return SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(timestamp))
}
