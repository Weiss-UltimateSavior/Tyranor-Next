package com.tyranor.next.ui.game

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
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.RadioButton
import top.yukonga.miuix.kmp.theme.MiuixTheme as MiuixPreferenceTheme

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
) {
    val context = LocalContext.current

    var loading by remember { mutableStateOf(true) }
    var loadFailed by remember { mutableStateOf(false) }
    var loadAttempt by remember { mutableIntStateOf(0) }
    var apps by remember { mutableStateOf<List<AndroidAppGames.InstalledApp>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<String?>(null) }
    var errorRes by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(loadAttempt) {
        loading = true
        loadFailed = false
        val result = withContext(Dispatchers.IO) { AndroidAppGames.listLaunchableApps(context) }
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

    // 过滤后选中项不可见时清除选择，避免确认按钮把「已被搜索隐藏」的应用加入库
    LaunchedEffect(filtered) {
        val current = selected ?: return@LaunchedEffect
        if (filtered.none { it.packageName == current }) {
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

                errorRes?.let { res ->
                    Text(
                        stringResource(res),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                    )
                }

                when {
                    loading -> Box(
                        modifier = Modifier.fillMaxWidth().height(120.dp),
                        contentAlignment = Alignment.Center,
                    ) { CircularProgressIndicator() }

                    loadFailed -> Column(
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

                    apps.isEmpty() -> Text(
                        stringResource(R.string.android_add_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
                    )

                    filtered.isEmpty() -> Text(
                        stringResource(R.string.android_add_no_match),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
                    )

                    else -> MiuixPreferenceTheme {
                        LazyColumn(
                            modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            items(filtered, key = { it.packageName }) { app ->
                                val added = AndroidAppGames.uriFor(app.packageName) in existingUris
                                Row(
                                    modifier = Modifier
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
                                    RadioButton(
                                        selected = selected == app.packageName,
                                        onClick = null,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            DialogTextButton(
                text = stringResource(R.string.common_confirm),
                enabled = !loading && selected != null &&
                    filtered.any { it.packageName == selected },
            ) {
                val packageName = selected ?: return@DialogTextButton
                val app = filtered.firstOrNull { it.packageName == packageName }
                    ?: return@DialogTextButton
                val game = AndroidAppGames.toScanGame(app.label, app.packageName)
                if (onAdd(game)) onDismiss() else errorRes = R.string.android_add_duplicate
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
