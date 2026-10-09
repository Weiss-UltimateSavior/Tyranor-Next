package com.tyranor.next.core.game.launch

import com.tyranor.next.core.engine.external.ExternalEmulatorLauncher
import com.tyranor.next.core.engine.external.ExternalEngineLaunchResult
import com.tyranor.next.core.engine.plugin.EnginePluginBootstrap

/**
 * KRKR 可移动存储镜像准备的失败阶段（app 侧视图模型）。
 *
 * 是 engine 侧 `KrSafMirror.Stage`（5 个细分阶段）按**用户处置方式**的收窄映射，
 * 不复用其类型：ui 层按三层架构不得 import `bridge` 包，core 负责在这一层做映射
 * （见 `EngineLauncher`）。
 */
enum class MirrorPrepareStage {
    /** 无法解析 SD 卡/SAF 源目录（授权失效或目录已被移动）→ 需重新授权。 */
    SOURCE_UNRESOLVED,

    /** 无法创建镜像根目录 / SAF 索引目录 / 镜像子项 → 需检查存储空间与权限。 */
    CREATE_FAILED,
}

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

        /** KRKR 可移动存储镜像准备失败；携带类型化阶段（文案由 UI 映射）。 */
        data class KrkrMirrorPrepareFailed(
            val stage: MirrorPrepareStage,
            val name: String?,
        ) : Failure

        /** KRKR 存档目录存在但不是目录。 */
        data class KrkrSavePathNotDirectory(val path: String) : Failure

        /** KRKR 存档目录创建失败；[scoped] 区分应用独立目录与游戏目录。 */
        data class KrkrSaveDirUnavailable(val path: String, val scoped: Boolean) : Failure

        /** KRKR 可移动存储镜像内部的 savedata 目录创建失败。 */
        data class KrkrMirrorSaveDirFailed(val path: String) : Failure

        /** 外置 APK 引擎模块启动失败；携带模块层类型化错误（文案由 UI 映射）。 */
        data class ExternalModuleFailed(val result: ExternalEngineLaunchResult) : Failure

        /** 外置主机模拟器跳转失败（PPSSPP / Eden / Winlator）；携带目标与错误码，文案由 UI 映射。 */
        data class ExternalEmulatorFailed(val result: ExternalEmulatorLauncher.Result) : Failure

        /** YU-RIS：游戏目录内没有可启动的 .exe（可由单游戏「启动文件」手动指定）。 */
        data object YurisExeMissing : Failure

        /** 手动添加的安卓应用不存在或不可启动（未安装 / 已卸载 / 无启动入口）。 */
        data object AndroidAppMissing : Failure

        /** startActivity 抛出异常（[detail] 可为空）。 */
        data class StartFailed(val detail: String?) : Failure
    }
}
