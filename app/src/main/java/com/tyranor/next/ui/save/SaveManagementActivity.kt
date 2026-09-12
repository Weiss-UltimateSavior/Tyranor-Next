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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tyranor.next.R
import com.tyranor.next.core.game.save.GameSaveManager
import com.tyranor.next.core.game.save.RpgSaveFormat
import com.tyranor.next.core.game.save.RpgSaveSync
import com.tyranor.next.core.game.launch.EngineLauncher
import com.tyranor.next.core.game.model.ScanGame
import com.tyranor.next.core.game.model.ScanGameIntents
import com.tyranor.next.theme.NavWhite
import com.tyranor.next.theme.PageGrey
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
    val saveSyncResultFormat = stringResource(R.string.save_sync_result)
    val saveSyncNoChangeMessage = stringResource(R.string.save_sync_result_no_change)
    val saveSyncFailedFormat = stringResource(R.string.save_sync_result_failed)
    val saveSyncUnmappedFormat = stringResource(R.string.save_sync_unmapped)
    val manager = remember { GameSaveManager(context) }
    var location by remember { mutableStateOf<GameSaveManager.SaveLocation?>(null) }
    var fileCount by remember { mutableStateOf(0) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    // MV/MZ 导出格式选择（标准模式 / Tyranor 模式）；其他引擎直接导出。
    // 用 rememberSaveable：CreateDocument 系统页期间进程重建后仍按用户所选格式导出。
    var showExportFormatPicker by remember { mutableStateOf(false) }
    var exportFormat by rememberSaveable { mutableStateOf(GameSaveManager.ExportFormat.TYRANOR) }
    val rpgWebGame = RpgSaveFormat.isRpgWebEngine(game.engine)
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

    /** 把同步结果格式化成用户可读文案：无变化提示、有变化给明细、失败与无法识别的追加说明。 */
    fun formatSyncResult(result: RpgSaveSync.Result): String {
        // failed > 0 时不能只看 changed==0 就说「两侧一致」——可能是处理失败什么都没做成
        val base = when {
            result.failed > 0 -> saveSyncFailedFormat.format(result.failed)
            result.changed == 0 -> saveSyncNoChangeMessage
            else -> {
                val overwritten = result.toTyranor + result.toStandard
                saveSyncResultFormat.format(result.imported, result.exported, overwritten, result.movedToDeleted)
            }
        }
        return if (result.unmapped > 0) "$base\n${saveSyncUnmappedFormat.format(result.unmapped)}" else base
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
                val count = manager.exportToZip(game, uri, exportFormat)
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
                    modifier = Modifier.fillMaxWidth(),
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
                    onClick = {
                        if (rpgWebGame) {
                            showExportFormatPicker = true
                        } else {
                            exportFormat = GameSaveManager.ExportFormat.TYRANOR
                            exportLauncher.launch(defaultArchiveName(game))
                        }
                    },
                )
            }
            if (rpgWebGame) {
                item {
                    AppNavItem(
                        title = stringResource(R.string.save_sync_now),
                        showLeadingIcon = false,
                        onClick = {
                            runSaveTask {
                                val result = EngineLauncher.syncRpgSaves(context, game)
                                formatSyncResult(result)
                            }
                        },
                    )
                }
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

    // MV/MZ 导出格式选择：标准模式（JoiPlay/PC 兼容）/ Tyranor 模式；选项用 AppNavItem（弹窗内反色）
    if (showExportFormatPicker) {
        AppAlertDialog(
            onDismissRequest = { showExportFormatPicker = false },
            title = { Text(stringResource(R.string.save_export_format_title), style = MaterialTheme.typography.titleMedium) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    AppNavItem(
                        title = stringResource(R.string.save_export_format_standard),
                        summary = stringResource(R.string.save_export_format_standard_summary),
                        leadingIcon = R.drawable.ic_sheet_folder,
                        containerColor = PageGrey,
                        showArrow = false,
                        onClick = {
                            showExportFormatPicker = false
                            exportFormat = GameSaveManager.ExportFormat.STANDARD
                            exportLauncher.launch(defaultArchiveName(game))
                        },
                    )
                    AppNavItem(
                        title = stringResource(R.string.save_export_format_tyranor),
                        summary = stringResource(R.string.save_export_format_tyranor_summary),
                        leadingIcon = R.drawable.ic_sheet_saves,
                        containerColor = PageGrey,
                        showArrow = false,
                        onClick = {
                            showExportFormatPicker = false
                            exportFormat = GameSaveManager.ExportFormat.TYRANOR
                            exportLauncher.launch(defaultArchiveName(game))
                        },
                    )
                }
            },
            confirmButton = {},
        )
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
