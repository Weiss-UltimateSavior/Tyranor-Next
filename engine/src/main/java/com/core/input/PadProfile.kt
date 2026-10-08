package com.core.input

import android.view.KeyEvent
import org.json.JSONArray
import org.json.JSONObject

/**
 * 虚拟按键方案（可编辑布局）数据模型。
 *
 * 坐标全部归一化到**全视口**（0..1，允许 -0.1..1.1 小幅出界摆放）：横竖屏切换按比例
 * 重映射，位置不漂移（沿用原 `__touch_pad.js` 的归一化策略）。`size` 为宽度相对
 * `min(视口宽, 视口高)` 的比例；高度 = 宽度 × [PadButton.aspect]。
 */
data class PadButton(
    val id: String,
    val text: String,
    val x: Float,
    val y: Float,
    val size: Float,
    val aspect: Float = DEFAULT_ASPECT,
    val shape: String = SHAPE_ROUND,
    val visible: Boolean = true,
    val keys: List<Int> = emptyList(),
    val autoKeep: Boolean = false,
) {
    companion object {
        const val SHAPE_ROUND = "round"
        const val SHAPE_SQUARE = "square"
        const val DEFAULT_ASPECT = 0.46f
        const val MIN_SIZE = 0.02f
        const val MAX_SIZE = 0.60f
    }
}

/**
 * 新增按钮的几何预设（编辑面板「新增」对话框的选项）。
 *
 * 只描述宽高比：椭圆是默认键位按钮；圆形用正方形盒渲染出正圆，
 * 与方案自带的 QWZX 键外观一致。角样式由 [PadButton.shape] 另行控制。
 */
enum class PadButtonGeometry {
    /** 椭圆形（宽高比 0.46）。 */
    OVAL,

    /** 圆形（正方形盒 + 全圆角）。 */
    ROUND,
}

/** 方向控件（摇杆外观，四向/八向）。八向由相邻两方向组合派发，不单独配置键位。 */
data class PadDirection(
    val x: Float = 0.17f,
    val y: Float = 0.80f,
    val size: Float = 0.30f,
    val visible: Boolean = true,
    val eightDir: Boolean = true,
    val up: List<Int> = listOf(KeyEvent.KEYCODE_DPAD_UP),
    val down: List<Int> = listOf(KeyEvent.KEYCODE_DPAD_DOWN),
    val left: List<Int> = listOf(KeyEvent.KEYCODE_DPAD_LEFT),
    val right: List<Int> = listOf(KeyEvent.KEYCODE_DPAD_RIGHT),
)

