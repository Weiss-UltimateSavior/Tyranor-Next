package com.tyranor.next.core.engine.external

import androidx.annotation.StringRes

/**
 * 外置 APK 引擎启动失败原因（P0-6）：核心层只给类型与资源引用，
 * 本地化文案由 UI 层 `LaunchErrorMessages` 组装。
 */
enum class ExternalEngineErrorCode {
    INVALID_GAME_PATH,
    PACKAGE_NOT_INSTALLED,
    PREPARE_FAILED,
    ACTIVITY_NOT_FOUND,
    SECURITY_EXCEPTION,
    LAUNCH_EXCEPTION,
}

/**
 * 外置 APK 引擎启动结果；由上层决定如何展示错误文案。
 *
 * [moduleNameRes] / [messageRes] 为各模块自定义文案的资源引用，UI 层优先取资源，
 * 否则回退 [moduleNameFallback] / [detail]；[detail] 只承载底层异常信息，不是文案。
 */
data class ExternalEngineLaunchResult(
    val success: Boolean,
    val error: ExternalEngineErrorCode? = null,
    @param:StringRes val moduleNameRes: Int? = null,
    val moduleNameFallback: String? = null,
    val engineName: String? = null,
    @param:StringRes val messageRes: Int? = null,
    val detail: String? = null,
) {
    companion object {
        fun success(): ExternalEngineLaunchResult = ExternalEngineLaunchResult(success = true)

        fun failure(
            error: ExternalEngineErrorCode,
            @StringRes moduleNameRes: Int? = null,
            moduleNameFallback: String? = null,
            engineName: String? = null,
            @StringRes messageRes: Int? = null,
            detail: String? = null,
        ): ExternalEngineLaunchResult = ExternalEngineLaunchResult(
            success = false,
            error = error,
            moduleNameRes = moduleNameRes,
            moduleNameFallback = moduleNameFallback,
            engineName = engineName,
            messageRes = messageRes,
            detail = detail,
        )
    }
}
