package com.core.input

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent

/**
 * 手柄输入路由器：物理手柄事件 → 逻辑按键 → [InputSink]。
 *
 * 由宿主在 `Activity.dispatchKeyEvent` / `dispatchGenericMotionEvent` 最前调用（早于
 * View 树与 WebView）。命中映射即消费，避免 SDL 系宿主二次分发；BACK 等系统键不在此处理。
 *
 * 轴处理：HAT / 扳机 / 左右摇杆分别归一为逻辑按键；摇杆方向仿真带死区与滞回，
 * 仅在方向状态翻转时派发，避免边界抖动连发。多来源（按键 / HAT / 摇杆）驱动同一逻辑
 * 按键时按来源计数，互不误释放。
 */
class InputRouter(
    initialMap: GamepadMap,
    /** 与虚拟按键层共享的派发器：保证同一键的键级引用计数跨来源生效。 */
    private val dispatcher: KeyDispatcher,
) {

    /**
     * 当前映射快照。宿主在启动与回到前台时经 [updateMap] 刷新；事件处理只读内存快照，
     * 不做文件 IO（轴事件以输入采样率到达，逐事件读盘会造成主线程抖动）。
     */
    var map: GamepadMap = initialMap
        private set

    /** 更新映射快照（app 侧改写手柄映射文件后由宿主在 onResume 调用）。 */
    fun updateMap(value: GamepadMap) {
        map = value
    }


    /** 逻辑按键 → 当前按下的来源集合（"key" / "hat" / "trigger" / "stick"）。 */
    private val activeSources = HashMap<String, MutableSet<String>>()

    /** 摇杆方向激活状态（死区/滞回判定；状态机实现见 [StickDirectionResolver]）。 */
    private val stickDirs = StickDirectionResolver()

    private var hatActive = false
    private var leftTriggerActive = false
    private var rightTriggerActive = false

    fun handleKeyEvent(event: KeyEvent): Boolean {
        if (!isGamepadKeyEvent(event)) return false
        val id = logicalFor(event.keyCode)
        if (id == null) {
            // D-Pad 中心键：一次性释放四向（对齐 FCL 行为），不参与映射
            if (event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER) {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    dpadIds.forEach { setPressed(it, SOURCE_KEY, false) }
                }
                return true
            }
            return false
        }
        when (event.action) {
            KeyEvent.ACTION_DOWN -> if (event.repeatCount == 0) setPressed(id, SOURCE_KEY, true)
            KeyEvent.ACTION_UP -> setPressed(id, SOURCE_KEY, false)
        }
        return true
    }

    fun handleGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.actionMasked != MotionEvent.ACTION_MOVE) return false
        if (!isGamepadMotionEvent(event)) return false

        val hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X)
        val hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
        if (hatX != 0f || hatY != 0f) {
            hatActive = true
            setPressed(GamepadButtons.DPAD_RIGHT, SOURCE_HAT, hatX > HAT_THRESHOLD)
            setPressed(GamepadButtons.DPAD_LEFT, SOURCE_HAT, hatX < -HAT_THRESHOLD)
            setPressed(GamepadButtons.DPAD_DOWN, SOURCE_HAT, hatY > HAT_THRESHOLD)
            setPressed(GamepadButtons.DPAD_UP, SOURCE_HAT, hatY < -HAT_THRESHOLD)
        } else if (hatActive) {
            hatActive = false
            dpadIds.forEach { setPressed(it, SOURCE_HAT, false) }
        }

        val leftTrigger = event.getAxisValue(MotionEvent.AXIS_LTRIGGER)
        val rightTrigger = event.getAxisValue(MotionEvent.AXIS_RTRIGGER)
        if (leftTrigger != 0f) {
            leftTriggerActive = true
            setPressed(GamepadButtons.L2, SOURCE_TRIGGER, leftTrigger > TRIGGER_THRESHOLD)
        } else if (leftTriggerActive) {
            leftTriggerActive = false
            setPressed(GamepadButtons.L2, SOURCE_TRIGGER, false)
        }
        if (rightTrigger != 0f) {
            rightTriggerActive = true
            setPressed(GamepadButtons.R2, SOURCE_TRIGGER, rightTrigger > TRIGGER_THRESHOLD)
        } else if (rightTriggerActive) {
            rightTriggerActive = false
            setPressed(GamepadButtons.R2, SOURCE_TRIGGER, false)
        }

        updateStick("left", map.leftStick, event.getAxisValue(MotionEvent.AXIS_X), event.getAxisValue(MotionEvent.AXIS_Y))
        updateStick("right", map.rightStick, event.getAxisValue(MotionEvent.AXIS_Z), event.getAxisValue(MotionEvent.AXIS_RZ))
        return true
    }

    /** 释放全部输出（退后台 / 页面重载 / 关闭映射）。 */
    fun reset() {
        // 只释放手柄侧：拔插手柄、轴 CANCEL、关闭手柄开关时，
        // 屏幕上按住的虚拟按键与 autoKeep 保持不应被一并解除
        dispatcher.releaseScope(GP_SCOPE)
        activeSources.clear()
        stickDirs.clear()
        hatActive = false
        leftTriggerActive = false
        rightTriggerActive = false
    }

    private fun updateStick(prefix: String, stick: StickBinding, x: Float, y: Float) {
        updateStickDir(prefix, "up", -y, stick)
        updateStickDir(prefix, "down", y, stick)
        updateStickDir(prefix, "left", -x, stick)
        updateStickDir(prefix, "right", x, stick)
    }

    private fun updateStickDir(prefix: String, dir: String, component: Float, stick: StickBinding) {
        val key = "$prefix.$dir"
        // 阈值来自当前映射快照（updateMap 后即生效），状态保留在状态机内
        val flipped = stickDirs.update(key, component, stick.deadzone, stick.hysteresis)
        if (!flipped) return
        setPressed(stickLogicalId(prefix, dir), SOURCE_STICK, stickDirs.isActive(key))
    }

    private fun stickLogicalId(prefix: String, dir: String): String = "$prefix.$dir"

    private fun setPressed(id: String, source: String, down: Boolean) {
        val sources = activeSources.getOrPut(id) { mutableSetOf() }
        val wasActive = sources.isNotEmpty()
        if (down) sources.add(source) else sources.remove(source)
        val isActive = sources.isNotEmpty()
        if (wasActive == isActive) return
        val binding = bindingFor(id)
        val dispatchId = GP_SCOPE + id
        if (isActive) {
            dispatcher.press(dispatchId, binding.keys, binding.autoKeep)
        } else {
            dispatcher.release(dispatchId, binding.autoKeep)
        }
    }

    private fun bindingFor(id: String): GamepadBinding {
        val map = this.map
        return when (id) {
            "left.up" -> GamepadBinding(keys = map.leftStick.up)
            "left.down" -> GamepadBinding(keys = map.leftStick.down)
            "left.left" -> GamepadBinding(keys = map.leftStick.left)
            "left.right" -> GamepadBinding(keys = map.leftStick.right)
            "right.up" -> GamepadBinding(keys = map.rightStick.up)
            "right.down" -> GamepadBinding(keys = map.rightStick.down)
            "right.left" -> GamepadBinding(keys = map.rightStick.left)
            "right.right" -> GamepadBinding(keys = map.rightStick.right)
            else -> map.binding(id)
        }
    }

    private fun logicalFor(keyCode: Int): String? = when (keyCode) {
        KeyEvent.KEYCODE_BUTTON_A -> GamepadButtons.A
        KeyEvent.KEYCODE_BUTTON_B -> GamepadButtons.B
        KeyEvent.KEYCODE_BUTTON_X -> GamepadButtons.X
        KeyEvent.KEYCODE_BUTTON_Y -> GamepadButtons.Y
        KeyEvent.KEYCODE_BUTTON_START -> GamepadButtons.START
        KeyEvent.KEYCODE_BUTTON_SELECT -> GamepadButtons.SELECT
        KeyEvent.KEYCODE_BUTTON_L1 -> GamepadButtons.L1
        KeyEvent.KEYCODE_BUTTON_R1 -> GamepadButtons.R1
        KeyEvent.KEYCODE_BUTTON_L2 -> GamepadButtons.L2
        KeyEvent.KEYCODE_BUTTON_R2 -> GamepadButtons.R2
        KeyEvent.KEYCODE_BUTTON_THUMBL -> GamepadButtons.L3
        KeyEvent.KEYCODE_BUTTON_THUMBR -> GamepadButtons.R3
        KeyEvent.KEYCODE_DPAD_UP -> GamepadButtons.DPAD_UP
        KeyEvent.KEYCODE_DPAD_DOWN -> GamepadButtons.DPAD_DOWN
        KeyEvent.KEYCODE_DPAD_LEFT -> GamepadButtons.DPAD_LEFT
        KeyEvent.KEYCODE_DPAD_RIGHT -> GamepadButtons.DPAD_RIGHT
        else -> null
    }

    companion object {
        /** 派发器里的来源前缀：与虚拟按键侧（`pad:`）隔离，互不误释放。 */
        private const val GP_SCOPE = "gp:"

        private const val SOURCE_KEY = "key"
        private const val SOURCE_HAT = "hat"
        private const val SOURCE_TRIGGER = "trigger"
        private const val SOURCE_STICK = "stick"
        private const val HAT_THRESHOLD = 0.85f
        private const val TRIGGER_THRESHOLD = 0.5f

        private val dpadIds = listOf(
            GamepadButtons.DPAD_UP, GamepadButtons.DPAD_DOWN,
            GamepadButtons.DPAD_LEFT, GamepadButtons.DPAD_RIGHT,
        )

        /** 手柄按键事件识别：SOURCE_GAMEPAD 设备；排除字母键盘类型（外接键盘不拦截）。 */
        fun isGamepadKeyEvent(event: KeyEvent): Boolean {
            val device = event.device
            if (device != null && device.keyboardType == InputDevice.KEYBOARD_TYPE_ALPHABETIC) return false
            // 摇杆类设备也可能发按键（部分手柄的方向键经 JOYSTICK 上报），
            // 与轴判定保持一致，否则这类设备的按键会被静默忽略
            val gamepad = InputDevice.SOURCE_GAMEPAD
            val joystick = InputDevice.SOURCE_JOYSTICK
            if (event.isFromSource(gamepad) || event.isFromSource(joystick)) return true
            val sources = device?.sources ?: return false
            return (sources and gamepad) == gamepad || (sources and joystick) == joystick
        }

        /** 手柄轴事件识别：SOURCE_JOYSTICK / SOURCE_GAMEPAD。 */
        fun isGamepadMotionEvent(event: MotionEvent): Boolean {
            val source = event.source
            return (source and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK ||
                (source and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
        }
    }
}
