package com.tyranor.next.core.cover

/**
 * 封面搜索 / 批量刮削的类型化错误码。
 *
 * AGENTS.md「错误处理协议」要求 core 只返回类型化失败，不得把本地化文案当错误消息；
 * 因此本包内所有失败一律携带错误码，文案组装上移到 UI（`ui/cover/CoverErrorMessages.kt`）。
 */
enum class CoverErrorCode {
    /** 来源标识不属于 [com.tyranor.next.core.settings.AppSettingsStore.DEFAULT_COVER_SCRAPER_SOURCES]。 */
    INVALID_SOURCE,

    /** 该来源已在封面刮削设置中关闭。 */
    SOURCE_DISABLED,

    /** 未预期失败的兜底（底层异常 message 只作日志线索）。 */
    UNKNOWN,

    /** Hikarinagi 授权已失效，需要重新登录。 */
    HIKARINAGI_AUTH_EXPIRED,

    HIKARINAGI_NETWORK,

    HIKARINAGI_RETRY,

    BANGUMI_NETWORK,

    STEAM_NETWORK,

    VNDB_NETWORK,

    NEXTMOE_NETWORK,

    /** 未萌 NextMoe 触发限流 / 超额（problem code `RATE_LIMITED` / `QUOTA_EXCEEDED`）。 */
    NEXTMOE_RATE_LIMITED,

    /** 批量刮削被取消：终态标记，不是失败（`CancellationException` 仍原样向上传播）。 */
    CANCELLED,
}
