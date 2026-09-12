package com.tyranor.next.ui.home

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.tyranor.next.R
import com.tyranor.next.core.engine.EngineType
import com.tyranor.next.core.game.launch.EngineLauncher
import com.tyranor.next.core.game.model.ScanGame
import com.tyranor.next.core.game.save.RpgSaveFormat
import com.tyranor.next.theme.NavWhite
import com.tyranor.next.theme.QuickLaunchFallback
import com.tyranor.next.ui.common.AppAlertDialog
import com.tyranor.next.ui.common.AppTopBar
import com.tyranor.next.ui.common.TimeFormats
import com.tyranor.next.ui.common.boxBlurArgb
import com.tyranor.next.ui.common.glassNavBottomInset
import com.tyranor.next.ui.common.userMessage
import com.tyranor.next.ui.game.GameActionsSheet
import com.tyranor.next.ui.game.RpgSaveFormatDialog
import com.tyranor.next.ui.game.coverColor
import com.tyranor.next.ui.game.dialogArgs
import com.tyranor.next.ui.game.rememberCoverBitmap
import com.tyranor.next.ui.game.startActivityWithPageTransition
import com.tyranor.next.ui.main.MainLibraryUiState
import com.tyranor.next.ui.settings.PerGameSettingsActivity
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun HomeScreen(
    modifier: Modifier = Modifier,
    libraryState: MainLibraryUiState,
    onGameUpdated: (ScanGame) -> Unit,
    onGameDeleted: (ScanGame) -> Unit,
    onRecentRemoved: (ScanGame) -> Unit,
    onQuickLaunchToggle: (ScanGame) -> Boolean,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val quickLaunch = libraryState.quickLaunch
    val recentGames = libraryState.recentGames
    var selectedGame by remember { mutableStateOf<ScanGame?>(null) }
    var launchError by remember { mutableStateOf<String?>(null) }
    var patchLaunchTarget by remember { mutableStateOf<ScanGame?>(null) }
    // MV/MZ 存档格式转化确认：待转化检测结果 + 目标游戏 + 已选补丁策略（Artemis 选择后串联）
    var saveFormatTarget by remember { mutableStateOf<ScanGame?>(null) }
    var saveFormatDetection by remember { mutableStateOf<RpgSaveFormat.Detection?>(null) }
    var pendingPatchChoice by remember { mutableStateOf<EngineLauncher.ArtemisPatchChoice?>(null) }
    val saveFormatConvertedFormat = stringResource(R.string.save_format_converted_count)
    val saveFormatConvertFailedMessage = stringResource(R.string.save_format_convert_failed)

    LaunchedEffect(libraryState.games) {
        selectedGame = selectedGame?.let { selected ->
            libraryState.games.firstOrNull { it.uri == selected.uri }
        }
    }

    fun replaceGame(updated: ScanGame) {
        selectedGame = selectedGame?.let { if (it.uri == updated.uri) updated else it }
        onGameUpdated(updated)
    }

    fun deleteGame(target: ScanGame) {
        selectedGame = null
        onGameDeleted(target)
    }

    /** 仅删除该条最近游玩记录，不影响游戏库。 */
    fun removeRecentRecord(target: ScanGame) {
        onRecentRemoved(target)
    }

    /** Artemis 选择（或无需补丁）后，再检查 MV/MZ 存档格式；有标准存档则弹窗，否则直接启动。
     *  开启存档互通时跳过弹窗——启动前同步已覆盖其语义。 */
    fun launchWithSaveFormatGate(game: ScanGame, patchChoice: EngineLauncher.ArtemisPatchChoice?) {
        scope.launch {
            if (EngineLauncher.isRpgSaveInteropEnabled(context, game)) {
                launchError = EngineLauncher.launch(context, game, patchChoice).userMessage(context)
                return@launch
            }
            val pending = EngineLauncher.rpgSaveFormatPending(context, game)
            if (pending != null) {
                saveFormatTarget = game
                saveFormatDetection = pending
                pendingPatchChoice = patchChoice
            } else {
                launchError = EngineLauncher.launch(context, game, patchChoice).userMessage(context)
            }
        }
    }

    // 点按直接启动游戏；Artemis 按既有策略弹出补丁确认（与游戏页长按启动一致）。
    fun launchGame(game: ScanGame) {
        scope.launch {
            if (EngineLauncher.needsArtemisPatchConfirm(context, game)) {
                patchLaunchTarget = game
            } else {
                launchWithSaveFormatGate(game, null)
            }
        }
    }

    /** 用户确认转化后执行转化（best-effort），随后按既定策略启动。 */
    fun resolveSaveFormat(target: ScanGame, convert: Boolean) {
        val detection = saveFormatDetection
        val patchChoice = pendingPatchChoice
        saveFormatTarget = null
        saveFormatDetection = null
        pendingPatchChoice = null
        scope.launch {
            if (convert && detection != null) {
                val converted = try {
                    withContext(Dispatchers.IO) { EngineLauncher.convertRpgSaveFormat(context, target) }
                } catch (ce: CancellationException) {
                    throw ce
                } catch (_: Throwable) {
                    null
                }
                val message = if (converted != null) {
                    saveFormatConvertedFormat.format(converted.converted)
                } else {
                    saveFormatConvertFailedMessage
                }
                android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_LONG).show()
            }
            launchError = EngineLauncher.launch(context, target, patchChoice).userMessage(context)
        }
    }

    Column(modifier.fillMaxSize()) {
        AppTopBar(title = stringResource(R.string.nav_home))

        // ===== 快捷启动卡与最近打开列表合并为同一个滚动列表 =====
        // 水平内边距统一由 contentPadding 提供（覆盖快捷启动区与列表行）
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 12.dp,
                end = 12.dp,
                top = 4.dp,
                bottom = 16.dp + glassNavBottomInset(),
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (!libraryState.loaded) {
                // 加载中：快捷启动区（空态）在顶部，指示器占满剩余空间居中
                item(key = "loading", contentType = "loading") {
                    Column(Modifier.fillParentMaxSize()) {
                        QuickLaunchSection(
                            quickLaunch = quickLaunch,
                            onGameClick = { selectedGame = it },
                            onGameLongClick = { launchGame(it) },
                        )
                        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }
                }
            } else if (recentGames.isEmpty()) {
                item(key = "recent_empty", contentType = "recent_empty") {
                    Column(Modifier.fillParentMaxSize()) {
                        QuickLaunchSection(
                            quickLaunch = quickLaunch,
                            onGameClick = { selectedGame = it },
                            onGameLongClick = { launchGame(it) },
                        )
                        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                            Text(
                                stringResource(R.string.home_recent_empty),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            } else {
                item(key = "quick_launch", contentType = "quick_launch") {
                    QuickLaunchSection(
                        quickLaunch = quickLaunch,
                        onGameClick = { selectedGame = it },
                        onGameLongClick = { launchGame(it) },
                    )
                }
                items(
                    items = recentGames,
                    key = { it.uri },
                    contentType = { "recent_game" },
                ) { game ->
                    RecentGameRow(
                        game = game,
                        onClick = { selectedGame = game },
                        onLongClick = { launchGame(game) },
                        onSwipeDelete = { removeRecentRecord(game) },
                    )
                }
            }
        }
    }

    // ===== 与游戏页统一：点按打开操作抽屉，长按直接启动 =====
    selectedGame?.let { game ->
        key(game.uri) {
            GameActionsSheet(
                game = game,
                onDismiss = { selectedGame = null },
                onGameUpdated = { replaceGame(it) },
                onDeleteGame = { deleteGame(game) },
                quickLaunched = quickLaunch.any { it.uri == game.uri },
                onQuickLaunchToggle = { onQuickLaunchToggle(game) },
                onEngineSettings = {
                    startActivityWithPageTransition(context, PerGameSettingsActivity.createIntent(context, game))
                    selectedGame = null
                },
            )
        }
    }

    // ===== Artemis 首次启动补丁确认（与游戏页一致） =====
    patchLaunchTarget?.let { game ->
        AppAlertDialog(
            onDismissRequest = { patchLaunchTarget = null },
            title = {
                Text(
                    stringResource(R.string.game_auto_patch_title),
                    style = MaterialTheme.typography.titleMedium,
                )
            },
            text = {
                Text(
                    stringResource(R.string.game_auto_patch_message, game.title),
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val target = patchLaunchTarget
                        patchLaunchTarget = null
                        target?.let { launchWithSaveFormatGate(it, EngineLauncher.ArtemisPatchChoice.ALWAYS) }
                    },
                ) { Text(stringResource(R.string.game_patch_always)) }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(
                        onClick = {
                            val target = patchLaunchTarget
                            patchLaunchTarget = null
                            target?.let { launchWithSaveFormatGate(it, EngineLauncher.ArtemisPatchChoice.NEVER) }
                        },
                    ) { Text(stringResource(R.string.game_patch_never)) }
                    TextButton(
                        onClick = {
                            val target = patchLaunchTarget
                            patchLaunchTarget = null
                            target?.let { launchWithSaveFormatGate(it, EngineLauncher.ArtemisPatchChoice.ONCE) }
                        },
                    ) { Text(stringResource(R.string.game_patch_once)) }
                }
            },
        )
    }

    // ===== MV/MZ 存档格式转化确认（标准 → Tyranor）；点遮罩 = 保持原样启动 =====
    saveFormatTarget?.let { target ->
        saveFormatDetection?.let { detection ->
            val (standardCount, hashedCount) = detection.dialogArgs()
            RpgSaveFormatDialog(
                standardCount = standardCount,
                hashedCount = hashedCount,
                onChoice = { convert -> resolveSaveFormat(target, convert) },
            )
        }
    }

    launchError?.let { message ->
        AppAlertDialog(
            onDismissRequest = { launchError = null },
            title = { Text(stringResource(R.string.game_launch_failed), style = MaterialTheme.typography.titleMedium) },
            text = { Text(message, style = MaterialTheme.typography.bodyMedium) },
            confirmButton = {
                TextButton(onClick = { launchError = null }) { Text(stringResource(R.string.common_confirm)) }
            },
        )
    }
}

