package com.core.input

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import com.core.engine.EngineThemeColors
import com.core.engine.R
import kotlin.math.min

/**
 * 游戏内按键编辑面板（原生实现，替代原 `__touch_pad.js` 的 DOM 面板）。
 *
 * 承载选中元素的属性编辑：文本 / 尺寸 / 键位 / 保持 / 显隐 / 新增 / 复制 / 删除 /
 * 恢复默认，以及整体保存与取消；键位选择为深色卡片多选对话框（canonical 键位 + 鼠标段）。
 *
 * 面板本身可拖动：顶部拖动条按住拖到任意位置（编辑时避免遮住正在调整的按键）。
 * 同一面板同时服务游戏内编辑（[InputRemapController]）与设置页方案编辑（app 侧）。
 */
class VirtualPadEditPanel(
    private val context: Context,
    private val pad: VirtualPadView,
    private val theme: EngineThemeColors.Palette,
    private val onSave: () -> Unit,
    private val onCancel: () -> Unit,
) {

    private val density = context.resources.displayMetrics.density
    private val content = LinearLayout(context)
    private val selectionView: TextView
    private val textInput: EditText
    private val sizeLabel: TextView
    private val sizeSeek: SeekBar
    private val autoKeepBox: CheckBox
    private val visibleBox: CheckBox
    private val eightDirBox: CheckBox
    private val keysButton: Button
    private val directionKeysTitle: TextView
    private val directionKeysSummary: TextView
    private val directionButtons: LinearLayout
    private var suppressCallbacks = false

    /** 文字框当前绑定的是哪个按钮（用于识别选中切换并回写上一个按钮的未提交文字）。 */
    private var boundId: String? = null

    private var container: FrameLayout? = null

    private fun dp(value: Float): Int = (value * density + 0.5f).toInt()

    init {
        selectionView = TextView(context).apply {
            setTextColor(theme.primary)
            textSize = 12f
            setPadding(0, dp(2f), 0, dp(6f))
        }
        textInput = EditText(context).apply {
            hint = context.getString(R.string.engine_input_button_text)
            inputType = InputType.TYPE_CLASS_TEXT
            setTextColor(Color.WHITE)
            setHintTextColor(0x88FFFFFF.toInt())
            textSize = 12f
        }
        textInput.setOnFocusChangeListener { _, focused ->
            if (!focused) applyButtonEdit()
        }

        sizeLabel = TextView(context).apply {
            setTextColor(Color.WHITE)
            textSize = 12f
            setPadding(0, dp(8f), 0, 0)
        }
        sizeSeek = SeekBar(context).apply {
            max = 60
            min = 2
        }
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
        autoKeepBox.setOnCheckedChangeListener { _, _ -> applyButtonEdit() }

        visibleBox = CheckBox(context).apply {
            text = context.getString(R.string.engine_input_visible)
            setTextColor(Color.WHITE)
            textSize = 12f
        }
        visibleBox.setOnCheckedChangeListener { _, _ -> applyVisibilityEdit() }

        eightDirBox = CheckBox(context).apply {
            text = context.getString(R.string.engine_input_eight_dir)
            setTextColor(Color.WHITE)
            textSize = 12f
        }
        eightDirBox.setOnCheckedChangeListener { _, checked ->
            // 与其他监听一致：refresh() 回填控件时不回调，避免重入 refresh
            if (suppressCallbacks) return@setOnCheckedChangeListener
            val info = pad.selectionInfo() ?: return@setOnCheckedChangeListener
            if (!info.isDirection) return@setOnCheckedChangeListener
            pad.updateSelectedDirection(checked, info.up, info.down, info.left, info.right)
        }

        keysButton = pillButton(context.getString(R.string.engine_input_pick_keys))
        keysButton.setOnClickListener { showKeyDialog() }

        // 方向控件的键位同样可编辑（四个方向各一组，空 = 该方向不输出）
        directionKeysTitle = TextView(context).apply {
            text = context.getString(R.string.engine_input_direction_keys_title)
            setTextColor(Color.WHITE)
            textSize = 12f
            setPadding(0, dp(8f), 0, 0)
        }
        directionKeysSummary = TextView(context).apply {
            setTextColor(0xFFB9BDC6.toInt())
            textSize = 11f
            setPadding(0, dp(2f), 0, dp(4f))
        }
        directionButtons = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }

        listOf(
            "up" to R.string.engine_input_direction_up,
            "down" to R.string.engine_input_direction_down,
            "left" to R.string.engine_input_direction_left,
            "right" to R.string.engine_input_direction_right,
        ).forEach { (dir, labelRes) ->
            directionButtons.addView(
                pillButton(context.getString(labelRes)).apply {
                    setOnClickListener { showDirectionKeyDialog(dir) }
                },
                LinearLayout.LayoutParams(0, dp(32f), 1f).apply {
                    marginStart = dp(2f)
                    marginEnd = dp(2f)
                },
            )
        }

        val actionRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8f), 0, 0)
        }
        actionRow.addView(
            pillButton(context.getString(R.string.engine_input_add)).apply {
                setOnClickListener { showAddDialog() }
            },
            pillParams(end = dp(3f)),
        )
        actionRow.addView(
            pillButton(context.getString(R.string.engine_input_duplicate)).apply {
                setOnClickListener { pad.duplicateSelected(); refresh() }
            },
            pillParams(start = dp(1f), end = dp(1f)),
        )
        actionRow.addView(
            pillButton(context.getString(R.string.engine_input_delete)).apply {
                setOnClickListener { pad.deleteSelected(); refresh() }
            },
            pillParams(start = dp(3f)),
        )

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

        content.orientation = LinearLayout.VERTICAL
        content.setPadding(dp(14f), dp(2f), dp(14f), dp(12f))
        content.addView(selectionView)
        content.addView(textInput, LinearLayout.LayoutParams(-1, -2))
        content.addView(sizeLabel)
        content.addView(sizeSeek)
        content.addView(autoKeepBox)
        content.addView(visibleBox)
        content.addView(eightDirBox)
        content.addView(keysButton, LinearLayout.LayoutParams(-1, dp(34f)))
        content.addView(directionKeysTitle)
        content.addView(directionKeysSummary)
        content.addView(directionButtons)
        content.addView(actionRow)
        content.addView(commitRow)
    }

    /**
     * 选中变化时刷新面板控件；无选中时部分控件禁用（保存/取消始终可用）。
     *
     * 文字框与「当前选中按钮」的绑定靠 [boundId] 追踪：VirtualPadView 切换选中时面板
     * 收不到「切换前」通知，因此只能在下次 refresh 时把文字框里**属于上一个按钮**的
     * 未提交内容静默写回它——否则该内容会随下一次 applyButtonEdit 写进新选中的按钮
     * （用户报告的「切换按钮后旧文字串到新按钮」），且上一个按钮的修改会丢失。
     */
    fun refresh() {
        val info = pad.selectionInfo()
        val previousId = boundId
        val currentId = info?.id
        suppressCallbacks = true
        try {
            if (previousId != null && previousId != VirtualPadView.DIRECTION_ID && previousId != currentId) {
                pad.updateButtonTextSilently(previousId, textInput.text?.toString().orEmpty())
            }
            boundId = currentId
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
            // 选中已切换：必须用新按钮的文字重建输入框（此时框里是上一个按钮的内容）；
            // 同一选中下仅在「未聚焦」时回写，避免打断正在输入的用户
            if (!direction && (previousId != currentId || !textInput.hasFocus())) {
                textInput.setText(info.label)
                textInput.setSelection(textInput.text?.length ?: 0)
            }
            autoKeepBox.visibility = if (direction) View.GONE else View.VISIBLE
            autoKeepBox.isChecked = info.autoKeep
            eightDirBox.visibility = if (direction) View.VISIBLE else View.GONE
            eightDirBox.isChecked = info.eightDir
            visibleBox.visibility = View.VISIBLE
            visibleBox.isChecked = info.visible
            sizeSeek.progress = info.sizePercent.coerceIn(2, 60)
            sizeLabel.text = context.getString(R.string.engine_input_size, info.sizePercent)
            keysButton.visibility = if (direction) View.GONE else View.VISIBLE
            val directionVisibility = if (direction) View.VISIBLE else View.GONE
            directionKeysTitle.visibility = directionVisibility
            directionKeysSummary.visibility = directionVisibility
            directionButtons.visibility = directionVisibility
            if (direction) directionKeysSummary.text = directionSummary(info)
        } finally {
            suppressCallbacks = false
        }
    }

    /** 方向键位的可读摘要（未绑定的方向显示「未绑定」）。 */
    private fun directionSummary(info: VirtualPadView.SelectionInfo): String =
        listOf(
            context.getString(R.string.engine_input_direction_up) to info.up,
            context.getString(R.string.engine_input_direction_down) to info.down,
            context.getString(R.string.engine_input_direction_left) to info.left,
            context.getString(R.string.engine_input_direction_right) to info.right,
        ).joinToString("　") { (label, keys) ->
            val text = if (keys.isEmpty()) {
                context.getString(R.string.input_settings_binding_empty)
            } else {
                keys.joinToString("+") { key ->
                    InputKeyCatalog.label(context, key).ifBlank { key.toString() }
                }
            }
            "$label $text"
        }

    /**
     * 方向键位选择对话框（与普通按钮共用键位网格，单方向一组；确认空选 = 该方向不输出）。
     */
    private fun showDirectionKeyDialog(direction: String) {
        val info = pad.selectionInfo() ?: return
        if (!info.isDirection) return
        val current = when (direction) {
            "up" -> info.up
            "down" -> info.down
            "left" -> info.left
            else -> info.right
        }
        showKeyPickerDialog(
            titleRes = R.string.engine_input_pick_direction_key,
            selectedKeys = current,
        ) { keys ->
            pad.updateSelectedDirectionKey(direction, keys)
            refresh()
        }
    }

    fun dismiss() {
        // 已弹出的键位/新增对话框必须先关：否则宿主销毁后会留下挂在失效窗口上的 AlertDialog
        dismissDialog()
        val view = container ?: return
        (view.parent as? ViewGroup)?.removeView(view)
        container = null
    }

    // ---------- 编辑应用 ----------

    /**
     * 提交文字框当前内容。
     *
     * 输入框只在失焦时提交，而点「选择键位」等按钮不会让输入框失焦——这些入口
     * 必须先显式提交，否则后续用已提交的旧值重建按钮，会冲掉用户刚改的文字。
     */
    private fun commitPendingText() {
        applyButtonEdit()
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
        pad.selectionInfo() ?: return
        pad.updateSelectedVisibility(visibleBox.isChecked)
    }

    // ---------- 新增控件 ----------

    /**
     * 新增控件类型选择。
     *
     * 不可用的类型（方向键已存在）置灰不可点，而不是点击后才报错；
     * 「方向键」在已隐藏时可用（用于重新放回按键层）。
     */
    private fun showAddDialog() {
        // 先提交未失焦的文字，避免新增后 selectionInfo 回退到旧值
        commitPendingText()
        val directionAvailable = pad.canAdd(VirtualPadView.NewButtonType.DIRECTION)
        val options = listOf(
            Triple(context.getString(R.string.engine_input_new_button), VirtualPadView.NewButtonType.BUTTON, true),
            Triple(context.getString(R.string.engine_input_new_round_button), VirtualPadView.NewButtonType.ROUND_BUTTON, true),
            Triple(context.getString(R.string.engine_input_new_screenshot), VirtualPadView.NewButtonType.SCREENSHOT, true),
            Triple(context.getString(R.string.engine_input_new_direction), VirtualPadView.NewButtonType.DIRECTION, directionAvailable),
        )
        val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        list.addView(dialogTitle(context.getString(R.string.engine_input_add)))
        options.forEach { (label, type, available) ->
            val row = TextView(context).apply {
                text = if (available) {
                    label
                } else {
                    context.getString(
                        R.string.engine_input_unavailable_format,
                        label,
                        context.getString(R.string.engine_input_direction_exists),
                    )
                }
                setTextColor(if (available) Color.WHITE else 0x66FFFFFF)
                textSize = 14f
                gravity = Gravity.CENTER_VERTICAL
                background = chipBackground(
                    corner = 10f,
                    fill = if (available) 0x2EFFFFFF else 0x14FFFFFF,
                )
                isClickable = available
                setPadding(dp(14f), dp(12f), dp(14f), dp(12f))
                if (available) {
                    setOnClickListener {
                        if (pad.addButton(type) != null) {
                            dismissDialog()
                            refresh()
                        }
                    }
                }
            }
            list.addView(row, LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = dp(4f)
                bottomMargin = dp(4f)
            })
        }
        showDarkDialog(list)
    }

    // ---------- 键位选择（深色卡片，多选） ----------

    private fun showKeyDialog() {
        // 先提交未失焦的文字：否则选完键位后 refresh 会用旧值重建按钮，
        // 把用户刚改的文字冲掉（用户报告的「改完文字选键位又变回去」）
        commitPendingText()
        val info = pad.selectionInfo() ?: return
        if (info.isDirection) return
        showKeyPickerDialog(
            titleRes = R.string.engine_input_pick_keys,
            selectedKeys = info.keys,
        ) { keys ->
            pad.updateSelectedButton(info.label, keys, autoKeepBox.isChecked)
            refresh()
        }
    }

    /**
     * 键位多选对话框（普通按钮与方向键位共用）。
     *
     * 选中态用主题色实底 + 白字；确认时回传完整选中集合（空集合 = 不输出该键）。
     */
    private fun showKeyPickerDialog(
        titleRes: Int,
        selectedKeys: List<Int>,
        onConfirm: (List<Int>) -> Unit,
    ) {
        val selected = LinkedHashSet(selectedKeys)

        val grid = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        InputKeyCatalog.groups().forEach { group ->
            grid.addView(
                TextView(context).apply {
                    text = context.getString(group.titleRes)
                    setTextColor(0xFFB9BDC6.toInt())
                    textSize = 11f
                    setPadding(dp(2f), dp(10f), 0, dp(4f))
                },
            )
            group.keys.chunked(4).forEach { chunk ->
                val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
                chunk.forEach { entry ->
                    val chip = keyChip(
                        label = entry.label ?: context.getString(entry.labelRes),
                        selected = entry.code in selected,
                    )
                    chip.setOnClickListener {
                        if (!selected.add(entry.code)) selected.remove(entry.code)
                        val on = entry.code in selected
                        chip.background = chipBackground(corner = 10f, fill = if (on) theme.primary else 0x2EFFFFFF)
                        chip.setTextColor(if (on) theme.onPrimary else 0xFFE8E8E8.toInt())
                    }
                    row.addView(chip, LinearLayout.LayoutParams(0, dp(40f), 1f).apply {
                        marginStart = dp(2f)
                        marginEnd = dp(2f)
                        topMargin = dp(2f)
                        bottomMargin = dp(2f)
                    })
                }
                // 补满最后一行的占位，保持列宽一致
                repeat(4 - chunk.size) {
                    row.addView(View(context), LinearLayout.LayoutParams(0, dp(40f), 1f))
                }
                grid.addView(row)
            }
        }

        val scroll = ScrollView(context).apply {
            addView(grid)
            isVerticalScrollBarEnabled = true
        }

        val rootLayout = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        rootLayout.addView(dialogTitle(context.getString(titleRes)))
        // 键位网格固定高度（内容更高时由 ScrollView 滚动）：
        // 卡片整体是 wrap_content，若这里用 weight 会让网格塌成 0 高度
        val gridHeight = min((context.resources.displayMetrics.heightPixels * 0.52f).toInt(), dp(380f))
        rootLayout.addView(
            scroll,
            LinearLayout.LayoutParams(-1, gridHeight).apply {
                topMargin = dp(4f)
            },
        )

        val buttonRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10f), 0, 0)
        }
        buttonRow.addView(
            pillButton(context.getString(R.string.engine_input_cancel)).apply {
                setOnClickListener { dismissDialog() }
            },
            pillParams(end = dp(4f)),
        )
        buttonRow.addView(
            pillButton(context.getString(R.string.engine_input_confirm), primary = true).apply {
                setOnClickListener {
                    onConfirm(selected.toList())
                    dismissDialog()
                }
            },
            pillParams(start = dp(4f)),
        )
        rootLayout.addView(buttonRow)

        showDarkDialog(rootLayout)
    }

    // ---------- 对话框基础 ----------

    private var dialog: AlertDialog? = null

    /**
     * 统一深色卡片对话框。
     *
     * 引擎宿主多为框架主题（非 AppCompat），AlertDialog 默认窗口背景为浅色；旧实现直接
     * setView 会让浅色文字落在浅色底上（按键网格几乎不可见、按钮文字白底白字）。
     * 这里清掉窗口背景、由卡片自绘深色底，并按内容测量高度自适应（键位网格超限时内部滚动）。
     */
    private fun showDarkDialog(contentView: View) {
        dismissDialog()
        val cardPaddingH = dp(16f)
        val cardPaddingV = dp(14f) + dp(12f)
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(cardPaddingH, dp(14f), cardPaddingH, dp(12f))
            background = GradientDrawable().apply {
                setColor(Color.argb(245, 26, 28, 34))
                cornerRadius = dp(16f).toFloat()
            }
        }
        card.addView(contentView, LinearLayout.LayoutParams(-1, -2))

        val metrics = context.resources.displayMetrics
        val width = min((metrics.widthPixels * 0.86f).toInt(), dp(380f))
        val maxContentHeight = min((metrics.heightPixels * 0.80f).toInt(), dp(560f)) - cardPaddingV

        // 先量内容（键位网格用 AT_MOST 限制，超限由内部 ScrollView 滚动），再定窗口高度
        card.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(
                (maxContentHeight + cardPaddingV).coerceAtLeast(1),
                View.MeasureSpec.AT_MOST,
            ),
        )

        val created = AlertDialog.Builder(context).create()
        created.setView(card)
        created.setCanceledOnTouchOutside(true)
        created.show()
        created.window?.let { window ->
            window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            window.setLayout(width, card.measuredHeight)
        }
        dialog = created
    }

    private fun dismissDialog() {
        dialog?.dismiss()
        dialog = null
    }

    private fun dialogTitle(text: String): TextView = TextView(context).apply {
        this.text = text
        setTextColor(Color.WHITE)
        textSize = 15f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(dp(2f), 0, 0, dp(2f))
    }

    private fun keyChip(label: String, selected: Boolean): TextView =
        TextView(context).apply {
            text = label
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(if (selected) theme.onPrimary else 0xFFE8E8E8.toInt())
            background = chipBackground(corner = 10f, fill = if (selected) theme.primary else 0x2EFFFFFF)
            isClickable = true
        }

    private fun chipBackground(corner: Float, fill: Int): GradientDrawable = GradientDrawable().apply {
        setColor(fill)
        this.cornerRadius = corner * density
    }

    private fun pillButton(text: String, primary: Boolean = false): Button = Button(context).apply {
        this.text = text
        textSize = 12f
        isAllCaps = false
        stateListAnimator = null
        setTextColor(if (primary) theme.onPrimary else theme.primary)
        background = GradientDrawable().apply {
            setColor(
                if (primary) {
                    theme.primary
                } else {
                    // 次级按钮底色从主题色派生（AGENT.md：强调色不得硬编码）
                    Color.argb(60, Color.red(theme.primary), Color.green(theme.primary), Color.blue(theme.primary))
                },
            )
            cornerRadius = 999f
        }
    }

    private fun pillParams(start: Int = 0, end: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(0, dp(34f), 1f).apply {
            marginStart = start
            marginEnd = end
        }

    companion object {

        /**
         * 构建面板并挂到 [parent] 右上角；返回实例供刷新/销毁。
         *
         * 容器固定尺寸（宽 ≤300dp、高 ≤560dp），顶部拖动条可拖动到任意位置；
         * 内容超出时面板内滚动。
         */
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
            fun dp(value: Float): Int = (value * density + 0.5f).toInt()

            val metrics = context.resources.displayMetrics
            val width = min((metrics.widthPixels * 0.62f).toInt(), dp(300f)).coerceAtLeast(dp(220f))
            val height = min((metrics.heightPixels * 0.68f).toInt(), dp(560f)).coerceAtLeast(dp(260f))

            val handle = View(context).apply {
                background = GradientDrawable().apply {
                    setColor(0x66FFFFFF)
                    cornerRadius = dp(2f).toFloat()
                }
            }
            val dragBar = FrameLayout(context).apply {
                setBackgroundColor(Color.TRANSPARENT)
                isClickable = true
                addView(
                    handle,
                    FrameLayout.LayoutParams(dp(40f), dp(4f), Gravity.CENTER),
                )
            }

            val scroll = ScrollView(context).apply {
                addView(panel.content)
                isVerticalScrollBarEnabled = true
            }

            val card = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                background = GradientDrawable().apply {
                    setColor(Color.argb(238, 24, 26, 32))
                    cornerRadius = dp(14f).toFloat()
                }
                addView(dragBar, LinearLayout.LayoutParams(-1, dp(18f)))
                addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
            }

            val root = FrameLayout(context).apply { isClickable = true }
            root.addView(card, FrameLayout.LayoutParams(-1, -1))

            val params = FrameLayout.LayoutParams(width, height).apply {
                gravity = Gravity.TOP or Gravity.END
                topMargin = dp(52f)
                marginEnd = dp(8f)
            }
            parent.addView(root, params)
            panel.container = root
            panel.bindDrag(root, dragBar)
            panel.refresh()
            return panel
        }
    }

    // ---------- 拖动 ----------

    private var dragBaseLeft = 0
    private var dragBaseTop = 0
    private var dragStartRawX = 0f
    private var dragStartRawY = 0f

    private fun bindDrag(root: FrameLayout, dragBar: View) {
        dragBar.setOnTouchListener { _, event ->
            val parent = root.parent as? ViewGroup ?: return@setOnTouchListener false
            val params = root.layoutParams as? FrameLayout.LayoutParams ?: return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    // 统一切到「左上角锚定 + 边距」定位，拖动只改边距
                    params.gravity = Gravity.TOP or Gravity.START
                    params.leftMargin = root.left
                    params.topMargin = root.top
                    params.rightMargin = 0
                    params.bottomMargin = 0
                    root.layoutParams = params
                    dragBaseLeft = params.leftMargin
                    dragBaseTop = params.topMargin
                    dragStartRawX = event.rawX
                    dragStartRawY = event.rawY
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val maxLeft = (parent.width - root.width).coerceAtLeast(0)
                    val maxTop = (parent.height - root.height).coerceAtLeast(0)
                    params.leftMargin = (dragBaseLeft + (event.rawX - dragStartRawX)).toInt()
                        .coerceIn(0, maxLeft)
                    params.topMargin = (dragBaseTop + (event.rawY - dragStartRawY)).toInt()
                        .coerceIn(0, maxTop)
                    root.layoutParams = params
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> true
                else -> false
            }
        }
        // 宿主尺寸变化（横竖屏切换/分屏）：把面板重新钳回可视区，避免停在屏外
        root.addOnLayoutChangeListener { _, left, top, right, bottom, _, _, _, _ ->
            val parent = root.parent as? ViewGroup ?: return@addOnLayoutChangeListener
            val params = root.layoutParams as? FrameLayout.LayoutParams ?: return@addOnLayoutChangeListener
            val width = right - left
            val height = bottom - top
            if (width <= 0 || height <= 0) return@addOnLayoutChangeListener
            val maxLeft = (parent.width - width).coerceAtLeast(0)
            val maxTop = (parent.height - height).coerceAtLeast(0)
            if (left < 0 || top < 0 || left > maxLeft || top > maxTop) {
                params.leftMargin = left.coerceIn(0, maxLeft)
                params.topMargin = top.coerceIn(0, maxTop)
                params.gravity = Gravity.TOP or Gravity.START
                params.rightMargin = 0
                params.bottomMargin = 0
                root.layoutParams = params
            }
        }
    }
}
