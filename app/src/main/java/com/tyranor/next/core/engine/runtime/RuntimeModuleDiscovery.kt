package com.tyranor.next.core.engine.runtime

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Build
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import kotlin.coroutines.cancellation.CancellationException

/** 模块发现结果；查询异常（[Failure]）与「查询成功但没有模块」（[Success] 空列表）必须区分。 */
sealed interface RuntimeModuleDiscoveryResult {
    data class Success(val modules: List<RuntimeModuleDescriptor>) : RuntimeModuleDiscoveryResult

    data class Failure(val detail: String?) : RuntimeModuleDiscoveryResult
}

/**
 * 通过模块声明的 action 发现第三方运行时模块。
 *
 * 依赖 Manifest 的 `<queries>` 声明 [RuntimeModuleContract.ACTION_LAUNCH]（Android 11+ 包可见性）；
 * 未声明时查询会得到空列表而不是异常。只要求包名前缀 + action；不校验签名/版本/包内容。
 * 描述文件读取或解析失败只让 `scan = null`，模块本身仍保留（仍可被发现/启用/启动）。
 */
object RuntimeModuleDiscovery {

    fun discover(context: Context): RuntimeModuleDiscoveryResult {
        val packageManager = context.applicationContext.packageManager
        val intent = Intent(RuntimeModuleContract.ACTION_LAUNCH)
            .addCategory(Intent.CATEGORY_DEFAULT)
        val resolved = try {
            queryLaunchActivities(packageManager, intent)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return RuntimeModuleDiscoveryResult.Failure(e.message ?: e.javaClass.simpleName)
        }

        val candidates = resolved.asSequence()
            .mapNotNull { it.activityInfo }
            .filter { it.packageName.orEmpty().startsWith(RuntimeModuleContract.PACKAGE_PREFIX) }
            .distinctBy { it.packageName.orEmpty() to it.name.orEmpty() }
            .toList()

        val modules = candidates
            .groupBy { it.packageName.orEmpty() }
            .toSortedMap()
            .map { (packageName, activities) -> resolveDescriptor(context, packageManager, packageName, activities) }

        return RuntimeModuleDiscoveryResult.Success(modules)
    }

    private fun queryLaunchActivities(packageManager: PackageManager, intent: Intent): List<ResolveInfo> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.queryIntentActivities(
                intent,
                PackageManager.ResolveInfoFlags.of(PackageManager.GET_META_DATA.toLong()),
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.queryIntentActivities(intent, PackageManager.GET_META_DATA)
        }

    /**
     * 一个包只取一个启动 Activity：优先带 [RuntimeModuleContract.META_MANIFEST] 的，
     * 否则按 Activity 名排序取第一个；列表顺序不受 PackageManager 返回顺序影响。
     */
    private fun resolveDescriptor(
        context: Context,
        packageManager: PackageManager,
        packageName: String,
        activities: List<ActivityInfo>,
    ): RuntimeModuleDescriptor {
        val sorted = activities.sortedBy { it.name.orEmpty() }
        val activity = sorted.firstOrNull {
            it.metaData?.containsKey(RuntimeModuleContract.META_MANIFEST) == true
        } ?: sorted.first()
        val version = readPackageVersion(packageManager, packageName)
        val manifest = readManifest(context, activity)
        return RuntimeModuleDescriptor(
            packageName = packageName,
            label = manifest?.label?.takeIf { it.isNotBlank() } ?: loadLabel(packageManager, activity, packageName),
            versionName = version?.first,
            versionCode = version?.second ?: 0L,
            protocol = manifest?.protocol ?: RuntimeModuleContract.PROTOCOL_VERSION,
            activityName = activity.name.orEmpty(),
            scan = manifest?.scan,
            enabled = false,
        )
    }

    private fun loadLabel(
        packageManager: PackageManager,
        activity: ActivityInfo,
        fallback: String,
    ): String = try {
        activity.loadLabel(packageManager).toString().trim().takeIf { it.isNotEmpty() } ?: fallback
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        fallback
    }

    private fun readPackageVersion(packageManager: PackageManager, packageName: String): Pair<String?, Long>? =
        try {
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(packageName, 0)
            }
            val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                info.versionCode.toLong()
            }
            info.versionName to versionCode
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }

    private fun readManifest(context: Context, activity: ActivityInfo): RuntimeModuleManifest? {
        return try {
            val resourceId = activity.metaData?.getInt(RuntimeModuleContract.META_MANIFEST) ?: 0
            if (resourceId == 0) return null
            val moduleContext = context.createPackageContext(activity.packageName.orEmpty(), 0)
            val bytes = moduleContext.resources.openRawResource(resourceId).use { input ->
                readAtMost(input, RuntimeModuleManifestParser.MAX_BYTES)
            } ?: return null
            RuntimeModuleManifestParser.parse(String(bytes, StandardCharsets.UTF_8))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    /** 读取至多 [limit] 字节；超限返回 null（不截断后继续解析）。 */
    private fun readAtMost(input: InputStream, limit: Int): ByteArray? {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        var zeroReads = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read == 0) {
                // 病态流可能持续返回 0；有限重试后按读取失败处理，避免忙等。
                if (++zeroReads > 8) return null
                continue
            }
            zeroReads = 0
            if (output.size() + read > limit) return null
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }
}
