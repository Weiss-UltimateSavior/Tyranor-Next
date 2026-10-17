package com.tyranor.next.core.game.model

import java.io.File
import java.util.Locale

/**
 * System35（xsystem35）路径纯函数：启动侧 `EngineLauncher` 与存档镜像侧 `GameSaveManager`
 * 共用，保证「引擎实际读写的目录」与「存档管理读取的目录」永远同一处（单一实现，禁止两侧各自推导）。
 *
 * 目录语义（对齐上游 `GameInstaller`/`xsystem35.c`）：
 * - 游戏目录 = 含场景档（`S*<字母>.ALD` 或 `adisk.ald`）的目录：普通形态即游戏根，
 *   CD 镜像解出形态为 `<根>/GAMEDATA/`（大小写不敏感），宿主把该目录作为 `-gamedir`（chdir 后的 CWD）；
 * - 独立存档（默认开）= `<externalFilesDir>/save/<safeSaveName(游戏根)>`；
 *   关闭 = `<生效游戏目录>/save`（GAMEDATA 形态即 GAMEDATA/save）。
 */
object Xs35Paths {

    /** CD 镜像解出形态的固定子目录名（上游 `GameInstaller` 写出的布局标记）。 */
    const val GAMEDATA = "GAMEDATA"

    /**
     * 生效游戏目录：含场景档的目录优先（`root` 或大小写不敏感的 `gamedata` 子目录）；
     * 否则含任意 `*.ALD`；都没有时退回 `root`（启动预检会按场景档拦下）。
     */
    fun effectiveGameDir(root: File): File {
        val gamedata = findGamedataDir(root)
        if (hasScenarioAld(root)) return root
        if (gamedata != null && hasScenarioAld(gamedata)) return gamedata
        if (hasAnyAld(root)) return root
        if (gamedata != null && hasAnyAld(gamedata)) return gamedata
        return root
    }

    /**
     * 场景档判定：引擎实际接受的场景组合 = `S` + 字母（`a..z`）的 `*.ALD`，或特例 `adisk.ald`
     * （上游 `gameresource.c` 在通用分支前专门把它计入场景档，引擎可运行）。
     * `S*.ALD` 形态要求：文件名（lowercase）以 `.ald` 结尾、长度 ≥ 6、倒数第 6 位为 `s`、
     * 倒数第 5 位为字母 `a..z`（对齐上游 `toupper(x) - 'A'` 的有效范围，排除 `S1.ALD` 之类）。
     * 与 engine 侧宿主 `Xs35Activity.containsScenarioScript` 同口径，双向互指，修改须同步。
     */
    fun hasScenarioAld(dir: File): Boolean = listAldFiles(dir).any { name ->
        name == "adisk.ald" || (
            name.length >= 6 &&
                name[name.length - 6] == 's' &&
                name[name.length - 5] in 'a'..'z'
            )
    }

    /** 任意 `*.ALD`（大小写不敏感）存在判定，非递归。 */
    fun hasAnyAld(dir: File): Boolean = listAldFiles(dir).isNotEmpty()

    /**
     * 独立存档目录：`<scopedRoot>/save/<safeSaveName(rootPath)>`（scopedRoot 由调用方传
     * `getExternalFilesDir(null)`）。外部存储不可用（为 null）时由调用方按类型化失败处理，
     * **绝不回退游戏根**；本函数只负责路径推导，不让外部存储可用性漏进推导逻辑。
     */
    fun scopedSaveDir(scopedRoot: File, rootPath: String): File =
        File(File(scopedRoot, "save"), GamePathUtils.safeSaveName(rootPath))

    /** 非独立存档目录：`<生效游戏目录>/save`（GAMEDATA 形态下即 `GAMEDATA/save`）。 */
    fun gameDirSaveDir(effectiveDir: File): File = File(effectiveDir, "save")

    /**
     * 定位 `GAMEDATA` 子目录（大小写不敏感，如 `GAMEDATA/`/`gamedata/`）；不存在返回 null。
     */
    private fun findGamedataDir(root: File): File? = root.listFiles().orEmpty()
        .firstOrNull { it.isDirectory && it.name.equals(GAMEDATA, ignoreCase = true) }

    /** 目录内 `*.ald` 文件名（lowercase、非递归、仅文件）。 */
    private fun listAldFiles(dir: File): List<String> = dir.listFiles().orEmpty()
        .filter { !it.isDirectory }
        .map { it.name.lowercase(Locale.ROOT) }
        .filter { it.endsWith(".ald") }
}
