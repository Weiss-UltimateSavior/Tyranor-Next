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
    /**
     * 用户从未显式设置虚拟按键开关时本宿主的默认值。
     *
     * 只有 RPG Maker MV/MZ 传 true：改造前其余 Web 宿主（Tyrano / VN / WebOther）没有
     * 按键层，对它们默认开启会让老用户升级后凭空多出一层按钮。
     */
    private val engineDefaultsEnabled: Boolean,
) {

    private val settings: InputConfigStore.InputSettings =
        InputConfigStore.resolve(activity, gameId, engineDefaultsEnabled)

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

    /**
     * 宿主已销毁标记（[Bridge] 延迟投递的门控，见 [hostAlive]）。
     *
     * `runOnUiThread` 投递的 runnable 可能在 [onDestroy] 之后才执行：此时再
     * `enterEditMode` 会把新 panel 挂到已销毁的容器上（BadToken/泄漏），
     * 必须先拦掉。
     */
    private var destroyed = false

    /**
     * 宿主是否仍可承载 UI 操作（编辑面板的新建/弹窗）。
     *
     * JS 桥回调在 WebView 线程，`enterEdit` 等经 `runOnUiThread` 延迟执行，
     * 与宿主退出的竞态窗口必须用本判定收敛（不能只判 `padView != null`）。
     */
    private fun hostAlive(): Boolean =
        !destroyed && !activity.isFinishing && !activity.isDestroyed

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
        // 页面重载 / 切回前台：重新读取生效设置。
        // 注意 prefs 的跨进程可见性：方案 JSON 与手柄映射文件走文件系统、即时生效；
        // 三个 prefs 键（开关/方案选择）会被独立进程缓存，设置页改动需新开游戏才生效
        // （见 applyEnabledState 的 KDoc）。
        val current = InputConfigStore.resolve(activity, gameId, engineDefaultsEnabled)
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
     * 作用范围（原先只在构造时判定一次，用户在设置页关掉开关、回到游戏仍然有按键层，
     * 与设置不一致，这是本方法存在的理由）：
     *  - **方案 JSON 内容、手柄映射文件**：每次 [onResume] 重读文件系统，编辑后切回游戏
     *    即时生效；
     *  - **`input_pad_enabled` / `input_gamepad_enabled` / `input_profile_id`**：存在共享
     *    prefs，而引擎宿主跑在独立进程（`:tyrano` / `:rpgmaker` / ...），读到的是**本进程
     *    缓存的** `tyranor_prefs`——app 设置页（主进程）写完后，存活的引擎进程不会观察到，
     *    需新开一次游戏（进程重启）才生效。
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
        destroyed = true
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
            activity.runOnUiThread {
                // 退出竞态：onDestroy 后排队的投递不得再建 panel（死容器 + BadToken）
                if (!hostAlive()) return@runOnUiThread
                padView?.enterEditMode()
            }
        }

        @android.webkit.JavascriptInterface
        fun exitEdit() {
            activity.runOnUiThread {
                if (!hostAlive()) return@runOnUiThread
                padView?.cancelEdit()
            }
        }

        @android.webkit.JavascriptInterface
        fun resetToDefaults() {
            activity.runOnUiThread {
                if (!hostAlive()) return@runOnUiThread
                padView?.resetToDefaults()
            }
        }
    }

    companion object {

        /**
         * JS 桥注册名；**引用方在 engine assets**（`__rpgmaker_mod_ui.js` 的 `inputBridge()`）。
         *
         * 该字面量跨 Kotlin / JS 边界，改名只表现为「点了没反应」，由
         * `InputBridgeContractTest` 锚定两侧一致。
         */
        const val BRIDGE_NAME = "TyranorInputNative"

        private const val TAG = "TyranorInput"

        /** 在宿主 onCreate 装配（须在 setContentView 之后、addView 容器就绪时调用）。 */
        fun install(
            activity: Activity,
            container: ViewGroup,
            gameId: String,
            theme: EngineThemeColors.Palette,
            sink: InputSink,
            /** 未显式设置开关时的默认值；宿主按引擎类型传入（MV/MZ 为 true，其余 Web 为 false）。 */
            engineDefaultsEnabled: Boolean,
        ): InputRemapController = InputRemapController(
            activity, container, gameId, theme, sink, engineDefaultsEnabled,
        )

        /** Web 宿主便捷装配（WebView evaluateJavascript 出口 + 宿主侧动作）。 */
        fun installWeb(
            activity: Activity,
            container: ViewGroup,
            gameId: String,
            theme: EngineThemeColors.Palette,
            engineDefaultsEnabled: Boolean,
            dispatchJs: (String) -> Unit,
        ): InputRemapController = install(
            activity,
            container,
            gameId,
            theme,
            WebInputSink(dispatchJs) { action -> handleHostAction(activity, action) },
            engineDefaultsEnabled,
        )

        /**
         * 宿主侧动作处理（canonical 动作段）：虚拟按键与手柄映射共用。
         *
         * 目前只有截屏；返回 false 表示该动作本宿主不支持（调用方无需额外处理）。
         */
        private fun handleHostAction(activity: Activity, action: Int): Boolean = when (action) {
            CanonicalKeys.ACTION_SCREENSHOT -> {
                ScreenCapture.capture(activity) { result ->
                    // 截屏可拖到写盘结束（看门狗上限 5s，MediaStore 慢时更久）：期间用户可能
                    // 已退出游戏。回调仍持有 activity，这里先判存活，避免在已销毁的宿主上弹
                    // Toast（并把 activity 多留一段时间的强引用）。
                    if (activity.isFinishing || activity.isDestroyed) return@capture
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
                    // Toast 不依赖窗口令牌，用 applicationContext 更稳（且不再持有 Activity）
                    runCatching {
                        Toast.makeText(activity.applicationContext, message, Toast.LENGTH_LONG).show()
                    }
                }
                true
            }

            else -> false
        }
    }
}
