package com.tyranor.next.ui.common.motion

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.tyranor.next.core.settings.AppSettingsStore

/**
 * 界面动效档位（应用设置「界面动效」）：
 * - 增强（默认）：本目录工具集的新动效（弹簧、Q 弹按压、入场、模糊替换、水平侧推……）；
 * - 普通：原版 Tyranor 动效，工具集全部退化为原版行为（无形变、无入场、瞬时替换、原版转场与时长）。
 *
 * 持久化与内存态在 core 的 [AppSettingsStore.motionEnhancedState]；这里是给 Compose 读的快照镜像，
 * 由 [ProvideMotionEnvironment] 在每个 Activity 根部同步。非 Compose 代码（Activity 转场）也可直接读。
 * 下拉选择按用户要求在两档共用无选框选择（按下即为选择，可滑选）：候选只改文字色，松手才提交；
 * 第三方 Miuix 弹层的进退场仍使用库本身的动画，不在这里反射修改库常量。
 */
object MotionMode {
    var enhanced: Boolean by mutableStateOf(AppSettingsStore.DEFAULT_MOTION_ENHANCED)
        private set

    /** 同步持久化档位的快照镜像。 */
    internal fun sync(value: Boolean) {
        if (enhanced == value) return
        enhanced = value
    }
}
