package com.tyranor.next.ui.common.motion

import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
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
 */
object MotionMode {
    var enhanced: Boolean by mutableStateOf(AppSettingsStore.DEFAULT_MOTION_ENHANCED)
        private set

    /** 同步持久化档位的快照镜像；值改变时同时更新第三方下拉弹窗弹簧。 */
    internal fun sync(value: Boolean) {
        if (enhanced == value) return
        enhanced = value
        MiuixPopupMotion.apply(value)
    }

    init {
        MiuixPopupMotion.apply(enhanced)
    }
}

/**
 * Miuix 下拉 / 列表弹窗的展开弹簧。Miuix 0.9.2 只从 `ListPopupDefaults.FractionAnimationSpec` 常量读取
 * （无参数可传）；弹窗缩放为 `0.15 + 0.85 × 进度`，换成带回弹的弹簧后展开时会轻轻冲过再落稳。
 *
 * 通过反射替换该静态字段；任何失败（字段被混淆、ART 拒绝写入）都静默保持原版行为。
 * Release 混淆保留规则见 `app/proguard-rules.pro`。
 */
internal object MiuixPopupMotion {
    private const val DEFAULTS_CLASS = "top.yukonga.miuix.kmp.basic.ListPopupDefaults"
    private const val FIELD = "FractionAnimationSpec"

    private var original: Any? = null

    /** Q 弹：约 0.42s 周期、回弹 0.32。 */
    private val bouncy: SpringSpec<Float> = spring(
        dampingRatio = 0.68f,
        stiffness = MotionSprings.stiffnessFor(420),
        visibilityThreshold = 0.0001f,
    )

    /** 串行切换增强弹簧与首次保存的原版配置；反射不可用时保留当前库配置。 */
    @Synchronized
    fun apply(enhanced: Boolean) {
        runCatching {
            val field = Class.forName(DEFAULTS_CLASS).getDeclaredField(FIELD)
            field.isAccessible = true
            if (original == null) original = field.get(null)
            field.set(null, if (enhanced) bouncy else original)
        }
    }
}
