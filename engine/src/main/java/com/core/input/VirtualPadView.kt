package com.core.input

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 虚拟按键覆盖层（可编辑）：渲染当前方案、收发按键、提供游戏内编辑交互。
 *
 * 与旧 `__touch_pad.js` 的关系：本 View 是其原生重写——渲染、命中、编辑（拖拽/缩放/
 * 增删/改键）全部在进程内完成，配置经 [InputConfigStore] 落盘；按键经 [InputSink]
 * 送到对应引擎（Web 见 [WebInputSink]）。
 *
 * 触摸策略：只有命中按键层元素的手势才被本 View 消费（其余 DOWN 返回 false 放行给
 * 下层 WebView / 虚拟鼠标层），编辑模式下则全量拦截。
 */
class VirtualPadView(
    context: Context,
    private val sink: InputSink,
) : View(context) {

    /** 主题色（与 app 引擎主题一致，由宿主经 EngineThemeColors 注入）。 */
    data class PadTheme(val primary: Int, val onPrimary: Int) {
        companion object {
            val DEFAULT = PadTheme(0xFF307DEF.toInt(), Color.WHITE)
        }
    }

    /** 编辑态选中元素的快照（供编辑面板渲染）。 */
    data class SelectionInfo(
        val id: String,
        val isDirection: Boolean,
        val label: String,
        val visible: Boolean,
        val sizePercent: Int,
        val keys: List<Int>,
        val autoKeep: Boolean,
        val eightDir: Boolean,
        val up: List<Int> = emptyList(),
        val down: List<Int> = emptyList(),
        val left: List<Int> = emptyList(),
        val right: List<Int> = emptyList(),
    )

    interface Listener {
        fun onEditStarted()
        fun onEditEnded()
        fun onSelectionChanged(info: SelectionInfo?)
        fun onProfileCommitted(profile: PadProfile)
        fun onPadVisibilityChanged(visible: Boolean)
    }

    var listener: Listener? = null

    var theme: PadTheme = PadTheme.DEFAULT
        set(value) { field = value; invalidate() }

    /** 当前生效方案（非编辑态由控制器在启动 / 保存后注入）。 */
    var profile: PadProfile = PadProfile.defaultProfile()
        set(value) {
            field = value
            if (!editing) { rebuild(); invalidate() }
        }

    private var editing = false
    private var editProfile: PadProfile? = null
    private var selectedId: String? = null

    private var padVisible = true

    private val dispatcher = KeyDispatcher(sink)
    private val density = resources.displayMetrics.density

    // ---------- 元素布局 ----------

    private class Element(val id: String, val isDirection: Boolean) {
        val rect = RectF()
        var button: PadButton? = null
    }

    private val elements = ArrayList<Element>()
    private val elementById = HashMap<String, Element>()
    private val fabRect = RectF()
    private var fabX = 0.055f
    private var fabY = 0.48f

    private val pressedIds = HashSet<String>()
    private val dirActiveSet = HashSet<String>()
    private var dirKnobDx = 0f
    private var dirKnobDy = 0f

    private val pointerTargets = HashMap<Int, String>()
    private val pointerButtons = HashMap<Int, PadButton>()
    private var dirPointerId = -1

    private var fabPointerId = -1
    private var fabMoved = false
    private var fabDownX = 0f
    private var fabDownY = 0f
    private var fabGrabDx = 0f
    private var fabGrabDy = 0f

    private var editDragId: String? = null
    private var editDragDx = 0f
    private var editDragDy = 0f
    private var editMoved = false

    private val handler = Handler(Looper.getMainLooper())
    private val fabLongPress = Runnable {
        if (!fabMoved && !editing) {
            // 长按已消费本次手势：先清掉 FAB 跟踪状态，否则编辑保存后残留的
            // fabPointerId/fabGrabDx 会在下一次非编辑触摸的 MOVE 中把 FAB 拖到手指处
            resetFabGesture()
            enterEditMode()
        }
    }

    // ---------- 画笔 ----------

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }
    private val scrimPaint = Paint().apply { color = 0x99000000.toInt() }
    private val selectPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
    }

    init {
        isClickable = true
        setWillNotDraw(false)
        val fab = InputConfigStore.fabPosition(context)
        fabX = fab.first
        fabY = fab.second
    }

    fun bindPreferences(context: Context) {
        padVisible = InputConfigStore.isPadVisible(context)
        val fab = InputConfigStore.fabPosition(context)
        fabX = fab.first
        fabY = fab.second
        requestLayoutRebuild()
    }

    // ---------- 布局 ----------

    @SuppressLint("DrawAllocation")
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuild()
    }

    private fun requestLayoutRebuild() {
        rebuild()
        invalidate()
    }

    private fun rebuild() {
        val active = activeProfile()
        elements.clear()
        elementById.clear()
        if (width <= 0 || height <= 0) return
        val minSide = min(width, height).toFloat()

        val dir = active.direction
        val dirElement = Element(DIRECTION_ID, true)
        val dirSize = dir.size * minSide
        setRectCentered(dirElement.rect, dir.x * width, dir.y * height, dirSize, dirSize)
        elements.add(dirElement)
        elementById[DIRECTION_ID] = dirElement

        active.buttons.forEach { button ->
            val element = Element(button.id, false)
            val bw = button.size * minSide
            val bh = bw * button.aspect
            setRectCentered(element.rect, button.x * width, button.y * height, bw, bh)
            element.button = button
            elements.add(element)
            elementById[button.id] = element
        }

        val fabRadius = FAB_RADIUS_DP * density
        setRectCentered(fabRect, fabX * width, fabY * height, fabRadius * 2, fabRadius * 2)
    }

    private fun activeProfile(): PadProfile = editProfile ?: profile

    private fun setRectCentered(rect: RectF, cx: Float, cy: Float, w: Float, h: Float) {
        rect.set(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)
    }

    // ---------- 绘制 ----------

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val active = activeProfile()
        if (editing) canvas.drawColor(scrimPaint.color)

        if (padVisible || editing) {
            elementById[DIRECTION_ID]?.let { drawDirection(canvas, it, active.direction) }
            active.buttons.forEach { button ->
                elementById[button.id]?.let { drawButton(canvas, it, button) }
            }
        }
        if (!editing) drawFab(canvas)
        if (editing && selectedId != null) {
            elementById[selectedId]?.let { element ->
                selectPaint.color = theme.onPrimary
                canvas.drawRoundRect(
                    element.rect.left - 3f * density, element.rect.top - 3f * density,
                    element.rect.right + 3f * density, element.rect.bottom + 3f * density,
                    6f * density, 6f * density, selectPaint,
                )
            }
        }
    }

    private fun drawButton(canvas: Canvas, element: Element, button: PadButton) {
        val hidden = !button.visible
        if (hidden && !editing) return
        val pressed = button.id in pressedIds || dispatcher.isToggled(button.id)
        fillPaint.color = when {
            pressed -> withAlpha(theme.primary, 0.46f)
            hidden -> withAlpha(theme.primary, 0.16f)
            else -> withAlpha(theme.primary, 0.22f)
        }
        val radius = if (button.shape == PadButton.SHAPE_ROUND) {
            min(element.rect.width(), element.rect.height()) / 2f
        } else {
            10f * density
        }
        canvas.drawRoundRect(element.rect, radius, radius, fillPaint)
        if (hidden) {
            strokePaint.color = withAlpha(theme.primary, 0.82f)
            canvas.drawRoundRect(element.rect, radius, radius, strokePaint)
        }
        if (button.text.isNotEmpty()) {
            textPaint.color = withAlpha(theme.onPrimary, 0.75f)
            textPaint.textSize = min(element.rect.height() * 0.46f, element.rect.width() * 0.42f)
                .coerceAtLeast(8f * density)
            val baseline = element.rect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f
            canvas.drawText(button.text, element.rect.centerX(), baseline, textPaint)
        }
    }

    private fun drawDirection(canvas: Canvas, element: Element, direction: PadDirection) {
        if (!direction.visible && !editing) return
        val cx = element.rect.centerX()
        val cy = element.rect.centerY()
        val r = element.rect.width() / 2f
        if (r <= 0f) return
        fillPaint.color = withAlpha(theme.primary, 0.16f)
        canvas.drawCircle(cx, cy, r, fillPaint)
        strokePaint.color = withAlpha(theme.primary, if (direction.visible) 0.30f else 0.82f)
        canvas.drawCircle(cx, cy, r, strokePaint)
        // 四向刻度
        textPaint.color = withAlpha(theme.onPrimary, 0.45f)
        textPaint.textSize = (r * 0.22f).coerceAtLeast(8f * density)
        val tickR = r * 0.78f
        canvas.drawText("▲", cx, cy - tickR + textPaint.textSize / 2f, textPaint)
        canvas.drawText("▼", cx, cy + tickR + textPaint.textSize / 2f, textPaint)
        canvas.drawText("◀", cx - tickR, cy - (textPaint.descent() + textPaint.ascent()) / 2f, textPaint)
        canvas.drawText("▶", cx + tickR, cy - (textPaint.descent() + textPaint.ascent()) / 2f, textPaint)
        // 摇杆帽
        fillPaint.color = withAlpha(theme.primary, 0.55f)
        canvas.drawCircle(cx + dirKnobDx * r * 0.6f, cy + dirKnobDy * r * 0.6f, r * 0.34f, fillPaint)
    }

    private fun drawFab(canvas: Canvas) {
        val cx = fabRect.centerX()
        val cy = fabRect.centerY()
        val r = fabRect.width() / 2f
        fillPaint.color = if (padVisible) withAlpha(theme.primary, 0.45f) else 0x29FFFFFF
        canvas.drawCircle(cx, cy, r, fillPaint)
        strokePaint.color = 0x73FFFFFF
        canvas.drawCircle(cx, cy, r, strokePaint)
        // 键盘四格图标
        fillPaint.color = withAlpha(theme.onPrimary, 0.92f)
        val cell = r * 0.30f
        val gap = r * 0.10f
        for (row in 0..1) {
            for (col in 0..1) {
                val left = cx - cell - gap / 2f + col * (cell + gap)
                val top = cy - cell - gap / 2f + row * (cell + gap)
                canvas.drawRoundRect(
                    RectF(left, top, left + cell, top + cell),
                    cell * 0.28f, cell * 0.28f, fillPaint,
                )
            }
        }
    }

    private fun withAlpha(color: Int, alpha: Float): Int {
        val a = (Color.alpha(color) * alpha).roundToInt().coerceIn(0, 255)
        return Color.argb(a, Color.red(color), Color.green(color), Color.blue(color))
    }

    // ---------- 触摸 ----------

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (editing) {
            // 长按进入编辑时手指可能仍按着：进入编辑的瞬间终止 FAB 手势跟踪，
            // 避免其状态跨会话残留到编辑结束之后（见 fabLongPress）
            if (fabPointerId != -1) resetFabGesture()
            return handleEditTouch(event)
        }
        return handlePlayTouch(event)
    }

    /** 终止 FAB 手势跟踪并取消未决的长按。 */
    private fun resetFabGesture() {
        fabPointerId = -1
        fabMoved = false
        handler.removeCallbacks(fabLongPress)
    }

    /**
     * 被跟踪的 FAB 手指抬起时收尾：轻点切换显隐、拖动落盘位置，随后清理状态。
     * 非 FAB 手指抬起时为空操作（由 [resetFabGesture] 在别的路径兜底）。
     */
    private fun finishFabGesture(pointerId: Int) {
        if (fabPointerId != pointerId) return
        val moved = fabMoved
        resetFabGesture()
        if (!moved) {
            setPadVisible(!padVisible)
            listener?.onPadVisibilityChanged(padVisible)
        } else {
            InputConfigStore.saveFabPosition(context, fabX, fabY)
        }
    }

    private fun handlePlayTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val index = event.actionIndex
                val pointerId = event.getPointerId(index)
                val x = event.getX(index)
                val y = event.getY(index)
                // 新手势开始：上一手势若未走完 UP/CANCEL（如长按进入编辑），先清理陈旧状态
                if (event.actionMasked == MotionEvent.ACTION_DOWN) resetFabGesture()
                if (event.actionMasked == MotionEvent.ACTION_DOWN && fabRect.contains(x, y)) {
                    fabPointerId = pointerId
                    fabMoved = false
                    fabDownX = x
                    fabDownY = y
                    fabGrabDx = x - fabRect.centerX()
                    fabGrabDy = y - fabRect.centerY()
                    handler.postDelayed(fabLongPress, FAB_LONG_PRESS_MS)
                    return true
                }
                if (!padVisible) return event.actionMasked == MotionEvent.ACTION_POINTER_DOWN
                val element = hitTest(x, y) ?: return event.actionMasked == MotionEvent.ACTION_POINTER_DOWN
                if (element.isDirection) {
                    if (dirPointerId != -1) return true
                    dirPointerId = pointerId
                    updateDirection(x, y)
                } else {
                    val button = element.button ?: return true
                    pointerTargets[pointerId] = button.id
                    pointerButtons[pointerId] = button
                    pressButton(button)
                }
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (fabPointerId != -1) {
                    val index = event.findPointerIndex(fabPointerId)
                    if (index >= 0) {
                        val x = event.getX(index)
                        val y = event.getY(index)
                        if (hypot((x - fabDownX).toDouble(), (y - fabDownY).toDouble()) > 8 * density) {
                            fabMoved = true
                            handler.removeCallbacks(fabLongPress)
                        }
                        if (fabMoved) {
                            fabX = ((x - fabGrabDx) / width).coerceIn(0.02f, 0.98f)
                            fabY = ((y - fabGrabDy) / height).coerceIn(0.02f, 0.98f)
                            rebuild()
                            invalidate()
                        }
                    }
                }
                if (dirPointerId != -1) {
                    val index = event.findPointerIndex(dirPointerId)
                    if (index >= 0) updateDirection(event.getX(index), event.getY(index))
                }
                if (pointerTargets.isNotEmpty()) {
                    val iterator = pointerTargets.entries.iterator()
                    while (iterator.hasNext()) {
                        val (pointerId, buttonId) = iterator.next()
                        val index = event.findPointerIndex(pointerId)
                        if (index < 0) continue
                        val element = elementById[buttonId] ?: continue
                        if (!element.rect.contains(event.getX(index), event.getY(index))) {
                            pointerButtons.remove(pointerId)?.let { releaseButton(it) }
                            iterator.remove()
                        }
                    }
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                finishFabGesture(event.getPointerId(event.actionIndex))
                releasePointers(event, event.actionIndex)
                return true
            }

            MotionEvent.ACTION_POINTER_UP -> {
                // FAB 手指先抬起时也必须收尾：否则长按回调仍在队列里，
                // 420ms 后在用户已松手的情况下被动进入编辑态
                finishFabGesture(event.getPointerId(event.actionIndex))
                releasePointers(event, event.actionIndex)
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                resetFabGesture()
                releaseAllPointers()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun releasePointers(event: MotionEvent, actionIndex: Int) {
        val pointerId = event.getPointerId(actionIndex)
        pointerButtons.remove(pointerId)?.let { releaseButton(it) }
        pointerTargets.remove(pointerId)
        if (pointerId == dirPointerId) {
            dirPointerId = -1
            dirKnobDx = 0f
            dirKnobDy = 0f
            applyDirectionState(
                mapOf("up" to false, "down" to false, "left" to false, "right" to false),
                activeProfile().direction,
            )
            invalidate()
        }
    }

    private fun releaseAllPointers() {
        pointerButtons.values.forEach { releaseButton(it) }
        pointerButtons.clear()
        pointerTargets.clear()
        dirPointerId = -1
        dirKnobDx = 0f
        dirKnobDy = 0f
        applyDirectionState(
            mapOf("up" to false, "down" to false, "left" to false, "right" to false),
            activeProfile().direction,
        )
        invalidate()
    }

    private fun pressButton(button: PadButton) {
        if (!button.visible) return
        pressedIds.add(button.id)
        dispatcher.press(button.id, button.keys, button.autoKeep)
        invalidate()
    }

    private fun releaseButton(button: PadButton) {
        pressedIds.remove(button.id)
        dispatcher.release(button.id, button.autoKeep)
        invalidate()
    }

    private fun hitTest(x: Float, y: Float): Element? {
        val active = activeProfile()
        for (index in elements.indices.reversed()) {
            val element = elements[index]
            if (element.isDirection) {
                if (!editing && !active.direction.visible) continue
                val r = element.rect.width() / 2f
                if (hypot((x - element.rect.centerX()).toDouble(), (y - element.rect.centerY()).toDouble()) <= r) {
                    return element
                }
            } else {
                val button = element.button ?: continue
                if (!editing && !button.visible) continue
                if (element.rect.contains(x, y)) return element
            }
        }
        return null
    }

    private fun updateDirection(x: Float, y: Float) {
        val element = elementById[DIRECTION_ID] ?: return
        val direction = activeProfile().direction
        val r = element.rect.width() / 2f
        if (r <= 0f) return
        val dx = x - element.rect.centerX()
        val dy = y - element.rect.centerY()
        val deadzone = r * DIR_DEADZONE_RATIO
        val hysteresis = r * DIR_HYSTERESIS_RATIO
        val threshold = if (dirActiveSet.isEmpty()) deadzone else max(0f, deadzone - hysteresis)
        val next = LinkedHashMap<String, Boolean>()
        if (direction.eightDir) {
            next["up"] = -dy > threshold
            next["down"] = dy > threshold
            next["left"] = -dx > threshold
            next["right"] = dx > threshold
        } else if (abs(dx) > abs(dy)) {
            next["up"] = false
            next["down"] = false
            next["left"] = -dx > threshold
            next["right"] = dx > threshold
        } else {
            next["left"] = false
            next["right"] = false
            next["up"] = -dy > threshold
            next["down"] = dy > threshold
        }
        val magnitude = hypot(dx.toDouble(), dy.toDouble()).toFloat()
        dirKnobDx = if (r > 0f) (dx / r).coerceIn(-1f, 1f) else 0f
        dirKnobDy = if (r > 0f) (dy / r).coerceIn(-1f, 1f) else 0f
        if (magnitude < deadzone && dirActiveSet.isEmpty()) {
            dirKnobDx = 0f
            dirKnobDy = 0f
        }
        applyDirectionState(next, direction)
    }

    private fun applyDirectionState(next: Map<String, Boolean>, direction: PadDirection) {
        val keysOf = mapOf(
            "up" to direction.up,
            "down" to direction.down,
            "left" to direction.left,
            "right" to direction.right,
        )
        next.forEach { (name, active) ->
            val was = name in dirActiveSet
            if (active == was) return@forEach
            val keys = keysOf[name].orEmpty()
            if (active) {
                dirActiveSet.add(name)
                keys.forEach { sink.send(it, true) }
            } else {
                dirActiveSet.remove(name)
                keys.asReversed().forEach { sink.send(it, false) }
            }
        }
        invalidate()
    }

    // ---------- 编辑模式 ----------

    private fun handleEditTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val x = event.x
                val y = event.y
                val element = hitTest(x, y)
                if (element != null) {
                    selectedId = element.id
                    editDragId = element.id
                    editMoved = false
                    editDragDx = x - element.rect.centerX()
                    editDragDy = y - element.rect.centerY()
                    notifySelection()
                    invalidate()
                }
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val dragId = editDragId ?: return true
                val element = elementById[dragId] ?: return true
                editMoved = true
                val target = activeProfile()
                val nx = ((event.x - editDragDx) / width).coerceIn(-0.1f, 1.1f)
                val ny = ((event.y - editDragDy) / height).coerceIn(-0.1f, 1.1f)
                if (element.isDirection) {
                    editProfile = target.copy(direction = target.direction.copy(x = nx, y = ny))
                } else {
                    element.button?.let { button ->
                        editProfile = target.withButton(button.copy(x = nx, y = ny))
                    }
                }
                rebuild()
                notifySelection()
                invalidate()
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                editDragId = null
                return true
            }
        }
        return true
    }

    // ---------- 对外 API（控制器 / 编辑面板） ----------

    val isEditing: Boolean get() = editing

    fun enterEditMode() {
        if (editing) return
        // 进入编辑前清掉可能的 FAB 手势残留（长按路径与 JS 桥路径都经这里收口）
        resetFabGesture()
        // 编辑期释放所有按下的游戏按键，避免遗留按下状态
        releaseAllPointers()
        dispatcher.releaseAll()
        editing = true
        editProfile = profile.copy(buttons = profile.buttons.map { it.copy() })
        selectedId = elements.firstOrNull { element ->
            val visible = if (element.isDirection) activeProfile().direction.visible
            else element.button?.visible ?: false
            visible
        }?.id ?: DIRECTION_ID
        rebuild()
        notifySelection()
        listener?.onEditStarted()
        invalidate()
    }

    /** 提交编辑（写回内存并通知控制器落盘）。 */
    fun commitEdit() {
        if (!editing) return
        val committed = editProfile ?: return
        profile = committed
        editing = false
        editProfile = null
        selectedId = null
        rebuild()
        listener?.onSelectionChanged(null)
        listener?.onEditEnded()
        listener?.onProfileCommitted(committed)
        invalidate()
    }

    fun cancelEdit() {
        if (!editing) return
        editing = false
        editProfile = null
        selectedId = null
        rebuild()
        listener?.onSelectionChanged(null)
        listener?.onEditEnded()
        invalidate()
    }

    fun selectionInfo(): SelectionInfo? {
        val id = selectedId ?: return null
        val active = activeProfile()
        return if (id == DIRECTION_ID) {
            SelectionInfo(
                id = id,
                isDirection = true,
                label = context.getString(com.core.engine.R.string.engine_input_direction),
                visible = active.direction.visible,
                sizePercent = (active.direction.size * 100f).roundToInt(),
                keys = emptyList(),
                autoKeep = false,
                eightDir = active.direction.eightDir,
                up = active.direction.up,
                down = active.direction.down,
                left = active.direction.left,
                right = active.direction.right,
            )
        } else {
            val button = active.buttons.firstOrNull { it.id == id } ?: return null
            SelectionInfo(
                id = id,
                isDirection = false,
                label = button.text.ifBlank { button.id },
                visible = button.visible,
                sizePercent = (button.size * 100f).roundToInt(),
                keys = button.keys,
                autoKeep = button.autoKeep,
                eightDir = false,
            )
        }
    }

    /** 设置选中元素宽度（percent = 宽 / 屏幕短边 × 100）。 */
    fun setSelectedSizePercent(percent: Int) {
        val id = selectedId ?: return
        val active = activeProfile()
        val size = (percent / 100f).coerceIn(PadButton.MIN_SIZE, PadButton.MAX_SIZE)
        editProfile = if (id == DIRECTION_ID) {
            active.copy(direction = active.direction.copy(size = size.coerceIn(0.08f, 0.8f)))
        } else {
            val button = active.buttons.firstOrNull { it.id == id } ?: return
            active.withButton(button.copy(size = size))
        }
        rebuild()
        notifySelection()
        invalidate()
    }

    fun updateSelectedButton(text: String, keys: List<Int>, autoKeep: Boolean) {
        val id = selectedId ?: return
        val active = activeProfile()
        val button = active.buttons.firstOrNull { it.id == id } ?: return
        editProfile = active.withButton(
            button.copy(text = text.take(12), keys = keys, autoKeep = autoKeep),
        )
        rebuild()
        notifySelection()
        invalidate()
    }

    fun updateSelectedDirection(eightDir: Boolean, up: List<Int>, down: List<Int>, left: List<Int>, right: List<Int>) {
        val id = selectedId ?: return
        if (id != DIRECTION_ID) return
        val active = activeProfile()
        editProfile = active.copy(
            direction = active.direction.copy(
                eightDir = eightDir,
                up = up.ifEmpty { active.direction.up },
                down = down.ifEmpty { active.direction.down },
                left = left.ifEmpty { active.direction.left },
                right = right.ifEmpty { active.direction.right },
            ),
        )
        rebuild()
        notifySelection()
        invalidate()
    }

    fun updateSelectedVisibility(visible: Boolean) {
        val id = selectedId ?: return
        val active = activeProfile()
        editProfile = if (id == DIRECTION_ID) {
            active.copy(direction = active.direction.copy(visible = visible))
        } else {
            val button = active.buttons.firstOrNull { it.id == id } ?: return
            active.withButton(button.copy(visible = visible))
        }
        rebuild()
        notifySelection()
        invalidate()
    }

    /** 新增按钮的类型（编辑面板的「新增」对话框）。 */
    enum class NewButtonType {
        /** 椭圆形普通按钮（宽高比 0.46）：可绑定任意键位 / 鼠标键。 */
        BUTTON,

        /** 圆形普通按钮（正方形盒 + 全圆角）：用于单字键位。 */
        ROUND_BUTTON,

        /** 方向键（摇杆外观，四/八方向）；每个方案仅一个。 */
        DIRECTION,

        ;

        /** 对应的按钮几何预设；方向键无几何预设（不是 PadButton）。 */
        fun toGeometry(): PadButtonGeometry? = when (this) {
            BUTTON -> PadButtonGeometry.OVAL
            ROUND_BUTTON -> PadButtonGeometry.ROUND
            DIRECTION -> null
        }
    }

    /** 该类型当前能否新增（方向键至多一个）。 */
    fun canAdd(type: NewButtonType): Boolean = when (type) {
        NewButtonType.BUTTON, NewButtonType.ROUND_BUTTON -> true
        NewButtonType.DIRECTION -> !activeProfile().direction.visible
    }

    /**
     * 新增一个控件并选中；类型不可用时返回 null（方向键已存在）。
     *
     * 普通按钮落在屏幕中央、默认无键位绑定（先出现再绑定，避免误触发送错键）。
     */
    fun addButton(type: NewButtonType): String? {
        if (!editing) return null
        val active = activeProfile()
        return when (type) {
            NewButtonType.BUTTON,
            NewButtonType.ROUND_BUTTON -> {
                var index = 1
                var id = "btn-$index"
                while (active.buttons.any { it.id == id } || id == DIRECTION_ID) {
                    index++
                    id = "btn-$index"
                }
                // 几何参数由纯函数给出（可单测锚定椭圆/圆形两种外观）
                val geometry = type.toGeometry() ?: PadButtonGeometry.OVAL
                val button = PadProfile.newButtonDefaults(geometry, id, "New")
                editProfile = active.copy(buttons = active.buttons + button)
                selectedId = id
                rebuild()
                notifySelection()
                invalidate()
                id
            }

            NewButtonType.DIRECTION -> {
                if (active.direction.visible) return null
                editProfile = active.copy(direction = active.direction.copy(visible = true))
                selectedId = DIRECTION_ID
                rebuild()
                notifySelection()
                invalidate()
                DIRECTION_ID
            }
        }
    }

    /** 复制选中按钮（副本偏移 2%，不与原控件完全重叠）。 */
    fun duplicateSelected() {
        val id = selectedId ?: return
        if (id == DIRECTION_ID) return
        val active = activeProfile()
        val button = active.buttons.firstOrNull { it.id == id } ?: return
        var index = 2
        var newId = "${button.id}-$index"
        while (active.buttons.any { it.id == newId }) {
            index++
            newId = "${button.id}-$index"
        }
        val copy = button.copy(
            id = newId,
            text = button.text,
            x = (button.x + 0.02f).coerceIn(-0.1f, 1.1f),
            y = (button.y + 0.02f).coerceIn(-0.1f, 1.1f),
        )
        editProfile = active.copy(buttons = active.buttons + copy)
        selectedId = copy.id
        rebuild()
        notifySelection()
        invalidate()
    }

    fun deleteSelected() {
        val id = selectedId ?: return
        if (id == DIRECTION_ID) return
        val active = activeProfile()
        editProfile = active.removeButton(id)
        selectedId = active.buttons.firstOrNull()?.id ?: DIRECTION_ID
        rebuild()
        notifySelection()
        invalidate()
    }

    /** 恢复出厂布局（保留方案 id / 名称）。编辑态作用于工作副本，非编辑态立即提交。 */
    fun resetToDefaults() {
        val active = activeProfile()
        val defaults = PadProfile.defaultProfile(active.id, active.name)
        if (editing) {
            editProfile = defaults
            selectedId = DIRECTION_ID
            rebuild()
            notifySelection()
            invalidate()
        } else {
            profile = defaults
            rebuild()
            invalidate()
            listener?.onProfileCommitted(defaults)
        }
    }

    fun setPadVisible(visible: Boolean) {
        padVisible = visible
        releaseAllPointers()
        invalidate()
    }

    fun isPadVisible(): Boolean = padVisible

    fun releaseAllKeys() {
        releaseAllPointers()
        dispatcher.releaseAll()
        invalidate()
    }

    fun detach() {
        handler.removeCallbacks(fabLongPress)
        releaseAllKeys()
    }

    private fun notifySelection() {
        listener?.onSelectionChanged(selectionInfo())
    }

    override fun onDetachedFromWindow() {
        detach()
        super.onDetachedFromWindow()
    }

    companion object {
        const val DIRECTION_ID = "direction"

        private const val FAB_RADIUS_DP = 22f
        private const val FAB_LONG_PRESS_MS = 420L
        private const val DIR_DEADZONE_RATIO = 0.30f
        private const val DIR_HYSTERESIS_RATIO = 0.08f
    }
}
