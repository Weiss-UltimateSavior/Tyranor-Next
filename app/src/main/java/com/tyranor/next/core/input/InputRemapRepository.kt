package com.tyranor.next.core.input

import android.content.Context
import com.core.input.GamepadBinding
import com.core.input.GamepadButtons
import com.core.input.GamepadMap
import com.core.input.InputConfigStore
import com.core.input.InputKeyCatalog
import com.core.input.PadProfile
import com.core.input.StickBinding
import com.tyranor.next.core.engine.EngineType
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
 *  - 方案文件：`<filesDir>/input/profiles/<id>.json`（游戏内编辑保存与设置页共同读写；
 *    旧触屏手柄迁移产物用 `migrated-<游戏作用域>-*` 命名，避免多游戏共用内置 `default`）；
 *  - 手柄映射：`<filesDir>/input/gamepad_map.json`；
 *  - 开关/方案选择：全局存 tyranor_prefs（[EngineSettingsStore]，键名与 engine
 *    `InputConfigStore` 的 `KEY_*` 常量锚定），单游戏覆盖存 game_overrides blob
 *    （[PerGameSettingsStore]，键名同样与 engine 锚定；引擎侧只读）。
 */
object InputRemapRepository {

    // ---------- 方案命名规则 ----------

    /** 方案名长度上限（字符）。 */
    const val PROFILE_NAME_MAX_LENGTH = 24

    /** 去重序号的上限；超出后退化为时间戳名，保证去重一定终止。 */
    private const val MAX_NAME_SUFFIX_INDEX = 999

    /** 输入名为空白时的兜底名称（语言中立，默认方案的展示名在 UI 层本地化）。 */
    private const val FALLBACK_PROFILE_NAME = "Profile"

    // ---------- 全局开关 / 方案选择 ----------

    /**
     * 该引擎在「用户从未设置开关」时是否默认开启虚拟按键。
     *
     * 与 engine 侧 `InputRemapController.engineDefaultsEnabled` 同一策略：只有 MV/MZ
     * 改造前就有按键层（旧 `__touch_pad.js`），故默认开；Tyrano / VN / WebOther 默认关，
     * 否则老用户升级后会凭空多出一层按钮。设置页用它给「跟随全局」标出正确取值。
     */
    fun engineDefaultsPadEnabled(engine: EngineType): Boolean =
        engine == EngineType.RPG_MV || engine == EngineType.RPG_MZ

    /**
     * 设置页「虚拟按键」开关应展示的取值。
     *
     * 命名刻意不用 `effective...`：这不是「所有引擎的实际生效值」——未显式设置时按**开**
     * 显示（与 MV/MZ 的默认一致），而 Tyrano / VN / WebOther 的实际默认是关。全局开关对
     * 所有引擎只有一份，无法同时表达两种默认；展示值取「开」是为了让想关闭的用户
     * 一次点击即生效（若显示为关，用户得先点开、再点关）。
     * 摘要文案（`engine_settings_input_pad_summary`）向用户说明了这一默认范围，
     * 单游戏覆盖仍可逐游戏调整。
     */
    fun globalPadSwitchValue(context: Context): Boolean =
        EngineSettingsStore.isInputPadEnabled(context, default = true)

    fun setGlobalPadEnabled(context: Context, enabled: Boolean) =
        EngineSettingsStore.setInputPadEnabled(context, enabled)

    fun globalGamepadEnabled(context: Context): Boolean = EngineSettingsStore.isInputGamepadEnabled(context)

    fun setGlobalGamepadEnabled(context: Context, enabled: Boolean) =
        EngineSettingsStore.setInputGamepadEnabled(context, enabled)

    fun globalProfileId(context: Context): String = EngineSettingsStore.getInputProfileId(context)

    fun setGlobalProfileId(context: Context, id: String) =
        EngineSettingsStore.setInputProfileId(context, id)

    // ---------- 单游戏覆盖（null = 跟随全局） ----------

    fun gameProfileIdOverride(context: Context, gameId: String): String? =
        PerGameSettingsStore.getStr(context, gameId, PerGameSettingsStore.F_INPUT_PROFILE_ID)
            ?.takeIf { it.isNotBlank() }

    fun setGameProfileIdOverride(context: Context, gameId: String, value: String?) =
        PerGameSettingsStore.setStr(
            context, gameId, PerGameSettingsStore.F_INPUT_PROFILE_ID,
            value?.takeIf { it.isNotBlank() },
        )

    // ---------- 方案 ----------

