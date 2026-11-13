package com.tyranor.next.ui.patch

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tyranor.next.R
import com.tyranor.next.core.patch.KrkrOnlinePatchService
import com.tyranor.next.core.patch.KrkrPatchEntry
import com.tyranor.next.core.game.model.ScanGame
import com.tyranor.next.core.game.model.ScanGameIntents
import com.tyranor.next.theme.NavWhite
import com.tyranor.next.theme.PageSecondaryTextColor
import com.tyranor.next.theme.TextColor
import com.tyranor.next.theme.glassBorder
import com.tyranor.next.theme.AppComponentShape
import com.tyranor.next.ui.common.AppScreenActivity
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
                KrkrOnlinePatchScreen(game = game)
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
 * 为指定游戏加载并筛选补丁索引，向服务提交勾选的补丁文件并展示安装结果。
 * 搜索、加载、消息与补丁条目使用独立列表身份，使内容转场不会复用其他状态的节点。
 */
@Composable
private fun KrkrOnlinePatchScreen(game: ScanGame) {
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

    fun loadIndex() {
        if (loading) return
        scope.launch {
            loading = true
            message = null
            val result = withContext(Dispatchers.IO) {
                try {
                    Result.success(KrkrOnlinePatchService.fetchPatchIndex(context))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (t: Throwable) {
                    Result.failure(t)
                }
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
    // 与原「无匹配」条件完全相同，抽出后同时用于空态条目与搜索框的抖动反馈
    val noMatch = !loading && filtered.isEmpty() && message == null

    Column(Modifier.fillMaxSize().skipEntranceOnDrag()) {
        AppSecondaryTopBar(
            title = stringResource(R.string.patch_title),
            trailing = {
                // 拉取索引期间刷新图标缓缓明灭（包一层不改尺寸的 Box，静止时与原样一致）
                Box(Modifier.breathing(loading)) {
                    TopBarIcon(painterResource(R.drawable.ic_refresh), stringResource(R.string.patch_refresh_content_description), MaterialTheme.colorScheme.primary, spin = true) {
                        loadIndex()
                    }
                }
            },
        )

        // 补丁卡片列表：滚得越快卡片越被拉长一点，停下 Q 弹回原样
        val jelly = rememberScrollJellyState()
        LazyColumn(
            modifier = Modifier.fillMaxSize().scrollJellyHost(jelly).padding(horizontal = 16.dp),
            contentPadding = PaddingValues(top = 16.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "search") {
                // 关键字过滤到「无匹配」的那一刻左右抖一下
                // （只在用户自己改过关键字之后才抖：打开页面时默认关键字无匹配不算「被拒绝」）
                var keywordEdited by remember { mutableStateOf(false) }
                AppSearchField(
                    query = keyword,
                    onQueryChange = {
                        keyword = it
                        keywordEdited = true
                    },
                    modifier = Modifier
                        .staggeredEntrance(0)
                        .shakeOnTrigger(noMatch && keywordEdited, amplitude = 5.dp),
                )
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
                        color = PageSecondaryTextColor,
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
                    installing = installing,
                    onInstall = { selected ->
                        scope.launch {
                            installing = true
                            message = null
                            val result = try {
                                Result.success(
                                    KrkrOnlinePatchService.downloadAndInstall(context, game, selected) {
                                        message = it
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
                            installing = false
                        }
                    },
                )
            }

            item(key = "bottom_spacer") {
                Spacer(Modifier.navigationBarsPadding().height(4.dp))
            }
        }
    }
}

/** 按补丁条目保存文件勾选状态；安装期间禁用提交，由调用方执行下载与写入。 */
@Composable
private fun PatchEntryCard(
    entry: KrkrPatchEntry,
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

    Card(
        modifier = modifier.pressSquishGroup().fillMaxWidth().glassBorder(),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        colors = CardDefaults.cardColors(containerColor = NavWhite, contentColor = TextColor),
        shape = AppComponentShape,
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
                        modifier = Modifier.pressSquish(scale = PressSquishDefaults.RowScale).fillMaxWidth().clickable {
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
                    // 可用态仍取实时的 installing：退场中的旧按钮也不能重复提交
                    enabled = selected.isNotEmpty() && !installing,
                    modifier = Modifier.fillMaxWidth().breathing(shownInstalling && installStartedHere),
                    leadingIcon = rememberVectorPainter(Icons.Filled.CloudDownload),
                )
            }
        }
    }
}
