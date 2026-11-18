package com.tyranor.next.core.engine.runtime

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Process
import android.provider.DocumentsContract
import com.tyranor.next.core.engine.runtime.RuntimeModuleLaunchDispatch.SafSource
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
 * Intent.data 传递精确游戏 tree URI、通过 [RuntimeModuleContract.EXTRA_GAME_ROOT] 传递该 tree 的根 ID，
 * 并只授予临时只读权限。父 tree 不转授；协议版本保持 v1，旧模块可以继续只消费路径字段。
 */
object RuntimeModuleLauncher {

    fun launch(
        context: Context,
        module: RuntimeModuleDescriptor,
        game: ScanGame,
        folderPath: String?,
        settingsPayload: String? = null,
    ): RuntimeModuleLaunchResult {
        if (!module.enabled) return RuntimeModuleLaunchResult.ModuleDisabled
        if (module.packageName.isBlank() || module.activityName.isBlank()) return RuntimeModuleLaunchResult.EntryMissing
        if (module.settings != null && settingsPayload == null) {
            return RuntimeModuleLaunchResult.StartFailed("runtime module settings payload missing")
        }
        if (module.settings != null && settingsPayload != null &&
            settingsPayload.toByteArray(Charsets.UTF_8).size > RuntimeModuleSettingsParser.MAX_PAYLOAD_BYTES
        ) {
            return RuntimeModuleLaunchResult.StartFailed("runtime module settings payload exceeds 48 KiB")
        }
        val applicationContext = context.applicationContext
        return RuntimeModuleLaunchDispatch.launch(
            folderPath = folderPath,
            safSource = safSource(game.uri),
            persistedSafSources = {
                applicationContext.contentResolver.persistedUriPermissions.asSequence()
                    .filter { it.isReadPermission }
                    .mapNotNull { permission ->
                        // 只复用系统记录的真实授权 URI，不从父 tree 构造游戏 retree。
                        safSource(permission.uri.toString())?.takeIf { it.treeUri == permission.uri }
                    }
            },
            canReadSafSource = { source ->
                applicationContext.checkUriPermission(
                    DocumentsContract.buildDocumentUriUsingTree(source.treeUri, source.rootDocumentId),
                    Process.myPid(),
                    Process.myUid(),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                ) == PackageManager.PERMISSION_GRANTED
            },
            dispatch = { path, source ->
                dispatch(applicationContext, module, game, path, source, settingsPayload)
            },
        )
    }

    private fun dispatch(
        context: Context,
        module: RuntimeModuleDescriptor,
        game: ScanGame,
        folderPath: String?,
        safSource: SafSource<Uri>?,
        settingsPayload: String?,
    ): RuntimeModuleLaunchResult {
        // 每次新建 Intent：路径回退不能残留 data、GAME_ROOT 或 URI 授权 flags。
        val intent = Intent(RuntimeModuleContract.ACTION_LAUNCH).apply {
            component = ComponentName(module.packageName, module.activityName)
            folderPath?.let {
                putExtra(RuntimeModuleContract.EXTRA_FOLDER_PATH, it)
            }
            safSource?.let { source ->
                data = source.treeUri
                putExtra(RuntimeModuleContract.EXTRA_GAME_ROOT, source.rootDocumentId)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
            }
            putExtra(RuntimeModuleContract.EXTRA_GAME_TITLE, game.title)
            putExtra(RuntimeModuleContract.EXTRA_PROTOCOL_VERSION, RuntimeModuleContract.PROTOCOL_VERSION)
            if (module.settings != null && settingsPayload != null) {
                putExtra(RuntimeModuleContract.EXTRA_SETTINGS, settingsPayload)
            }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        return try {
            context.startActivity(intent)
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

    /**
     * Parse the selected tree and game document identities from a scanned URI.
     *
     * Parsing does not establish permission. A parent tree is only a lookup key:
     * dispatch requires an existing exact game tree so its prefix grant cannot
     * include sibling games.
     */
    internal fun safSource(uriText: String?): SafSource<Uri>? {
        if (uriText.isNullOrBlank()) return null
        return try {
            val documentUri = Uri.parse(uriText)
            if (!documentUri.scheme.equals("content", ignoreCase = true)) return null
            if (!DocumentsContract.isTreeUri(documentUri)) return null
            val authority = documentUri.authority ?: return null
            val treeDocumentId = DocumentsContract.getTreeDocumentId(documentUri)
                .takeIf { it.isNotBlank() } ?: return null
            val segments = documentUri.pathSegments
            val rootDocumentId = when {
                segments.size == 2 -> treeDocumentId
                segments.size == 4 && segments[2] == "document" ->
                    DocumentsContract.getDocumentId(documentUri).takeIf { it.isNotBlank() } ?: return null
                else -> return null
            }
            val treeUri = DocumentsContract.buildTreeDocumentUri(authority, treeDocumentId)
            SafSource(treeUri, authority, treeDocumentId, rootDocumentId)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }
}
