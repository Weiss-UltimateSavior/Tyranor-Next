package com.tyranor.next.core.input

import android.content.Context
import com.core.input.GamepadMap
import com.core.input.InputConfigStore
import com.core.input.PadProfile
import com.tyranor.next.core.settings.EngineSettingsStore
import com.tyranor.next.core.settings.PerGameSettingsStore
import org.json.JSONObject

/**
 * 输入重映射（虚拟按键方案 + 手柄映射）应用侧仓储。
 *
 * 存储位置与 engine 侧 [InputConfigStore] 同源（同一 filesDir 下的 input/ 目录与
 * 共享 prefs 键），本类只做应用侧读写门面：设置页、方案管理与旧数据迁移都经此收口，
 * 不直接碰文件与 prefs 键名。
 *
 * 数据分类：
 *  - 方案文件：`<filesDir>/input/profiles/<id>.json`（游戏内编辑保存与设置页共同读写）；
 *  - 手柄映射：`<filesDir>/input/gamepad_map.json`；
 *  - 开关/方案选择：全局存 tyranor_prefs（[EngineSettingsStore]），单游戏覆盖存
 *    game_overrides blob（[PerGameSettingsStore]，键名与 engine InputConfigStore 锚定）。
 */
object InputRemapRepository {

    /** 生效设置（单游戏覆盖 ?: 全局）。 */
    data class Settings(
        val padEnabled: Boolean,
        val gamepadEnabled: Boolean,
        val profileId: String,
    )

    // ---------- 全局开关 / 方案选择 ----------

    fun globalPadEnabled(context: Context): Boolean = EngineSettingsStore.isInputPadEnabled(context)

    fun setGlobalPadEnabled(context: Context, enabled: Boolean) =
        EngineSettingsStore.setInputPadEnabled(context, enabled)

    fun globalGamepadEnabled(context: Context): Boolean = EngineSettingsStore.isInputGamepadEnabled(context)

    fun setGlobalGamepadEnabled(context: Context, enabled: Boolean) =
        EngineSettingsStore.setInputGamepadEnabled(context, enabled)

    fun globalProfileId(context: Context): String = EngineSettingsStore.getInputProfileId(context)

    fun setGlobalProfileId(context: Context, id: String) =
        EngineSettingsStore.setInputProfileId(context, id)

    // ---------- 单游戏覆盖（null = 跟随全局） ----------

    fun gamePadEnabledOverride(context: Context, gameId: String): Boolean? =
        PerGameSettingsStore.getBool(context, gameId, PerGameSettingsStore.F_INPUT_PAD_ENABLED)

    fun setGamePadEnabledOverride(context: Context, gameId: String, value: Boolean?) =
        PerGameSettingsStore.setBool(context, gameId, PerGameSettingsStore.F_INPUT_PAD_ENABLED, value)

    fun gameGamepadEnabledOverride(context: Context, gameId: String): Boolean? =
        PerGameSettingsStore.getBool(context, gameId, PerGameSettingsStore.F_INPUT_GAMEPAD_ENABLED)

    fun setGameGamepadEnabledOverride(context: Context, gameId: String, value: Boolean?) =
        PerGameSettingsStore.setBool(context, gameId, PerGameSettingsStore.F_INPUT_GAMEPAD_ENABLED, value)

    fun gameProfileIdOverride(context: Context, gameId: String): String? =
        PerGameSettingsStore.getStr(context, gameId, PerGameSettingsStore.F_INPUT_PROFILE_ID)
            ?.takeIf { it.isNotBlank() }

    fun setGameProfileIdOverride(context: Context, gameId: String, value: String?) =
        PerGameSettingsStore.setStr(
            context, gameId, PerGameSettingsStore.F_INPUT_PROFILE_ID,
            value?.takeIf { it.isNotBlank() },
        )

    /** 该游戏生效设置（与 engine 侧解析同源）。 */
    fun resolve(context: Context, gameId: String): Settings {
        val resolved = InputConfigStore.resolve(context, gameId)
        return Settings(
            padEnabled = resolved.padEnabled,
            gamepadEnabled = resolved.gamepadEnabled,
            profileId = resolved.profileId,
        )
    }

    // ---------- 方案 ----------

    fun listProfiles(context: Context): List<PadProfile> = InputConfigStore.listProfiles(context)

    fun readProfile(context: Context, id: String): PadProfile? = InputConfigStore.readProfile(context, id)

