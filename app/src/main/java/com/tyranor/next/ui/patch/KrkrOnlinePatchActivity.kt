package com.tyranor.next.ui.patch

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tyranor.next.R
import com.tyranor.next.core.patch.KrkrOnlinePatchService
import com.tyranor.next.core.patch.KrkrPatchEntry
import com.tyranor.next.core.game.model.ScanGame
import com.tyranor.next.core.game.model.ScanGameIntents
import com.tyranor.next.theme.DialogItemSurface
import com.tyranor.next.theme.NavWhite
import com.tyranor.next.theme.PageSecondaryTextColor
import com.tyranor.next.theme.SecondaryTextColor
import com.tyranor.next.theme.TextColor
import com.tyranor.next.theme.glassBorder
import com.tyranor.next.theme.AppComponentShape
import com.tyranor.next.ui.common.AppAlertDialog
import com.tyranor.next.ui.common.AppScreenActivity
import com.tyranor.next.ui.common.DialogTextButton
import com.tyranor.next.ui.common.LocalDialogExit
import com.tyranor.next.ui.common.AppSecondaryTopBar
import com.tyranor.next.ui.common.AppSearchField
import com.tyranor.next.ui.common.NoRippleButton
import com.tyranor.next.ui.common.TimeFormats
import com.tyranor.next.ui.common.TopBarIcon
import com.tyranor.next.ui.common.motion.BlurCrossfade
import com.tyranor.next.ui.common.motion.listItemMotion
import com.tyranor.next.ui.common.motion.PressSquishDefaults
import com.tyranor.next.ui.common.motion.ProvideEntrance
import com.tyranor.next.ui.common.motion.skipEntranceOnDrag
import com.tyranor.next.ui.common.motion.pressSquish
import com.tyranor.next.ui.common.motion.pressSquishGroup
import com.tyranor.next.ui.common.motion.breathing
import com.tyranor.next.ui.common.motion.rememberScrollJellyState
import com.tyranor.next.ui.common.motion.scrollJelly
import com.tyranor.next.ui.common.motion.scrollJellyHost
import com.tyranor.next.ui.common.motion.shakeOnTrigger
import com.tyranor.next.ui.common.motion.staggeredEntrance
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.tyranor.next.ui.common.notice.AppNotice
import com.tyranor.next.ui.common.motion.DelayedProgress

class KrkrOnlinePatchActivity : AppScreenActivity() {
    /** 先完成页面基类初始化；缺少游戏输入时直接退出，否则为补丁页建立独立入场作用域。 */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val game = intent.readScanGame()
        if (game == null) {
            finish()
            return
        }

        setAppScreenContent {
            ProvideEntrance {
                KrkrOnlinePatchContent(game = game, inBook = false) { loading, onRefresh ->
                    AppSecondaryTopBar(
                        title = stringResource(R.string.patch_title),
                        trailing = {
                            // 拉取索引期间刷新图标缓缓明灭（包一层不改尺寸的 Box，静止时与原样一致）
                            Box(Modifier.breathing(loading)) {
                                TopBarIcon(painterResource(R.drawable.ic_refresh), stringResource(R.string.patch_refresh_content_description), MaterialTheme.colorScheme.primary, spin = true) {
                                    onRefresh()
                                }
                            }
                        },
                    )
                }
            }
        }
    }

    companion object {
        fun createIntent(context: Context, game: ScanGame): Intent =
            ScanGameIntents.putGame(Intent(context, KrkrOnlinePatchActivity::class.java), game)

        private fun Intent.readScanGame(): ScanGame? = ScanGameIntents.getGame(this)
    }
}

/**
 * 「游戏盒」翻开的书里的在线补丁：不另开二级页面，补丁页翻在书的下一页上（章名「在线补丁」，下面是搜索 + 补丁条目，
 * 页脚「关闭」先翻回再关）。内容与二级页面是同一份（[KrkrOnlinePatchContent]），只是书里没有顶栏，刷新放在搜索框旁边。
 */
@Composable
internal fun KrkrOnlinePatchDialog(game: ScanGame, onDismiss: () -> Unit) {
    var busy by remember { mutableStateOf(false) }
    val canDismiss = { !busy }
    AppAlertDialog(
        onDismissRequest = onDismiss,
        dismissEnabledNow = canDismiss,
        title = { Text(stringResource(R.string.patch_title), style = MaterialTheme.typography.titleMedium) },
        text = { KrkrOnlinePatchContent(game = game, inBook = true, onBusyChange = { busy = it }) },
        confirmButton = {
            val exit = LocalDialogExit.current
            CompositionLocalProvider(LocalDialogExit provides null) {
                DialogTextButton(text = stringResource(R.string.common_close), enabled = !busy) {
                    if (!canDismiss()) return@DialogTextButton
                    if (exit != null) exit.closeThen(onDismiss) else onDismiss()
                }
            }
        },
    )
}

