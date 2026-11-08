package bridge

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager

/**
 * 主存储卷的「进程视角」真实挂载根。
 *
 * Android 隔离 user（三星 Knox 安全文件夹等）下，同一台设备的主存储在该 user
 * 的应用挂载命名空间内是 `/storage/emulated/<userId>`（如安全文件夹为 150），
 * 而 `/storage/emulated/0` 在该命名空间中不存在；SAF documentId 里的 `primary:`
 * 是 ExternalStorageProvider 的卷标识，不随 user 变化。因此所有
 * `primary:` ↔ 真实路径 的映射都必须以本进程可见的挂载根为准，不能硬编码
 * `/storage/emulated/0`（issue #152）。
 */
object PrimaryStorageRoot {

    /** 常规单 user 环境的主存储根；JVM 测试与解析失败时的兜底值。 */
    const val DEFAULT = "/storage/emulated/0"

    @Volatile
    private var cache: String? = null

    /** 进程级主存储根（解析一次后缓存；无 Context 场景走 canonical 链解析）。 */
    @JvmStatic
    fun get(): String = resolve(null)

    /**
     * 解析主存储根并缓存（进程内挂载不会变化）。[context] 非空且 API 30+ 时优先
     * 取 StorageManager 的 primary volume directory（系统视角，per-user 正确）；
     * 否则回退 `Environment.getExternalStorageDirectory().canonicalPath`
     * （`/sdcard` → `/storage/self/primary` → 本 user 的 emulated 挂载点）。
     * 全部失败时返回 [DEFAULT]。
     */
    @JvmStatic
    fun resolve(context: Context?): String {
        cache?.let { return it }
        val root = compute(context)
        cache = root
        return root
    }

    private fun compute(context: Context?): String {
        if (context != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching {
                context.getSystemService(StorageManager::class.java)
                    ?.primaryStorageVolume?.directory?.absolutePath
            }.getOrNull()?.takeIf { it.startsWith("/storage/") }?.let { return it }
        }
        runCatching {
            @Suppress("DEPRECATION")
            Environment.getExternalStorageDirectory().canonicalPath
                .takeIf { it.startsWith("/storage/") }
        }.getOrNull()?.let { return it }
        return DEFAULT
    }
}