    /**
     * 方案摘要（UI 视图模型）。
     *
     * UI 层不持有 engine 的 `PadProfile`（三层架构：ui 不得 import `com.core.*`），
     * 只按 id 与展示名操作；编辑内容由 [PadEditorHost] 直接读写文件。
     */
    data class ProfileSummary(
        val id: String,
        val name: String,
        val isDefault: Boolean,
        /**
         * 迁移产物的**主布局**（`migrated-<作用域>-main`）且仍未被用户重命名。
         *
         * 判定由持久化的 `PadProfile.legacyMain` 承担，占位名只作**旧文件**（早于该字段
         * 引入的迁移产物）的兜底：旧迁移主布局写死占位名 "Default"，UI 按 [isDefault]
         * 只对内置方案做本地化，少了该标记中日文界面会把迁移主布局直接显示成英文
         * "Default"。
         *
         * 只用名字判断有个边角：用户把迁移主布局**恰好改名为 "Default"** 会被误认回
         * 「未重命名」状态（列表改显「默认方案（迁移）」、复制基数也变回本地化名）。
         * `legacyMain` 在重命名时被清为 false，即使名字撞占位名也不会翻回。
         */
        val isLegacyMain: Boolean,
    )

    /**
     * 迁移产物主布局的 id 约定：`migrated-<游戏作用域>-main`（见 [PadProfile.migrateLegacy]）。
     *
     * 与 [PadProfile] 的 [PadProfile.MAIN_SLUG] 锚定。
     */
    private val LEGACY_MAIN_ID = Regex("^migrated-.+-${PadProfile.MAIN_SLUG}$")

    /** 迁移主布局的占位名（语言中立的 ASCII，展示时按 [isLegacyMain] 本地化）。 */
    private const val LEGACY_PLACEHOLDER_NAME = "Default"

    fun listProfileSummaries(context: Context): List<ProfileSummary> =
        InputConfigStore.listProfiles(context).map { profile ->
            ProfileSummary(
                id = profile.id,
                name = profile.name,
                isDefault = profile.id == InputConfigStore.DEFAULT_PROFILE_ID,
                isLegacyMain = isLegacyMainProfile(profile),
            )
        }

    /**
     * 迁移主布局判定（id + 标记/占位名一并判断）：
     *
     *  - id 必须匹配 `migrated-<作用域>-main`（预设/自建方案的 id 天然不匹配）；
     *  - `legacyMain == true` 权威；`null`（旧文件无该字段）回退到占位名判断；
     *    `false`（含用户重命名后）一律不是——名字撞占位名也不会翻回。
     */
    internal fun isLegacyMainProfile(profile: PadProfile): Boolean =
        LEGACY_MAIN_ID.matches(profile.id) &&
            (profile.legacyMain == true ||
                (profile.legacyMain == null && profile.name == LEGACY_PLACEHOLDER_NAME))

    private fun listProfiles(context: Context): List<PadProfile> = InputConfigStore.listProfiles(context)

    /** 写入方案（编辑器落盘与方案管理共用）。 */
    fun writeProfile(context: Context, profile: PadProfile): Boolean =
        InputConfigStore.writeProfile(context, profile)

    /** 读取方案（编辑器入口校验用）。 */
    fun readProfile(context: Context, id: String): PadProfile? =
        InputConfigStore.readProfile(context, id)

    /** 复制方案（id 由调用方保证唯一；默认方案也可复制）。 */
    fun duplicateProfile(context: Context, id: String, newId: String, newName: String): ProfileSummary? {
        if (InputConfigStore.sanitizeProfileId(newId) == null) return null
        val source = InputConfigStore.readProfile(context, id) ?: return null
        // 副本是新方案（新 id 天然不匹配迁移主布局正则）：显式清掉 legacyMain，
        // 避免把源方案的迁移标记写进副本 JSON
        val copy = source.copy(id = newId, name = newName, legacyMain = false)
        if (!writeProfile(context, copy)) return null
        return ProfileSummary(id = newId, name = newName, isDefault = false, isLegacyMain = false)
    }

    /** 新建方案：以出厂默认布局为底（出厂布局不向 app 暴露构造细节）。 */
    fun createProfile(context: Context, newId: String, newName: String): ProfileSummary? {
        if (InputConfigStore.sanitizeProfileId(newId) == null) return null
        val profile = PadProfile.defaultProfile(newId, newName)
        if (!writeProfile(context, profile)) return null
        return ProfileSummary(id = newId, name = newName, isDefault = false, isLegacyMain = false)
    }

