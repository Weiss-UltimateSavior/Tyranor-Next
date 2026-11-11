package com.tyranor.next.ui.game

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tyranor.next.ui.common.motion.bounceOnChange
import com.tyranor.next.ui.common.motion.breathing
import com.tyranor.next.ui.common.motion.shakeOnTrigger
import com.tyranor.next.ui.common.motion.staggeredEntrance
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.tyranor.next.ui.common.DialogExitController
import com.tyranor.next.ui.common.LocalDialogExit

/**
 * 游戏域弹窗的「先退场再执行」句柄。
 *
 * [com.tyranor.next.ui.common.AppAlertDialog] 在按钮槽（无按钮行时在正文）提供退场控制器。
 * 在对应槽位调用 [capture]，同一弹窗的其它动作即可共用退场路径：
 * 「增强」档卡片沉下、遮罩淡出后再执行动作；「普通」档控制器本身直接执行（与原版一致）。
 * 尚未捕获到（或弹窗没有提供）时直接执行动作。
 */
internal class GameDialogExitHandle {
    private var controller: DialogExitController? = null

    /** 在提供控制器的槽位组合时调用，记下所在弹窗的退场控制器。 */
    @Composable
    fun capture() {
        val current = LocalDialogExit.current
        if (current != null) controller = current
    }

    /** 先播放所在弹窗的退场再执行 [action]；没有控制器时直接执行。 */
    fun closeThen(action: () -> Unit) {
        val exit = controller
        if (exit != null) exit.closeThen(action) else action()
    }
}

/** 记住一个弹窗退场句柄（随弹窗一起存在）。 */
@Composable
internal fun rememberGameDialogExitHandle(): GameDialogExitHandle = remember { GameDialogExitHandle() }

/** Buttons follow the dialog body; confirm also pulses when it becomes actionable. */
internal fun Modifier.gameDialogAction(order: Int, confirmEnabled: Boolean? = null): Modifier {
    val entered = staggeredEntrance(order, distance = 10.dp, stepMillis = 45L)
    return if (confirmEnabled == null) entered else entered.bounceOnChange(confirmEnabled, depth = 0.12f)
}

internal enum class GamePanelPart(val order: Int, val cropDistance: Int) {
    Title(0, 14), Input(1, 10), Content(2, 18), Actions(3, 14),
}

internal fun Modifier.enterGamePanel(overlay: GameOverlay, part: GamePanelPart): Modifier =
    staggeredEntrance(part.order, distance = (if (overlay == GameOverlay.ShortcutCrop) part.cropDistance else 14).dp, stepMillis = 45L)

/** Keep the old toggle label throughout exit, and route repeated refusals to the affected row. */
internal class QuickActionFeedback {
    private var exiting by mutableStateOf(false)
    private var lastQuickLaunch = false
    private var toggleRejects by mutableIntStateOf(0)
    private var shortcutRejects by mutableIntStateOf(0)
    private var coverRejects by mutableIntStateOf(0)

    fun shownQuickLaunch(current: Boolean): Boolean {
        if (!exiting) lastQuickLaunch = current
        return lastQuickLaunch
    }

    fun toggle(change: () -> Boolean, close: () -> Unit, rejected: () -> Unit) {
        exiting = true
        if (change()) close() else {
            exiting = false
            toggleRejects++
            rejected()
        }
    }

    fun rejectShortcut() { shortcutRejects++ }
    fun rejectCover() { coverRejects++ }

    fun toggleRow(): Modifier = Modifier.staggeredEntrance(0).shakeOnTrigger(toggleRejects)

    @Composable
    fun shortcutRow(requestInFlight: Boolean): Modifier = Modifier.staggeredEntrance(1)
        .shakeOnTrigger(shortcutRejects)
        .breathing(active = requestInFlight, minAlpha = 0.6f)

    fun coverEntry(): Modifier = Modifier.shakeOnTrigger(coverRejects)
}
