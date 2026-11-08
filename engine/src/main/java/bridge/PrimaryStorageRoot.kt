package bridge

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager

/**
 * 主存储卷的「进程视角」真实挂载根。
 *
 * Android 隔离 user（三星 Knox 安全文件夹、小米手机分身等）下，同一台设备的主存储在该
 * user 的应用挂载命名空间内是 `/storage/emulated/<userId>`（如安全文件夹为 150），而
 * `/storage/emulated/0` 在该命名空间中不存在；SAF documentId 里的 `primary:` 是
 * ExternalStorageProvider 的卷标识，不随 user 变化。因此所有
 * `primary:` ↔ 真实路径 的映射都必须以本进程可见的挂载根为准，不能硬编码
 * `/storage/emulated/0`（issue #152）。
 */
object PrimaryStorageRoot {

    /** 常规单 user 环境的主存储根；JVM 测试与解析失败时的兜底值。 */
    const val DEFAULT = "/storage/emulated/0"

    /** 不可作挂载根的拼写：别名（悬空符号链宽容解析会原样返回）与缺失 uid 的 emulated 宽根。 */
    private val NON_MOUNT_SPELLINGS = listOf("/sdcard", "/storage/self/primary", "/storage/emulated")

    @Volatile
    private var cache: String? = null

    /** JVM 回归注入点：非 null 时替代真实 canonical 链探测；设备代码不得使用。 */
    @Volatile
    internal var probeCanonicalRoot: (() -> String?)? = null

    /** 进程级主存储根（解析一次后缓存；无 Context 场景走 canonical 链解析）。 */
    @JvmStatic
    fun get(): String = resolve(null)

    /**
     * 解析主存储根；仅成功时写缓存（进程内挂载不会变化），失败返回 [DEFAULT] 并允许下次重算，
     * 避免解析失败值或别名拼写被进程级固化后无法自愈。
     *
     * [context] 非空且 API 30+ 时优先取 StorageManager 的 primary volume directory
     * （系统视角，per-user 正确）；否则回退 `Environment.getExternalStorageDirectory().canonicalPath`
     * （`/sdcard` → `/storage/self/primary` → 本 user 的 emulated 挂载点）。
     * 全部失败时返回 [DEFAULT]。
     */
    @JvmStatic
    fun resolve(context: Context?): String {
        cache?.let { return it }
        val root = compute(context)
        if (root.isEmpty()) return DEFAULT
        cache = root
        return root
    }

    /** 返回空串表示两路解析均未得到可用挂载根。 */
    private fun compute(context: Context?): String {
        val smPath = if (context != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching {
                context.getSystemService(StorageManager::class.java)
                    ?.primaryStorageVolume?.directory?.absolutePath
            }.getOrNull()
        } else {
            null
        }
        probeCanonicalRoot?.let { return selectRoot(smPath, it()) }
        return selectRoot(
            smPath,
            runCatching {
                @Suppress("DEPRECATION")
                Environment.getExternalStorageDirectory().canonicalPath
            }.getOrNull(),
        )
    }

    /**
     * 解析决策纯函数（供 JVM 单测）：[smPath]（StorageManager，per-user 权威）优先，
     * [canonicalPath]（`/sdcard` 符号链解析）回退；任一来源不是真实 emulated 挂载根
     * （别名拼写或缺失 uid 的宽根都被 [isMountRoot] 拒绝）即视为失败，返回空串。
     */
    internal fun selectRoot(smPath: String?, canonicalPath: String?): String =
        smPath?.takeIf { isMountRoot(it) }
            ?: canonicalPath?.takeIf { isMountRoot(it) }
            ?: ""

    /** 必须是真实 emulated 挂载根；别名拼写与宽根不可作根。 */
    private fun isMountRoot(root: String): Boolean =
        root.startsWith("/storage/") && root !in NON_MOUNT_SPELLINGS

    /** 仅供 JVM 回归：清空缓存与探针，恢复生产语义。 */
    internal fun resetForTest() {
        cache = null
        probeCanonicalRoot = null
    }
}
