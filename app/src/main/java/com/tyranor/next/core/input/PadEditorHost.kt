package com.tyranor.next.core.input

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.core.engine.EngineThemeColors
import com.core.input.InputSink
import com.core.input.PadProfile
import com.core.input.VirtualPadEditPanel
import com.core.input.VirtualPadView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 按键布局编辑器的应用层宿主（core 门面）。
 *
 * 为什么需要它：engine 侧的 [VirtualPadView] / [VirtualPadEditPanel] 必须活在游戏宿主
 * 进程内，而设置页希望复用同一套渲染与编辑逻辑（所见即所得）。按三层架构约定 `ui` 层
 * 不得直接 import `com.core.*`，因此由 core 承担这层适配：UI 只拿到一个普通 [rootView]
 * 与保存/取消回调，不接触任何 engine 类型。
 *
 * 生命周期：根视图由本类自建并持有 pad 与编辑面板；UI 在 `AndroidView` 工厂里取
 * [rootView] 挂载即可，Activity 销毁时随视图树回收（pad 的 Handler 在
 * `onDetachedFromWindow` 中清理，本类的协程作用域在根视图 detach 时取消）。
 */
class PadEditorHost private constructor(
    private val pad: VirtualPadView,
    val rootView: View,
    private val onSavedCallback: (PadProfile) -> Unit,
    /**
     * 编辑会话结束（保存成功与取消共用）。
     *
     * 命名刻意不叫 `onCancelled`：保存成功路径也会走到这里（面板收起 + 页面退出）。
     * 若将来在此挂「丢弃草稿」之类的语义，保存路径会误执行。
     */
    private val onEditEndedCallback: () -> Unit,
    private val onSaveFailedCallback: () -> Unit,
) {

    /** 落盘协程：挂在编辑器根视图的生命周期上（detach 即取消）。 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    companion object {

        /** 预览用按键出口：设置页里编辑布局不向任何引擎发送输入。 */
        private object PreviewSink : InputSink {
            override fun send(key: Int, down: Boolean) = Unit
            override fun releaseAll() = Unit
        }

        /**
         * 构建编辑器宿主；方案文件缺失/不可读时返回 null（调用方提示并退出，
         * 不在组合期抛异常）。
         */
        fun create(
            context: Context,
            profileId: String,
            primaryColor: Int,
            onPrimaryColor: Int,
            onSaved: (PadProfile) -> Unit,
            onEditEnded: () -> Unit,
            onSaveFailed: () -> Unit = {},
        ): PadEditorHost? {
            val profile = InputRemapRepository.readProfile(context, profileId) ?: return null
            val palette = EngineThemeColors.Palette(
                primary = primaryColor,
                onPrimary = onPrimaryColor,
                card = 0,
                text = 0,
                textMuted = 0,
            )

            val pad = VirtualPadView(context, PreviewSink).apply {
                theme = VirtualPadView.PadTheme(primaryColor, onPrimaryColor)
                this.profile = profile
                setPadVisible(true)
            }
            val root = FrameLayout(context).apply {
                addView(
                    pad,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    ),
                )
            }
            val panel = VirtualPadEditPanel.attach(
                parent = root,
                context = context,
                pad = pad,
                theme = palette,
                onSave = { pad.commitEdit() },
                onCancel = { pad.cancelEdit() },
            )
            val host = PadEditorHost(pad, root, onSaved, onEditEnded, onSaveFailed)
            // 落盘协程随视图树销毁：detach 后不该再有人调 onSaved/onSaveFailed
            root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(view: View) = Unit

                override fun onViewDetachedFromWindow(view: View) {
                    host.scope.cancel()
                }
            })
            pad.listener = object : VirtualPadView.Listener {
                override fun onEditStarted() = Unit

                override fun onEditEnded() {
                    // 保存与取消都走这里；面板先收起，再交给页面决定是否退出
                    panel.dismiss()
                    host.onEditEndedCallback()
                }

                override fun onSelectionChanged(info: VirtualPadView.SelectionInfo?) {
                    panel.refresh()
                }

                override fun onProfileCommitted(committed: PadProfile) {
                    // 写盘带 fsync，放主线程会在保存瞬间卡住界面：交给 IO 线程，
                    // 结果回主线程再收尾。提交协议据此走「会话号 → 结果校验」两段
                    // （见 VirtualPadView.beginDeferredSave / completeDeferredSave）
                    val session = pad.beginDeferredSave()
                    host.scope.launch {
                        val ok = withContext(Dispatchers.IO) {
                            InputRemapRepository.writeProfile(context, committed)
                        }
                        // 会话已被新的提交接替、或用户已取消/离开：结果不再提示、不再改状态
                        // （否则连点保存会「已保存成功又弹失败」，取消后还会弹过期提示）
                        if (!pad.isDeferredSaveCurrent(session)) return@launch
                        if (ok) {
                            // 先通知页面再收尾：与同步路径一致（页面在 onSaved 里会退出并
                            // 回收视图树），失败时才由页面提示并留在编辑态
                            host.onSavedCallback(committed)
                            pad.completeDeferredSave(session, true)
                        } else {
                            pad.completeDeferredSave(session, false)
                            // 写盘失败：留在编辑态并提示，避免用户改完的布局被静默丢弃
                            host.onSaveFailedCallback()
                        }
                    }
                }

                override fun onPadVisibilityChanged(visible: Boolean) = Unit
            }
            // 进屏即编辑态：等首次布局完成（元素矩形在 onSizeChanged 里计算，
            // 提前进入会拿到空元素表而无法选中）
            pad.post { pad.enterEditMode() }
            return host
        }
    }
}
