package com.tyranor.next.core.game.launch

import com.tyranor.next.core.engine.plugin.EnginePluginBootstrap

/**
 * 引擎启动结果（P0-6 错误协议）：成功 / 失败两类，失败携带可程序化区分的类型，
 * UI 层统一经 `ui/common/LaunchErrorMessages.kt` 映射本地化文案；core 不再拼接展示文案。
 */
sealed interface LaunchResult {

    /** 已成功发起引擎 Activity。 */
    data object Success : LaunchResult

    /** 未发起启动（失败或需用户先处理权限）。 */
    sealed interface Failure : LaunchResult {

        /** 无法把游戏 URI 解析为本地目录路径。 */
        data object GameDirUnresolved : Failure

        /** 已打开「管理所有文件」系统授权页，用户返回后需再次启动。 */
        data object AllFilesAccessRequested : Failure

        /** 无法打开授权页且缺少「管理所有文件」权限。 */
        data object AllFilesAccessMissing : Failure

        /** 内置引擎原生插件保障失败。 */
        data class PluginBootstrapFailed(val reason: EnginePluginBootstrap.Failure) : Failure

        /** KRKR 可移动存储镜像准备失败（[detail] 为底层异常信息，可为空）。 */
        data class KrkrMirrorPrepareFailed(val detail: String?) : Failure

        /** KRKR 存档目录存在但不是目录。 */
        data class KrkrSavePathNotDirectory(val path: String) : Failure

        /** KRKR 存档目录创建失败；[scoped] 区分应用独立目录与游戏目录。 */
        data class KrkrSaveDirUnavailable(val path: String, val scoped: Boolean) : Failure

        /** KRKR 可移动存储镜像内部的 savedata 目录创建失败。 */
        data class KrkrMirrorSaveDirFailed(val path: String) : Failure

        /** 外置 APK 引擎模块启动失败；[message] 为模块层已生成的文案（可为空）。 */
        data class ExternalModuleFailed(val moduleName: String, val message: String?) : Failure

        /** startActivity 抛出异常（[detail] 可为空）。 */
        data class StartFailed(val detail: String?) : Failure
    }
}
