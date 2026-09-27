package com.tyranor.next.core.engine.external

import com.tyranor.next.R
import com.tyranor.next.core.engine.EngineType
import com.tyranor.next.core.settings.EngineSettingsStore

/** 外置主机模拟器跳转目标注册表（包名/Activity/契约的单源）。 */
object ExternalEmulatorRegistry {

    /** PPSSPP 标准版包名。 */
    const val PPSSPP_PACKAGE_STANDARD = "org.ppsspp.ppsspp"

    /** PPSSPP 黄金版包名（同源构建，仅 applicationId 不同，Activity 类名一致）。 */
    const val PPSSPP_PACKAGE_GOLD = "org.ppsspp.ppssppgold"

    private const val PPSSPP_ACTIVITY = "org.ppsspp.ppsspp.PpssppActivity"

    val ppssppStandard: EmulatorTarget = EmulatorTarget(
        engine = EngineType.PSP,
        displayNameRes = R.string.engine_emulator_ppsspp,
        packageName = PPSSPP_PACKAGE_STANDARD,
        activityName = PPSSPP_ACTIVITY,
        mime = "*/*",
        grantWrite = true,
        installUrl = "https://www.ppsspp.org/",
    )

    val ppssppGold: EmulatorTarget = EmulatorTarget(
        engine = EngineType.PSP,
        displayNameRes = R.string.engine_emulator_ppsspp_gold,
        packageName = PPSSPP_PACKAGE_GOLD,
        activityName = PPSSPP_ACTIVITY,
        mime = "*/*",
        grantWrite = true,
        installUrl = "https://play.google.com/store/apps/details?id=$PPSSPP_PACKAGE_GOLD",
    )

    val targets: List<EmulatorTarget> = listOf(
        ppssppStandard,
        ppssppGold,
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
        EmulatorTarget(
            engines = listOf(EngineType.YURIS, EngineType.CATSYSTEM2, EngineType.PC),
            displayNameRes = R.string.engine_emulator_winlator,
            packageName = WinlatorContract.PACKAGE_NAME,
            activityName = WinlatorContract.ACTIVITY_NAME,
            mime = "*/*",
            grantWrite = false,
            installUrl = "https://github.com/Weiss-UltimateSavior/winlator-cn",
            launchStyle = EmulatorLaunchStyle.WINLATOR_EXTERNAL,
        ),
    )

    /** 该引擎是否由外置模拟器/模拟器跳转承载（PSP / Switch / YURIS）。 */
    fun forEngine(engine: EngineType): EmulatorTarget? = targets.firstOrNull { it.supports(engine) }

    /**
     * 按生效版本解析 PPSSPP 目标：黄金版 → [ppssppGold]，其余（含未知值）→ [ppssppStandard]。
     */
    fun ppssppTarget(version: String?): EmulatorTarget =
        if (version?.trim()?.lowercase() == EngineSettingsStore.PPSSPP_VERSION_GOLD) ppssppGold else ppssppStandard
}
