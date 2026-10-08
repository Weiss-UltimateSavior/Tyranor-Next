package com.core.input

import org.json.JSONArray
import org.json.JSONObject

/**
 * 手柄逻辑按键 id（[GamepadMap.buttons] 的键）。
 *
 * 语义对齐 FoldCraftLauncher 的逻辑按钮集：物理按键 / D-Pad / 摇杆方向仿真共用同一套
 * 映射结构；每个逻辑按键输出一组 canonical 键位。
 */
object GamepadButtons {
    const val A = "A"
    const val B = "B"
    const val X = "X"
    const val Y = "Y"
    const val START = "START"
    const val SELECT = "SELECT"
    const val L1 = "L1"
    const val R1 = "R1"
    const val L2 = "L2"
    const val R2 = "R2"
    const val L3 = "L3"
    const val R3 = "R3"
    const val DPAD_UP = "DPAD_UP"
    const val DPAD_DOWN = "DPAD_DOWN"
    const val DPAD_LEFT = "DPAD_LEFT"
    const val DPAD_RIGHT = "DPAD_RIGHT"

    /** 界面展示顺序（映射页按此排列）。 */
    val ALL: List<String> = listOf(
        A, B, X, Y, START, SELECT, L1, R1, L2, R2, L3, R3,
        DPAD_UP, DPAD_DOWN, DPAD_LEFT, DPAD_RIGHT,
    )
}

/** 单个逻辑按键的映射：多键输出 + 保持（toggle）语义。 */
data class GamepadBinding(
    val keys: List<Int> = emptyList(),
    val autoKeep: Boolean = false,
)

/** 摇杆 → 四方向仿真配置（deadzone 内不触发；hysteresis 防边界抖动）。 */
data class StickBinding(
    val up: List<Int> = emptyList(),
    val down: List<Int> = emptyList(),
    val left: List<Int> = emptyList(),
    val right: List<Int> = emptyList(),
    val deadzone: Float = DEFAULT_DEADZONE,
    val hysteresis: Float = DEFAULT_HYSTERESIS,
) {

    /** 读取指定方向（"up"/"down"/"left"/"right"）的当前键位。 */
    fun keys(direction: String): List<Int> = when (direction) {
        "up" -> up
        "down" -> down
        "left" -> left
        else -> right
    }

    /** 写入指定方向（保留其它方向与死区配置）。 */
    fun with(direction: String, binding: GamepadBinding): StickBinding = when (direction) {
        "up" -> copy(up = binding.keys)
        "down" -> copy(down = binding.keys)
        "left" -> copy(left = binding.keys)
        else -> copy(right = binding.keys)
    }

    companion object {
        const val DEFAULT_DEADZONE = 0.35f
        const val DEFAULT_HYSTERESIS = 0.05f
        val MIN_DEADZONE = 0.05f
        val MAX_DEADZONE = 0.80f
        val DIRECTIONS = listOf("up", "down", "left", "right")
    }
}

/**
 * 手柄映射表（全局一份，见 [InputConfigStore.gamepadMapFile]）。
 *
 * 默认映射按 Web 引擎（Tyrano / MV / MZ 类 AVG/单键推进玩法）给：A=确定（Enter+Space）、
 * B=取消/菜单（Esc）、X=跳过（Ctrl 保持）、Y=快进（Shift）、肩键=PageUp/PageDown、
 * 扳机=鼠标左右键（MV/MZ 的鼠标菜单）、L3=加速（Ctrl 保持）、R3=Shift 保持、
 * D-Pad/左摇杆=方向键。
 */
