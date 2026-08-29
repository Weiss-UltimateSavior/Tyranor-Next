package com.tyranor.next.core.engine.external

/** 外置 APK 引擎启动结果；由上层决定如何展示错误文案。 */
data class ExternalEngineLaunchResult(
    val success: Boolean,
    val message: String? = null,
    val reason: String? = null,
) {
    companion object {
        fun success(): ExternalEngineLaunchResult = ExternalEngineLaunchResult(success = true)

        fun failure(message: String, reason: String): ExternalEngineLaunchResult =
            ExternalEngineLaunchResult(success = false, message = message, reason = reason)
    }
}
