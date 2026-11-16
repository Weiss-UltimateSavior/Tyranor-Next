package com.tyranor.next.core.engine.runtime

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import com.tyranor.next.core.game.model.ScanGame
import kotlin.coroutines.cancellation.CancellationException

/**
 * 启动交棒结果。`Dispatched` 只表示 Intent 已交给系统，不表示模块已进入游戏或未崩溃。
 *
 * 文案由 UI 层按类型映射；[StartFailed.detail] 只承载底层异常信息，用于日志/排障。
 */
sealed interface RuntimeModuleLaunchResult {
    data object Dispatched : RuntimeModuleLaunchResult

    data object ModuleMissing : RuntimeModuleLaunchResult

    data object ModuleDisabled : RuntimeModuleLaunchResult

    data object EntryMissing : RuntimeModuleLaunchResult

    data object PathUnresolved : RuntimeModuleLaunchResult

    data object SecurityDenied : RuntimeModuleLaunchResult

    data class StartFailed(val detail: String?) : RuntimeModuleLaunchResult
}

/**
 * 通过显式组件把游戏目录交给第三方运行时模块。
 *
 * 本地路径可用时继续传 [RuntimeModuleContract.EXTRA_FOLDER_PATH]；SAF 游戏目录同时通过
 * Intent.data 传递 tree URI、通过 [RuntimeModuleContract.EXTRA_GAME_ROOT] 传递子目录 ID，
 * 并只授予临时只读权限。协议版本保持 v1，旧模块可以继续只消费路径字段。
 */
object RuntimeModuleLauncher {

    fun launch(
        context: Context,
        module: RuntimeModuleDescriptor,
        game: ScanGame,
        folderPath: String?,
    ): RuntimeModuleLaunchResult {
        if (!module.enabled) return RuntimeModuleLaunchResult.ModuleDisabled
        if (module.packageName.isBlank() || module.activityName.isBlank()) return RuntimeModuleLaunchResult.EntryMissing
        val safSource = safSource(game.uri)
        if (folderPath.isNullOrBlank() && safSource == null) return RuntimeModuleLaunchResult.PathUnresolved

        val intent = Intent(RuntimeModuleContract.ACTION_LAUNCH).apply {
            component = ComponentName(module.packageName, module.activityName)
            folderPath?.takeIf { it.isNotBlank() }?.let {
                putExtra(RuntimeModuleContract.EXTRA_FOLDER_PATH, it)
            }
            safSource?.let { source ->
                data = source.treeUri
                putExtra(RuntimeModuleContract.EXTRA_GAME_ROOT, source.rootDocumentId)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
            }
            putExtra(RuntimeModuleContract.EXTRA_GAME_TITLE, game.title)
            putExtra(RuntimeModuleContract.EXTRA_PROTOCOL_VERSION, RuntimeModuleContract.PROTOCOL_VERSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        return try {
            context.applicationContext.startActivity(intent)
            RuntimeModuleLaunchResult.Dispatched
        } catch (e: CancellationException) {
            throw e
        } catch (_: ActivityNotFoundException) {
            RuntimeModuleLaunchResult.EntryMissing
        } catch (_: SecurityException) {
            RuntimeModuleLaunchResult.SecurityDenied
        } catch (e: Exception) {
            RuntimeModuleLaunchResult.StartFailed(e.message)
        }
    }

    internal data class SafSource(
        val treeUri: Uri,
        val rootDocumentId: String,
    )

    /**
     * Rebuild the persisted tree URI from a scanned child document URI.
     *
     * The child document URI is the game identity stored by the scanner. A receiving
     * APK needs the tree URI as Intent.data so the temporary prefix grant covers the
     * game's files below the selected directory.
     */
    internal fun safSource(uriText: String?): SafSource? {
        if (uriText.isNullOrBlank()) return null
        val documentUri = runCatching { Uri.parse(uriText) }.getOrNull() ?: return null
        if (!documentUri.scheme.equals("content", ignoreCase = true)) return null
        if (!DocumentsContract.isTreeUri(documentUri)) return null
        val authority = documentUri.authority ?: return null
        val treeDocumentId = runCatching {
            DocumentsContract.getTreeDocumentId(documentUri)
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: return null
        val rootDocumentId = runCatching {
            DocumentsContract.getDocumentId(documentUri)
        }.getOrDefault(treeDocumentId).takeIf { it.isNotBlank() } ?: treeDocumentId
        val treeUri = runCatching {
            DocumentsContract.buildTreeDocumentUri(authority, treeDocumentId)
        }.getOrNull() ?: return null
        return SafSource(treeUri, rootDocumentId)
    }
}