    /** 重命名方案（默认方案由 UI 禁止入口）。 */
    fun renameProfile(context: Context, id: String, newName: String): Boolean {
        val profile = InputConfigStore.readProfile(context, id) ?: return false
        // 重命名即脱离「迁移主布局」展示语义（含恰好改名为占位名 "Default" 的情形）：
        // 清掉 legacyMain，避免列表/复制基数误认回未重命名状态
        return writeProfile(
            context,
            profile.copy(
                name = newName.trim().take(PROFILE_NAME_MAX_LENGTH),
                legacyMain = false,
            ),
        )
    }

    /** 导出为 JSON 文本（与方案文件同格式，便于分享/备份）。 */
    fun exportProfile(context: Context, id: String): String? =
        InputConfigStore.readProfile(context, id)?.let { JSONObject(it.toJson()).toString(2) }

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
     * 旧 `__touch_pad.js` 布局/预设 → 新方案文件（每游戏一次性）。
     *
     * 守卫顺序：无旧键即返回 → 该游戏已迁移过即返回 → 逐文件判存在（已有方案一律不覆盖，
     * 尊重游戏内编辑的成果与既有自建方案）。旧键迁移后不清除（保留为数据留底），因此
     * 幂等性完全由「已迁移」标记承担；缺了它会在每次启动重复迁移。
     *
     * 方案目录全 App 共享，所以迁移产物一律用**按游戏唯一**的 id（`migrated-<gameScope>-*`），
     * 并把该游戏的方案选择写进**单游戏覆盖**：若沿用内置 `default` id 并改全局选择，
     * 第一个迁移的游戏会污染全局默认布局，其余未配置游戏也会看到它。
     */
    fun migrateLegacyIfNeeded(context: Context, gameId: String): List<String> {
        if (gameId.isBlank()) return emptyList()
        if (PerGameSettingsStore.getBool(context, gameId, PerGameSettingsStore.F_LEGACY_MIGRATED) == true) {
            return emptyList()
        }
        val legacy = InputConfigStore.readLegacyTouchPad(context, gameId) ?: return emptyList()
        val profiles = PadProfile.migrateLegacy(legacy.first, legacy.second, gameScope(gameId))
        val outcome = writeMigratedProfiles(
            profiles = profiles,
            exists = { id -> InputConfigStore.profileFile(context, id).isFile },
            write = { profile -> writeProfile(context, profile) },
        )
        // 仅在全部待写方案都成功（或已存在）时继续：写盘失败（磁盘满、目录创建失败）
        // 时留待下次启动重试，否则旧布局再也迁不进来
        if (!outcome.allWritten) return outcome.migrated
        if (profiles.isNotEmpty() && gameProfileIdOverride(context, gameId) == null) {
            // 该游戏未显式选择方案时，指向迁移出的主布局（单游戏覆盖，不动全局）。
            // 目标从**完整 profiles 列表**推导而非 outcome.migrated：后者是「本次新写集合」，
            // 部分失败重试后可能只剩 preset（主布局已被 exists 跳过），会让游戏错误地
            // 用上某个预设；文件已写全但上次在置标记前中断时它还会是空集，覆盖永不写入。
            //
            // 这一步必须**先于**置标记：两者是独立持久化，中间被杀时留下「覆盖已写、标记未置」
            // 只会让下次启动幂等重试；顺序反过来会留下「标记已置但覆盖未写」，覆盖永不写入，
            // 该游戏的方案选择静默留在全局默认。
            setGameProfileIdOverride(context, gameId, profiles.first().id)
        }
        PerGameSettingsStore.setBool(context, gameId, PerGameSettingsStore.F_LEGACY_MIGRATED, true)
        return outcome.migrated
    }

    /**
     * 迁移产物的按游戏作用域（进 id，需符合 `[A-Za-z0-9_-]` 白名单）。
     *
     * 用 gameId 的稳定短哈希（直接塞 uri 会带上 `:` `/` 等非法字符且超长）。
     * 32 位哈希理论上可碰撞，碰撞时第二个游戏会因「产物已存在」被跳过——
     * 属可接受的极端情况（同名游戏库条目已先一步冲突），此处仅保证确定性。
     */
    internal fun gameScope(gameId: String): String =
        "g" + Integer.toHexString(gameId.hashCode())

