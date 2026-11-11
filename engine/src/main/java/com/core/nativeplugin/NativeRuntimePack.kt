package com.core.nativeplugin

import android.content.Context
import android.os.StatFs
import android.util.Log
import java.io.File
import java.io.IOException
import java.util.zip.ZipInputStream
import org.tukaani.xz.XZInputStream

/**
 * 大体积引擎运行库的「随包压缩、按需解压」入口。
 *
 * 这些运行库（`libsiglus` / `librfvp` / `libethornell` / `libkrkrsdl3` / `libcmvs_android` /
 * IJK 三件套 / System35+4 与 `libSDL2x`）不再放进 APK 的 `lib/arm64-v8a/`，而是打成
 * `assets/engine-runtime/<pack>.zip.xz`：LZMA2 比 APK 的 deflate 小 30–40%，代价是首次使用
 * 某个引擎时要先解压。解压目录固定在应用私有目录，正好也是各 JNI 桥按绝对路径 `dlopen`
 * 的位置（Android 10+ 允许从应用数据目录加载只读 `.so`，与既有 nativeplugin 同一机制）。
 *
 * 分片按引擎家族划分而不是一个大包：只有真正要跑的引擎才付出解压时间与内存，
 * 后续也可以平滑演进为单独下载的运行库包。
 *
 * 更新检测使用 APK 的 `lastUpdateTime` + `versionName`：应用升级后 stamp 不匹配即重新解压，
 * 不需要手工维护版本号；PackageManager 查询失败时写入空 stamp，避免每次启动都重复解压。
 */
object NativeRuntimePack {

    private const val TAG = "NativeRuntimePack"
    private const val ASSET_DIR = "engine-runtime"
    private const val STAMP_NAME = ".stamp"
    private const val BUFFER_SIZE = 64 * 1024
    private const val MAX_ENTRIES = 32
    private const val MAX_TOTAL_BYTES = 512L * 1024L * 1024L

    /** 无法读取资产大小时退回的保守预留：staging + 旧包备份同时存在（约 2 × 41 MB）。 */
    private const val FALLBACK_MIN_FREE_BYTES = 128L * 1024L * 1024L

    /** 资产大小换算到「解压后体积 × 2 + 余量」的经验系数（实测压缩比约 2.8–3.3）。 */
    private const val EXPANSION_FACTOR = 7L
    private const val FREE_SPACE_MARGIN_BYTES = 16L * 1024L * 1024L

    /** `libsiglus.so`：Siglus 宿主与 RealLive / AVG32 / UK2 的 game_launcher 共用。 */
    const val PACK_SIGLUS = "siglus"

    /** `librfvp.so`：FVP 引擎。 */
    const val PACK_FVP = "fvp"

    /** `libethornell.so`：Ethornell / BGI 引擎（JNI 表由 bridge 注册）。 */
    const val PACK_ETHORNELL = "ethornell"

    /** `libkrkrsdl3.so`：KiriKiri 的 SDL3 内核（`libSDL3.so` 仍随 `lib/`）。 */
    const val PACK_KRKRSDL3 = "krkrsdl3"

    /** `libimpacto.so`: statically linked private SDL 3.4.10; never shares libSDL3.so. */
    const val PACK_IMPACTO = "impacto"

    /** `libxsystem35.so` / `libxsystem4.so` / `libSDL2x.so`：System3.x 与 System4 共用 SDL2x。 */
    const val PACK_XSYSTEM = "xsystem"

    /** IJK 播放器三件套（`ijkffmpeg` / `ijksdl` / `ijkplayer`），ONS 与 Artemis 的视频共用。 */
    const val PACK_IJK = "ijk"

    /** `libcmvs_android.so`：CMVS NativeActivity 引擎，由 `cmvs_loader` 从私有目录 dlopen。 */
    const val PACK_CMVS = "cmvs"

    /** `libsystemnnn.so`：SystemNNN 播放与存档快照校验共用。 */
    const val PACK_SYSTEMNNN = "systemnnn"

    /**
     * 幂等：运行库已按当前 APK 版本解压则直接返回 true，否则从 assets 重新解压。
     *
     * 仅在应用主进程调用（启动编排处）；宿主进程只经 [libraryPath] 读取已解压的文件。
     */
    @JvmStatic
    @Synchronized
    fun ensure(context: Context, packId: String): Boolean {
        val app = context.applicationContext
        val root = rootDir(app, packId)
        // 解压中途被杀会留下同级的 *.staging-* / *.backup-*（24–41 MB），入口先清掉。
        runCatching { cleanupResidue(root.parentFile) }
            .onFailure { Log.w(TAG, "clean $packId runtime pack residue failed", it) }
        val stamp = expectedStamp(app)
        if (isCurrent(root, stamp)) return true
        if (!hasFreeSpace(app, packId)) return false
        return try {
            install(app, packId, root, stamp)
            true
        } catch (t: Throwable) {
            Log.w(TAG, "prepare $packId runtime pack failed", t)
            false
        }
    }

    /**
     * 已解压运行库的绝对路径；未解压、文件缺失或 `.stamp` 与当前 APK 不一致时返回 null
     * （调用方自行转成宿主错误）。
     *
     * 宿主是独立进程 + singleInstance/taskAffinity：应用升级后从最近任务恢复不会重新经过
     * [ensure]，因此这里必须再校验一次 stamp，避免继续加载升级前解压的旧 `.so`。
     */
    @JvmStatic
    fun libraryPath(context: Context, packId: String, libraryName: String): String? {
        val app = context.applicationContext
        val root = rootDir(app, packId)
        if (!isCurrent(root, expectedStamp(app))) return null
        val file = File(root, libraryName)
        return if (file.isFile) file.absolutePath else null
    }

