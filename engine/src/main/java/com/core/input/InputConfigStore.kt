package com.core.input

import android.content.Context
import com.core.engine.EnginePrefs
import java.io.File
import org.json.JSONObject

/**
 * 输入配置存储（engine 侧事实源，app 侧只经本类读写同一批文件/prefs）。
 *
 *  - 方案文件：`filesDir/input/profiles/<id>.json`（engine 与 app 均为同 UID、同一
 *    filesDir，跨进程共享）；
 *  - 手柄映射：`filesDir/input/gamepad_map.json`；
 *  - 开关与方案选择：全局存 [EnginePrefs.APP_PREFS]（`input_*` 键，app 设置页写入），
 *    单游戏覆盖存 [EnginePrefs.GAME_OVERRIDES_PREFS] 的整条 JSON blob（键同名，
 *    app 侧 `PerGameSettingsStore` 写入；engine 只读）。
 *
 * 写入一律「临时文件 + rename」原子替换，避免跨进程读到半截 JSON。
 */
object InputConfigStore {

    /** 全局与单游戏覆盖共用的键名（三处字面量锚定：本文件 / PerGameSettingsStore / GameOverridePartitions）。 */
    const val KEY_PAD_ENABLED = "input_pad_enabled"
    const val KEY_GAMEPAD_ENABLED = "input_gamepad_enabled"
    const val KEY_PROFILE_ID = "input_profile_id"

    /** 旧触屏手柄键（迁移后仅作读取来源，引擎与新代码不再写入）。 */
    const val LEGACY_TOUCH_PAD_KEY = "touch_pad_config"
    const val LEGACY_TOUCH_PAD_PRESETS_KEY = "touch_pad_presets"

    const val DEFAULT_PROFILE_ID = PadProfile.BUILTIN_DEFAULT_ID

    private const val INPUT_DIR = "input"
    private const val PROFILE_DIR = "profiles"
    private const val GAMEPAD_MAP_FILE = "gamepad_map.json"
    private const val FAB_PREFS = "tyranor_input"
    private const val KEY_FAB_X = "fab_x"
    private const val KEY_FAB_Y = "fab_y"
    private const val KEY_PAD_VISIBLE = "pad_visible"

    private val ID_PATTERN = Regex("[A-Za-z0-9_-]{1,32}")

    /** 原子写的临时文件名序号：仅带 pid 时，同进程内两次并发写会共用同一个临时文件。 */
    private val tmpCounter = java.util.concurrent.atomic.AtomicInteger(0)

    /** 生效输入设置（单游戏覆盖 ?: 全局 ?: 内置默认）。 */
    data class InputSettings(
        val padEnabled: Boolean,
        val gamepadEnabled: Boolean,
        val profileId: String,
    )

    // ---------- 路径 ----------

    fun profilesDir(context: Context): File = File(File(context.filesDir, INPUT_DIR), PROFILE_DIR)

    fun profileFile(context: Context, id: String): File = File(profilesDir(context), "$id.json")

    fun gamepadMapFile(context: Context): File = File(File(context.filesDir, INPUT_DIR), GAMEPAD_MAP_FILE)

    fun sanitizeProfileId(raw: String?): String? =
        raw?.trim()?.takeIf { ID_PATTERN.matches(it) }

    // ---------- 方案读写 ----------

    fun readProfile(context: Context, id: String): PadProfile? {
        val safeId = sanitizeProfileId(id) ?: return null
        if (safeId == DEFAULT_PROFILE_ID) {
            // 内置默认方案允许被用户编辑：优先读同名文件，缺失回退出厂布局
            val file = profileFile(context, safeId)
            if (!file.isFile) return PadProfile.defaultProfile()
            return PadProfile.parse(runCatching { file.readText() }.getOrNull())
                ?: PadProfile.defaultProfile()
        }
        val file = profileFile(context, safeId)
        if (!file.isFile) return null
        return PadProfile.parse(runCatching { file.readText() }.getOrNull())
    }

