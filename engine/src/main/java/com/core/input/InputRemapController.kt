package com.core.input

import android.app.Activity
import android.util.Log
import android.hardware.input.InputManager
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import com.core.engine.EngineThemeColors
import com.core.engine.R

/**
 * 输入重映射统一组件：宿主（Web 系 Tyrano / MV / MZ，后续其余内置引擎）只需在
 * onCreate 里 [install]，并把 [dispatchKeyEvent] / [dispatchGenericMotionEvent] /
 * [onPause] / [onResume] / [handleBack] 接到 Activity 生命周期即可。
 *
 * 职责聚合：
 *  - 装配 [InputSink]（本引擎的按键出口，Web 为 [WebInputSink]）；
 *  - 手柄：[InputRouter] 消费物理手柄事件（映射表来自 [InputConfigStore]）；
 *  - 虚拟按键：[VirtualPadView] 覆盖层 + 游戏内编辑（[VirtualPadEditPanel]）+ 落盘；
 *  - 宿主侧动作（canonical 动作段）：截屏经 [ScreenCapture]（PixelCopy 取全窗口像素）；
 *  - 生效开关与方案选择：启动时经 [InputConfigStore.resolve] 读全局/单游戏覆盖。
 *
 * 宿主不得在未调用 [install] 时调用其余方法；[enabled] 为 false 时组件不挂载任何 View、
 * 也不消费事件（设置页关闭后行为与改造前一致）。
 */
