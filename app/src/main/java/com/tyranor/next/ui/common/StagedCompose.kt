package com.tyranor.next.ui.common

import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.LocalContext
import com.tyranor.next.ui.common.motion.MotionMode
import com.tyranor.next.ui.common.motion.PageEnter
import com.tyranor.next.ui.common.motion.PageTransitions

/**
 * 二级页面进场时的「分帧组合」进度：页面内容很重（应用设置约 12 个下拉、滑条、开关）时，一次性组合会让第一帧卡 60~90ms，
 * 侧推动画一开始就顿一下。分帧后第一帧只组合页面骨架与最上面几项，其余每帧再补一组——窗口的侧推是系统驱动的，不受主线程拖累，
 * 内容随页面滑入自上而下依次出现（与已有的逐项入场同一方向），总工作量不变，但每一帧都在预算之内。
 *
 * 只在「新页正在侧推进场」且「增强」档时分帧；系统重建 / 恢复（已就位）、「普通」档与系统关闭动画时一次组合完，行为与原来一致。
 */
@Stable
class StagedCompose internal constructor(last: Int, fresh: Boolean) {
    // 每项只订阅自己所属的组；放行下一组不再使前几组已经可见的行一起失效。
    private val groups = if (fresh) Array(last + 1) { mutableStateOf(it == 0) } else null

    internal fun isVisible(order: Int): Boolean = groups?.get(order)?.value ?: true

    internal suspend fun revealGroups() {
        val groups = groups ?: return
        for (order in 1 until groups.size) withFrameNanos { groups[order].value = true }
    }

    internal companion object {
        /** 入场身份只在创建时判定，不能把页面组合订阅到逐帧变化的动画进度。 */
        fun forPage(last: Int, enhanced: Boolean, enter: PageEnter?): StagedCompose =
            Snapshot.withoutReadObservation {
                StagedCompose(last, enhanced && enter != null && enter.progress.value < 1f)
            }
    }
}

/** 创建本页的分帧进度，共 [last] + 1 组（0..[last]）：第一帧放行第 0 组，之后每帧放行下一组。 */
@Composable
fun rememberStagedCompose(last: Int): StagedCompose {
    val context = LocalContext.current
    val staged = remember(context, last) {
        val enter = (PageTransitions.findActivity(context) as? AppScreenActivity)?.pageEnter
        // 进度已满 = 系统重建 / 恢复的页面，没有侧推进场，不分帧
        StagedCompose.forPage(last, MotionMode.enhanced, enter)
    }
    LaunchedEffect(staged) {
        staged.revealGroups()
    }
    return staged
}

/** 分帧组合的列表项：[order] 组放行之前这一项是空的（0 高度），放行后组合出完整内容。 */
fun LazyListScope.stagedItem(order: Int, staged: StagedCompose, content: @Composable LazyItemScope.() -> Unit) {
    item {
        if (staged.isVisible(order)) content()
    }
}