    /** 迁移写盘结果（纯数据，供 [writeMigratedProfiles] 与单测使用）。 */
    internal data class MigrationOutcome(
        /** 本次真正写入的方案 id（已存在而跳过的不计入）。 */
        val migrated: List<String>,
        /** 是否全部待写方案都已存在或写入成功；false 表示应留待下次重试。 */
        val allWritten: Boolean,
    )

    /**
     * 逐个写入迁移产物；已存在的方案文件跳过（不得覆盖用户成果）。
     *
     * 抽成不依赖 Context 的纯函数：`allWritten` 是「迁移标记可否置位」的唯一依据，
     * 写盘失败时置位会让旧布局永久无法迁移（磁盘满是真实场景），必须由测试锚定。
     */
    internal fun writeMigratedProfiles(
        profiles: List<PadProfile>,
        exists: (String) -> Boolean,
        write: (PadProfile) -> Boolean,
    ): MigrationOutcome {
        val migrated = ArrayList<String>()
        var allWritten = true
        profiles.forEach { profile ->
            if (exists(profile.id)) return@forEach
            if (write(profile)) migrated.add(profile.id) else allWritten = false
        }
        return MigrationOutcome(migrated = migrated, allWritten = allWritten)
    }

    /**
     * 删除方案。内置默认方案不可删除（返回 false）。
     *
     * 同时清理指向该方案的引用：全局选择（调用方已回退）之外，各游戏的单游戏覆盖
     * 若仍指向已删除的 id，游戏内会回退到内置默认方案——看似能用，但设置页显示
     * 该游戏显示已选某方案却找不到，属悬空引用，这里一并清掉。
     */
    fun deleteProfile(context: Context, id: String): Boolean {
        if (!InputConfigStore.deleteProfile(context, id)) return false
        clearProfileReferences(context, id)
        return true
    }

    /** 清空所有把 [id] 作为方案选择的单游戏覆盖。 */
    private fun clearProfileReferences(context: Context, id: String) {
        val gameIds = PerGameSettingsStore.overrideGameIds(context)
        gameIds.forEach { gameId ->
            if (gameProfileIdOverride(context, gameId) == id) {
                setGameProfileIdOverride(context, gameId, null)
            }
        }
    }

    // ---------- 键位目录（UI 键位选择器用；engine 侧类型不向 ui 泄漏） ----------

    /** 单个可选键位：canonical 码 + 已本地化的展示名。 */
    data class KeyOption(val code: Int, val label: String)

    /** 键位分组（字母 / 数字 / 方向 / 功能键 / 鼠标）。 */
    data class KeyGroup(val title: String, val keys: List<KeyOption>)

    fun keyGroups(context: Context): List<KeyGroup> =
        InputKeyCatalog.groups().map { group ->
            KeyGroup(
                title = context.getString(group.titleRes),
                keys = group.keys.map { entry ->
                    KeyOption(
                        code = entry.code,
                        label = entry.label ?: context.getString(entry.labelRes),
                    )
                },
            )
        }

    /** 键位展示名（未知键位回退为数字，避免出现空白项）。 */
    fun keyLabel(context: Context, code: Int): String =
        InputKeyCatalog.label(context, code).ifBlank { code.toString() }

    // ---------- 手柄映射（UI 读写门面） ----------

    /** 单个逻辑按键的映射视图。 */
    data class GamepadEntry(val id: String, val keys: List<Int>, val autoKeep: Boolean)

    /** 单个摇杆方向的映射视图。 */
    data class StickEntry(val stick: String, val direction: String, val keys: List<Int>)

    fun gamepadEntries(context: Context): List<GamepadEntry> {
        val map = InputConfigStore.readGamepadMap(context)
        return GamepadButtons.ALL.map { id ->
            val binding = map.binding(id)
            GamepadEntry(id = id, keys = binding.keys, autoKeep = binding.autoKeep)
        }
    }

    fun setGamepadBinding(context: Context, id: String, keys: List<Int>, autoKeep: Boolean): Boolean {
        val map = InputConfigStore.readGamepadMap(context)
        return InputConfigStore.writeGamepadMap(
            context,
            map.withBinding(id, GamepadBinding(keys = keys, autoKeep = autoKeep)),
        )
    }

    /** 摇杆方向：`stick` ∈ {left, right}，`direction` ∈ {up, down, left, right}。 */
    fun stickEntries(context: Context): List<StickEntry> {
        val map = InputConfigStore.readGamepadMap(context)
        return STICKS.flatMap { stick ->
            val binding = if (stick == STICK_LEFT) map.leftStick else map.rightStick
            StickBinding.DIRECTIONS.map { direction ->
                StickEntry(stick = stick, direction = direction, keys = binding.keys(direction))
            }
        }
    }