data class GamepadMap(
    val buttons: Map<String, GamepadBinding> = emptyMap(),
    val leftStick: StickBinding = StickBinding(),
    val rightStick: StickBinding = StickBinding(),
) {

    fun binding(id: String): GamepadBinding = buttons[id] ?: GamepadBinding()

    fun withBinding(id: String, binding: GamepadBinding): GamepadMap =
        copy(buttons = buttons + (id to binding))

    fun toJson(): String {
        val root = JSONObject()
        root.put("schema", SCHEMA)
        val buttonsJson = JSONObject()
        buttons.forEach { (id, binding) ->
            buttonsJson.put(id, bindingToJson(binding))
        }
        root.put("buttons", buttonsJson)
        val sticks = JSONObject()
        sticks.put("left", stickToJson(leftStick))
        sticks.put("right", stickToJson(rightStick))
        root.put("sticks", sticks)
        return root.toString()
    }

    companion object {

        const val SCHEMA = 1

        /** 容忍坏数据：解析失败或 schema 高于本实现时返回 null，由调用方回退默认。 */
        fun parse(raw: String?): GamepadMap? {
            if (raw.isNullOrBlank()) return null
            return runCatching {
                val root = JSONObject(raw)
                if (!InputJsonLimits.isSupportedSchema(root.opt("schema"), SCHEMA)) return null
                val buttons = LinkedHashMap<String, GamepadBinding>()
                root.optJSONObject("buttons")?.let { obj ->
                    for (id in obj.keys()) {
                        if (id !in GamepadButtons.ALL) continue
                        buttons[id] = bindingFromJson(obj.optJSONObject(id))
                    }
                }
                val sticks = root.optJSONObject("sticks")
                GamepadMap(
                    buttons = buttons,
                    leftStick = stickFromJson(sticks?.optJSONObject("left")),
                    rightStick = stickFromJson(sticks?.optJSONObject("right")),
                )
            }.getOrNull()
        }

        /** 出厂默认映射（Web 引擎语义，见类注释）。 */
        fun default(): GamepadMap = GamepadMap(
            buttons = mapOf(
                GamepadButtons.A to GamepadBinding(keys = listOf(KEY_ENTER, KEY_SPACE)),
                GamepadButtons.B to GamepadBinding(keys = listOf(KEY_ESCAPE)),
                GamepadButtons.X to GamepadBinding(keys = listOf(KEY_CTRL_LEFT), autoKeep = true),
                GamepadButtons.Y to GamepadBinding(keys = listOf(KEY_SHIFT_LEFT)),
                GamepadButtons.START to GamepadBinding(keys = listOf(KEY_ESCAPE)),
                GamepadButtons.SELECT to GamepadBinding(keys = listOf(KEY_TAB)),
                GamepadButtons.L1 to GamepadBinding(keys = listOf(KEY_PAGE_UP)),
                GamepadButtons.R1 to GamepadBinding(keys = listOf(KEY_PAGE_DOWN)),
                GamepadButtons.L2 to GamepadBinding(keys = listOf(CanonicalKeys.MOUSE_LEFT)),
                GamepadButtons.R2 to GamepadBinding(keys = listOf(CanonicalKeys.MOUSE_RIGHT)),
                GamepadButtons.L3 to GamepadBinding(keys = listOf(KEY_CTRL_LEFT), autoKeep = true),
                GamepadButtons.R3 to GamepadBinding(keys = listOf(KEY_SHIFT_LEFT), autoKeep = true),
                GamepadButtons.DPAD_UP to GamepadBinding(keys = listOf(KEY_DPAD_UP)),
                GamepadButtons.DPAD_DOWN to GamepadBinding(keys = listOf(KEY_DPAD_DOWN)),
                GamepadButtons.DPAD_LEFT to GamepadBinding(keys = listOf(KEY_DPAD_LEFT)),
                GamepadButtons.DPAD_RIGHT to GamepadBinding(keys = listOf(KEY_DPAD_RIGHT)),
            ),
            leftStick = StickBinding(
                up = listOf(KEY_DPAD_UP),
                down = listOf(KEY_DPAD_DOWN),
                left = listOf(KEY_DPAD_LEFT),
                right = listOf(KEY_DPAD_RIGHT),
            ),
        )

        private const val KEY_ENTER = 66
        private const val KEY_SPACE = 62
        private const val KEY_ESCAPE = 111
        private const val KEY_TAB = 61
        private const val KEY_SHIFT_LEFT = 59
        private const val KEY_CTRL_LEFT = 113
        private const val KEY_PAGE_UP = 92
        private const val KEY_PAGE_DOWN = 93
        private const val KEY_DPAD_UP = 19
        private const val KEY_DPAD_DOWN = 20
        private const val KEY_DPAD_LEFT = 21
        private const val KEY_DPAD_RIGHT = 22

        private fun bindingToJson(binding: GamepadBinding): JSONObject {
            val obj = JSONObject()
            val keys = JSONArray()
            binding.keys.forEach { keys.put(it) }
            obj.put("keys", keys)
            if (binding.autoKeep) obj.put("autoKeep", true)
            return obj
        }

        private fun bindingFromJson(obj: JSONObject?): GamepadBinding {
            if (obj == null) return GamepadBinding()
            return GamepadBinding(
                keys = keysFromJson(obj.optJSONArray("keys")),
                autoKeep = obj.optBoolean("autoKeep", false),
            )
        }

        private fun stickToJson(stick: StickBinding): JSONObject = JSONObject().apply {
            put("up", keysToJson(stick.up))
            put("down", keysToJson(stick.down))
            put("left", keysToJson(stick.left))
            put("right", keysToJson(stick.right))
            put("deadzone", stick.deadzone.toDouble())
            put("hysteresis", stick.hysteresis.toDouble())
        }

        private fun stickFromJson(obj: JSONObject?): StickBinding {
            if (obj == null) return StickBinding()
            return StickBinding(
                up = keysFromJson(obj.optJSONArray("up")),
                down = keysFromJson(obj.optJSONArray("down")),
                left = keysFromJson(obj.optJSONArray("left")),
                right = keysFromJson(obj.optJSONArray("right")),
                deadzone = obj.optDouble("deadzone", StickBinding.DEFAULT_DEADZONE.toDouble())
                    .toFloat().coerceIn(StickBinding.MIN_DEADZONE, StickBinding.MAX_DEADZONE),
                hysteresis = obj.optDouble("hysteresis", StickBinding.DEFAULT_HYSTERESIS.toDouble())
                    .toFloat().coerceIn(0f, 0.4f),
            )
        }

        private fun keysToJson(keys: List<Int>): JSONArray = JSONArray().apply {
            keys.forEach { put(it) }
        }

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
