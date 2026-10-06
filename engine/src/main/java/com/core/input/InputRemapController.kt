package com.core.input

import android.app.Activity
import android.util.Log
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
            val id = InputConfigStore.sanitizeProfileId(profile.id) ?: return
            if (InputConfigStore.writeProfile(activity, profile.copy(id = id))) {
                Log.i(TAG, "pad profile saved id=$id buttons=${profile.buttons.size}")
            } else {
                Log.w(TAG, "pad profile save failed id=$id")
            }
        }

        override fun onPadVisibilityChanged(visible: Boolean) {
            InputConfigStore.setPadVisible(activity, visible)
        }
    }

    private val padView: VirtualPadView? = if (settings.padEnabled) {
        VirtualPadView(activity, sink).also { view ->
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

    private val router: InputRouter? = if (settings.gamepadEnabled) {
        InputRouter(sink, InputConfigStore.readGamepadMap(activity))
    } else {
        null
    }

    /** 手柄按键事件入口：宿主在 dispatchKeyEvent 最前调用；返回 true 表示已消费。 */
    fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // BACK 交给宿主既有逻辑（返回手势 / 退出确认），本组件不碰
        if (event.keyCode == KeyEvent.KEYCODE_BACK) return false
        return router?.handleKeyEvent(event) == true
    }

    /** 手柄轴事件入口：宿主在 dispatchGenericMotionEvent 最前调用。 */
    fun dispatchGenericMotionEvent(event: MotionEvent): Boolean =
        router?.handleGenericMotionEvent(event) == true

    /** 编辑态的返回键拦截：宿主在 handleBackRequest 前调用；返回 true 表示已消费。 */
    fun handleBack(): Boolean {
        val pad = padView ?: return false
        if (!pad.isEditing) return false
        pad.cancelEdit()
        return true
    }

    fun onPause() {
        padView?.releaseAllKeys()
        router?.reset()
    }

    fun onResume() {
        // 页面重载 / 切回前台：重新读取方案与手柄映射（设置页可能已改）
        val current = InputConfigStore.resolve(activity, gameId)
        router?.updateMap(InputConfigStore.readGamepadMap(activity))
        val pad = padView ?: return
        if (pad.isEditing) return
        val profile = InputConfigStore.readProfileOrBuiltin(activity, current.profileId)
        pad.profile = profile
        pad.bindPreferences(activity)
    }

    fun onDestroy() {
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
                ScreenCapture.capture(activity) { file ->
                    val message = if (file != null) {
                        activity.getString(R.string.engine_input_screenshot_saved, file.name)
                    } else {
                        activity.getString(R.string.engine_input_screenshot_failed)
                    }
                    runCatching { Toast.makeText(activity, message, Toast.LENGTH_SHORT).show() }
                }
                true
            }

            else -> false
        }
    }
}
