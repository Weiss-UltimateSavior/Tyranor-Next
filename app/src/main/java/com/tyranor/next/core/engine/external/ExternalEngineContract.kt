package com.tyranor.next.core.engine.external

import android.content.pm.ActivityInfo
import com.core.engine.EnginePlayerOrientation

/**
 * 外置 APK 引擎模块（Joiplay runtime 系）的 Intent extras 键单一来源（P1-7）。
 * 协议由外置 APK 固定，App 侧只负责按契约写入；两侧不再散落裸字符串。
 *
 * [ORIENTATION] 值域收敛为 `0`（[ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE]）与
 * `8`（[ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE]）两个具体常量。旧版宿主兼容结论
 * （P3-6）：旧宿主对额外值只有两种消费路径——读取该 extra 并透传 `setRequestedOrientation`
 * 的宿主，`0`/`8` 均为 API 9+ 合法常量、行为不变；完全不消费该 extra 的宿主回落其自身横竖屏
 * 策略（不受影响）。主 App 仅保证按契约写入该 extra，不承诺第三方宿主的最终窗口方向
 * （边界口径见 SPEC.md，宿主侧行为以真机验收为准）。
 */
object ExternalEngineContract {
    const val GAME = "game"
    const val SETTINGS = "settings"
    const val ORIENTATION = "orientation"
    const val ROOT_URI = "rootUri"
    const val LAUNCH_TARGET = "launchTarget"

    /**
     * 将外置模块启动请求中的方向收敛为具体单向横屏常量（`0` 或 `8`）。
     * 判定实现收口到 engine 侧 [EnginePlayerOrientation.normalizeLandscapeOrientation]（P3-2，
     * app→engine 单向依赖），本函数仅保留外置契约入口；两侧一致性由 JVM 测试钉住。
     */
    fun normalizeOrientation(orientation: Int): Int =
        EnginePlayerOrientation.normalizeLandscapeOrientation(orientation)
}
