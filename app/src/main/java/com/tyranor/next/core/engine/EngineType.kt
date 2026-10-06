package com.tyranor.next.core.engine

/**
 * 游戏引擎类型（精简自 RinneMobile：重点 kr/ons/ty/ar）。
 *
 * [abbr] 为卡片角标等紧凑场景使用的缩写（显示名过长；无角标场景仍用 [displayName]）。
 * [isManual] 标记「手动添加入库、不参与扫描」的类型（PC / 安卓游戏）：重扫时必须保留，
 * 且不纳入存档管理与引擎配置。
 */
enum class EngineType(val displayName: String, val abbr: String, val isManual: Boolean = false) {
    KIRIKIRI("Kirikiri", "KR2"),
    ONS("ONScripter", "ONS"),
    TYRANO("Tyrano", "TY"),
    RPGMAKER("RPG Maker", "RPGM"),
    RPG_MV("RPG Maker MV", "MV"),
    RPG_MZ("RPG Maker MZ", "MZ"),
    VN("VN", "VN"),
    WEB_OTHER("WebOther", "WEB"),
    ARTEMIS("Artemis", "AR"),
    SIGLUS("Siglus", "SIG"),
    REALLIVE("RealLive", "RY"),
    AVG32("AVG32", "AVG"),
    UK2("UK2", "UK"),
    FVP("FVP", "FVP"),
    RENPY("Ren'Py", "RPY"),
    YURIS("YU-RIS", "YR"),
    CATSYSTEM2("CatSystem2", "CS2"),
    PC("PC", "PC", isManual = true),
    ANDROID_APP("Android", "APP", isManual = true),
    PSP("PSP", "PSP"),
    NINTENDO_SWITCH("Nintendo Switch", "NS"),
    UNKNOWN("Unknown", "UNK");
}
