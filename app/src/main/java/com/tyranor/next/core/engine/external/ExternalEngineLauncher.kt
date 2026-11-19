package com.tyranor.next.core.engine.external

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import com.tyranor.next.core.engine.graphics.putDisabledGraphicsRequest
import kotlinx.coroutines.CancellationException

/** 外置 APK 引擎启动与安装状态检查。 */
object ExternalEngineLauncher {
    private const val TAG = "ExternalEngineLaunch"

    /** [prepareRequest] 的类型化结果：失败与就绪二选一，消除「请求与失败双可空」的歧义。 */
    internal sealed class PreparedLaunchRequest {
        /** prepareStep 判定不可启动；启动链应立即返回该类型化失败，不再组装 Intent。 */
        internal class Failed(val failure: ExternalEngineLaunchResult) : PreparedLaunchRequest()

        /** 准备与取消检查完成，[request] 已按判定结果携带具体单向横屏方向。 */
        internal class Ready(val request: ExternalEngineLaunchRequest) : PreparedLaunchRequest()
    }

    fun isPackageInstalled(context: Context, module: ExternalEngineModule): Boolean =
        checkInstallation(context, module) == null

    /**
     * 模块缺失或版本不匹配时返回类型化失败，可用时返回 null。
     * 传入 [launchTarget] 时按该目标要求的最低版本（[ExternalEngineModule.minimumVersionCodeFor]）判定，
     * 不传则只看模块级要求（引擎页、设置页的安装状态）。
     */
    fun checkInstallation(
        context: Context,
        module: ExternalEngineModule,
        launchTarget: String? = null,
    ): ExternalEngineLaunchResult? =
        try {
            val packageManager = context.applicationContext.packageManager
            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getPackageInfo(module.packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(module.packageName, 0)
            }
            val installedVersionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                packageInfo.versionCode.toLong()
            }
            val versionError = versionCodeError(installedVersionCode, module, launchTarget)
            if (versionError != null) {
                ExternalEngineLaunchResult.failure(
                    versionError,
                    moduleNameRes = module.displayNameRes,
                    moduleNameFallback = module.displayName,
                    engineName = module.engine.displayName,
                    installUrl = module.installUrl,
                )
            } else {
                null
            }
        } catch (_: PackageManager.NameNotFoundException) {
            ExternalEngineLaunchResult.failure(
                ExternalEngineErrorCode.PACKAGE_NOT_INSTALLED,
                moduleNameRes = module.displayNameRes,
                moduleNameFallback = module.displayName,
                engineName = module.engine.displayName,
                installUrl = module.installUrl,
            )
        }

    internal fun isVersionCodeSupported(installed: Long, minimum: Long): Boolean =
        minimum <= 0L || installed >= minimum

    internal fun versionCodeError(
        installed: Long,
        module: ExternalEngineModule,
        launchTarget: String? = null,
    ): ExternalEngineErrorCode? {
        val minimum = launchTarget?.let(module::minimumVersionCodeFor) ?: module.minimumVersionCode
        return when {
            !isVersionCodeSupported(installed, minimum) -> ExternalEngineErrorCode.PACKAGE_OUTDATED
            installed > module.maximumVersionCode -> ExternalEngineErrorCode.PACKAGE_VERSION_MISMATCH
            else -> null
        }
    }

    fun launch(
        context: Context,
        module: ExternalEngineModule,
        request: ExternalEngineLaunchRequest,
        orientationResolver: (() -> Int)? = null,
        cancellationCheck: (() -> Unit)? = null,
    ): ExternalEngineLaunchResult {
        if (module.requiresGameDirectoryPath && request.gameDirectoryPath.isBlank()) {
            return ExternalEngineLaunchResult.failure(
                ExternalEngineErrorCode.INVALID_GAME_PATH,
                engineName = request.game.engine.displayName,
            )
        }
        checkInstallation(context, module, request.launchTarget)?.let { return it }
        val prepared = prepareRequest(
            prepareStep = { module.prepareForLaunch(context, request) },
            request = request,
            orientationResolver = orientationResolver,
            cancellationCheck = cancellationCheck,
        )
        val resolvedRequest = when (prepared) {
            is PreparedLaunchRequest.Failed -> return prepared.failure
            is PreparedLaunchRequest.Ready -> prepared.request
        }

        val intent = module.buildLaunchIntent(resolvedRequest).addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
        intent.putDisabledGraphicsRequest(onlyMissing = true)
        return try {
            context.applicationContext.startActivity(intent)
            ExternalEngineLaunchResult.success()
        } catch (_: ActivityNotFoundException) {
            ExternalEngineLaunchResult.failure(
                ExternalEngineErrorCode.ACTIVITY_NOT_FOUND,
                moduleNameRes = module.displayNameRes,
                moduleNameFallback = module.displayName,
                installUrl = module.installUrl,
            )
        } catch (_: SecurityException) {
            ExternalEngineLaunchResult.failure(
                ExternalEngineErrorCode.SECURITY_EXCEPTION,
                moduleNameRes = module.displayNameRes,
                moduleNameFallback = module.displayName,
            )
        } catch (t: Throwable) {
            ExternalEngineLaunchResult.failure(
                ExternalEngineErrorCode.LAUNCH_EXCEPTION,
                moduleNameRes = module.displayNameRes,
                moduleNameFallback = module.displayName,
                detail = t.message,
            )
        }
    }

    /**
     * 执行 [prepareStep] 并在启动前完成方向解析的编排（internal 以便测试直接驱动，生产走 [launch]）。
     *
     * 执行顺序：[prepareStep]（失败即 [PreparedLaunchRequest.Failed]，不触发取消检查与方向解析）
     * → [cancellationCheck]（取消检查点 ①，准备完成后快速失败）→ 方向解析（[orientationResolver]
     * 非 null 时执行并归一化，解析异常回落 request 原方向并经 [onOrientationResolveFailed] 上报）
     * → [cancellationCheck]（取消检查点 ②，闭合解析窗口）→ [PreparedLaunchRequest.Ready]。
     *
     * [cancellationCheck] 是**取消检查点**而非「startActivity 前钩子」：它会被调用两次，
     * 传入的实现必须可重入（生产为 `launchContext.ensureActive()`）。
     */
    internal fun prepareRequest(
        prepareStep: () -> ExternalEngineLaunchResult?,
        request: ExternalEngineLaunchRequest,
        orientationResolver: (() -> Int)? = null,
        cancellationCheck: (() -> Unit)? = null,
        onOrientationResolveFailed: (Throwable) -> Unit = {
            Log.w(TAG, "orientation resolver failed, fall back to request orientation", it)
        },
    ): PreparedLaunchRequest {
        prepareStep()?.let { return PreparedLaunchRequest.Failed(it) }
        // 外置模块准备（如 RPG Maker 创建 RTP 环境、写 configuration.json）完成后再检查取消
        // 并解析此刻握持方向，避免准备耗时期间设备旋转导致沿用过期方向。
        cancellationCheck?.invoke()
        val resolvedRequest = if (orientationResolver != null) {
            request.copy(
                orientation = resolveOrientationSafely(
                    orientationResolver,
                    request,
                    onOrientationResolveFailed,
                ),
            )
        } else {
            request
        }
        cancellationCheck?.invoke()
        return PreparedLaunchRequest.Ready(resolvedRequest)
    }

    /**
     * 方向解析容错（ROB-08）：解析器抛出的取消异常原样传播（取消不是失败，类型化失败路径
     * 不吞取消）；其余 [RuntimeException] 记日志后回落请求原方向——解析失败不应让整个启动
     * 失败，模块宿主拿到请求原方向仍可正常运行。
     */
    private fun resolveOrientationSafely(
        orientationResolver: () -> Int,
        request: ExternalEngineLaunchRequest,
        onFailed: (Throwable) -> Unit,
    ): Int =
        try {
            ExternalEngineContract.normalizeOrientation(orientationResolver())
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: RuntimeException) {
            onFailed(failure)
            request.orientation
        }

    fun openInstallPage(context: Context, module: ExternalEngineModule): Boolean =
        module.installUrl?.let { openInstallPage(context, it) } ?: false

    fun openInstallPage(context: Context, url: String): Boolean {
        if (url.isBlank()) return false
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.applicationContext.startActivity(intent)
            true
        } catch (_: Throwable) {
            false
        }
    }
}
