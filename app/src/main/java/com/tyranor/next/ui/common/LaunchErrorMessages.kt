package com.tyranor.next.ui.common

import android.content.Context
import com.tyranor.next.R
import com.tyranor.next.core.engine.plugin.EnginePluginBootstrap
import com.tyranor.next.core.game.launch.LaunchResult
import com.tyranor.next.core.i18n.AppLocaleController

/**
 * 启动结果 → 用户文案映射（P0-6）：core 只返回 [LaunchResult] 类型，
 * 本地化文案统一在 UI 层组装，调用点无需自行 when 分支。
 */

/** 成功返回 null；失败返回可直接展示的本地化文案。 */
fun LaunchResult.userMessage(context: Context): String? = when (this) {
    LaunchResult.Success -> null
    is LaunchResult.Failure -> toUserMessage(context)
}

private fun LaunchResult.Failure.toUserMessage(context: Context): String {
    val localized = AppLocaleController.wrap(context)
    return when (this) {
        LaunchResult.Failure.GameDirUnresolved ->
            localized.getString(R.string.launch_resolve_local_dir_failed)

        LaunchResult.Failure.AllFilesAccessRequested ->
            localized.getString(R.string.launch_all_files_access_request)

        LaunchResult.Failure.AllFilesAccessMissing ->
            localized.getString(R.string.launch_all_files_access_missing)

        is LaunchResult.Failure.PluginBootstrapFailed -> when (val reason = reason) {
            is EnginePluginBootstrap.Failure.UnknownEngine ->
                localized.getString(R.string.plugin_unknown_engine, reason.engineId)
            EnginePluginBootstrap.Failure.InstallFailed ->
                localized.getString(R.string.plugin_install_failed)
        }

        is LaunchResult.Failure.KrkrMirrorPrepareFailed ->
            localized.getString(R.string.launch_prepare_krkr_sd_mirror_failed)

        is LaunchResult.Failure.KrkrSavePathNotDirectory ->
            localized.getString(R.string.launch_krkr_save_path_not_dir, path)

        is LaunchResult.Failure.KrkrSaveDirUnavailable ->
            if (scoped) {
                localized.getString(R.string.launch_create_krkr_scoped_save_failed, path)
            } else {
                localized.getString(R.string.launch_create_krkr_save_failed, path)
            }

        is LaunchResult.Failure.KrkrMirrorSaveDirFailed ->
            localized.getString(R.string.launch_create_krkr_mirror_save_failed)

        is LaunchResult.Failure.ExternalModuleFailed ->
            message ?: localized.getString(R.string.launch_external_module_failed, moduleName)

        is LaunchResult.Failure.StartFailed ->
            detail ?: localized.getString(R.string.launch_failed)
    }
}