data class PadProfile(
    val id: String,
    val name: String,
    val buttons: List<PadButton>,
    val direction: PadDirection = PadDirection(),
) {

    /** 覆盖同 id 按钮；不存在时追加到末尾（编辑模式新增 / 复制的写入路径）。 */
    fun withButton(button: PadButton): PadProfile =
        if (buttons.any { it.id == button.id }) {
            copy(buttons = buttons.map { if (it.id == button.id) button else it })
        } else {
            copy(buttons = buttons + button)
        }

    fun removeButton(id: String): PadProfile = copy(buttons = buttons.filterNot { it.id == id })

    fun toJson(): String {
        val root = JSONObject()
        root.put("schema", SCHEMA)
        root.put("id", id)
        root.put("name", name)
        root.put("direction", directionToJson(direction))
        val array = JSONArray()
        buttons.forEach { array.put(buttonToJson(it)) }
        root.put("buttons", array)
        return root.toString()
    }

    companion object {

        const val SCHEMA = 1

        const val BUILTIN_DEFAULT_ID = "default"

        /** 方案内按钮数上限（防御坏文件/构造文件）。 */
        const val MAX_PAD_BUTTONS = 128

        /**
         * 方向控件的保留 id。
         *
         * 方向控件与普通按钮共处同一张 `elementById`（见 [VirtualPadView]），按钮 id 占用
         * 该值会让渲染、命中与编辑全部错乱，因此解析时直接丢弃同名按钮。
         */
        const val DIRECTION_ID = "direction"

        /** 按钮文字长度上限（与编辑面板的输入限制一致）。 */
        const val MAX_BUTTON_TEXT = 12

        /** 迁移产物 id 前缀。 */
        private const val MIGRATED_PREFIX = "migrated-"

        /** 主布局在迁移产物里的 slug（无旧预设序号）。 */
        private const val MAIN_SLUG = "main"

        /** `InputConfigStore` 的 id 白名单上限（[A-Za-z0-9_-]{1,32}）。 */
        private const val MAX_MIGRATED_ID_LENGTH = 32

        /** 作用域 slug 的上限（给前缀与序号留出余量）。 */
        private const val MAX_SCOPE_SLUG_LENGTH = 12

        fun parse(raw: String?): PadProfile? {
            if (raw.isNullOrBlank()) return null
            return runCatching {
                val root = JSONObject(raw)
                if (!InputJsonLimits.isSupportedSchema(root.opt("schema"), SCHEMA)) return null
                val id = root.optString("id").takeIf { it.isNotBlank() } ?: return null
                val name = root.optString("name").takeIf { it.isNotBlank() } ?: id
                val buttons = ArrayList<PadButton>()
                root.optJSONArray("buttons")?.let { array ->
                    // 上限防御：方案文件可能被外部改坏或构造（导入路径直接吃用户文件），
                    // 无上限解析会一次分配大量对象
                    val count = minOf(array.length(), MAX_PAD_BUTTONS)
                    for (i in 0 until count) {
                        val obj = array.optJSONObject(i) ?: continue
                        val button = buttonFromJson(obj) ?: continue
                        // 保留 id 与方向控件撞键：两者同处 elementById，重名会让渲染、
                        // 命中与编辑全部错乱（导入的用户方案可达此路径）
                        if (button.id == DIRECTION_ID) continue
                        buttons.add(button)
                    }
                }
                PadProfile(
                    id = id,
                    name = name,
                    buttons = buttons,
                    direction = directionFromJson(root.optJSONObject("direction")),
                )
            }.getOrNull()
        }

        /**
         * 新增按钮的出厂几何参数。
         *
         * 抽成纯函数（不依赖 View）以便单测锚定「新增能出椭圆也能出圆形」这类回归。
         */
        fun newButtonDefaults(geometry: PadButtonGeometry, id: String, text: String): PadButton =
            when (geometry) {
                PadButtonGeometry.OVAL -> PadButton(
                    id = id, text = text, x = 0.5f, y = 0.5f,
                    size = 0.12f, aspect = PadButton.DEFAULT_ASPECT, shape = PadButton.SHAPE_ROUND,
                )

                PadButtonGeometry.ROUND -> PadButton(
                    id = id, text = text, x = 0.5f, y = 0.5f,
                    size = 0.088f, aspect = 1f, shape = PadButton.SHAPE_ROUND,
                )
            }

        /**
         * 出厂默认方案：右侧动作键列 + 右下 QWZX 菱形 + 左下方向摇杆。
         *
         * 名称与按钮文字为语言中立的 ASCII（方案名/按钮文字是随方案持久化的用户数据，
         * 不参与界面文案本地化；默认方案的展示名在设置页按 id 映射到字符串资源）。
         */
        fun defaultProfile(id: String = BUILTIN_DEFAULT_ID, name: String = "Default"): PadProfile {
            val ok = KeyEvent.KEYCODE_ENTER
            val space = KeyEvent.KEYCODE_SPACE
            val esc = KeyEvent.KEYCODE_ESCAPE
            val ctrl = KeyEvent.KEYCODE_CTRL_LEFT
            val pageUp = KeyEvent.KEYCODE_PAGE_UP
            val pageDown = KeyEvent.KEYCODE_PAGE_DOWN
            val tab = KeyEvent.KEYCODE_TAB

            fun action(btnId: String, text: String, y: Float, keys: List<Int>, autoKeep: Boolean = false) =
                PadButton(
                    id = btnId, text = text, x = 0.93f, y = y, size = 0.115f,
                    keys = keys, autoKeep = autoKeep,
                )

            fun qwzx(btnId: String, text: String, x: Float, y: Float, keyCode: Int) =
                PadButton(
                    id = btnId, text = text, x = x, y = y, size = 0.088f,
                    aspect = 1f, shape = PadButton.SHAPE_ROUND, keys = listOf(keyCode),
                )

            return PadProfile(
                id = id,
                name = name,
                buttons = listOf(
                    action("ok", "OK", 0.50f, listOf(ok, space)),
                    action("cancel", "Esc", 0.585f, listOf(esc)),
                    action("skip", "Skip", 0.67f, listOf(ctrl), autoKeep = true),
                    action("pageup", "PageUp", 0.415f, listOf(pageUp)),
                    action("pagedown", "PageDn", 0.33f, listOf(pageDown)),
                    action("tab", "Tab", 0.245f, listOf(tab)),
                    qwzx("q", "Q", 0.695f, 0.885f, KeyEvent.KEYCODE_Q),
                    qwzx("w", "W", 0.78f, 0.80f, KeyEvent.KEYCODE_W),
                    qwzx("z", "Z", 0.78f, 0.97f, KeyEvent.KEYCODE_Z),
                    qwzx("x", "X", 0.865f, 0.885f, KeyEvent.KEYCODE_X),
                ),
                direction = PadDirection(),
            )
        }

        /**
         * 旧 `__touch_pad.js` 布局（`touch_pad_config`）与其预设（`touch_pad_presets`）
         * → 新方案列表。返回空列表表示无旧数据。
         *
         * 位置（旧编辑态已存归一化中心坐标）与显隐原样保留；旧 `scale` 与新 `size`
         * 语义不同（旧相对 padSize、新相对 min(视口边)），不强行换算，尺寸回落新默认。
         * 旧开关类按钮（btn.hide / btn.stick / btn.dir8）由原生 FAB 取代，不迁移。
         *
         * @param gameScope 迁移产物 id 的按游戏作用域。方案目录全 App 共享，主布局若沿用
         *   内置 [BUILTIN_DEFAULT_ID] 会让第一个迁移的游戏污染全局默认布局，因此迁移产物
         *   一律用独立 id，由调用方写单游戏覆盖指向它。
         */
        fun migrateLegacy(
            configJson: String?,
            presetsJson: String?,
            gameScope: String,
        ): List<PadProfile> {
            val scope = sanitizeLegacyId(gameScope).take(MAX_SCOPE_SLUG_LENGTH)
            val result = ArrayList<PadProfile>()
            parseLegacyConfig(configJson)?.let {
                result.add(it.copy(id = migratedId(scope, 0, MAIN_SLUG), name = "Default"))
            }
            runCatching {
                val presets = JSONObject(presetsJson.orEmpty())
                val names = presets.names() ?: return@runCatching
                for (i in 0 until names.length()) {
                    val name = names.optString(i).takeIf { it.isNotBlank() } ?: continue
                    val legacy = parseLegacyConfig(presets.optJSONObject(name)?.toString()) ?: continue
                    result.add(
                        legacy.copy(
                            id = migratedId(scope, i + 1, sanitizeLegacyId(name)),
                            name = name.take(24),
                        ),
                    )
                }
            }
            return result
        }

        /**
         * 迁移产物的方案 id，长度硬约束在 `InputConfigStore` 的 id 白名单上限（32 字符）内。
         *
         * 两处必须留意的坑：
         *  - 原实现 `"migrated-$slug"` 可达 33 字符（前缀 9 + slug 24），超出上限后
         *    `writeProfile` 静默拒绝 → 迁移标记永不置位 → 每次启动重试且永不成功；
         *  - **不能对拼接结果直接 take(32)**：序号在尾部，截断会把它吃掉，两个长 slug
         *    预设（如 `...for phone` / `...for tablet`）会算出同一个 id，后者被当作
         *    「已存在」跳过且不计失败 → 静默丢预设且永不重试。
         *
         * 分配顺序因此是「**先给作用域** → 再给序号 → 最后才让 slug 吃剩余空间」：
         * 作用域是跨游戏唯一性的唯一来源，若被长 slug 挤到只剩 1 字符，不同游戏就会
         * 算出同一个 id（导入的用户方案可直接触发），因此它优先拿满自己的配额。
         */
        private fun migratedId(scope: String, index: Int, slug: String): String {
            val suffix = if (index == 0) "" else "-$index"
            val scopePart = scope.ifBlank { "g" }.take(MAX_SCOPE_SLUG_LENGTH)
            // 固定占用：前缀 + 作用域 + 分隔符 + 序号；余量全给 slug（截断不影响唯一性，
            // 同游戏内的预设已由尾部序号区分）
            val fixed = MIGRATED_PREFIX.length + scopePart.length + 1 + suffix.length
            val slugRoom = (MAX_MIGRATED_ID_LENGTH - fixed).coerceAtLeast(1)
            val slugPart = slug.take(slugRoom).ifBlank { "p" }
            return MIGRATED_PREFIX + scopePart + "-" + slugPart + suffix
        }

        /** 旧按钮 id → 新按钮模板（文本 / canonical 键位 / 默认尺寸）。 */
        private class LegacyTemplate(
            val text: String,
            val keys: List<Int>,
            val size: Float = 0.115f,
            val aspect: Float = PadButton.DEFAULT_ASPECT,
            val shape: String = PadButton.SHAPE_ROUND,
        )

        private val LEGACY_ACTIONS: List<Pair<String, LegacyTemplate>> = listOf(
            "pageup" to LegacyTemplate("PageUp", listOf(KeyEvent.KEYCODE_PAGE_UP)),
            "pagedown" to LegacyTemplate("PageDn", listOf(KeyEvent.KEYCODE_PAGE_DOWN)),
            "tab" to LegacyTemplate("Tab", listOf(KeyEvent.KEYCODE_TAB)),
            "alt" to LegacyTemplate("Alt", listOf(KeyEvent.KEYCODE_ALT_LEFT)),
            "ctrl" to LegacyTemplate("Ctrl", listOf(KeyEvent.KEYCODE_CTRL_LEFT)),
            "shift" to LegacyTemplate("Shift", listOf(KeyEvent.KEYCODE_SHIFT_LEFT)),
            "space" to LegacyTemplate("Space", listOf(KeyEvent.KEYCODE_SPACE)),
            "enter" to LegacyTemplate("Enter", listOf(KeyEvent.KEYCODE_ENTER)),
            "esc" to LegacyTemplate("Esc", listOf(KeyEvent.KEYCODE_ESCAPE)),
        )

        private val LEGACY_QWZX: List<Triple<String, Int, Pair<Float, Float>>> = listOf(
            Triple("q", KeyEvent.KEYCODE_Q, -0.085f to 0f),
            Triple("w", KeyEvent.KEYCODE_W, 0f to -0.085f),
            Triple("z", KeyEvent.KEYCODE_Z, 0f to 0.085f),
            Triple("x", KeyEvent.KEYCODE_X, 0.085f to 0f),
        )

        /**
         * 单个旧布局 JSON → 新方案；无任何可迁移按钮（如仅旧开关）返回 null。
         *
         * 旧配置是「相对出厂布局的增量」：旧 JS 编辑态的条目只在用户动过的控件上产生
         * （`ensureButtonCfg` 按需新建），因此以 [defaultProfile] 为底、用旧条目按 id
         * 覆盖位置与显隐——只拖过一个按钮的旧配置迁移后仍保有完整按键集。
         * 旧条目 `x/y` 为 JSON null（旧 JS 只改显隐时的写法）时保留默认锚点。
         */
        private fun parseLegacyConfig(raw: String?): PadProfile? {
            if (raw.isNullOrBlank()) return null
            return runCatching {
                val buttonsObj = JSONObject(raw).optJSONObject("buttons") ?: return null
                val defaults = defaultProfile()
                var touched = false

                val migrated = ArrayList<PadButton>()
                LEGACY_ACTIONS.forEach { (legacyId, template) ->
                    val base = defaults.buttons.firstOrNull { it.id == legacyId } ?: PadButton(
                        id = legacyId, text = template.text, x = 0.5f, y = 0.5f,
                        size = template.size, aspect = template.aspect, shape = template.shape,
                        keys = template.keys,
                    )
                    val legacy = buttonsObj.optJSONObject(legacyId)
                    if (legacy == null) {
                        migrated.add(base)
                        return@forEach
                    }
                    touched = true
                    migrated.add(
                        base.copy(
                            x = legacyCoord(legacy, "x", base.x),
                            y = legacyCoord(legacy, "y", base.y),
                            visible = legacy.optBoolean("visible", true),
                        ),
                    )
                }

                // 旧 QWZX 为整组一个配置（存组中心），拆成四个独立按钮组成菱形
                val qwzxDefaults = defaults.buttons.filter { it.id in setOf("q", "w", "z", "x") }
                buttonsObj.optJSONObject("qwzx")?.let { legacy ->
                    touched = true
                    val cx = legacyCoord(legacy, "x", 0.78f)
                    val cy = legacyCoord(legacy, "y", 0.885f)
                    val visible = legacy.optBoolean("visible", true)
                    migrated.removeAll { it.id in setOf("q", "w", "z", "x") }
                    LEGACY_QWZX.forEach { (btnId, keyCode, offset) ->
                        val base = qwzxDefaults.firstOrNull { it.id == btnId }
                        migrated.add(
                            PadButton(
                                id = btnId,
                                text = btnId.uppercase(),
                                x = (cx + offset.first).coerceIn(-0.1f, 1.1f),
                                y = (cy + offset.second).coerceIn(-0.1f, 1.1f),
                                size = base?.size ?: 0.088f,
                                aspect = 1f,
                                visible = visible,
                                keys = base?.keys ?: listOf(keyCode),
                            ),
                        )
                    }
                } ?: qwzxDefaults.forEach { migrated.add(it) }

                var direction = defaults.direction
                (buttonsObj.optJSONObject("joystick") ?: buttonsObj.optJSONObject("dpad"))?.let { legacy ->
                    touched = true
                    direction = direction.copy(
                        x = legacyCoord(legacy, "x", direction.x),
                        y = legacyCoord(legacy, "y", direction.y),
                        visible = legacy.optBoolean("visible", true),
                    )
                }
                if (!touched && buttonsObj.length() > 0) {
                    // 旧条目全是已下线的开关（btn.hide 等）：无可迁移内容
                    return null
                }
                PadProfile(BUILTIN_DEFAULT_ID, "Default", migrated, direction)
            }.getOrNull()
        }

        /** 旧配置坐标：缺失或 JSON null 时回落默认锚点（旧 JS 只改显隐时写 null）。 */
        private fun legacyCoord(obj: JSONObject, key: String, fallback: Float): Float {
            if (!obj.has(key) || obj.isNull(key)) return fallback
            return obj.optDouble(key, fallback.toDouble()).toFloat().coerceIn(-0.1f, 1.1f)
        }

        private fun sanitizeLegacyId(name: String): String =
            name.trim().replace(Regex("[^A-Za-z0-9_-]+"), "-").trim('-').take(24)
                .ifBlank { "preset" }

        private fun buttonToJson(button: PadButton): JSONObject = JSONObject().apply {
            put("id", button.id)
            put("text", button.text)
            put("x", button.x.toDouble())
            put("y", button.y.toDouble())
            put("size", button.size.toDouble())
            put("aspect", button.aspect.toDouble())
            put("shape", button.shape)
            if (!button.visible) put("visible", false)
            put("keys", JSONArray().apply { button.keys.forEach { put(it) } })
            if (button.autoKeep) put("autoKeep", true)
        }

        private fun buttonFromJson(obj: JSONObject): PadButton? {
            val id = obj.optString("id").takeIf { it.isNotBlank() } ?: return null
            return PadButton(
                id = id,
                text = obj.optString("text").take(MAX_BUTTON_TEXT),
                x = obj.optDouble("x", 0.5).toFloat().coerceIn(-0.1f, 1.1f),
                y = obj.optDouble("y", 0.5).toFloat().coerceIn(-0.1f, 1.1f),
                size = obj.optDouble("size", 0.1).toFloat()
                    .coerceIn(PadButton.MIN_SIZE, PadButton.MAX_SIZE),
                aspect = obj.optDouble("aspect", PadButton.DEFAULT_ASPECT.toDouble()).toFloat()
                    .coerceIn(0.1f, 2f),
                shape = obj.optString("shape", PadButton.SHAPE_ROUND),
                visible = obj.optBoolean("visible", true),
                keys = keysFromJson(obj.optJSONArray("keys")),
                autoKeep = obj.optBoolean("autoKeep", false),
            )
        }

        private fun directionToJson(direction: PadDirection): JSONObject = JSONObject().apply {
            put("x", direction.x.toDouble())
            put("y", direction.y.toDouble())
            put("size", direction.size.toDouble())
            if (!direction.visible) put("visible", false)
            put("eightDir", direction.eightDir)
            put("up", JSONArray().apply { direction.up.forEach { put(it) } })
            put("down", JSONArray().apply { direction.down.forEach { put(it) } })
            put("left", JSONArray().apply { direction.left.forEach { put(it) } })
            put("right", JSONArray().apply { direction.right.forEach { put(it) } })
        }

        private fun directionFromJson(obj: JSONObject?): PadDirection {
            if (obj == null) return PadDirection()
            return PadDirection(
                x = obj.optDouble("x", 0.17).toFloat().coerceIn(-0.1f, 1.1f),
                y = obj.optDouble("y", 0.80).toFloat().coerceIn(-0.1f, 1.1f),
                size = obj.optDouble("size", 0.30).toFloat().coerceIn(0.08f, 0.8f),
                visible = obj.optBoolean("visible", true),
                eightDir = obj.optBoolean("eightDir", true),
                // 字段缺失 = 用出厂方向键；字段存在但为空数组 = 该方向不输出（显式解绑）
                up = directionKeys(obj, "up", KeyEvent.KEYCODE_DPAD_UP),
                down = directionKeys(obj, "down", KeyEvent.KEYCODE_DPAD_DOWN),
                left = directionKeys(obj, "left", KeyEvent.KEYCODE_DPAD_LEFT),
                right = directionKeys(obj, "right", KeyEvent.KEYCODE_DPAD_RIGHT),
            )
        }

        /** 方向键位：字段缺失回落 [defaultKey]，字段存在（含空数组）按原样使用。 */
        private fun directionKeys(obj: JSONObject, name: String, defaultKey: Int): List<Int> =
            if (obj.has(name)) keysFromJson(obj.optJSONArray(name)) else listOf(defaultKey)

        private fun keysFromJson(array: JSONArray?): List<Int> {
            if (array == null) return emptyList()
            val count = minOf(array.length(), InputJsonLimits.MAX_KEYS_PER_BINDING)
            val result = ArrayList<Int>(count)
            for (i in 0 until count) {
                val value = array.optInt(i, 0)
                if (value > 0) result.add(value)
            }
            return result
        }
    }
}
