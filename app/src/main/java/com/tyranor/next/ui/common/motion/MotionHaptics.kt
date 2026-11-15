package com.tyranor.next.ui.common.motion

import android.os.SystemClock
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType

/**
 * 触觉门面：语义化的触觉类型 + 统一门控，让全应用的触觉轻重、频率一致。
 *
 * 门控（任一不满足就不震）：
 * 1. 只在「增强」动效档（「普通」档保持原版）；
 * 2. 只回应手指：距最近一次触摸超过 [MotionIntent.TOUCH_WINDOW_MILLIS] 的事件（后台完成、自动检测）不震，
 *    需要无条件震的调用传 `requireTouch = false`；
 * 3. 限频：任意两次间隔 >= [MIN_GAP_MILLIS]，同类刻度（[Kind.Tick]）间隔 >= [TICK_GAP_MILLIS]，
 *    [Kind.Reject] 在 [REJECT_GAP_MILLIS] 内只震一次（抖动与提示同时触发时不会震两下）；
 * 3.5 一次操作只给一次「结果触觉」：[Kind.Reject] / [Kind.Confirm] / [Kind.Notice] 同属结果类，
 *    [RESULT_GAP_MILLIS] 内只放行第一次（一次点按的失败既抖一下又弹提示时，不会因两种触觉类型不同而震两下）；
 * 4. 尊重系统触感开关（经平台 `performHapticFeedback`，系统关了就不会震）。
 *
 * 原则（与底栏透镜一致）：**只在「提交」和「越过离散边界」时震**，连续接近 / 悬停 / 普通点按 / 滚动都不震。
 */
object MotionHaptics {
    enum class Kind(internal val type: HapticFeedbackType) {
        /** 长按成立后自绘的提升（拖拽排序的行被拿起）；`combinedClickable` 的长按自带系统触觉，不要再叠。 */
        LongPress(HapticFeedbackType.LongPress),

        /** 离散边界：翻页过半、换位、越过一格。 */
        Tick(HapticFeedbackType.SegmentTick),

        /** 一次确认：保存成功、选定。 */
        Confirm(HapticFeedbackType.Confirm),

        /** 应用内提示条出现（成功 / 信息类）：属于「结果触觉」，与同一次操作的拒绝 / 确认合并为一次。 */
        Notice(HapticFeedbackType.SegmentTick),

        /** 拒绝：输入无效、不可点元素被按。 */
        Reject(HapticFeedbackType.Reject),

        /** 手势越过「会生效」的阈值（抽屉下拉过关闭线、侧栏拉过打开线）。 */
        ThresholdActivate(HapticFeedbackType.GestureThresholdActivate),
    }

    const val MIN_GAP_MILLIS: Long = 30L
    const val TICK_GAP_MILLIS: Long = 50L
    const val REJECT_GAP_MILLIS: Long = 200L
    const val RESULT_GAP_MILLIS: Long = 300L

    /** 当前可用的触觉宿主（由 `ProvideMotionEnvironment` 绑定，栈顶 = 最近组合的页面）。 */
    private val hosts = ArrayList<HapticFeedback>(2)

    internal fun bind(haptic: HapticFeedback) {
        hosts.add(haptic)
    }

    internal fun unbind(haptic: HapticFeedback) {
        hosts.remove(haptic)
    }

    private var lastAt: Long = 0L
    private var lastTickAt: Long = 0L
    private var lastRejectAt: Long = 0L
    private var lastResultAt: Long = 0L

    private fun Kind.isResult(): Boolean = this == Kind.Reject || this == Kind.Confirm || this == Kind.Notice

    /** 按 [kind] 触发一次触觉；被门控拦下（或没有宿主）返回 false。 */
    fun perform(kind: Kind, requireTouch: Boolean = true): Boolean {
        val haptic = hosts.lastOrNull() ?: return false
        return perform(haptic, kind, requireTouch)
    }

    private fun perform(haptic: HapticFeedback, kind: Kind, requireTouch: Boolean = true): Boolean {
        if (!MotionMode.enhanced) return false
        if (requireTouch && !MotionIntent.touchedRecently()) return false
        val now = SystemClock.uptimeMillis()
        if (now - lastAt < MIN_GAP_MILLIS) return false
        if (kind.isResult()) {
            if (now - lastResultAt < RESULT_GAP_MILLIS) return false
        }
        when (kind) {
            Kind.Tick -> {
                if (now - lastTickAt < TICK_GAP_MILLIS) return false
                lastTickAt = now
            }
            Kind.Reject -> {
                if (now - lastRejectAt < REJECT_GAP_MILLIS) return false
                lastRejectAt = now
            }
            else -> Unit
        }
        lastAt = now
        if (kind.isResult()) lastResultAt = now
        haptic.performHapticFeedback(kind.type)
        return true
    }
}
