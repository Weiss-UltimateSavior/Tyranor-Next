package com.tyranor.next.ui.game

import android.app.UiAutomation
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.Display
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tyranor.next.MainActivity
import com.tyranor.next.R
import com.tyranor.next.core.engine.EngineType
import com.tyranor.next.core.game.model.ScanGame
import com.tyranor.next.core.game.storage.GameLibraryFacade
import com.tyranor.next.core.game.storage.GameLibraryRepository
import com.tyranor.next.core.i18n.AppLocaleController
import com.tyranor.next.core.settings.AppSettingsStore
import com.tyranor.next.core.settings.AppStorageNames
import com.tyranor.next.core.settings.AppearanceStyle
import com.tyranor.next.core.updater.UpdateNotificationManager
import com.tyranor.next.theme.AppThemeColors
import com.tyranor.next.ui.common.MotionDeviceAcceptance
import com.tyranor.next.ui.common.motion.MotionMode
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 验收真实主标签与屏幕合成；使用原创游戏库封面，不启动游戏、不增加生产测试入口。
 * 运行参数：motion_device_acceptance=true、motion_target_package=com.tyranor.next.pr195fix。
 * pr195_appearance 分轮选择 default-light（默认）、default-dark、retro-glass、milk-glass。
 * pr195_game_count 默认为 100，显式传入 3000 可加压；耗时为实际观察到的端到端上界。
 */
