package com.tyranor.next.core.engine

/** 游戏引擎类型（精简自 RinneMobile：重点 kr/ons/ty/ar） */
enum class EngineType(val displayName: String) {
    KIRIKIRI("Kirikiri"),
    ONS("ONScripter"),
    TYRANO("Tyrano"),
    RPG_MV("RPG Maker MV"),
    RPG_MZ("RPG Maker MZ"),
    VN("VN"),
    WEB_OTHER("WebOther"),
    ARTEMIS("Artemis"),
    RENPY("Ren'Py"),
    UNKNOWN("Unknown");
}
