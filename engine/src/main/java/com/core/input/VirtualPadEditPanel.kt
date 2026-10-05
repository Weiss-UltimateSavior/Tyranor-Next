package com.core.input

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import com.core.engine.EngineThemeColors
import com.core.engine.R

/**
 * 游戏内按键编辑面板（原生实现，替代原 `__touch_pad.js` 的 DOM 面板）。
 *
 * 承载选中元素的属性编辑：文本 / 尺寸 / 键位 / 保持 / 显隐 / 复制 / 删除 / 恢复默认，
 * 以及整体保存与取消；键位选择为多选对话框（canonical 键位 + 鼠标段）。
 */
internal class VirtualPadEditPanel(
    private val context: Context,
    private val pad: VirtualPadView,
    private val theme: EngineThemeColors.Palette,
    private val onSave: () -> Unit,
    private val onCancel: () -> Unit,
) {

    private val density = context.resources.displayMetrics.density
    private val root = LinearLayout(context)
    private val titleView: TextView
    private val selectionView: TextView
    private val textInput: EditText
    private val sizeLabel: TextView
    private val sizeSeek: SeekBar
    private val autoKeepBox: CheckBox
    private val visibleBox: CheckBox
    private val eightDirBox: CheckBox
    private val keysButton: Button
    private var suppressCallbacks = false
    private var containerView: View? = null

    private fun dp(value: Float): Int = (value * density + 0.5f).toInt()

    init {
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(14f), dp(12f), dp(14f), dp(12f))
        root.background = GradientDrawable().apply {
            setColor(Color.argb(235, 24, 26, 32))
            cornerRadius = dp(14f).toFloat()
        }

        titleView = TextView(context).apply {
            text = context.getString(R.string.engine_input_edit_title)
            setTextColor(Color.WHITE)
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
        }
        root.addView(titleView)

        selectionView = TextView(context).apply {
            setTextColor(theme.primary)
            textSize = 12f
            setPadding(0, dp(4f), 0, dp(6f))
        }
        root.addView(selectionView)

        textInput = EditText(context).apply {
            hint = context.getString(R.string.engine_input_button_text)
            inputType = InputType.TYPE_CLASS_TEXT
            setTextColor(Color.WHITE)
            setHintTextColor(0x88FFFFFF.toInt())
            textSize = 12f
        }
        root.addView(textInput, LinearLayout.LayoutParams(-1, -2))
        textInput.setOnFocusChangeListener { _, focused ->
            if (!focused) applyButtonEdit()
        }

        sizeLabel = TextView(context).apply {
            setTextColor(Color.WHITE)
            textSize = 12f
            setPadding(0, dp(8f), 0, 0)
        }
        root.addView(sizeLabel)

        sizeSeek = SeekBar(context).apply {
            max = 60
            min = 2
        }
        root.addView(sizeSeek)
        sizeSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!suppressCallbacks && fromUser) {
                    pad.setSelectedSizePercent(progress)
                    sizeLabel.text = context.getString(R.string.engine_input_size, progress)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })

        autoKeepBox = CheckBox(context).apply {
            text = context.getString(R.string.engine_input_auto_keep)
            setTextColor(Color.WHITE)
            textSize = 12f
        }
        root.addView(autoKeepBox)
        autoKeepBox.setOnCheckedChangeListener { _, _ -> applyButtonEdit() }

        visibleBox = CheckBox(context).apply {
            text = context.getString(R.string.engine_input_visible)
            setTextColor(Color.WHITE)
            textSize = 12f
        }
        root.addView(visibleBox)
        visibleBox.setOnCheckedChangeListener { _, _ -> applyVisibilityEdit() }

        eightDirBox = CheckBox(context).apply {
            text = context.getString(R.string.engine_input_eight_dir)
            setTextColor(Color.WHITE)
            textSize = 12f
        }
        root.addView(eightDirBox)
        eightDirBox.setOnCheckedChangeListener { _, checked ->
            val info = pad.selectionInfo() ?: return@setOnCheckedChangeListener
            if (!info.isDirection) return@setOnCheckedChangeListener
            pad.updateSelectedDirection(checked, info.up, info.down, info.left, info.right)
        }

        keysButton = pillButton(context.getString(R.string.engine_input_pick_keys))
        root.addView(keysButton, LinearLayout.LayoutParams(-1, dp(34f)))
        keysButton.setOnClickListener { showKeyDialog() }

        val actionRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8f), 0, 0)
        }
        actionRow.addView(
            pillButton(context.getString(R.string.engine_input_duplicate)).apply {
                setOnClickListener { pad.duplicateSelected(); refresh() }
            },
            pillParams(end = dp(3f)),
        )
        actionRow.addView(
            pillButton(context.getString(R.string.engine_input_delete)).apply {
                setOnClickListener { pad.deleteSelected(); refresh() }
            },
            pillParams(start = dp(3f)),
        )
        root.addView(actionRow)

        val commitRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(6f), 0, 0)
        }
        commitRow.addView(
            pillButton(context.getString(R.string.engine_input_reset)).apply {
                setOnClickListener { pad.resetToDefaults(); refresh() }
            },
            pillParams(end = dp(3f)),
        )
        commitRow.addView(
            pillButton(context.getString(R.string.engine_input_cancel)).apply {
                setOnClickListener { onCancel() }
            },
            pillParams(start = dp(3f), end = dp(3f)),
        )
        commitRow.addView(
            pillButton(context.getString(R.string.engine_input_save), primary = true).apply {
                setOnClickListener {
                    applyButtonEdit()
                    onSave()
                }
            },
            pillParams(start = dp(3f)),
        )
        root.addView(commitRow)
    }

    fun view(): View = root

    /** 选中变化时刷新面板控件；无选中时部分控件禁用（保存/取消始终可用）。 */
    fun refresh() {
        val info = pad.selectionInfo()
        suppressCallbacks = true
        try {
            if (info == null) {
                selectionView.text = context.getString(R.string.engine_input_select_hint)
                textInput.isEnabled = false
                sizeSeek.isEnabled = false
                keysButton.isEnabled = false
                autoKeepBox.visibility = View.GONE
                eightDirBox.visibility = View.GONE
                visibleBox.visibility = View.GONE
                return
            }
            val direction = info.isDirection
            selectionView.text = context.getString(R.string.engine_input_selected, info.label)
            textInput.isEnabled = !direction
            sizeSeek.isEnabled = true
            keysButton.isEnabled = true
            if (!direction) textInput.setText(info.label)
            autoKeepBox.visibility = if (direction) View.GONE else View.VISIBLE
            autoKeepBox.isChecked = info.autoKeep
            eightDirBox.visibility = if (direction) View.VISIBLE else View.GONE
            eightDirBox.isChecked = info.eightDir
            visibleBox.visibility = View.VISIBLE
            visibleBox.isChecked = info.visible
            sizeSeek.progress = info.sizePercent.coerceIn(2, 60)
            sizeLabel.text = context.getString(R.string.engine_input_size, info.sizePercent)
            keysButton.visibility = if (direction) View.GONE else View.VISIBLE
        } finally {
            suppressCallbacks = false
        }
    }

    private fun applyButtonEdit() {
        if (suppressCallbacks) return
        val info = pad.selectionInfo() ?: return
        if (info.isDirection) return
        pad.updateSelectedButton(
            text = textInput.text?.toString().orEmpty(),
            keys = info.keys,
            autoKeep = autoKeepBox.isChecked,
        )
    }

    private fun applyVisibilityEdit() {
        if (suppressCallbacks) return
        val info = pad.selectionInfo() ?: return
        pad.updateSelectedVisibility(visibleBox.isChecked)
    }

    /** 键位多选对话框：canonical 键位网格 + 鼠标段（点击切换选中）。 */
    private fun showKeyDialog() {
        val info = pad.selectionInfo() ?: return
        if (info.isDirection) return
        val selected = LinkedHashSet(info.keys)
        val container = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

        InputKeyCatalog.groups().forEach { group ->
            container.addView(
                TextView(context).apply {
                    text = context.getString(group.titleRes)
                    setTextColor(0xAAFFFFFF.toInt())
                    textSize = 11f
                    setPadding(0, dp(6f), 0, dp(2f))
                },
            )
            group.keys.chunked(5).forEach { chunk ->
                val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
                chunk.forEach { entry ->
                    val label = entry.label ?: context.getString(entry.labelRes)
                    val button = Button(context).apply {
                        text = label
                        textSize = 10f
                        isAllCaps = false
                        setPadding(dp(4f), dp(2f), dp(4f), dp(2f))
                        minimumWidth = 0
                        minWidth = 0
                        setBackgroundColor(if (entry.code in selected) theme.primary else 0x22FFFFFF)
                        setTextColor(if (entry.code in selected) theme.onPrimary else Color.WHITE)
                        setOnClickListener {
                            if (!selected.add(entry.code)) selected.remove(entry.code)
                            val on = entry.code in selected
                            setBackgroundColor(if (on) theme.primary else 0x22FFFFFF)
                            setTextColor(if (on) theme.onPrimary else Color.WHITE)
                        }
                    }
                    row.addView(button, LinearLayout.LayoutParams(0, dp(32f), 1f))
                }
                container.addView(row)
            }
        }

        val dialog = AlertDialog.Builder(context)
            .setTitle(R.string.engine_input_pick_keys)
            .setView(ScrollView(context).apply { addView(container) })
            .setPositiveButton(R.string.engine_input_confirm) { _, _ ->
                pad.updateSelectedButton(info.label, selected.toList(), pad.selectionInfo()?.autoKeep ?: false)
                refresh()
            }
            .setNegativeButton(R.string.engine_cancel, null)
            .create()
        dialog.show()
    }

    private fun pillButton(text: String, primary: Boolean = false): Button = Button(context).apply {
        this.text = text
        textSize = 12f
        isAllCaps = false
        setTextColor(if (primary) theme.onPrimary else theme.primary)
        background = GradientDrawable().apply {
            setColor(if (primary) theme.primary else Color.argb(60, 48, 125, 239))
            cornerRadius = 999f
        }
    }

    private fun pillParams(start: Int = 0, end: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(0, dp(34f), 1f).apply {
            marginStart = start
            marginEnd = end
        }

    fun dismiss() {
        val view = containerView ?: return
        (view.parent as? ViewGroup)?.removeView(view)
        containerView = null
    }

    companion object {

        /** 构建面板并挂到 [parent] 右上角（不覆盖 FAB 拖拽区）；返回实例供刷新/销毁。 */
        fun attach(
            parent: ViewGroup,
            context: Context,
            pad: VirtualPadView,
            theme: EngineThemeColors.Palette,
            onSave: () -> Unit,
            onCancel: () -> Unit,
        ): VirtualPadEditPanel {
            val panel = VirtualPadEditPanel(context, pad, theme, onSave, onCancel)
            val density = context.resources.displayMetrics.density
            // 宿主容器为 FrameLayout（见 InputRemapController 的装配）；面板固定右上角
            val params = android.widget.FrameLayout.LayoutParams(
                (268 * density).toInt(),
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = (52 * density).toInt()
                marginEnd = (8 * density).toInt()
                gravity = Gravity.TOP or Gravity.END
            }
            val scroll = ScrollView(context).apply { addView(panel.view()) }
            parent.addView(scroll, params)
            panel.containerView = scroll
            panel.refresh()
            return panel
        }
    }
}
