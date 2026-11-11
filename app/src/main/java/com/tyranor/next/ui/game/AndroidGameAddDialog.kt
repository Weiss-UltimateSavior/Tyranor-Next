package com.tyranor.next.ui.game

import com.tyranor.next.ui.common.motion.listItemMotion
import android.content.Context
import androidx.compose.foundation.LocalIndication
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tyranor.next.R
import com.tyranor.next.core.game.manual.AndroidAppGames
import com.tyranor.next.core.game.model.ScanGame
import com.tyranor.next.theme.AppComponentShape
import com.tyranor.next.theme.DialogItemSurface
import com.tyranor.next.ui.common.AppAlertDialog
import com.tyranor.next.ui.common.AppSearchField
import com.tyranor.next.ui.common.DialogTextButton
import com.tyranor.next.ui.common.NoIndication
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
 * 安卓游戏添加弹窗（不参与扫描）：读取设备上可启动的应用列表（应用名 + 包名）→
 * 搜索/单选 → 以 `EngineType.ANDROID_APP` 入库（uri = `androidapp://<包名>`，包名写
 * `launchTarget`）；之后从游戏库启动即按包名跳转该应用。已在库的应用置灰不可选。
 */
@Composable
internal fun AndroidGameAddDialog(
    existingUris: Set<String>,
    onDismiss: () -> Unit,
    onAdd: (ScanGame) -> Boolean,
    loadApps: (Context) -> List<AndroidAppGames.InstalledApp>? = AndroidAppGames::listLaunchableApps,
) {
    val context = LocalContext.current

    var loading by remember { mutableStateOf(true) }
    var loadFailed by remember { mutableStateOf(false) }
    var loadAttempt by remember { mutableIntStateOf(0) }
    var apps by remember { mutableStateOf<List<AndroidAppGames.InstalledApp>>(emptyList()) }
    var query by rememberSaveable { mutableStateOf("") }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var errorRes by remember { mutableStateOf<Int?>(null) }
    // 重复添加被拒绝：错误提示抖一下（失败计数器，+1 即抖）
    var errorShake by remember { mutableIntStateOf(0) }
    // 确认在「取消」之外的确认槽：借取消槽捕获的退场控制器，添加成功后先沉下再关闭
    val exitHandle = rememberGameDialogExitHandle()

    LaunchedEffect(loadAttempt) {
        loading = true
        loadFailed = false
        val result = withContext(Dispatchers.IO) { loadApps(context) }
        if (result == null) loadFailed = true else apps = result
        loading = false
    }

    val filtered = remember(apps, query) {
        val q = query.trim()
        if (q.isEmpty()) {
            apps
        } else {
            apps.filter {
                it.label.contains(q, ignoreCase = true) || it.packageName.contains(q, ignoreCase = true)
            }
        }
    }

    val selectedApp = remember(filtered, selected, existingUris) {
        selected?.let { pkg ->
            filtered.firstOrNull { it.packageName == pkg && AndroidAppGames.uriFor(pkg) !in existingUris }
        }
    }
    // 旋转后 apps 暂为空；只有成功读到本轮列表，才能判定恢复的包已经不存在或不可选。
    // 用同一次已提交组合的列表和加载状态做判断。异步重读可能已经完成，而旧协程仍捕获着空列表。
    if (!loading && !loadFailed && selected != null && selectedApp == null) {
        SideEffect {
            selected = null
            errorRes = null
        }
    }

    AppAlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(R.string.android_add_dialog_title),
                style = MaterialTheme.typography.titleMedium,
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                // 弹窗正文内的输入框禁用点击反馈（清空按钮走 LocalIndication）
                CompositionLocalProvider(LocalIndication provides NoIndication) {
                    AppSearchField(
                        query = query,
                        onQueryChange = { query = it },
                        textStyle = MaterialTheme.typography.bodyMedium,
                    )
                }

                // 错误提示：展开推开下方列表并从模糊里清晰，收起原路退回；再次被拒绝时抖一下
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

                // 加载中 / 失败 / 空 / 无匹配 / 列表 之间模糊替换（恒为一个子项，间距不变）
                val pane = when {
                    loading -> 0
                    loadFailed -> 1
                    apps.isEmpty() -> 2
                    filtered.isEmpty() -> 3
                    else -> 4
                }
                // 列表状态放在 BlurCrossfade 外：过滤到「无匹配」再回来时滚动位置不丢
                val listState = rememberLazyListState()
                BlurCrossfade(pane) { current ->
                when (current) {
                    0 -> Box(
                        modifier = Modifier.fillMaxWidth().height(120.dp),
                        contentAlignment = Alignment.Center,
                    ) { DelayedProgress { CircularProgressIndicator() } }

                    1 -> Column(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            stringResource(R.string.android_add_load_failed),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                        DialogTextButton(
                            text = stringResource(R.string.common_retry),
                        ) { loadAttempt++ }
                    }

                    2 -> Text(
                        stringResource(R.string.android_add_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
                    )

                    3 -> Text(
                        stringResource(R.string.android_add_no_match),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
                    )

                    // 列表到达：应用自上而下依次弹入（列表出现 / 从「无匹配」回来时的一小段窗口内；之后滚入的直接显示）
                    else -> ProvideEntrance { MiuixPreferenceTheme {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp).skipEntranceOnDrag(),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            itemsIndexed(filtered, key = { _, it -> it.packageName }) { index, app ->
                                val added = AndroidAppGames.uriFor(app.packageName) in existingUris
                                Row(
                                    modifier = Modifier
                                        .staggeredEntrance(index, distance = 14.dp)
                                        .then(listItemMotion())
                                        .pressSquish(enabled = !added, scale = PressSquishDefaults.RowScale)
                                        .fillMaxWidth()
                                        // 已在库的应用整行弱化，明确不可选原因
                                        .alpha(if (added) 0.45f else 1f)
                                        .clip(AppComponentShape)
                                        .background(DialogItemSurface)
                                        .clickable(
                                            enabled = !added,
                                            interactionSource = remember { MutableInteractionSource() },
                                            indication = null,
                                        ) {
                                            selected = app.packageName
                                            errorRes = null
                                        }
                                        .padding(horizontal = 12.dp, vertical = 9.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            app.label,
                                            style = MaterialTheme.typography.bodyMedium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            if (added) {
                                                "${app.packageName} · ${stringResource(R.string.android_add_already_added)}"
                                            } else {
                                                app.packageName
                                            },
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    // 与「启动文件」/「添加 PC 游戏」弹窗同款选中标识：Miuix RadioButton 绘制的粗对勾；
                                    // onClick = null 只负责显示（选择由整行处理，无任何点击/按压效果）
                                    // 选中落点：对勾出现的一刻弹一下（不改尺寸，只改绘制图层）
                                    Box(Modifier.bounceOnChange(selected == app.packageName, depth = 0.2f)) {
                                        RadioButton(
                                            selected = selected == app.packageName,
                                            onClick = null,
                                        )
                                    }
                                }
                            }
                        }
                    } }
                }
                }
            }
        },
        confirmButton = {
            val canConfirm = !loading && !loadFailed && selectedApp != null
            DialogTextButton(
                text = stringResource(R.string.common_confirm),
                // 变为可确认的一刻轻轻弹一下；按钮在取消之后弹入
                modifier = Modifier
                    .gameDialogAction(4, confirmEnabled = canConfirm),
                enabled = canConfirm,
            ) {
                val app = selectedApp ?: return@DialogTextButton
                val game = AndroidAppGames.toScanGame(app.label, app.packageName)
                if (onAdd(game)) {
                    // 添加成功的落点：弹窗先沉下退场再关闭
                    exitHandle.closeThen(onDismiss)
                } else {
                    errorRes = R.string.android_add_duplicate
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