    private fun rootDir(app: Context, packId: String): File =
        File(File(app.filesDir, "engine_runtime"), packId)

    private fun isCurrent(root: File, stamp: String): Boolean = runCatching {
        val stampFile = File(root, STAMP_NAME)
        stampFile.isFile && stampFile.readText() == stamp &&
            root.listFiles()?.any { it.name.endsWith(".so") } == true
    }.getOrDefault(false)

    private fun cleanupResidue(parent: File?) {
        if (parent == null) return
        parent.listFiles()?.forEach { file ->
            if (file.isDirectory && (file.name.contains(".staging-") || file.name.contains(".backup-"))) {
                if (!file.deleteRecursively()) Log.w(TAG, "cannot remove runtime pack residue: $file")
            }
        }
    }

    /**
     * 解压前空间预检：按该分片的资产大小换算（解压后约 2.8–3.3 倍，替换期间旧包与新包并存），
     * 读不到资产长度时退回保守常量。读不到可用空间时放行，避免把统计失败误判成空间不足。
     */
    private fun hasFreeSpace(app: Context, packId: String): Boolean {
        val available = runCatching { StatFs(app.filesDir.absolutePath).availableBytes }.getOrNull()
            ?: return true
        val required = requiredSpace(app, packId)
        if (available >= required) return true
        Log.w(TAG, "insufficient storage for $packId runtime pack: $available < $required")
        return false
    }

    private fun requiredSpace(app: Context, packId: String): Long {
        val assetBytes = runCatching {
            app.assets.openFd("$ASSET_DIR/$packId.zip.xz").use { it.length }
        }.getOrNull()
        return if (assetBytes != null && assetBytes > 0) {
            assetBytes * EXPANSION_FACTOR + FREE_SPACE_MARGIN_BYTES
        } else {
            FALLBACK_MIN_FREE_BYTES
        }
    }

    private fun installStamp(app: Context): String? = runCatching {
        @Suppress("DEPRECATION")
        val info = app.packageManager.getPackageInfo(app.packageName, 0)
        // lastUpdateTime alone can be coarse on some installers; versionName covers version bumps.
        "${info.lastUpdateTime}:${info.versionName.orEmpty()}"
    }.getOrNull()

    /**
     * 当前 APK 对应的 stamp。查询失败时退化为空串：解压仍会落盘一个空 stamp，
     * 后续启动据此判定为「已就绪」，避免 PackageManager 异常时每次启动都全量重解压；
     * 查询恢复后 stamp 不匹配，再补一次正常解压即可。
     */
    private fun expectedStamp(app: Context): String = installStamp(app) ?: ""

    private fun install(app: Context, packId: String, root: File, stamp: String) {
        val parent = root.parentFile ?: throw IOException("runtime pack has no parent: $root")
        if (!parent.isDirectory && !parent.mkdirs()) {
            throw IOException("cannot create runtime pack root: $parent")
        }
        val staging = File(parent, "$packId.staging-${System.currentTimeMillis()}")
        if (!staging.mkdirs()) throw IOException("cannot create staging dir: $staging")
        try {
            app.assets.open("$ASSET_DIR/$packId.zip.xz").use { asset ->
                ZipInputStream(XZInputStream(asset.buffered())).use { zip ->
                    extractSafely(zip, staging)
                }
            }
            File(staging, STAMP_NAME).writeText(stamp)
            // Android 10+ 只允许从应用数据目录加载不可写的 .so；目录保持可写以便升级时替换。
            staging.listFiles()?.filter { it.isFile }?.forEach { file ->
                file.setReadable(true, true)
                file.setExecutable(false, false)
                file.setWritable(false, false)
            }
            replace(staging, root)
        } finally {
            if (staging.exists()) {
                staging.deleteRecursively()
            }
        }
    }

    private fun extractSafely(zip: ZipInputStream, targetDir: File) {
        val targetRoot = targetDir.canonicalFile
        val targetPrefix = targetRoot.path + File.separator
        var entries = 0
        var totalBytes = 0L
        while (true) {
            val entry = zip.nextEntry ?: break
            entries += 1
            if (entries > MAX_ENTRIES) throw IOException("too many runtime pack entries")
            val name = entry.name ?: throw IOException("runtime pack entry without name")
            if (name.isEmpty() || name.startsWith("/") || name.contains('\\')) {
                throw IOException("invalid runtime pack entry: $name")
            }
            val output = File(targetRoot, name).canonicalFile
            if (output.path != targetRoot.path && !output.path.startsWith(targetPrefix)) {
                throw IOException("runtime pack entry escapes target: $name")
            }
            if (entry.isDirectory) {
                if (!output.exists() && !output.mkdirs()) throw IOException("mkdir $output")
            } else {
                output.parentFile?.let { if (!it.exists() && !it.mkdirs()) throw IOException("mkdir $it") }
                output.outputStream().use { sink ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        val read = zip.read(buffer)
                        if (read <= 0) break
                        totalBytes += read
                        if (totalBytes > MAX_TOTAL_BYTES) {
                            throw IOException("runtime pack exceeds size budget")
                        }
                        sink.write(buffer, 0, read)
                    }
                }
            }
            zip.closeEntry()
        }
        if (entries == 0) throw IOException("empty runtime pack")
    }

    private fun replace(staging: File, root: File) {
        val backup = File(root.parentFile, "${root.name}.backup-${System.currentTimeMillis()}")
        if (root.exists() && !root.renameTo(backup)) {
            throw IOException("cannot move previous runtime pack: $root")
        }
        if (!staging.renameTo(root)) {
            if (backup.exists()) backup.renameTo(root)
            throw IOException("cannot install runtime pack: $root")
        }
        if (backup.exists()) backup.deleteRecursively()
    }
}