/**
 * 为指定游戏加载并筛选补丁索引，向服务提交勾选的补丁文件并展示安装结果。
 * 搜索、加载、消息与补丁条目使用独立列表身份，使内容转场不会复用其他状态的节点。
 * [inBook]：书页里的版本（条目底色取弹窗条目色、刷新钮放在搜索框旁、没有顶栏）；[topBar] 是二级页面的顶栏槽。
 */
@Composable
private fun KrkrOnlinePatchContent(
    game: ScanGame,
    inBook: Boolean,
    onBusyChange: (Boolean) -> Unit = {},
    topBar: @Composable (loading: Boolean, onRefresh: () -> Unit) -> Unit = { _, _ -> },
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val patchInstalledCountFormat = stringResource(R.string.patch_installed_count)
    val patchWrittenToFormat = stringResource(R.string.patch_written_to)
    val patchInstallFailedMessage = stringResource(R.string.patch_install_failed)
    var keyword by rememberSaveable(game.uri) { mutableStateOf(game.title) }
    var loading by remember { mutableStateOf(false) }
    var installing by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var entries by remember { mutableStateOf<List<KrkrPatchEntry>>(emptyList()) }

    DisposableEffect(Unit) { onDispose { onBusyChange(false) } }

    fun loadIndex() {
        if (loading || installing) return
        loading = true
        message = null
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    try {
                        Result.success(KrkrOnlinePatchService.fetchPatchIndex(context))
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (t: Throwable) {
                        Result.failure(t)
                    }
                }
                result.onSuccess { entries = it }
                message = result.exceptionOrNull()?.message
            } finally {
                loading = false
            }
        }
    }

    LaunchedEffect(game.uri) {
        loadIndex()
    }

    val filtered = remember(entries, keyword) {
        KrkrOnlinePatchService.search(entries, keyword).take(80)
    }
    // 与原「无匹配」条件完全相同，抽出后同时用于空态条目与搜索框的抖动反馈
    val noMatch = !loading && filtered.isEmpty() && message == null

    Column(Modifier.fillMaxSize().skipEntranceOnDrag()) {
        topBar(loading) { loadIndex() }

        // 补丁卡片列表：滚得越快卡片越被拉长一点，停下 Q 弹回原样
        val jelly = rememberScrollJellyState()
        LazyColumn(
            modifier = Modifier.fillMaxSize().scrollJellyHost(jelly).then(if (inBook) Modifier else Modifier.padding(horizontal = 16.dp)),
            contentPadding = if (inBook) PaddingValues(bottom = 8.dp) else PaddingValues(top = 16.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(if (inBook) 8.dp else 12.dp),
        ) {
            item(key = "search") {
                // 关键字过滤到「无匹配」的那一刻左右抖一下
                // （只在用户自己改过关键字之后才抖：打开页面时默认关键字无匹配不算「被拒绝」）
                var keywordEdited by remember { mutableStateOf(false) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AppSearchField(
                        query = keyword,
                        onQueryChange = {
                            keyword = it
                            keywordEdited = true
                        },
                        modifier = Modifier
                            .weight(1f)
                            .staggeredEntrance(0)
                            .shakeOnTrigger(noMatch && keywordEdited, amplitude = 5.dp),
                    )
                    if (inBook) {
                        // 书里没有顶栏，刷新钮跟在搜索框后面（脱离顶栏专用 TopBarIcon）
                        val refreshInteraction = remember { MutableInteractionSource() }
                        Box(
                            modifier = Modifier
                                .padding(start = 6.dp)
                                .size(40.dp)
                                .breathing(loading)
                                .pressSquish(enabled = !loading && !installing, scale = PressSquishDefaults.Scale)
                                .clickable(
                                    interactionSource = refreshInteraction,
                                    indication = null,
                                    enabled = !loading && !installing,
                                    role = Role.Button,
                                    onClick = { loadIndex() },
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_refresh),
                                contentDescription = stringResource(R.string.patch_refresh_content_description),
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(22.dp),
                            )
                        }
                    }
                }
            }

            if (loading) {
                item(key = "loading") {
                    Row(
                        modifier = Modifier
                            .then(listItemMotion())
                            .fillMaxWidth()
                            .padding(vertical = 24.dp),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        DelayedProgress { CircularProgressIndicator(Modifier.size(28.dp)) }
                    }
                }
            }

            message?.let { text ->
                item(key = "message") {
                    // 错误 / 进度文字变化时模糊替换
                    BlurCrossfade(
                        targetState = text,
                        modifier = Modifier
                            .then(listItemMotion())
                            .padding(horizontal = 4.dp),
                    ) { messageText ->
                        Text(
                            messageText,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }

            if (noMatch) {
                item(key = "no_match") {
                    Text(
                        stringResource(R.string.patch_no_match),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (inBook) SecondaryTextColor else PageSecondaryTextColor,
                        modifier = Modifier
                            .then(listItemMotion())
                            .padding(horizontal = 4.dp, vertical = 12.dp),
                    )
                }
            }

            itemsIndexed(filtered, key = { _, it -> "${it.timestamp}-${it.name}-${it.path}" }) { index, entry ->
                PatchEntryCard(
                    modifier = Modifier
                        .scrollJelly(jelly)
                        .staggeredEntrance(index + 1)
                        .then(listItemMotion()),
                    entry = entry,
                    inBook = inBook,
                    loading = loading,
                    installing = installing,
                    onInstall = { selected ->
                        if (loading || installing) return@PatchEntryCard
                        installing = true
                        onBusyChange(true)
                        message = null
                        scope.launch(start = CoroutineStart.UNDISPATCHED) {
                            try {
                                val result = try {
                                    Result.success(
                                        KrkrOnlinePatchService.downloadAndInstall(context, game, selected) { progressMsg ->
                                            scope.launch(Dispatchers.Main.immediate) { message = progressMsg }
                                        },
                                    )
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (t: Throwable) {
                                    Result.failure(t)
                                }
                                result.onSuccess {
                                    AppNotice.makeText(context, patchInstalledCountFormat.format(it.installed.size), Toast.LENGTH_LONG, AppNotice.Kind.Success).show()
                                    message = patchWrittenToFormat.format(it.target)
                                }.onFailure {
                                    message = it.message ?: patchInstallFailedMessage
                                }
                            } finally {
                                installing = false
                                onBusyChange(false)
                            }
                        }
                    },
                )
            }

            item(key = "bottom_spacer") {
                if (inBook) {
                    Spacer(Modifier.height(4.dp))
                } else {
                    Spacer(Modifier.navigationBarsPadding().height(4.dp))
                }
            }
        }
    }
}

/** 按补丁条目保存文件勾选状态；刷新或安装期间禁用提交，由调用方执行下载与写入。 */
@Composable
private fun PatchEntryCard(
    entry: KrkrPatchEntry,
    inBook: Boolean,
    loading: Boolean,
    installing: Boolean,
    onInstall: (List<String>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val checked = remember(entry) {
        mutableStateMapOf<String, Boolean>().apply {
            entry.patches.forEach { put(it, true) }
        }
    }
    val selected = entry.patches.filter { checked[it] == true }
    // 仅供动效：安装是否由本卡发起（只让发起的那张卡的按钮呼吸）
    var installStartedHere by remember(entry) { mutableStateOf(false) }
    LaunchedEffect(installing) {
        if (!installing) installStartedHere = false
    }

    com.tyranor.next.ui.common.AppSectionCard(
        modifier = modifier.pressSquishGroup(),
    ) {
        val secondaryInk = if (inBook) com.tyranor.next.theme.CurrentBookPaperPalette.inkSecondary else SecondaryTextColor
        Column(Modifier.fillMaxWidth().padding(if (inBook) 4.dp else 14.dp)) {
            Text(
                entry.name,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOf(entry.brand, TimeFormats.formatDate(entry.timestamp)).filter { it.isNotBlank() }.joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
                color = secondaryInk,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp),
            )
            Text(
                entry.path,
                style = MaterialTheme.typography.bodyMedium,
                color = secondaryInk,
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
                    val isChecked = checked[url] == true
                    val rowInteraction = remember { MutableInteractionSource() }
                    val rowToggle = if (inBook) {
                        Modifier.toggleable(
                            value = isChecked,
                            interactionSource = rowInteraction,
                            indication = null,
                            role = Role.Checkbox,
                            onValueChange = { checked[url] = it },
                        )
                    } else {
                        Modifier.clickable(interactionSource = rowInteraction, indication = null) {
                            checked[url] = checked[url] != true
                        }
                    }
                    Row(
                        modifier = Modifier
                            .pressSquish(scale = PressSquishDefaults.RowScale)
                            .fillMaxWidth()
                            .then(rowToggle),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        com.tyranor.next.ui.common.AppCheckbox(
                            checked = isChecked,
                            onCheckedChange = if (inBook) null else { value -> checked[url] = value },
                            modifier = if (inBook) Modifier.minimumInteractiveComponentSize() else Modifier,
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

            // 「下载安装」↔「处理中」整颗按钮模糊替换（文案与可用态底色一起过渡，不瞬间换字）；
            // 由本卡发起的安装进行中，按钮缓缓明灭
            BlurCrossfade(
                targetState = installing,
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                label = "patchInstallButton",
            ) { shownInstalling ->
                NoRippleButton(
                    text = if (shownInstalling) stringResource(R.string.patch_processing) else stringResource(R.string.patch_download_install),
                    onClick = {
                        installStartedHere = true
                        onInstall(selected)
                    },
                    // 可用态取当前任务状态：退场中的旧按钮也不能提交
                    enabled = selected.isNotEmpty() && !loading && !installing,
                    modifier = Modifier.fillMaxWidth().breathing(shownInstalling && installStartedHere),
                    leadingIcon = rememberVectorPainter(Icons.Filled.CloudDownload),
                )
            }
        }
    }
}
