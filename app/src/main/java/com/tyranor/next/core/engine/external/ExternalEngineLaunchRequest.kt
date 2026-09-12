package com.tyranor.next.core.engine.external

import com.tyranor.next.core.game.model.ScanGame
import com.tyranor.next.core.settings.ResolvedEngineSettings

/** 一次外置 APK 引擎启动请求，UI 不直接拼装外部模块协议。 */
data class ExternalEngineLaunchRequest(
    val game: ScanGame,
    val gameDirectoryPath: String,
    val launchTarget: String = game.launchTarget,
    /** 三级设置合并结果；外置模块按各自协议消费（RPGM 取 rpg 节下发 settings extra）。 */
    val resolvedSettings: ResolvedEngineSettings? = null,
)
