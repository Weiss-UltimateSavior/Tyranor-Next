package com.tyranor.next.ui.game

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import com.tyranor.next.R
import com.tyranor.next.core.engine.EngineType
import com.tyranor.next.core.game.launch.YurisLaunchFiles
import com.tyranor.next.core.game.model.ScanGame
import com.tyranor.next.core.game.scan.EngineScanner
import com.tyranor.next.ui.common.AppAlertDialog
import com.tyranor.next.ui.common.DialogTextButton
import com.tyranor.next.ui.common.AppNavItem
import com.tyranor.next.theme.AppComponentShape
import com.tyranor.next.theme.DialogItemSurface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.RadioButton
import top.yukonga.miuix.kmp.theme.MiuixTheme as MiuixPreferenceTheme

/**
 * PC 游戏添加弹窗（不参与扫描）：选择目录（SAF，持久授权）→ 检索根目录 exe（过滤干扰项并排序）
 * → 指定启动 exe → 以 `EngineType.PC` 入库（标题默认目录名；启动 exe 写 `launchFile`，之后可在
 * 游戏详情「启动文件」中切换）。
 */
@Composable
internal fun PcGameAddDialog(
    onDismiss: () -> Unit,
    onAdd: (ScanGame) -> Boolean,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var dirUri by remember { mutableStateOf<Uri?>(null) }
    var dirName by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var candidates by remember { mutableStateOf<List<YurisLaunchFiles.ExeCandidate>>(emptyList()) }
    var selected by remember { mutableStateOf<String?>(null) }
    var errorRes by remember { mutableStateOf<Int?>(null) }

    val dirPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
        dirUri = uri
        errorRes = null
        loading = true
        candidates = emptyList()
        selected = null
        scope.launch {
            val result = withContext(Dispatchers.IO) { loadPcCandidates(context, uri) }
            dirName = result.first
            candidates = result.second
            selected = result.second.firstOrNull()?.name
            loading = false
        }
    }

    AppAlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(R.string.pc_add_dialog_title),
                style = MaterialTheme.typography.titleMedium,
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                AppNavItem(
                    title = stringResource(R.string.pc_add_pick_directory),
                    summary = dirName ?: stringResource(R.string.pc_add_directory_empty),
                    containerColor = DialogItemSurface,
                    indication = null,
                ) { dirPicker.launch(null) }

                when {
                    loading -> Box(
                        modifier = Modifier.fillMaxWidth().height(120.dp),
                        contentAlignment = Alignment.Center,
                    ) { CircularProgressIndicator() }

                    dirUri != null && candidates.isEmpty() -> Text(
                        stringResource(R.string.pc_add_exe_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
                    )

                    candidates.isNotEmpty() -> {
                        Text(
                            stringResource(R.string.pc_add_exe_label),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 4.dp, top = 6.dp, bottom = 2.dp),
                        )
                        MiuixPreferenceTheme {
                            LazyColumn(
                                modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                items(candidates, key = { it.name }) { candidate ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(AppComponentShape)
                                            .background(DialogItemSurface)
                                            .clickable(
                                                interactionSource = remember { MutableInteractionSource() },
                                                indication = null,
                                            ) { selected = candidate.name }
                                            .padding(horizontal = 12.dp, vertical = 9.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            candidate.name,
                                            style = MaterialTheme.typography.bodyMedium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f),
                                        )
                                        // 与「启动文件」弹窗同款选中标识：Miuix RadioButton 绘制的粗对勾；
                                        // onClick = null 只负责显示（选择由整行处理，无任何点击/按压效果）
                                        RadioButton(
                                            selected = selected == candidate.name,
                                            onClick = null,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                errorRes?.let { res ->
                    Text(
                        stringResource(res),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                    )
                }
            }
        },
        confirmButton = {
            DialogTextButton(
                text = stringResource(R.string.common_confirm),
                enabled = !loading && dirUri != null && selected != null,
            ) {
                val uri = dirUri ?: return@DialogTextButton
                val exe = selected ?: return@DialogTextButton
                val game = ScanGame(
                    title = dirName ?: exe,
                    uri = uri.toString(),
                    engine = EngineType.PC,
                    launchTarget = EngineScanner.LAUNCH_TARGET_GAME_DIR,
                    launchFile = exe,
                )
                val withCover = EngineScanner.applyLocalCover(context, game)
                if (onAdd(withCover)) onDismiss() else errorRes = R.string.pc_add_duplicate
            }
        },
        dismissButton = {
            DialogTextButton(
                text = stringResource(R.string.common_cancel),
                onClick = onDismiss,
            )
        },
    )
}


/** 读取所选目录（SAF）的目录名与 exe 候选（过滤干扰项并排序）。 */
private fun loadPcCandidates(
    context: Context,
    uri: Uri,
): Pair<String?, List<YurisLaunchFiles.ExeCandidate>> = runCatching {
    val tree = DocumentFile.fromTreeUri(context, uri)
    val name = tree?.name
    val entries = tree?.listFiles()
        ?.filter { it.isFile }
        ?.mapNotNull { file ->
            val fileName = file.name ?: return@mapNotNull null
            YurisLaunchFiles.ExeCandidate(fileName, file.length())
        }
        ?: emptyList()
    name to YurisLaunchFiles.candidatesOf(entries, name.orEmpty())
}.getOrElse { null to emptyList() }
