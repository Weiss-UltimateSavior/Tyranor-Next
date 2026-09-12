package com.tyranor.next.ui.save

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tyranor.next.R
import com.tyranor.next.core.game.save.GameSaveManager
import com.tyranor.next.core.game.model.ScanGame
import com.tyranor.next.core.game.model.ScanGameIntents
import com.tyranor.next.theme.NavWhite
import com.tyranor.next.theme.glassBorder
import com.tyranor.next.ui.common.AppAlertDialog
import com.tyranor.next.ui.common.AppNavItem
import com.tyranor.next.ui.common.AppScreenActivity
import com.tyranor.next.ui.common.AppTopBar
import com.tyranor.next.ui.common.BottomInsetSpacer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SaveManagementActivity : AppScreenActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val game = intent.readScanGame()
        if (game == null) {
            finish()
            return
        }

        setAppScreenContent {
            SaveManagementScreen(game = game)
        }
    }

    companion object {
        fun createIntent(context: Context, game: ScanGame): Intent =
            ScanGameIntents.putGame(Intent(context, SaveManagementActivity::class.java), game)

        private fun Intent.readScanGame(): ScanGame? = ScanGameIntents.getGame(this)
    }
}

@Composable
private fun SaveManagementScreen(game: ScanGame) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val saveOperationFailedMessage = stringResource(R.string.save_operation_failed)
    val saveExportedCountFormat = stringResource(R.string.save_exported_count)
    val saveImportedCountFormat = stringResource(R.string.save_imported_count)
    val saveDeletedCountFormat = stringResource(R.string.save_deleted_count)
    val manager = remember { GameSaveManager(context) }
    var location by remember { mutableStateOf<GameSaveManager.SaveLocation?>(null) }
    var fileCount by remember { mutableStateOf(0) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    // 导入/导出/删除互斥：并发任务会互相清掉对方的暂存目录，破坏导入的原子性
    var taskRunning by remember { mutableStateOf(false) }

    // 目录解析与文件递归遍历均为磁盘 IO：统一切到 IO 线程，避免组合期/主线程卡顿
    suspend fun refresh() {
        val snapshot = withContext(Dispatchers.IO) {
            manager.resolveSaveLocation(game) to manager.listSaveFiles(game).size
        }
        location = snapshot.first
        fileCount = snapshot.second
    }

    LaunchedEffect(game) {
        refresh()
    }

    fun runSaveTask(block: suspend () -> String) {
        if (taskRunning) return
        scope.launch {
            taskRunning = true
            try {
                val message = withContext(Dispatchers.IO) {
                    try {
                        block()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (t: Throwable) {
                        t.toSaveErrorMessage(context, saveOperationFailedMessage)
                    }
                }
                refresh()
                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            } finally {
                taskRunning = false
            }
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri: Uri? ->
        if (uri != null) {
            runSaveTask {
                val count = manager.exportToZip(game, uri)
                saveExportedCountFormat.format(count)
            }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            runSaveTask {
                val count = manager.importFromZip(game, uri)
                saveImportedCountFormat.format(count)
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        AppTopBar(title = stringResource(R.string.save_management_title))

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
            contentPadding = PaddingValues(top = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth().glassBorder(),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                    colors = CardDefaults.cardColors(containerColor = NavWhite),
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        Text(game.title, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        location?.let { loadedLocation ->
                            Text(
                                loadedLocation.description,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                            Text(
                                if (loadedLocation.available) stringResource(R.string.save_file_count, fileCount) else stringResource(R.string.save_unmanageable),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                }
            }

            item {
                AppNavItem(
                    title = stringResource(R.string.save_export_zip),
                    showLeadingIcon = false,
                    onClick = { exportLauncher.launch(defaultArchiveName(game)) },
                )
            }
            item {
                AppNavItem(
                    title = stringResource(R.string.save_import_zip),
                    showLeadingIcon = false,
                    onClick = { importLauncher.launch("application/zip") },
                )
            }
            item {
                AppNavItem(
                    title = stringResource(R.string.save_delete_title),
                    showLeadingIcon = false,
                    onClick = { showDeleteConfirm = true },
                )
            }
            item { BottomInsetSpacer() }
        }
    }

    if (showDeleteConfirm) {
        AppAlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(stringResource(R.string.save_delete_title), style = MaterialTheme.typography.titleMedium) },
            text = { Text(stringResource(R.string.save_delete_message, game.title)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirm = false
                        runSaveTask {
                            val count = manager.deleteSaves(game)
                            saveDeletedCountFormat.format(count)
                        }
                    },
                ) { Text(stringResource(R.string.common_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }
}

private fun defaultArchiveName(game: ScanGame): String {
    val safeTitle = game.title.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "game" }
    return "${safeTitle}_saves.zip"
}
