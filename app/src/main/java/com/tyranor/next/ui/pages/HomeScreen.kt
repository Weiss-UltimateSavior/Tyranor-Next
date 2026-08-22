package com.tyranor.next.ui.pages

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tyranor.next.R
import com.tyranor.next.scanner.EngineScanner
import com.tyranor.next.scanner.ScanGame
import com.tyranor.next.theme.NavWhite
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HomeScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // 首页数据每次进入重组时重新加载，保证游戏页改动后切回立即生效；
    // 快捷启动用主库最新数据刷新，封面等修改实时同步
    var quickLaunch by remember { mutableStateOf(EngineScanner.refreshQuickLaunch(context)) }
    var recentGames by remember { mutableStateOf(EngineScanner.loadRecentGames(context).take(10)) }
    var selectedGame by remember { mutableStateOf<ScanGame?>(null) }
    var removeRecentTarget by remember { mutableStateOf<ScanGame?>(null) }

    fun replaceGame(updated: ScanGame) {
        quickLaunch = quickLaunch.map { if (it.uri == updated.uri) updated else it }
        recentGames = recentGames.map { if (it.uri == updated.uri) updated else it }
        selectedGame = selectedGame?.let { if (it.uri == updated.uri) updated else it }
        // 同步持久化最近记录、快捷启动与主游戏库，避免修改丢失
        EngineScanner.saveRecentGames(context, recentGames)
        EngineScanner.saveQuickLaunch(context, quickLaunch)
        EngineScanner.saveGames(
            context,
            EngineScanner.loadGames(context).map { if (it.uri == updated.uri) updated else it },
        )
    }

    fun deleteGame(target: ScanGame) {
        quickLaunch = quickLaunch.filterNot { it.uri == target.uri }
        recentGames = recentGames.filterNot { it.uri == target.uri }
        selectedGame = null
        // 从持久游戏库一并移除，避免首页删了但「游戏」页仍显示（复活）
        EngineScanner.removeGame(context, target.uri)
        // 最近记录/快捷启动同步持久化移除，避免切页取消 IO 清理协程后残留脏数据
        EngineScanner.removeRecentGame(context, target.uri)
        EngineScanner.removeQuickLaunch(context, target.uri)
        // 仅清理应用内数据（每游戏设置、最近记录、封面缓存、应用内存档镜像）；不触碰游戏文件
        scope.launch(Dispatchers.IO) {
            cleanupDeletedGame(context, target)
        }
    }

    /** 仅删除该条最近游玩记录，不影响游戏库。 */
    fun removeRecentRecord(target: ScanGame) {
        recentGames = recentGames.filterNot { it.uri == target.uri }
        EngineScanner.removeRecentGame(context, target.uri)
    }

    Column(modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background)) {
            Column(modifier = Modifier.fillMaxWidth().statusBarsPadding()) {
                Column(
                    modifier = Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text("首页", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
                }
            }
        }

        // ===== 顶部栏底下固定三个快捷启动游戏（一行三个） =====
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            repeat(3) { i ->
                val game = quickLaunch.getOrNull(i)
                QuickLaunchSlot(
                    game = game,
                    onClick = { if (game != null) selectedGame = game },
                    modifier = Modifier.weight(1f),
                )
            }
        }

        // ===== 快捷启动下方：最近打开列表（最多 10 条，圆角长矩形） =====
        if (recentGames.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "暂无最近打开的游戏",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
                contentPadding = PaddingValues(top = 8.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(recentGames, key = { it.uri }) { game ->
                    RecentGameRow(
                        game = game,
                        onClick = { selectedGame = game },
                        onLongClick = { removeRecentTarget = game },
                    )
                }
            }
        }
    }

    // ===== 点击最近打开项的底部抽屉栏（与游戏页一致，不直接启动游戏） =====
    selectedGame?.let { game ->
        GameActionsSheet(
            game = game,
            onDismiss = { selectedGame = null },
            onGameUpdated = { replaceGame(it) },
            onDeleteGame = { deleteGame(game) },
            onEngineSettings = {
                startActivityWithFade(context, PerGameSettingsActivity.createIntent(context, game))
                selectedGame = null
            },
        )
    }

    // ===== 长按最近游玩项：确认删除该条游玩记录 =====
    removeRecentTarget?.let { game ->
        AppAlertDialog(
            onDismissRequest = { removeRecentTarget = null },
            title = {
                Text("删除游玩记录", style = MaterialTheme.typography.titleMedium)
            },
            text = {
                Text(
                    "将「${game.title}」从最近游玩中移除？",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    removeRecentRecord(game)
                    removeRecentTarget = null
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { removeRecentTarget = null }) { Text("取消") }
            },
        )
    }
}

/** 首页快捷启动槽位：已设置复用游戏页卡片样式（封面跟随游戏页），空槽显示白色封面 + 加号。 */
@Composable
private fun QuickLaunchSlot(
    game: ScanGame?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (game != null) {
        GameCard(game = game, onClick = onClick, modifier = modifier)
    } else {
        Column(modifier) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(3f / 4f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(NavWhite),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Add,
                    contentDescription = "空槽位",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp),
                )
            }
            Text(
                "快捷启动",
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            )
        }
    }
}

/** 最近打开列表项：圆角长矩形，左侧统一图标 + 游戏名，右侧打开时间；长按删除该条记录。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RecentGameRow(game: ScanGame, onClick: () -> Unit, onLongClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(NavWhite)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 23.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            painter = painterResource(R.drawable.ic_recent),
            contentDescription = null,
            colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.primary),
            modifier = Modifier.size(24.dp),
        )
        Text(
            game.title,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(start = 14.dp),
        )
        Text(
            formatOpenTime(game.openTime),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 12.dp),
        )
    }
}

private fun formatOpenTime(ts: Long): String {
    if (ts <= 0) return ""
    return runCatching {
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(ts))
    }.getOrDefault("")
}