    fun setStickBinding(context: Context, stick: String, direction: String, keys: List<Int>): Boolean {
        val map = InputConfigStore.readGamepadMap(context)
        val updated = if (stick == STICK_LEFT) {
            map.copy(leftStick = map.leftStick.with(direction, GamepadBinding(keys = keys)))
        } else {
            map.copy(rightStick = map.rightStick.with(direction, GamepadBinding(keys = keys)))
        }
        return InputConfigStore.writeGamepadMap(context, updated)
    }

    const val STICK_LEFT = "left"
    const val STICK_RIGHT = "right"
    private val STICKS = listOf(STICK_LEFT, STICK_RIGHT)

    // 手柄逻辑按键 id 常量（UI 用它们做标签映射；值由 engine 侧定义，此处只做再导出）
    const val GAMEPAD_A = "A"
    const val GAMEPAD_B = "B"
    const val GAMEPAD_X = "X"
    const val GAMEPAD_Y = "Y"
    const val GAMEPAD_START = "START"
    const val GAMEPAD_SELECT = "SELECT"
    const val GAMEPAD_L1 = "L1"
    const val GAMEPAD_R1 = "R1"
    const val GAMEPAD_L2 = "L2"
    const val GAMEPAD_R2 = "R2"
    const val GAMEPAD_L3 = "L3"
    const val GAMEPAD_R3 = "R3"
    const val GAMEPAD_DPAD_UP = "DPAD_UP"
    const val GAMEPAD_DPAD_DOWN = "DPAD_DOWN"
    const val GAMEPAD_DPAD_LEFT = "DPAD_LEFT"
    const val GAMEPAD_DPAD_RIGHT = "DPAD_RIGHT"

    /**
     * 方案名去重（新增 / 复制 / 导入用）。
     *
     * @return 一个不与现有方案重名、且长度不超过 [PROFILE_NAME_MAX_LENGTH] 的名称。
     */
    fun uniqueProfileName(context: Context, base: String): String =
        uniqueName(base, listProfiles(context).map { it.name }.toSet())

    /**
     * 名称去重的纯函数部分（不依赖 Context，便于单测锚定）。
     *
     * 两个容易写错的点，都曾是实际缺陷：
     *  - **后缀必须先预留长度再截断**：直接对 `"$base $index"` 取前 24 字符，在 base
     *    已满 24 字符时会把后缀整段截掉、原样返回 base，复制满长名称就得到两个无法
     *    区分的同名方案；
     *  - **必须以截断后的候选名查重**：按未截断字符串查重会漏掉「截断后才撞名」的情况。
     */
    internal fun uniqueName(base: String, existing: Set<String>): String {
        val seed = base.trim().take(PROFILE_NAME_MAX_LENGTH).ifBlank { FALLBACK_PROFILE_NAME }
        if (seed !in existing) return seed
        for (index in 2..MAX_NAME_SUFFIX_INDEX) {
            val suffix = " $index"
            val room = (PROFILE_NAME_MAX_LENGTH - suffix.length).coerceAtLeast(0)
            val candidate = seed.take(room) + suffix
            if (candidate !in existing) return candidate
        }
        // 极端情况（同名方案多到序号耗尽）：退化为含时间戳的唯一名，保证一定终止
        return ("p" + System.currentTimeMillis().toString(36)).take(PROFILE_NAME_MAX_LENGTH)
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

    /** 内置默认方案 id（UI 判定展示名与不可删除语义用）。 */
    fun defaultProfileId(): String = InputConfigStore.DEFAULT_PROFILE_ID

    /**
     * 从 JSON 文本解析为方案内容并写入新方案；解析失败返回 null。
     *
     * 导入路径不把 engine 类型交给 UI：调用方只给 id 与名称，内容由本方法负责。
     */
    fun importProfileAsNew(context: Context, raw: String?, newId: String, newName: String): ProfileSummary? {
        val parsed = PadProfile.parse(raw) ?: return null
        if (InputConfigStore.sanitizeProfileId(newId) == null) return null
        val profile = parsed.copy(id = newId, name = newName)
        if (!writeProfile(context, profile)) return null
        return ProfileSummary(id = newId, name = newName, isDefault = false, isLegacyMain = false)
    }

    /** 从导入的 JSON 中读取方案名（解析失败返回 null）。 */
    fun importedProfileName(raw: String?): String? = PadProfile.parse(raw)?.name?.takeIf { it.isNotBlank() }
}