    fun writeProfile(context: Context, profile: PadProfile): Boolean =
        InputConfigStore.writeProfile(context, profile)

    fun deleteProfile(context: Context, id: String): Boolean = InputConfigStore.deleteProfile(context, id)

    /** 复制方案（副本名追加“副本”，id 由调用方保证唯一；默认方案也可复制）。 */
    fun duplicateProfile(context: Context, source: PadProfile, newId: String, newName: String): PadProfile? {
        if (InputConfigStore.sanitizeProfileId(newId) == null) return null
        val copy = source.copy(id = newId, name = newName)
        return copy.takeIf { writeProfile(context, it) }
    }

    /**
     * 重置方案布局为出厂默认（保留方案 id 与名称）。
     *
     * 名称必须沿用原值：core 层禁止硬编码可展示文案，默认方案的展示名由界面侧
     * 经 `R.string.input_settings_profile_default` 映射。
     */
    fun resetProfileToDefaults(context: Context, id: String): Boolean {
        val target = InputConfigStore.sanitizeProfileId(id) ?: return false
        val name = readProfile(context, target)?.name ?: target
        return writeProfile(context, PadProfile.defaultProfile(target, name))
    }

    // ---------- 手柄映射 ----------

    fun readGamepadMap(context: Context): GamepadMap = InputConfigStore.readGamepadMap(context)

    fun writeGamepadMap(context: Context, map: GamepadMap): Boolean =
        InputConfigStore.writeGamepadMap(context, map)

    fun resetGamepadMap(context: Context): GamepadMap {
        val defaults = GamepadMap.default()
        writeGamepadMap(context, defaults)
        return defaults
    }

    // ---------- 旧数据迁移 ----------

    /**
     * 旧 `__touch_pad.js` 布局/预设 → 新方案文件（一次性）。
     *
     * 触发条件：该游戏存在旧键且方案目录尚无用户方案（内置 default 不计）。
     * 迁移完成后把该游戏的方案选择指向迁移结果；返回迁移出的方案 id 列表（无操作返回空）。
     */
    fun migrateLegacyIfNeeded(context: Context, gameId: String): List<String> {
        val legacy = InputConfigStore.readLegacyTouchPad(context, gameId) ?: return emptyList()
        val profiles = PadProfile.migrateLegacy(legacy.first, legacy.second)
        if (profiles.isEmpty()) return emptyList()
        val migrated = ArrayList<String>()
        profiles.forEach { profile ->
            if (writeProfile(context, profile)) migrated.add(profile.id)
        }
        if (migrated.isEmpty()) return emptyList()
        // 游戏未显式选择方案（或选择的方案不存在）时，指向迁移出的默认方案
        val explicitOverride = gameProfileIdOverride(context, gameId)
        val globalId = globalProfileId(context)
        if (explicitOverride == null && globalId == InputConfigStore.DEFAULT_PROFILE_ID) {
            setGlobalProfileId(context, migrated.first())
        }
        return migrated
    }

    /** 方案目录中是否存在用户自建方案（内置 default 不计）。 */
    fun hasUserProfiles(context: Context): Boolean =
        listProfiles(context).any { it.id != InputConfigStore.DEFAULT_PROFILE_ID }

    /** 方案名去重（同 id 或同名时追加序号；上限 24 字符）。 */
    fun uniqueProfileName(context: Context, base: String): String {
        val existing = listProfiles(context).map { it.name }.toSet()
        if (base !in existing) return base.take(24)
        var index = 2
        while ("$base $index" in existing) index++
        return "$base $index".take(24)
    }

    /** 新的唯一方案 id（时间戳 + 序号，避免与已有冲突）。 */
    fun newProfileId(context: Context): String {
        val base = "p" + System.currentTimeMillis().toString(36)
        var candidate = base
        var index = 1
        while (InputConfigStore.profileFile(context, candidate).isFile) {
            candidate = base + "-" + index++
        }
        return candidate
    }

    /** 导出为 JSON 文本（与方案文件同格式，便于分享/备份）。 */
    fun exportProfile(profile: PadProfile): String = JSONObject(profile.toJson()).toString(2)

    /** 从 JSON 文本导入（校验 schema/id；id 冲突由调用方处理）。 */
    fun importProfile(raw: String?): PadProfile? = PadProfile.parse(raw)
}
