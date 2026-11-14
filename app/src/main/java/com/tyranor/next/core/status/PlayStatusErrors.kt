package com.tyranor.next.core.status

/**
 * 游玩状态同步错误码（core → ui 类型化错误协议）：core 只抛类型化失败，文案由
 * `ui/cover/PlayStatusMessages.kt` 映射，core 不携带本地化文本、不外泄技术细节。
 *
 * 域归位注记（BK-64）：文案映射驻 ui/cover（与 CoverErrorMessages 同域同构、最早随封面
 * 刮削域落地），弹窗驻 ui/game——两包分居为既有归位，消费点跨包引用为既有约定；若按
 * 规范迁 ui/common（有 LaunchErrorMessages 先例），需连带 PlayStatusDialog 与
 * CoverScraperSettingsActivity 两处 import 及本 KDoc 指引同步改写，触碰时评估。
 *
 * 跨域键借用约定（BK-37）：`play_error_*` 词表为本域（status）文案，授权域 UI 按语义
 * 借用为既有约定——封面刮削设置页摘要（CoverScraperSettingsActivity 的 Hikarinagi/VNDB
 * 权限与失效分档、凭据弹窗兜底）直接引用 `play_error_*` 键，不为授权域另设同义键
 * （复制文案会引入双源漂移；「缺少游玩状态权限」出现在授权上下文语义准确）。
 */
enum class PlayStatusError {
    /** Hikarinagi OAuth 未登录。 */
    HIKARINAGI_AUTH_MISSING,

    /** Hikarinagi 令牌已失效或刷新失败，需重新授权。 */
    HIKARINAGI_TOKEN_EXPIRED,

    /** Hikarinagi 端点返回 403：缺少游玩状态权限或账号受限。 */
    HIKARINAGI_TOKEN_PERMISSION,

    /** Hikarinagi 端点返回 404（如 GALGAME_NOT_FOUND）：绑定条目不存在或已删除。 */
    HIKARINAGI_SUBJECT_NOT_FOUND,

    HIKARINAGI_NETWORK,

    /** Bangumi OAuth 凭证未配置或用户未登录。 */
    BANGUMI_AUTH_MISSING,

    /** Bangumi 令牌刷新失败，需重新授权。 */
    BANGUMI_TOKEN_EXPIRED,

    /** Bangumi 端点返回 403：令牌有效但缺少收藏写权限或账号受限。 */
    BANGUMI_TOKEN_PERMISSION,

    BANGUMI_NETWORK,

    /** VNDB token 未配置。 */
    VNDB_AUTH_MISSING,

    /** VNDB 端点返回 401：token 失效。 */
    VNDB_TOKEN_INVALID,

    /** VNDB 端点返回 403：token 有效但缺少 listread / listwrite 权限。 */
    VNDB_TOKEN_PERMISSION,

    VNDB_NETWORK,

    /** 未萌 NextMoe OAuth 未登录。 */
    NEXTMOE_AUTH_MISSING,

    /** 未萌 NextMoe 令牌已失效或刷新失败，需重新授权。 */
    NEXTMOE_TOKEN_EXPIRED,

    /** 未萌 NextMoe 端点返回 403：权限不足或账号受限。 */
    NEXTMOE_TOKEN_PERMISSION,

    /** 未萌 NextMoe 条目不存在、已删除或已合并，需重新绑定。 */
    NEXTMOE_SUBJECT_NOT_FOUND,

    /** 未萌 NextMoe 触发限流或配额耗尽。 */
    NEXTMOE_RATE_LIMITED,

    NEXTMOE_NETWORK,

    /** 写入等待（刷新令牌/节流）期间登录会话已切换或登出，拒绝以新会话发送旧操作。 */
    AUTH_SESSION_CHANGED,

    UNKNOWN,
}

/**
 * 游玩状态排障埋点单一事实源（BK-76）：`adb logcat -s PlayStatus`（记录 HTTP 状态码
 * 与端点，不含令牌）。[toPlayStatusError] 与 UI 侧收敛未预期异常时复用同一 tag；
 * PlayStatusService 同包直接引用，不再私有复制。
 */
internal const val PLAY_STATUS_LOG_TAG = "PlayStatus"

/** 状态同步失败：携带错误码而非文案。`detail` 只进异常 message 供日志排查。 */
class PlayStatusException(val error: PlayStatusError, detail: String? = null) :
    RuntimeException(detail ?: error.name)

/** 未预期的异常收敛为 [PlayStatusError.UNKNOWN] 并把原始异常打进日志。 */
internal fun Throwable.toPlayStatusError(tag: String): PlayStatusError {
    val known = this as? PlayStatusException
    if (known != null) return known.error
    android.util.Log.w(tag, "play status request failed", this)
    return PlayStatusError.UNKNOWN
}