    /** 读取方案；缺失/损坏时回退出厂默认（id 归一到内置默认）。 */
    fun readProfileOrBuiltin(context: Context, id: String): PadProfile =
        readProfile(context, id) ?: PadProfile.defaultProfile()

    fun writeProfile(context: Context, profile: PadProfile): Boolean {
        val safeId = sanitizeProfileId(profile.id) ?: return false
        val dir = profilesDir(context)
        if (!dir.isDirectory && !dir.mkdirs()) return false
        return atomicWrite(profileFile(context, safeId), profile.copy(id = safeId).toJson())
    }

    fun deleteProfile(context: Context, id: String): Boolean {
        val safeId = sanitizeProfileId(id) ?: return false
        if (safeId == DEFAULT_PROFILE_ID) return false
        return profileFile(context, safeId).delete()
    }

    /** 全部用户方案（按名称排序）；内置默认排最前，不读盘。 */
    fun listProfiles(context: Context): List<PadProfile> {
        val result = ArrayList<PadProfile>()
        result.add(readProfile(context, DEFAULT_PROFILE_ID) ?: PadProfile.defaultProfile())
        val dir = profilesDir(context)
        if (dir.isDirectory) {
            dir.listFiles { file -> file.isFile && file.name.endsWith(".json") }
                ?.mapNotNull { file ->
                    val id = file.name.removeSuffix(".json")
                    if (id == DEFAULT_PROFILE_ID) null
                    else PadProfile.parse(runCatching { file.readText() }.getOrNull())
                }
                ?.sortedBy { it.name.lowercase() }
                ?.let { result.addAll(it) }
        }
        return result
    }

    // ---------- 手柄映射读写 ----------

    fun readGamepadMap(context: Context): GamepadMap {
        val file = gamepadMapFile(context)
        if (!file.isFile) return GamepadMap.default()
        return GamepadMap.parse(runCatching { file.readText() }.getOrNull()) ?: GamepadMap.default()
    }

    fun writeGamepadMap(context: Context, map: GamepadMap): Boolean {
        val file = gamepadMapFile(context)
        val dir = file.parentFile ?: return false
        if (!dir.isDirectory && !dir.mkdirs()) return false
        return atomicWrite(file, map.toJson())
    }

    // ---------- 生效设置 ----------

    /**
     * 单游戏覆盖 ?: 全局 ?: 内置默认。
     *
     * 覆盖字段必须用 `has()` 判定存在性：`org.json` 的 `optBoolean(name)` 在键缺失时
     * 返回 false（而非 null），直接 `optBoolean(name) ?: global` 会让「未覆盖」被当成
     * 「显式关闭」——任何存过单游戏设置（如保存 ONSS 覆盖）的游戏都会静默失去虚拟按键。
     */
    fun resolve(context: Context, gameId: String): InputSettings {
        val blob = if (gameId.isNotBlank()) gameOverrideBlob(context, gameId) else null
        val global = context.getSharedPreferences(EnginePrefs.APP_PREFS, Context.MODE_PRIVATE)
        return resolveSettings(
            blob = blob,
            globalPadEnabled = global.getBoolean(KEY_PAD_ENABLED, true),
            globalGamepadEnabled = global.getBoolean(KEY_GAMEPAD_ENABLED, true),
            globalProfileId = global.getString(KEY_PROFILE_ID, null),
        )
    }

