package com.tyranor.next.core.auth

/**
 * VNDB 权限层级语义（三层均合法）：未勾选=只读公开；Access private items=可读含私密；
 * Add/remove/edit=读写齐全。
 *
 * 权限档位由授权域的 token permissions（/authinfo）派生，故与 [VndbAuthStore] 同包定义，
 * `core/status` 反向引用本文件，避免 auth ⇄ status 包级循环依赖。
 */
enum class VndbPermissionTier {
    /** 具备 listwrite 权限：可读写并同步游玩状态。 */
    WRITE,

    /** 仅具备 listread 权限：只读（可访问私密条目），写入不可用。 */
    READ_PRIVATE,

    /** 无附加权限：只读公开（仅可访问公开条目），写入不可用。 */
    READ_PUBLIC,
}

/** 纯映射（JVM 可测）：根据 VNDB authinfo permissions 解析权限层级。 */
internal fun evaluateVndbPermissionTier(permissions: Collection<String>): VndbPermissionTier = when {
    "listwrite" in permissions -> VndbPermissionTier.WRITE
    "listread" in permissions -> VndbPermissionTier.READ_PRIVATE
    else -> VndbPermissionTier.READ_PUBLIC
}