/**
 * 快捷启动区（最多 3 个）：小屏（可用宽度 < 600dp，竖屏手机）单张大卡左右滑动切换；
 * 大屏（横屏/平板）直接一行三个卡位，空槽显示占位。两种形态都限制最大宽度并水平居中。
 */
@Composable
private fun QuickLaunchSection(
    quickLaunch: List<ScanGame>,
    onGameClick: (ScanGame) -> Unit,
    onGameLongClick: (ScanGame) -> Unit,
) {
    BoxWithConstraints(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        if (maxWidth >= 600.dp) {
            // 三张横幅卡并排需要比单卡形态更宽的行：上限放宽到 900dp，
            // 每张卡约 293dp，保证左侧文字列在封面之外仍有可用宽度
            Row(
                modifier = Modifier.widthIn(max = 900.dp).fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                repeat(3) { i ->
                    val game = quickLaunch.getOrNull(i)
                    // 按游戏 uri 做 key：删除卡片后槽位状态跟随游戏而不是按位置复用，
                    // 避免复用被删卡的封面 MutableState（上一个封面串到另一张卡）。
                    key(game?.uri ?: "quick_launch_empty_$i") {
                        if (game != null) {
                            QuickLaunchCard(
                                game = game,
                                onClick = { onGameClick(game) },
                                onLongClick = { onGameLongClick(game) },
                                modifier = Modifier.weight(1f),
                            )
                        } else {
                            QuickLaunchEmptyCard(modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        } else if (quickLaunch.isEmpty()) {
            QuickLaunchEmptyCard(modifier = Modifier.widthIn(max = 520.dp).fillMaxWidth())
        } else {
            val pagerState = rememberPagerState { quickLaunch.size }
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.widthIn(max = 520.dp).fillMaxWidth(),
                pageSpacing = 10.dp,
                verticalAlignment = Alignment.Top,
            ) { page ->
                val game = quickLaunch.getOrNull(page) ?: return@HorizontalPager
                // 按游戏 uri 做 key：删除卡片后页面状态跟随游戏而不是按位置复用，
                // 避免复用被删卡的封面 MutableState（上一个封面串到另一张卡）。
                key(game.uri) {
                    QuickLaunchCard(
                        game = game,
                        onClick = { onGameClick(game) },
                        onLongClick = { onGameLongClick(game) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

/**
 * 首页快捷启动大卡：封面高斯模糊铺满整卡作背景（无封面/加载中退回中性深灰底），
 * 左上角引擎类型（下方游戏名）、右侧游戏封面。交互与游戏页统一——点按开操作抽屉、长按直接启动。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun QuickLaunchCard(
    game: ScanGame,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
) {
    val pressModifier = if (onLongClick != null) {
        Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)
    } else {
        Modifier.clickable(onClick = onClick)
    }
    BoxWithConstraints(
        modifier = modifier
            .height(172.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(QuickLaunchFallback),
    ) {
        val engineName = if (game.engine == EngineType.UNKNOWN) {
            stringResource(R.string.engine_name_unknown)
        } else {
            game.engine.displayName
        }
        val cardMaxWidth = maxWidth
        val coverBitmap by rememberCoverBitmap(game.coverUri)
        coverBitmap?.let { bmp ->
            // 封面缩到 40px 再拉伸铺满：API 31+ 由 Modifier.blur（RenderEffect）做真高斯；
            // API 26-30 无 RenderEffect，若只放大 40px 会呈马赛克（issue #76），
            // 因此对小图先做 CPU 盒式模糊（3 轮近似高斯）再拉伸，保证低版本同样柔和。
            val blurSupported = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S
            val blurred = remember(bmp, blurSupported) {
                val src = bmp.asAndroidBitmap()
                val width = 40
                val height = (src.height * width / src.width).coerceAtLeast(1)
                val scaled = android.graphics.Bitmap.createScaledBitmap(src, width, height, true)
                if (blurSupported) {
                    scaled.asImageBitmap()
                } else {
                    val smallWidth = scaled.width
                    val smallHeight = scaled.height
                    val pixels = IntArray(smallWidth * smallHeight)
                    scaled.getPixels(pixels, 0, smallWidth, 0, 0, smallWidth, smallHeight)
                    boxBlurArgb(pixels, smallWidth, smallHeight, radius = 3)
                    android.graphics.Bitmap
                        .createBitmap(pixels, smallWidth, smallHeight, android.graphics.Bitmap.Config.ARGB_8888)
                        .asImageBitmap()
                }
            }
            Image(
                bitmap = blurred,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize().blur(24.dp),
            )
            // 黑色压暗遮罩：保证白色文字可读
            Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.25f)))
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .then(pressModifier)
                .padding(20.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().weight(1f),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        engineName,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        game.title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                // 封面按卡宽取比例宽度（大屏三卡并排时自动缩小，给文字留空间），上限 99dp，
                // 垂直居中于文字行高度内；左侧与文字列保持 12dp 间距
                Box(
                    modifier = Modifier.fillMaxHeight().padding(start = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    val coverWidth = (cardMaxWidth * 0.32f).coerceAtMost(99.dp)
                    Box(
                        modifier = Modifier
                            .width(coverWidth)
                            .height(coverWidth * 4f / 3f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color.White.copy(alpha = 0.18f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        val bmp = coverBitmap
                        if (bmp != null) {
                            Image(
                                bitmap = bmp,
                                contentDescription = game.title,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                        } else {
                            Text(
                                "Tyranor",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.White.copy(alpha = 0.7f),
                            )
                        }
                    }
                }
                // 封面右侧的指向箭头，仅作视觉引导，不单独响应点击；左侧与封面间距 9dp
                Icon(
                    painter = painterResource(R.drawable.ic_quick_launch_arrow),
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier
                        .align(Alignment.CenterVertically)
                        .padding(start = 9.dp)
                        .size(24.dp),
                )
            }
        }
    }
}

/** 快捷启动空状态：尚未设置任何快捷启动时显示整张白色卡片 + 加号。 */
@Composable
private fun QuickLaunchEmptyCard(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .height(172.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(NavWhite),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Filled.Add,
            contentDescription = stringResource(R.string.home_empty_slot_content_description),
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(28.dp),
        )
        Text(
            stringResource(R.string.home_quick_launch),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/** 最近打开列表项：圆角长矩形，左侧统一图标 + 游戏名，右侧打开时间；交互与游戏页统一——点按开菜单、长按直启；向左滑动约 1/6 露出独立删除按钮，点击直接移除该条记录。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RecentGameRow(
    game: ScanGame,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onSwipeDelete: () -> Unit,
) {
    BoxWithConstraints {
        val revealPx = with(LocalDensity.current) { (maxWidth / 6f).toPx() }
        val scope = rememberCoroutineScope()
        var offset by remember { mutableFloatStateOf(0f) }
        val settleJob = remember { arrayOfNulls<kotlinx.coroutines.Job>(1) }
        fun settleOffset(target: Float) {
            settleJob[0]?.cancel()
            settleJob[0] = scope.launch {
                androidx.compose.animation.core.Animatable(offset).animateTo(target) {
                    offset = value
                }
            }
        }
        val formattedOpenTime = remember(game.openTime) { TimeFormats.formatDateTime(game.openTime) }
        // 统一裁切圆角：红色删除层与白色内容层圆角一致，内容左移越界部分被裁掉
        Box(Modifier.clip(RoundedCornerShape(8.dp))) {
            // 删除层：主题色背景 + 白色删除图标，仅滑出约 1/6 时露出右侧「删除」区域
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(MaterialTheme.colorScheme.primary)
                    .clickable(onClick = onSwipeDelete),
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(with(LocalDensity.current) { revealPx.toDp() })
                        .align(Alignment.CenterEnd),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Delete,
                        contentDescription = stringResource(R.string.common_delete),
                        tint = Color.White,
                    )
                }
            }
            Row(
                modifier = Modifier
                    .offset { IntOffset(offset.roundToInt(), 0) }
                    .fillMaxWidth()
                    .background(NavWhite)
                    .combinedClickable(
                        onClick = {
                            if (offset != 0f) {
                                settleOffset(0f)
                            } else {
                                onClick()
                            }
                        },
                        onLongClick = onLongClick,
                    )
                    .pointerInput(revealPx) {
                        detectHorizontalDragGestures(
                            onDragEnd = {
                                settleOffset(if (offset < -revealPx / 2f) -revealPx else 0f)
                            },
                            onDragCancel = {
                                settleOffset(if (offset < -revealPx / 2f) -revealPx else 0f)
                            },
                        ) { _, dragAmount ->
                            settleJob[0]?.cancel()
                            offset = (offset + dragAmount).coerceIn(-revealPx, 0f)
                        }
                    }
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
                    formattedOpenTime,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 12.dp),
                )
            }
        }
    }
}