    /** 生效设置的纯函数部分（不依赖 Context，便于单测锚定覆盖语义）。 */
    internal fun resolveSettings(
        blob: JSONObject?,
        globalPadEnabled: Boolean,
        globalGamepadEnabled: Boolean,
        globalProfileId: String?,
    ): InputSettings {
        val padEnabled = if (blob?.has(KEY_PAD_ENABLED) == true) {
            blob.optBoolean(KEY_PAD_ENABLED)
        } else {
            globalPadEnabled
        }
        val gamepadEnabled = if (blob?.has(KEY_GAMEPAD_ENABLED) == true) {
            blob.optBoolean(KEY_GAMEPAD_ENABLED)
        } else {
            globalGamepadEnabled
        }
        val profileId = (if (blob?.has(KEY_PROFILE_ID) == true) blob.optString(KEY_PROFILE_ID) else null)
            ?.takeIf { it.isNotBlank() }
            ?: globalProfileId?.takeIf { it.isNotBlank() }
            ?: DEFAULT_PROFILE_ID
        return InputSettings(
            padEnabled = padEnabled,
            gamepadEnabled = gamepadEnabled,
            profileId = sanitizeProfileId(profileId) ?: DEFAULT_PROFILE_ID,
        )
    }

    /** 旧触屏手柄数据（供 app 侧一次性迁移读取）；两键均缺失返回 null。 */
    fun readLegacyTouchPad(context: Context, gameId: String): Pair<String?, String?>? {
        if (gameId.isBlank()) return null
        val blob = gameOverrideBlob(context, gameId) ?: return null
        val config = blob.optString(LEGACY_TOUCH_PAD_KEY).takeIf { it.isNotBlank() }
        val presets = blob.optString(LEGACY_TOUCH_PAD_PRESETS_KEY).takeIf { it.isNotBlank() }
        if (config == null && presets == null) return null
        return config to presets
    }

    // ---------- FAB / 显隐偏好（引擎自用） ----------

    fun fabPosition(context: Context): Pair<Float, Float> {
        val prefs = context.getSharedPreferences(FAB_PREFS, Context.MODE_PRIVATE)
        return prefs.getFloat(KEY_FAB_X, 0.055f) to prefs.getFloat(KEY_FAB_Y, 0.48f)
    }

    fun saveFabPosition(context: Context, x: Float, y: Float) {
        context.getSharedPreferences(FAB_PREFS, Context.MODE_PRIVATE)
            .edit().putFloat(KEY_FAB_X, x).putFloat(KEY_FAB_Y, y).apply()
    }

    fun isPadVisible(context: Context): Boolean =
        context.getSharedPreferences(FAB_PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_PAD_VISIBLE, true)

    fun setPadVisible(context: Context, visible: Boolean) {
        context.getSharedPreferences(FAB_PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_PAD_VISIBLE, visible).apply()
    }

    // ---------- 内部 ----------

    private fun gameOverrideBlob(context: Context, gameId: String): JSONObject? = runCatching {
        context.getSharedPreferences(EnginePrefs.GAME_OVERRIDES_PREFS, Context.MODE_PRIVATE)
            .getString(gameId, null)
            ?.let { JSONObject(it) }
    }.getOrNull()

    /**
     * 原子落盘：唯一临时名 + fsync + rename 覆盖。
     *
     * 不用「先 delete 再 rename」：POSIX rename 本身原子替换已存在目标，先删会留下
     * 「文件不存在」窗口（进程被杀即丢配置）。临时名带进程号 + 进程内序号，避免多进程
     * 以及同进程并发写同一目标时互相截断。rename 失败时兜底重试一次。
     */
    private fun atomicWrite(target: File, content: String): Boolean {
        val dir = target.parentFile ?: return false
        val tmp = File(dir, ".${target.name}.${android.os.Process.myPid()}.${tmpCounter.incrementAndGet()}.tmp")
        return runCatching {
            java.io.FileOutputStream(tmp).use { output ->
                output.write(content.toByteArray(Charsets.UTF_8))
                output.flush()
                output.fd.sync()
            }
            if (tmp.renameTo(target)) return@runCatching true
            // 个别文件系统上 rename 覆盖失败：删除目标后重试
            if (target.exists() && target.delete() && tmp.renameTo(target)) {
                true
            } else {
                tmp.delete()
                false
            }
        }.getOrDefault(false)
    }
}
