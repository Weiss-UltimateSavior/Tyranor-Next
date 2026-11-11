package com.tyranor.next.ui.settings

import androidx.annotation.StringRes
import com.tyranor.next.R
import com.tyranor.next.core.engine.EngineType

/** 引擎细分设置类型：与引擎设置 Activity 共用，标识各引擎配置页。 */
enum class EngineSettingsKind(@param:StringRes val titleRes: Int) {
    KRKR(R.string.engine_settings_krkr_title),
    ONS(R.string.engine_settings_ons_title),
    ARTEMIS(R.string.engine_settings_artemis_title),
    RPG_MAKER(R.string.engine_settings_rpg_maker_title),
    TYRANO(R.string.engine_settings_tyrano_title),
    RENPY(R.string.engine_settings_renpy_title),
    RUFFLE(R.string.engine_settings_ruffle_title),
    SIGLUS(R.string.engine_settings_siglus_title),
    FRAMEBUFFER(R.string.engine_settings_framebuffer_title),
    FVP(R.string.engine_settings_fvp_title),
    ETHORNELL(R.string.ethornell_settings_title),
    CMVS(R.string.engine_settings_cmvs_title),
    XSYS35(R.string.engine_settings_xsys35_title),
    XSYS4(R.string.engine_settings_xsys4_title),
    PPSSPP(R.string.engine_settings_ppsspp_title),
    WINLATOR(R.string.engine_settings_winlator_title),
}

/**
 * 引擎页条目对应的全局引擎设置页；返回 null 表示该引擎没有全局设置入口
 * （SystemNNN / Switch / 第三方运行时模块 / 安卓游戏 / 未知引擎）。
 */
internal fun engineSettingsKindOf(engine: EngineType): EngineSettingsKind? = when (engine) {
    EngineType.KIRIKIRI -> EngineSettingsKind.KRKR
    EngineType.ONS -> EngineSettingsKind.ONS
    EngineType.ARTEMIS -> EngineSettingsKind.ARTEMIS
    EngineType.RPGMAKER, EngineType.RPG_MV, EngineType.RPG_MZ -> EngineSettingsKind.RPG_MAKER
    EngineType.TYRANO, EngineType.VN, EngineType.WEB_OTHER -> EngineSettingsKind.TYRANO
    EngineType.RENPY -> EngineSettingsKind.RENPY
    EngineType.RUFFLE -> EngineSettingsKind.RUFFLE
    EngineType.SIGLUS -> EngineSettingsKind.SIGLUS
    EngineType.REALLIVE, EngineType.AVG32, EngineType.UK2 -> EngineSettingsKind.FRAMEBUFFER
    EngineType.FVP -> EngineSettingsKind.FVP
    EngineType.ETHORNELL -> EngineSettingsKind.ETHORNELL
    EngineType.CMVS -> EngineSettingsKind.CMVS
    EngineType.XSYS35 -> EngineSettingsKind.XSYS35
    EngineType.XSYS4 -> EngineSettingsKind.XSYS4
    EngineType.PSP -> EngineSettingsKind.PPSSPP
    EngineType.YURIS, EngineType.CATSYSTEM2, EngineType.PC -> EngineSettingsKind.WINLATOR
    EngineType.IMPACTO,
    EngineType.SYSTEMNNN,
    EngineType.NINTENDO_SWITCH,
    EngineType.RUNTIME_MODULE,
    EngineType.ANDROID_APP,
    EngineType.UNKNOWN,
    -> null
}
