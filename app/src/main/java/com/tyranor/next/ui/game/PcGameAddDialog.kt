package com.tyranor.next.ui.game

import com.tyranor.next.ui.common.motion.listItemMotion
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.tyranor.next.ui.common.motion.BlurCrossfade
import com.tyranor.next.ui.common.motion.ExpandVisibility
import com.tyranor.next.ui.common.motion.PressSquishDefaults
import com.tyranor.next.ui.common.motion.ProvideEntrance
import com.tyranor.next.ui.common.motion.bounceOnChange
import com.tyranor.next.ui.common.motion.pressSquish
import com.tyranor.next.ui.common.motion.rememberLastNonNull
import com.tyranor.next.ui.common.motion.shakeOnTrigger
import com.tyranor.next.ui.common.motion.skipEntranceOnDrag
import com.tyranor.next.ui.common.motion.staggeredEntrance
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.RadioButton
import top.yukonga.miuix.kmp.theme.MiuixTheme as MiuixPreferenceTheme
import com.tyranor.next.ui.common.motion.DelayedProgress

/**
 * PC 游戏添加弹窗（不参与扫描）：选择目录（SAF，持久授权）→ 检索根目录 exe（过滤干扰项并排序）
 * → 指定启动 exe → 以 `EngineType.PC` 入库（标题默认目录名；启动 exe 写 `launchFile`，之后可在
 * 游戏详情「启动文件」中切换）。
 */
@Composable
internal fun PcGameAddDialog(
    onDismiss: () -> Unit,
    onAdd: (ScanGame) -> Boolean,
    loadDirectory: (Context, Uri) -> Pair<String?, List<YurisLaunchFiles.ExeCandidate>> = ::loadPcCandidates,
) {
    val context = LocalContext.current
    var dirUri by rememberSaveable { mutableStateOf<Uri?>(null) }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var scanRequest by remember { mutableIntStateOf(0) }
    var listing by remember(dirUri, scanRequest) {
        mutableStateOf<Pair<String?, List<YurisLaunchFiles.ExeCandidate>>?>(null)
    }
    val dirName = listing?.first
    val candidates = listing?.second.orEmpty()
    val loading = dirUri != null && listing == null
    var errorRes by remember { mutableStateOf<Int?>(null) }
    // 重复添加被拒绝：错误提示抖一下（失败计数器，+1 即抖）
    var errorShake by remember { mutableIntStateOf(0) }
    // 确认在「取消」之外的确认槽：借取消槽捕获的退场控制器，添加成功后先沉下再关闭
    val exitHandle = rememberGameDialogExitHandle()

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
        selected = null
        scanRequest++ // 再选同一目录也重新读取。
    }

    LaunchedEffect(dirUri, scanRequest) {
        val uri = dirUri ?: return@LaunchedEffect
        val request = scanRequest
        val result = withContext(Dispatchers.IO) { loadDirectory(context, uri) }
        if (dirUri != uri || scanRequest != request) return@LaunchedEffect
        // 恢复时优先保留明确选的 exe；若它已删除，让用户重选，不能悄悄换一个启动文件。
        if (selected == null) selected = result.second.firstOrNull()?.name
        listing = result
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
                    // 选好目录（目录名摘要换上）的一刻整条轻轻弹一下，作为选择落点
                    modifier = Modifier.bounceOnChange(dirName, depth = 0.05f),
                    title = stringResource(R.string.pc_add_pick_directory),
                    summary = dirName ?: stringResource(R.string.pc_add_directory_empty),
                    containerColor = DialogItemSurface,
                    indication = null,
                ) { dirPicker.launch(null) }

                // 检索中 / 无 exe / exe 列表：首次出现时高度展开推开下方内容，之后各状态之间模糊替换
                // （原版每个状态各自直接出现；未选目录时不占位、不产生额外间距，与原版一致）
                val pane = when {
                    loading -> PC_PANE_LOADING
                    dirUri != null && candidates.isEmpty() -> PC_PANE_EMPTY
                    candidates.isNotEmpty() -> PC_PANE_LIST
                    else -> PC_PANE_NONE
                }
                val shownPane = rememberLastNonNull(pane.takeIf { it != PC_PANE_NONE }) ?: PC_PANE_LOADING
                ExpandVisibility(pane != PC_PANE_NONE) {
                BlurCrossfade(shownPane) { current ->
                when (current) {
                    PC_PANE_LOADING -> Box(
                        modifier = Modifier.fillMaxWidth().height(120.dp),
                        contentAlignment = Alignment.Center,
                    ) { DelayedProgress { CircularProgressIndicator() } }

                    PC_PANE_EMPTY -> Text(
                        stringResource(R.string.pc_add_exe_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
                    )

                    else -> Column(
                        modifier = Modifier.fillMaxWidth(),
                        // 与外层列同一间距：标签与列表之间的距离保持原样
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            stringResource(R.string.pc_add_exe_label),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 4.dp, top = 6.dp, bottom = 2.dp),
                        )
                        // 列表到达：候选 exe 自上而下依次弹入（列表出现时的一小段窗口内；之后滚入的直接显示）
                        ProvideEntrance {
                        MiuixPreferenceTheme {
                            LazyColumn(
                                modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp).skipEntranceOnDrag(),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                itemsIndexed(candidates, key = { _, it -> it.name }) { index, candidate ->
                                    Row(
                                        modifier = Modifier
                                            .staggeredEntrance(index, distance = 14.dp)
                                            .then(listItemMotion())
                                            .pressSquish(scale = PressSquishDefaults.RowScale)
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
                                        // 选中落点：对勾出现的一刻弹一下（不改尺寸，只改绘制图层）
                                        Box(Modifier.bounceOnChange(selected == candidate.name, depth = 0.2f)) {
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
                    }
                }
                }
                }

                // 错误提示：展开推开内容并从模糊里清晰，收起原路退回；再次被拒绝时抖一下
                val shownErrorRes = rememberLastNonNull(errorRes)
                // 抖动挂在显隐容器外层：展开过程的裁切不会切掉抖动中的文字边缘
                ExpandVisibility(errorRes != null, modifier = Modifier.shakeOnTrigger(errorShake)) {
                    shownErrorRes?.let { res ->
                        Text(
                            stringResource(res),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            val canConfirm = !loading && selected != null && candidates.any { it.name == selected }
            DialogTextButton(
                text = stringResource(R.string.common_confirm),
                // 变为可确认的一刻轻轻弹一下；按钮在取消之后弹入
                modifier = Modifier
                    .gameDialogAction(4, confirmEnabled = canConfirm),
                enabled = canConfirm,
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
                if (onAdd(withCover)) {
                    // 添加成功的落点：弹窗先沉下退场再关闭
                    exitHandle.closeThen(onDismiss)
                } else {
                    errorRes = R.string.pc_add_duplicate
                    errorShake++
                }
            }
        },
        dismissButton = {
            exitHandle.capture()
            DialogTextButton(
                text = stringResource(R.string.common_cancel),
                modifier = Modifier.gameDialogAction(3),
                onClick = onDismiss,
            )
        },
    )
}

/** 正文状态区的几种内容（[PC_PANE_NONE] = 尚未选择目录，不占位）。 */
private const val PC_PANE_NONE = -1
private const val PC_PANE_LOADING = 0
private const val PC_PANE_EMPTY = 1
private const val PC_PANE_LIST = 2


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
