package com.tyranor.next.core.engine.external

import com.tyranor.next.core.game.model.ScanGame

/** 一次外置 APK 引擎启动请求，UI 不直接拼装外部模块协议。 */
data class ExternalEngineLaunchRequest(
    val game: ScanGame,
    val gameDirectoryPath: String,
    val launchTarget: String = game.launchTarget,
)
