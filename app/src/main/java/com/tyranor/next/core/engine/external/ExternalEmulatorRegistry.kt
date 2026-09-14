package com.tyranor.next.core.engine.external

import com.tyranor.next.R
import com.tyranor.next.core.engine.EngineType

/** 外置主机模拟器跳转目标注册表（包名/Activity/契约的单源）。 */
object ExternalEmulatorRegistry {

    val targets: List<EmulatorTarget> = listOf(
        EmulatorTarget(
            engine = EngineType.PSP,
            displayNameRes = R.string.engine_emulator_ppsspp,
            packageName = "org.ppsspp.ppsspp",
            activityName = "org.ppsspp.ppsspp.PpssppActivity",
            mime = "*/*",
            grantWrite = true,
            installUrl = "https://www.ppsspp.org/",
        ),
        EmulatorTarget(
            engine = EngineType.NINTENDO_SWITCH,
            displayNameRes = R.string.engine_emulator_eden,
            packageName = "dev.eden.eden_emulator",
            // Eden 为 yuzu 派生，Activity 类名沿用 yuzu 命名空间（上游合法配置）
            activityName = "org.yuzu.yuzu_emu.activities.EmulationActivity",
            mime = "application/octet-stream",
            grantWrite = false,
            installUrl = "https://git.eden-emu.dev/eden-emu/eden/releases",
        ),
    )

    /** 该引擎是否由外置模拟器跳转承载（PSP / Switch）。 */
    fun forEngine(engine: EngineType): EmulatorTarget? = targets.firstOrNull { it.engine == engine }
}