@RunWith(AndroidJUnit4::class)
class GameScreenMainActivityAcceptanceDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val automation by lazy { instrumentation.uiAutomation }
    // 本用例的整体截止时间：所有 await 都收敛到它，超时在同一测试线程抛出。
    private var testDeadline = Long.MAX_VALUE

    // 不用 JUnit 的 timeout：它会把用例搬到守护线程并在超时后放弃等待，
    // finally 清理与下一条设备测试并发跑同一进程状态（设置单例、旋转、夹具目录）。
    // 改用测试内截止时间，超时仍然从同一线程抛出并走 finally。
    @Test
    fun hiddenGameTabRotatesAndReturnsToInteractiveCards() {
        testDeadline = SystemClock.uptimeMillis() + TEST_BUDGET_MILLIS
        MotionDeviceAcceptance.require(enhanced = null)
        assertEquals("Dedicated PR195 installation required", "com.tyranor.next.pr195fix", context.packageName)
        assertTrue("Screenshot prevention must already be off", !AppSettingsStore.isPrivacyPreventScreenshot(context))
        assertTrue("App lock must already be off", !AppSettingsStore.isPrivacyAppLockEnabled(context))
        assertTrue("No custom background is read", AppSettingsStore.getBackgroundImageUri(context).isEmpty())
        assertTrue("No external scan roots are used", GameLibraryFacade.loadRoots(context).isEmpty())
        val arguments = InstrumentationRegistry.getArguments()
        val count = (arguments.getString("pr195_game_count") ?: "100").toInt()
        check(count == 100 || count == 3000) { "pr195_game_count must be 100 or explicit 3000" }
        val appearance = arguments.getString("pr195_appearance") ?: "default-light"
        val style = when (appearance) {
            "default-light", "default-dark" -> AppearanceStyle.DEFAULT
            "retro-glass" -> AppearanceStyle.RETRO_GLASS
            "milk-glass" -> AppearanceStyle.MILK_GLASS
            else -> error("Unknown pr195_appearance: $appearance")
        }
        val existing = runBlocking { GameLibraryRepository.loadGames(context) }
        assertTrue("Existing records must be private PR195 fixtures", existing.all { game ->
            val uri = Uri.parse(game.uri)
            game.title.startsWith("PR195") && uri.scheme == "file" &&
                uri.path?.startsWith(context.filesDir.absolutePath + "/") == true
        })
        val preferences = context.getSharedPreferences(AppStorageNames.APP_SETTINGS_PREFS, Context.MODE_PRIVATE)
        val savedPreferences = preferences.all.toMap()
        val updatePreferences = context.getSharedPreferences("background_update", Context.MODE_PRIVATE)
        val savedUpdatePreferences = updatePreferences.all.toMap()
        val originalRotation = rotation()
        val originalLocale = Locale.getDefault()
        val automaticRotation = Settings.System.getInt(context.contentResolver, Settings.System.ACCELEROMETER_ROTATION, 0) != 0
        val runId = UUID.randomUUID().toString()
        val prefix = "PR195 synthetic $runId"
        val root = File(context.filesDir, "pr195-game-screen-fixtures/$runId")
        val evidence = File(context.filesDir, "pr195-game-screen-evidence/$appearance-$runId")
        val games = mutableListOf<ScanGame>()
        var scenario: ActivityScenario<MainActivity>? = null
        var failure: Throwable? = null
        try {
            check(root.mkdirs())
            check(evidence.mkdirs())
            val covers = List(6) { index -> File(root, "cover-$index.png").also { drawCover(it, index) } }
            repeat(count) { index ->
                games += ScanGame(
                    title = "$prefix ${index.toString().padStart(4, '0')}",
                    uri = Uri.fromFile(File(root, "game-$index")).toString(),
                    engine = EngineType.TYRANO,
                    launchTarget = "",
                    coverUri = Uri.fromFile(covers[index % covers.size]).toString(),
                )
            }
            val ownedUris = games.mapTo(HashSet(), ScanGame::uri)
            runBlocking {
                GameLibraryFacade.registerGames(context) { it + games }
                GameLibraryRepository.awaitPendingWrites()
                assertEquals(count, GameLibraryRepository.loadGames(context).count { it.uri in ownedUris })
            }
            AppSettingsStore.setHomePageEnabled(context, false)
            AppSettingsStore.setHomeStyle(context, AppSettingsStore.HOME_STYLE_NATIVE)
            AppSettingsStore.setLanguage(context, AppSettingsStore.LANGUAGE_EN)
            AppSettingsStore.setNavStyle(context, AppSettingsStore.NAV_STYLE_CLASSIC_MD)
            AppSettingsStore.setSideRailEnabled(context, true)
            AppSettingsStore.setGameCardStyle(context, AppSettingsStore.GAME_CARD_STYLE_WATERFALL)
            AppSettingsStore.setGameWaterfallPortraitColumns(context, 4)
            AppSettingsStore.setGameWaterfallLandscapeColumns(context, 7)
            AppSettingsStore.resetGameConditions(context)
            AppSettingsStore.setDefaultThemeParticlesEnabled(context, false)
            AppSettingsStore.setMotionEnhanced(context, true)
            AppSettingsStore.setAppearanceStyle(context, style)
            AppSettingsStore.setThemeMode(context,
                if (appearance == "default-dark") AppSettingsStore.THEME_MODE_DARK else AppSettingsStore.THEME_MODE_LIGHT)
            UpdateNotificationManager.markPermissionRequested(context)
            instrumentation.runOnMainSync {
                MotionMode.sync(true)
                AppThemeColors.refresh(context)
            }
            // 关闭背景粒子使空闲有明确终点；组合帧由测试规则自动推进。
            val launched = ActivityScenario.launch<MainActivity>(
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            scenario = launched
            orient(launched, Configuration.ORIENTATION_PORTRAIT)
            compose.onNode(hasSetTextAction()).performTextReplacement(prefix)
            compose.waitUntil(15_000L) {
                compose.onAllNodes(hasScrollToIndexAction()).fetchSemanticsNodes().size == 1
            }
            compose.onNode(hasScrollToIndexAction()).performScrollToIndex(count - 1)
            compose.onNode(hasScrollToIndexAction()).performTouchInput { swipeUp() }
            compose.waitForIdle()
            assertBottom()
            val target = games.last().title
            awaitBounds(target, clickable = true)
            report(evidence, "appearance=$appearance count=$count existingCount=${existing.size} libraryCount=${existing.size + count} " +
                "model=${Build.MODEL} manufacturer=${Build.MANUFACTURER} api=${Build.VERSION.SDK_INT} " +
                "smallestWidthDp=${context.resources.configuration.smallestScreenWidthDp} " +
                "host=${MainActivity::class.java.name} monotonicObservation=true composeAutoAdvance=true particles=false syntheticAssets=true")
            for ((direction, name) in listOf(
                Configuration.ORIENTATION_LANDSCAPE to "landscape", Configuration.ORIENTATION_PORTRAIT to "portrait")) {
                tapNavigation(launched, text(R.string.nav_settings), evidence)
                compose.waitForIdle()
                await("Game card must leave accessibility while hidden") { bounds(target, true) == null }
                orient(launched, direction)
                assertTrue("Hidden rotation must not expose game cards", bounds(target, true) == null)
                val started = tapNavigation(launched, text(R.string.nav_games), evidence)
                compose.waitForIdle()
                awaitBounds(target, clickable = true)
                compose.waitForIdle()
                val card = awaitBounds(target, clickable = true)
                awaitCoverDrawn(card)
                val captureStarted = SystemClock.uptimeMillis()
                val screen = requireNotNull(automation.takeScreenshot()) { "Missing actual screen screenshot" }
                val drawnAt = SystemClock.uptimeMillis()
                try {
                    saveScreen(screen, File(evidence, "$name-game.png"))
                    assertCoverDrawn(screen, card)
                } finally {
                    screen.recycle()
                }
                assertBottom()
                // 仅点一次真实卡片，以生产抽屉出现证明交互已经放行，不点启动按钮。
                tap(card)
                compose.waitForIdle()
                awaitBounds(target)
                awaitBounds(text(R.string.game_launch_action), clickable = true)
                val interactiveAt = SystemClock.uptimeMillis()
                report(evidence, "direction=$name returnToDrawObservedMs=${drawnAt - started} " +
                    "screenCaptureMs=${drawnAt - captureStarted} returnToSheetObservedMs=${interactiveAt - started} " +
                    "anchorIndex=${count - 1} singleCardTap=true engineLaunch=false")
                compose.waitForIdle()
                val sheet = requireNotNull(automation.takeScreenshot())
                try { saveScreen(sheet, File(evidence, "$name-sheet.png")) } finally { sheet.recycle() }
                // 门槛与整轮预算挂钩：预算被前面步骤吃掉后，这里必须跟着收紧，
                // 否则「超时」只会在最后的 finally 里才体现，等于用固定阈值掩盖总预算耗尽。
                val budgetLeft = (testDeadline - started).coerceAtLeast(0L)
                assertTrue(
                    "Return-to-drawn-card upper bound must be under 5000 ms",
                    drawnAt - started < minOf(5_000L, budgetLeft),
                )
                assertTrue(
                    "Return-to-interactive-sheet upper bound must be under 8000 ms",
                    interactiveAt - started < minOf(8_000L, budgetLeft),
                )
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                compose.waitForIdle()
                await("Sheet must close without launching a game") { bounds(text(R.string.game_launch_action), true) == null }
            }
        } catch (throwable: Throwable) {
            failure = throwable
            if (evidence.isDirectory) {
                try {
                    dumpFailureTree(evidence)
                    report(evidence, "FAIL ${throwable.javaClass.simpleName}: ${throwable.message}")
                } catch (diagnosticFailure: Throwable) {
                    throwable.addSuppressed(diagnosticFailure)
                }
            }
            throw throwable
        } finally {
            val ownedUris = games.mapTo(HashSet(), ScanGame::uri)
            retainErrors(failure,
                { scenario?.close() },
                {
                    // 清理阶段同样不能无限等待，但超时不能当作清理成功：
                    // 库写入卡住时本轮夹具会留在设备上，必须记为失败，而只放弃这一段，
                    // 后面的还原步骤由 retainErrors 继续执行。
                    runBlocking {
                        val removed = withTimeoutOrNull(15_000L) {
                            GameLibraryFacade.updateGames(context) { current -> current.filterNot { it.uri in ownedUris } }
                            GameLibraryRepository.awaitPendingWrites()
                            true
                        }
                        if (removed == null) {
                            throw IllegalStateException(
                                "Cleanup timed out after 15000 ms; ${ownedUris.size} fixture(s) may remain on the device",
                            )
                        }
                        val restored = GameLibraryRepository.loadGames(context)
                        if (evidence.isDirectory) report(evidence, "cleanupAfterCount=${restored.size} existingCount=${existing.size} " +
                            "ownedRemainingCount=${restored.count { it.uri in ownedUris }}")
                        assertEquals(existing.toSet(), restored.toSet())
                    }
                },
                { check(root.deleteRecursively() || !root.exists()) },
                { restorePreferences(preferences, savedPreferences, SETTINGS_KEYS) },
                { reloadSettings() },
                { Locale.setDefault(originalLocale) },
                { restorePreferences(updatePreferences, savedUpdatePreferences, setOf("notification_permission_requested")) },
                { restoreRotation(originalRotation, automaticRotation) },
            )
        }
    }

    private fun text(id: Int) = AppLocaleController.wrap(context).getString(id)

    private fun rotation() = requireNotNull(context.getSystemService(DisplayManager::class.java)
        .getDisplay(Display.DEFAULT_DISPLAY)).rotation

    // 恢复旋转与解冻是两个独立动作：锁回原方向失败时也必须继续解冻，
    // 否则设备会带着测试写入的固定旋转退回桌面。
    private fun restoreRotation(original: Int, automatic: Boolean) {
        val restored = runCatching { automation.setRotation(original) }
        val unfrozen = if (automatic) runCatching { automation.setRotation(UiAutomation.ROTATION_UNFREEZE) }
        else Result.success(true)
        assertEquals("Restoring rotation failed", true, restored.getOrNull())
        assertEquals("Unfreezing rotation failed", true, unfrozen.getOrNull())
    }

    private fun orient(scenario: ActivityScenario<MainActivity>, desired: Int) {
        var current = Configuration.ORIENTATION_UNDEFINED
        scenario.onActivity { current = it.resources.configuration.orientation }
        assertTrue("Display rotation failed",
            automation.setRotation(if (current == desired) rotation() else (rotation() + 1) % 4))
        await("Real Activity configuration, viewport and accessibility root orientation") {
            var ready = false
            var viewport = Rect()
            scenario.onActivity {
                val view = it.window.decorView
                val location = IntArray(2).also(view::getLocationOnScreen)
                viewport = Rect(location[0], location[1], location[0] + view.width, location[1] + view.height)
                ready = it.resources.configuration.orientation == desired && view.width > 0 && view.height > 0 &&
                    (if (desired == Configuration.ORIENTATION_LANDSCAPE) view.width > view.height else view.height > view.width)
            }
            ready && activeRootBounds() == viewport
        }
        compose.waitForIdle()
    }

    @Suppress("DEPRECATION")
    private fun activeRootBounds(): Rect? {
        val root = automation.rootInActiveWindow ?: return null
        return try {
            if (root.packageName?.toString() == context.packageName) Rect().also(root::getBoundsInScreen) else null
        } finally { root.recycle() }
    }

    private fun tapNavigation(scenario: ActivityScenario<MainActivity>, label: String, evidence: File): Long {
        var previous: Pair<Rect, Rect>? = null
        var result: Rect? = null
        var orientation = Configuration.ORIENTATION_UNDEFINED
        await("Stable current-window navigation: $label") {
            val frame = CountDownLatch(1)
            scenario.onActivity {
                val view = it.window.decorView
                view.postOnAnimation { view.post { frame.countDown() } }
            }
            check(frame.await(2, TimeUnit.SECONDS)) { "Host display frame timed out" }
            var viewport = Rect()
            scenario.onActivity {
                val view = it.window.decorView
                val location = IntArray(2).also(view::getLocationOnScreen)
                viewport = Rect(location[0], location[1], location[0] + view.width, location[1] + view.height)
                orientation = it.resources.configuration.orientation
            }
            // 仅导航取实时 Compose 布局坐标，避开旋转后原生无障碍子节点的旧矩形缓存。
            val nodes = compose.onAllNodes((hasText(label) or androidx.compose.ui.test.hasContentDescription(label)) and hasClickAction()).fetchSemanticsNodes()
            val node = nodes.singleOrNull()
            val position = node?.positionOnScreen
            val rectangle = if (node != null && position != null) Rect(position.x.toInt(), position.y.toInt(),
                (position.x + node.boundsInRoot.width).toInt(), (position.y + node.boundsInRoot.height).toInt()) else null
            val sample = rectangle?.let { viewport to it }
            val ready = rectangle != null && !rectangle.isEmpty && viewport.contains(rectangle) && activeRootBounds() == viewport
            val stable = ready && sample == previous
            previous = if (ready) sample else null
            if (stable) result = rectangle
            stable
        }
        val rectangle = requireNotNull(result)
        report(evidence, "navigation=$label orientation=$orientation window=${previous?.first} " +
            "composeBounds=$rectangle nativeBounds=${bounds(label, true)} " +
            "tapX=${rectangle.exactCenterX()} tapY=${rectangle.exactCenterY()} stableFrames=2")
        val started = SystemClock.uptimeMillis()
        compose.onNode((hasText(label) or androidx.compose.ui.test.hasContentDescription(label)) and hasClickAction()).performClick()
        return started
    }

    private fun assertBottom() {
        val axis = compose.onNode(hasScrollToIndexAction()).fetchSemanticsNode()
            .config[SemanticsProperties.VerticalScrollAxisRange]
        compose.runOnIdle { assertTrue("Tail anchor must remain at the actual scroll end", axis.value() >= axis.maxValue() - 0.01f) }
    }

    private fun await(message: String, condition: () -> Boolean) {
        val limit = minOf(SystemClock.uptimeMillis() + 15_000L, testDeadline)
        while (true) {
            // 被中断时立刻退出，避免测试线程在超时后仍继续驱动界面。
            if (Thread.currentThread().isInterrupted) throw InterruptedException("Interrupted while awaiting: $message")
            if (condition()) {
                // 条件成立也要复查整轮预算：前一步可能刚好把它吃完，
                // 直接 return 会让超时只在最后的 finally 里体现。
                if (SystemClock.uptimeMillis() < testDeadline) return
                error("Timed out: $message")
            }
            if (SystemClock.uptimeMillis() >= limit) error("Timed out: $message")
            SystemClock.sleep(25L)
        }
    }

    private fun awaitBounds(label: String, clickable: Boolean = false): Rect {
        var result: Rect? = null
        await("Visible node: $label clickable=$clickable") { bounds(label, clickable).also { result = it } != null }
        return requireNotNull(result)
    }

    @Suppress("DEPRECATION")
    private fun bounds(label: String, clickable: Boolean): Rect? {
        fun matches(value: CharSequence?) = value?.lineSequence()?.any { it.trim() == label } == true
        fun visit(node: AccessibilityNodeInfo, clickableParent: Rect?): Rect? {
            try {
                val owner = node.packageName?.toString()
                if (owner != null && owner != context.packageName) return null
                val rectangle = Rect().also(node::getBoundsInScreen).takeUnless { it.isEmpty }
                val actionBounds = if (node.isVisibleToUser && node.isEnabled && node.isClickable) {
                    rectangle
                } else clickableParent
                // 标题必须完整相等；只向最近的真实可点祖先提升，绝不以包含或前缀匹配替代身份。
                if (node.isVisibleToUser && node.isEnabled && (matches(node.text) || matches(node.contentDescription))) {
                    val result = if (clickable) actionBounds else rectangle
                    if (result != null) return result
                }
                for (index in 0 until node.childCount) {
                    val child = node.getChild(index) ?: continue
                    visit(child, actionBounds)?.let { return it }
                }
                return null
            } finally { node.recycle() }
        }
        val root = automation.rootInActiveWindow ?: return null
        if (root.packageName?.toString() != context.packageName) {
            root.recycle()
            return null
        }
        return visit(root, null)
    }

    @Suppress("DEPRECATION")
    private fun dumpFailureTree(directory: File) {
        val output = StringBuilder()
        var remaining = 1_000
        fun visit(node: AccessibilityNodeInfo, depth: Int) {
            try {
                val owner = node.packageName?.toString()
                if (owner != null && owner != context.packageName) return
                if (remaining-- <= 0) return
                val rectangle = Rect().also(node::getBoundsInScreen)
                output.append("  ".repeat(depth)).append(node.className).append(" bounds=").append(rectangle)
                    .append(" visible=").append(node.isVisibleToUser).append(" enabled=").append(node.isEnabled)
                    .append(" clickable=").append(node.isClickable)
                    .append(" text=").append(node.text?.toString()?.replace("\n", "\\n")?.take(1_000))
                    .append(" description=").append(node.contentDescription?.toString()?.replace("\n", "\\n")?.take(1_000))
                    .append('\n')
                for (index in 0 until node.childCount) {
                    if (remaining <= 0) break
                    node.getChild(index)?.let { visit(it, depth + 1) }
                }
            } finally { node.recycle() }
        }
        val root = automation.rootInActiveWindow
        if (root != null && root.packageName?.toString() == context.packageName) visit(root, 0) else {
            root?.recycle()
            output.append("No active target-package root\n")
        }
        File(directory, "failure-tree.txt").writeText(output.toString())
    }

    private fun tap(rect: Rect): Long {
        val started = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            if (action == MotionEvent.ACTION_UP) SystemClock.sleep(64L)
            val event = MotionEvent.obtain(started, SystemClock.uptimeMillis(), action,
                rect.exactCenterX(), rect.exactCenterY(), 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            try { assertTrue("Physical touch injection failed", automation.injectInputEvent(event, true)) } finally { event.recycle() }
        }
        return started
    }

    // 无障碍节点就位只说明卡片已布局，不代表封面已解码绘制；先轮询实际屏幕像素，
    // 命中原始封面再截图，避免把「卡片已出现但封面还空」的帧当成返回完成。
    private fun awaitCoverDrawn(card: Rect) {
        var sky = 0
        var moon = 0
        await("Cover art drawn inside the visible card") {
            val shot = automation.takeScreenshot() ?: return@await false
            try {
                val counts = coverArtCounts(shot, card)
                sky = counts.first
                moon = counts.second
            } finally { shot.recycle() }
            coverArtVisible(sky, moon)
        }
        assertTrue("Actual card must show original sky and moon art: sky=$sky moon=$moon", coverArtVisible(sky, moon))
    }

    private fun coverArtVisible(sky: Int, moon: Int) = sky > 20 && moon > 5

    private fun coverArtCounts(screen: Bitmap, card: Rect): Pair<Int, Int> {
        // bounds 来自带点击动作的合并卡片节点；排除只命中标题文字矩形。
        assertTrue("Expected a full portrait-cover card, not its title", card.height() > card.width())
        // 月亮居中且位于封面上部，生产卡片 0.60–0.80 的裁切比例均会保留它。
        val visible = Rect(card)
        assertTrue(visible.intersect(0, 0, screen.width, screen.height))
        var sky = 0
        var moon = 0
        for (y in visible.top until visible.top + visible.height() * 3 / 4 step 3) {
            for (x in visible.left until visible.right step 3) {
                val pixel = screen.getPixel(x, y)
                if (Color.blue(pixel) > 70 && Color.red(pixel) < 65 && Color.green(pixel) < 110) sky++
                if (Color.red(pixel) > 215 && Color.green(pixel) > 165 && Color.blue(pixel) < 125) moon++
            }
        }
        return sky to moon
    }

    private fun assertCoverDrawn(screen: Bitmap, card: Rect) {
        val (sky, moon) = coverArtCounts(screen, card)
        assertTrue("Actual card must show original sky and moon art: sky=$sky moon=$moon", coverArtVisible(sky, moon))
    }

    private fun drawCover(file: File, variant: Int) {
        val bitmap = Bitmap.createBitmap(240, 360, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            canvas.drawColor(Color.rgb(18 + variant * 3, 48, 94 + variant * 4))
            paint.color = Color.rgb(248, 207, 89)
            canvas.drawCircle(120f, 100f, 28f, paint)
            paint.color = Color.rgb(190, 220, 248)
            repeat(15) { index -> canvas.drawCircle((20 + index * 37 % 200).toFloat(), (20 + index * 17 % 150).toFloat(), 1.5f, paint) }
            paint.color = Color.rgb(35, 108 + variant * 5, 121)
            canvas.drawPath(Path().apply {
                moveTo(0f, 290f); lineTo(70f, 170f); lineTo(140f, 280f); lineTo(200f, 190f)
                lineTo(240f, 260f); lineTo(240f, 360f); lineTo(0f, 360f); close()
            }, paint)
            paint.color = Color.WHITE
            paint.textSize = 22f
            canvas.drawText("ORIGINAL ${variant + 1}", 24f, 326f, paint)
            saveScreen(bitmap, file)
        } finally { bitmap.recycle() }
    }

    private fun saveScreen(bitmap: Bitmap, file: File) {
        file.outputStream().use { assertTrue("PNG write failed", bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }

    private fun report(directory: File, line: String) {
        File(directory, "metrics.txt").appendText(line + "\n")
        instrumentation.sendStatus(2, Bundle().apply {
            putString("stream", "\nPR195GameScreen $line evidence=${directory.absolutePath}\n")
        })
    }

    private fun restorePreferences(preferences: SharedPreferences, saved: Map<String, *>, keys: Set<String>) {
        val editor = preferences.edit()
        for (key in keys) when (val value = saved[key]) {
            null -> editor.remove(key)
            is String -> editor.putString(key, value)
            is Boolean -> editor.putBoolean(key, value)
            is Int -> editor.putInt(key, value)
            else -> error("Unexpected saved preference type: $key")
        }
        check(editor.commit()) { "Preference snapshot restore failed" }
    }

    private fun reloadSettings() = instrumentation.runOnMainSync {
        AppSettingsStore.initHomePageEnabled(context)
        AppSettingsStore.initHomeStyle(context)
        AppSettingsStore.initLanguage(context)
        AppSettingsStore.initNavStyle(context)
        AppSettingsStore.initSideRail(context)
        AppSettingsStore.initGameCardStyle(context)
        AppSettingsStore.initGameConditions(context)
        AppSettingsStore.initDefaultThemeParticles(context)
        AppSettingsStore.initMotionEnhanced(context)
        AppThemeColors.refresh(context)
        MotionMode.sync(AppSettingsStore.isMotionEnhanced(context))
    }

    private fun retainErrors(primary: Throwable?, vararg actions: () -> Unit) {
        var failure = primary
        for (action in actions) try { action() } catch (throwable: Throwable) {
            if (failure == null) failure = throwable else failure.addSuppressed(throwable)
        }
        if (primary == null) failure?.let { throw it }
    }

    companion object {
        // 100 条夹具实测约 25 秒；3000 条加压轮留足余量，仍远小于同类用例的历史超时。
        private const val TEST_BUDGET_MILLIS = 180_000L
        private val SETTINGS_KEYS = setOf(
            AppSettingsStore.KEY_HOME_PAGE_ENABLED, AppSettingsStore.KEY_HOME_STYLE, AppSettingsStore.KEY_LANGUAGE,
            AppSettingsStore.KEY_NAV_STYLE, AppSettingsStore.KEY_SIDE_RAIL, AppSettingsStore.KEY_GAME_CARD_STYLE,
            AppSettingsStore.KEY_GAME_WATERFALL_PORTRAIT_COLUMNS, AppSettingsStore.KEY_GAME_WATERFALL_LANDSCAPE_COLUMNS,
            // 网格两键也要还原：遗留值会让下一次 initGameCardStyle 认为四键已存在，迁移分支不再被覆盖。
            AppSettingsStore.KEY_GAME_GRID_PORTRAIT_COLUMNS, AppSettingsStore.KEY_GAME_GRID_LANDSCAPE_COLUMNS,
            // 旧版窄/宽屏列数键一并还原，避免迁移把值再次写回四个新键。
            AppSettingsStore.KEY_GAME_CARD_COLUMNS_NARROW_LEGACY, AppSettingsStore.KEY_GAME_CARD_COLUMNS_WIDE_LEGACY,
            AppSettingsStore.KEY_GAME_SORT, AppSettingsStore.KEY_GAME_FILTER_ENGINE, AppSettingsStore.KEY_GAME_FILTER_PLAYED,
            AppSettingsStore.KEY_MOTION_ENHANCED, AppSettingsStore.KEY_APPEARANCE_STYLE, AppSettingsStore.KEY_THEME_MODE,
            "default_theme_particles_enabled", "migration_xsys4_rescan_done",
        )
    }
}