class InputRemapController private constructor(
    private val activity: Activity,
    private val container: ViewGroup,
    private val gameId: String,
    private val theme: EngineThemeColors.Palette,
    private val sink: InputSink,
) {

    private val settings: InputConfigStore.InputSettings = InputConfigStore.resolve(activity, gameId)

    /**
     * 虚拟按键与手柄映射共享的派发器。
     *
     * 共享的原因是键级引用计数必须跨来源生效：默认布局里虚拟按键 OK 与手柄 A 都映射
     * Enter+Space，各自持有独立派发器时「先松一侧」会误发 keyup，打断另一侧的长按。
     */
    private val dispatcher = KeyDispatcher(sink)

    /**
     * 手柄插拔监听：手柄拔出时按下的键不会收到 UP（部分设备连 CANCEL 都不发），
     * autoKeep 键（如跳过=Ctrl）会永久卡住；断开即重置手柄侧输出。
     *
     * 必须声明在 [router] 之前：Kotlin 按声明顺序初始化，router 的初始化器里会
     * 调 registerDeviceListener()，晚声明会让此处读到 null 而注册静默失败。
     */
    private val deviceListener = object : InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(deviceId: Int) = Unit

        override fun onInputDeviceRemoved(deviceId: Int) {
            router?.reset()
        }

        override fun onInputDeviceChanged(deviceId: Int) {
            router?.reset()
        }
    }

    private var editPanel: VirtualPadEditPanel? = null

    private val padListener = object : VirtualPadView.Listener {
        override fun onEditStarted() {
            val pad = padView ?: return
            editPanel = VirtualPadEditPanel.attach(
                parent = container,
                context = activity,
                pad = pad,
                theme = theme,
                onSave = {
                    pad.commitEdit()
                },
                onCancel = {
                    pad.cancelEdit()
                },
            )
        }

        override fun onEditEnded() {
            editPanel?.dismiss()
            editPanel = null
            // 编辑结束（含取消）立即释放可能挂着的按键，避免编辑期遗留按下状态
            padView?.releaseAllKeys()
        }

        override fun onSelectionChanged(info: VirtualPadView.SelectionInfo?) {
            editPanel?.refresh()
        }

        override fun onProfileCommitted(profile: PadProfile) {
            val id = InputConfigStore.sanitizeProfileId(profile.id)
            val ok = id != null && InputConfigStore.writeProfile(activity, profile.copy(id = id))
            if (ok) {
                Log.i(TAG, "pad profile saved id=$id buttons=${profile.buttons.size}")
            } else {
                // 写盘失败必须在收起编辑态之前打标，否则用户改完的布局被静默丢弃
                Log.w(TAG, "pad profile save failed id=$id")
                padView?.markSaveFailed()
                runCatching {
                    Toast.makeText(
                        activity,
                        activity.getString(R.string.engine_input_save_failed),
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }

        override fun onPadVisibilityChanged(visible: Boolean) {
            InputConfigStore.setPadVisible(activity, visible)
        }
    }

    private var padView: VirtualPadView? = if (settings.padEnabled) {
        VirtualPadView(activity, sink, dispatcher).also { view ->
            view.theme = VirtualPadView.PadTheme(theme.primary, theme.onPrimary)
            view.profile = InputConfigStore.readProfileOrBuiltin(activity, settings.profileId)
            view.bindPreferences(activity)
            view.listener = padListener
            container.addView(
                view,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
        }
    } else {
        null
    }

    private var router: InputRouter? = if (settings.gamepadEnabled) {
        InputRouter(InputConfigStore.readGamepadMap(activity), dispatcher).also {
            registerDeviceListener()
        }
    } else {
        null
    }

    /** 手柄按键事件入口：宿主在 dispatchKeyEvent 最前调用；返回 true 表示已消费。 */
    fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // BACK 交给宿主既有逻辑（返回手势 / 退出确认），本组件不碰
        if (event.keyCode == KeyEvent.KEYCODE_BACK) return false
        return router?.handleKeyEvent(event) == true
    }

    /** 编辑态的返回键拦截：宿主在 handleBackRequest 前调用；返回 true 表示已消费。 */
    fun handleBack(): Boolean {
        val pad = padView ?: return false
        if (!pad.isEditing) return false
        pad.cancelEdit()
        return true
    }

    /** 手柄轴事件入口：宿主在 dispatchGenericMotionEvent 最前调用；CANCEL 也要收尾。 */
    fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_CANCEL) {
            router?.reset()
            return false
        }
        return router?.handleGenericMotionEvent(event) == true
    }

    fun onPause() {
        padView?.onHostPause()
        router?.reset()
        // 派发器已释放 canonical 键，但页面侧（__tyranorInput）还持有 pressed 集合与
        // 未完成的 click timer：退后台/页面重载时必须让 Sink 收尾，否则残留按压
        runCatching { sink.releaseAll() }
    }

    fun onResume() {
        // 页面重载 / 切回前台：重新读取生效设置（设置页可能已改开关或方案）
        val current = InputConfigStore.resolve(activity, gameId)
        applyEnabledState(current)
        router?.updateMap(InputConfigStore.readGamepadMap(activity))
        val pad = padView ?: return
        if (pad.isEditing) return
        val profile = InputConfigStore.readProfileOrBuiltin(activity, current.profileId)
        pad.profile = profile
        pad.bindPreferences(activity)
    }

    /**
     * 按最新设置挂载/卸载虚拟按键与手柄映射。
     *
     * 设置页改动后回到游戏即可生效，无需重启：原先只在构造时判定一次，
     * 用户在设置页关掉开关、回到游戏仍然有按键层（与设置不一致）。
     */
    private fun applyEnabledState(current: InputConfigStore.InputSettings) {
        if (current.padEnabled && padView == null) {
            val pad = VirtualPadView(activity, sink, dispatcher)
            pad.theme = VirtualPadView.PadTheme(theme.primary, theme.onPrimary)
            pad.profile = InputConfigStore.readProfileOrBuiltin(activity, current.profileId)
            pad.bindPreferences(activity)
            pad.listener = padListener
            container.addView(
                pad,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
            padView = pad
        } else if (!current.padEnabled && padView != null) {
            padView?.let { pad ->
                // 先收起编辑面板：否则面板悬空，返回键不再能取消编辑
                if (pad.isEditing) pad.cancelEdit()
                pad.detach()
                (pad.parent as? ViewGroup)?.removeView(pad)
            }
            padView = null
        }

        if (current.gamepadEnabled && router == null) {
            router = InputRouter(InputConfigStore.readGamepadMap(activity), dispatcher)
                .also { registerDeviceListener() }
        } else if (!current.gamepadEnabled && router != null) {
            router?.reset()
            unregisterDeviceListener()
            router = null
        }
    }

    private fun registerDeviceListener() {
        runCatching {
            activity.getSystemService(InputManager::class.java)?.registerInputDeviceListener(deviceListener, null)
        }.onFailure { Log.w(TAG, "register input device listener failed", it) }
    }

    private fun unregisterDeviceListener() {
        runCatching {
            activity.getSystemService(InputManager::class.java)?.unregisterInputDeviceListener(deviceListener)
        }.onFailure { Log.w(TAG, "unregister input device listener failed", it) }
    }

    fun onDestroy() {
        unregisterDeviceListener()
        editPanel?.dismiss()
        editPanel = null
        padView?.detach()
        padView?.let { view -> (view.parent as? ViewGroup)?.removeView(view) }
        router?.reset()
    }

    /**
     * 页面内入口桥（注册名 [BRIDGE_NAME]）：供 RPG Maker 修改器 UI 的「键盘」页签调用。
     *
     * 与旧 `TyranorTouchPadNative` 的区别：不再读写布局配置（配置已由原生层直接落盘），
     * 只暴露「进入编辑 / 恢复默认 / 是否可用」三个动作。
     */
    inner class Bridge {
        @android.webkit.JavascriptInterface
        fun isAvailable(): Boolean = padView != null

        @android.webkit.JavascriptInterface
        fun enterEdit() {
            activity.runOnUiThread { padView?.enterEditMode() }
        }

        @android.webkit.JavascriptInterface
        fun exitEdit() {
            activity.runOnUiThread { padView?.cancelEdit() }
        }

        @android.webkit.JavascriptInterface
        fun resetToDefaults() {
            activity.runOnUiThread { padView?.resetToDefaults() }
        }
    }

    companion object {

        /** JS 桥注册名（app 侧修改器 UI 固定引用）。 */
        const val BRIDGE_NAME = "TyranorInputNative"

        private const val TAG = "TyranorInput"

        /** 在宿主 onCreate 装配（须在 setContentView 之后、addView 容器就绪时调用）。 */
        fun install(
            activity: Activity,
            container: ViewGroup,
            gameId: String,
            theme: EngineThemeColors.Palette,
            sink: InputSink,
        ): InputRemapController = InputRemapController(activity, container, gameId, theme, sink)

        /** Web 宿主便捷装配（WebView evaluateJavascript 出口 + 宿主侧动作）。 */
        fun installWeb(
            activity: Activity,
            container: ViewGroup,
            gameId: String,
            theme: EngineThemeColors.Palette,
            dispatchJs: (String) -> Unit,
        ): InputRemapController = install(
            activity,
            container,
            gameId,
            theme,
            WebInputSink(dispatchJs) { action -> handleHostAction(activity, action) },
        )

        /**
         * 宿主侧动作处理（canonical 动作段）：虚拟按键与手柄映射共用。
         *
         * 目前只有截屏；返回 false 表示该动作本宿主不支持（调用方无需额外处理）。
         */
        private fun handleHostAction(activity: Activity, action: Int): Boolean = when (action) {
            CanonicalKeys.ACTION_SCREENSHOT -> {
                ScreenCapture.capture(activity) { result ->
                    val publicDir = ScreenCapture.publicDirectory()
                    val message = when (result) {
                        is ScreenCapture.CaptureResult.Saved -> {
                            // 落在公共相册：提示完整路径（用户常要去找图/分享）；
                            // 其余路径（如无「所有文件访问」时的私有目录）如实说明，
                            // 避免用户去相册找不到
                            val file = result.file
                            if (file.parentFile?.absolutePath == publicDir.absolutePath) {
                                activity.getString(R.string.engine_input_screenshot_saved, file.absolutePath)
                            } else {
                                activity.getString(
                                    R.string.engine_input_screenshot_saved_private,
                                    file.absolutePath,
                                )
                            }
                        }

                        // 连点：上一张仍在保存——给反馈而不是静默丢弃
                        ScreenCapture.CaptureResult.Busy ->
                            activity.getString(R.string.engine_input_screenshot_busy)

                        ScreenCapture.CaptureResult.Failed ->
                            activity.getString(R.string.engine_input_screenshot_failed)
                    }
                    runCatching { Toast.makeText(activity, message, Toast.LENGTH_LONG).show() }
                }
                true
            }

            else -> false
        }
    }
}
