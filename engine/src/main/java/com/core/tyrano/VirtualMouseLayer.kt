package com.core.tyrano

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import java.util.Locale

/**
 * WebView 虚拟鼠标层（RPG Maker MV/MZ 专用，见 issue #25）。
 *
 * 手势语义（通用虚拟鼠标范式）：
 *  - 手柄：可拖动悬浮圆钮，单击切换光标模式，位置持久化。
 *  - 光标模式：
 *      单指拖动        → 相对移动光标（Wine 式，hover 跟随）
 *      单指快速点按    → 左键点击
 *      双指上下滑动    → 滚轮（作用于光标处窗口）
 *      单指长按 350ms  → 右键点击
 *      双指快速点按    → 右键点击
 *  - 光标关闭时除手柄区域一律放行 WebView（触屏零干扰）。
 *
 * 合成事件经 [dispatchJs] 调用注入的 `window.__tnMouse`（坐标为 CSS 像素），
 * move/wheel 按 ~16ms 批量刷新以降低 IPC 频率。
 */
class VirtualMouseLayer(
    context: Context,
    private val dispatchJs: (String) -> Unit,
) : View(context) {

    private companion object {
        const val LONG_PRESS_MS = 350L
        const val TWO_FINGER_TAP_MS = 300L
        const val WHEEL_SENS = 4.0f
        const val CURSOR_SENS = 1.5f
        const val FLUSH_INTERVAL_MS = 16L
        const val HANDLE_RADIUS_DP = 23f
        const val CURSOR_SCALE_DP = 1.2f
        const val PREFS = "tyrano_virtual_mouse"
        const val KEY_HANDLE_X = "handle_nx"
        const val KEY_HANDLE_Y = "handle_ny"

        // 手势状态机
        const val STATE_IDLE = 0      // 无手势
        const val STATE_HANDLE = 1    // 操作手柄
        const val STATE_PENDING = 2   // 单指按下未超 slop（等长按/点按判定）
        const val STATE_DRAG = 3      // 单指拖动光标
        const val STATE_WHEEL = 4     // 双指滚动
        const val STATE_COOLDOWN = 5  // 多指手势结束后的收尾（忽略剩余手指直至全部抬起）
        const val STATE_CONSUMED = 6  // 已触发右键，忽略本手势剩余部分
    }

    private val density = resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 手柄圆钮半径（px） */
    private val handleR = HANDLE_RADIUS_DP * density

    // ---- 手柄 / 光标位置（px；光标存归一化值防旋转丢位） ----
    private var hx = -1f
    private var hy = -1f
    private var active = false
    private var cnx = 0.5f
    private var cny = 0.4f

    // ---- 手势状态 ----
    private var state = STATE_IDLE
    private var pid1 = -1            // 主指（单指手势 / 双指中的第一指）
    private var pid2 = -1            // 第二指（双指滚动）
    private var p1x = 0f; private var p1y = 0f
    private var p2x = 0f; private var p2y = 0f
    private var lastCx = 0f; private var lastCy = 0f // 双指质心
    private var sx = 0f; private var sy = 0f
    private var moved = false
    private var handleDrag = false
    private var hox = 0f; private var hoy = 0f
    private var downAt = 0L
    private var twoFingerAt = 0L

    // ---- 合成事件批量刷新 ----
    private var pendingCssX = Float.NaN
    private var pendingCssY = 0f
    private var pendingWheelDelta = 0f
    private var flushPosted = false

    // ---- 画笔 ----
    private val handleFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val handleStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = density
        color = 0x73FFFFFF
    }
    private val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.8f * density
        color = Color.WHITE
        strokeCap = Paint.Cap.ROUND
    }
    private val cursorShadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val cursorFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val cursorStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = density * 0.9f
        color = 0xE6141414.toInt()
        strokeJoin = Paint.Join.ROUND
    }
    private val cursorPath = Path()
    private val glyphRect = RectF()

    private val longPressRunnable = Runnable {
        // 单指长按 = 右键（一次性消费，忽略本手势剩余部分）
        if (state == STATE_PENDING && !moved) {
            state = STATE_CONSUMED
            invalidate()
            js("rclick", cnx * width / density, cny * height / density)
        }
    }
    private val flushRunnable = Runnable {
        flushPosted = false
        flushNow()
    }

    /** 页面重载 / 退后台时复位手势，但保留光标开关与位置。 */
    fun reset() {
        removeCallbacks(longPressRunnable)
        removeCallbacks(flushRunnable)
        flushPosted = false
        state = STATE_IDLE
        pid1 = -1
        pid2 = -1
        flushNow()
        invalidate()
    }

    /**
     * Updates the handle position when the view size changes and keeps it within the view bounds.
     *
     * @param w The new view width in pixels.
     * @param h The new view height in pixels.
     * @param ow The previous view width in pixels.
     * @param oh The previous view height in pixels.
     */

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        if (w <= 0 || h <= 0) return
        if (hx < 0f) {
            hx = prefs.getFloat(KEY_HANDLE_X, 0.055f) * w
            hy = prefs.getFloat(KEY_HANDLE_Y, 0.34f) * h
        }
        hx = hx.coerceIn(handleR, w - handleR)
        hy = hy.coerceIn(handleR, h - handleR)
    }

    /**
     * Draws the virtual mouse handle and, when enabled, the cursor.
     *
     * @param canvas The canvas on which to draw the virtual mouse controls.
     */
    override fun onDraw(canvas: Canvas) {
        drawHandle(canvas)
        if (active) drawCursor(canvas)
    }

    /**
     * Draws the virtual mouse handle and its mouse-shaped glyph on the canvas.
     *
     * @param canvas The canvas on which to draw the handle.
     */
    private fun drawHandle(canvas: Canvas) {
        handleFill.color = if (active) 0x73469AFF.toInt() else 0x29FFFFFF
        canvas.drawCircle(hx, hy, handleR, handleFill)
        canvas.drawCircle(hx, hy, handleR, handleStroke)
        // 简笔鼠标轮廓：圆角矩形机身 + 中线 + 左键分界
        val bw = handleR * 0.78f
        val bh = handleR * 1.18f
        glyphRect.set(hx - bw / 2f, hy - bh / 2f, hx + bw / 2f, hy + bh / 2f)
        canvas.drawRoundRect(glyphRect, bw / 2.6f, bw / 2.6f, glyphPaint)
        canvas.drawLine(hx, hy - bh / 2f + glyphPaint.strokeWidth, hx, hy - bh * 0.08f, glyphPaint)
        canvas.drawLine(glyphRect.left, hy - bh * 0.08f, glyphRect.right, hy - bh * 0.08f, glyphPaint)
    }

    /** 标准指针箭头：竖直左缘 + 内凹缺口 + 甩尾，滚轮态填充黄色。 */
    private fun drawCursor(canvas: Canvas) {
        val x = cnx * width
        val y = cny * height
        val s = CURSOR_SCALE_DP * density
        cursorPath.rewind()
        cursorPath.moveTo(x, y)                 // 尖端
        cursorPath.lineTo(x, y + 13.6f * s)     // 左缘（竖直）
        cursorPath.lineTo(x + 3.3f * s, y + 10.6f * s)   // 内凹
        cursorPath.lineTo(x + 5.6f * s, y + 15.8f * s)   // 尾部外缘
        cursorPath.lineTo(x + 7.7f * s, y + 14.8f * s)   // 尾部内缘
        cursorPath.lineTo(x + 5.6f * s, y + 9.9f * s)    // 尾根
        cursorPath.lineTo(x + 10.3f * s, y + 9.6f * s)   // 右侧倒钩
        cursorPath.close()
        canvas.save()
        canvas.translate(0.9f * density, 1.5f * density)
        cursorShadow.color = 0x59000000
        canvas.drawPath(cursorPath, cursorShadow)
        canvas.restore()
        cursorFill.color = if (state == STATE_WHEEL) 0xFFFFD34D.toInt() else Color.WHITE
        canvas.drawPath(cursorPath, cursorFill)
        canvas.drawPath(cursorPath, cursorStroke)
    }

    /**
     * Handles touch gestures for the virtual mouse, including handle movement,
     * cursor dragging, left-click, right-click, and two-finger wheel input.
     *
     * @param event The touch event to process.
     * @return `true` when the event is consumed; `false` when inactive touches are
     * passed to the underlying WebView.
     */

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (pid1 != -1) return false
                pid1 = event.getPointerId(0)
                p1x = event.x; sx = event.x
                p1y = event.y; sy = event.y
                lastCx = p1x; lastCy = p1y
                moved = false
                handleDrag = false
                downAt = System.currentTimeMillis()
                if (inHandle(event.x, event.y)) {
                    parent?.requestDisallowInterceptTouchEvent(true)
                    state = STATE_HANDLE
                    hox = event.x - hx
                    hoy = event.y - hy
                    return true
                }
                if (!active) {
                    pid1 = -1
                    return false // 放行给 WebView：触屏模式零干扰
                }
                parent?.requestDisallowInterceptTouchEvent(true)
                state = STATE_PENDING
                postDelayed(longPressRunnable, LONG_PRESS_MS)
                return true
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                if (state == STATE_PENDING || state == STATE_DRAG) {
                    // 第二指落下 → 进入双指滚轮态（取消长按/点按判定）
                    if (pid2 == -1 && event.pointerCount >= 2) {
                        removeCallbacks(longPressRunnable)
                        pid2 = event.getPointerId(event.actionIndex)
                        updatePointer(event, pid1)
                        updatePointer(event, pid2)
                        lastCx = (p1x + p2x) / 2f
                        lastCy = (p1y + p2y) / 2f
                        twoFingerAt = System.currentTimeMillis()
                        state = STATE_WHEEL
                        invalidate()
                        return true
                    }
                }
                return true // 其余情况：多出的手指一律吞掉
            }

            MotionEvent.ACTION_MOVE -> {
                updatePointer(event, pid1)
                updatePointer(event, pid2)
                when (state) {
                    STATE_HANDLE -> {
                        if (Math.hypot((p1x - sx).toDouble(), (p1y - sy).toDouble()) > touchSlop) handleDrag = true
                        if (handleDrag) {
                            hx = (p1x - hox).coerceIn(handleR, width - handleR)
                            hy = (p1y - hoy).coerceIn(handleR, height - handleR)
                            invalidate()
                        }
                    }
                    STATE_PENDING -> {
                        if (!moved && Math.hypot((p1x - sx).toDouble(), (p1y - sy).toDouble()) > touchSlop) {
                            moved = true
                            removeCallbacks(longPressRunnable)
                            state = STATE_DRAG
                            invalidate()
                        }
                    }
                    STATE_DRAG -> {
                        cnx = (cnx + (p1x - lastCx) * CURSOR_SENS / width).coerceIn(0f, 1f)
                        cny = (cny + (p1y - lastCy) * CURSOR_SENS / height).coerceIn(0f, 1f)
                        pendingCssX = cnx * width / density
                        pendingCssY = cny * height / density
                        scheduleFlush()
                        invalidate()
                    }
                    STATE_WHEEL -> {
                        val cxNow = (p1x + p2x) / 2f
                        val cyNow = (p1y + p2y) / 2f
                        val dy = cyNow - lastCy
                        if (Math.hypot((cxNow - lastCx).toDouble(), dy.toDouble()) > touchSlop) moved = true
                        pendingWheelDelta += -dy * WHEEL_SENS / density
                        scheduleFlush()
                    }
                }
                lastCx = p1x; lastCy = p1y
                if (pid2 != -1) { lastCx = (p1x + p2x) / 2f; lastCy = (p1y + p2y) / 2f }
                return true
            }

            MotionEvent.ACTION_POINTER_UP -> {
                val upId = event.getPointerId(event.actionIndex)
                if (state == STATE_WHEEL && (upId == pid1 || upId == pid2)) {
                    flushNow()
                    // 双指快速点按（几乎未移动）= 右键
                    if (!moved && System.currentTimeMillis() - twoFingerAt < TWO_FINGER_TAP_MS) {
                        js("rclick", cnx * width / density, cny * height / density)
                    }
                    state = STATE_COOLDOWN
                    if (upId == pid2) pid2 = -1 else pid1 = pid2.also { pid2 = -1 }
                }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val upId = event.getPointerId(event.actionIndex)
                // 只处理被跟踪手指的抬起；未跟踪手指（第三指等）忽略
                if (event.actionMasked == MotionEvent.ACTION_CANCEL ||
                    upId == pid1 || upId == pid2
                ) {
                    removeCallbacks(longPressRunnable)
                    val quick = !moved && state == STATE_PENDING &&
                        System.currentTimeMillis() - downAt < LONG_PRESS_MS
                    if (event.actionMasked == MotionEvent.ACTION_UP) {
                        if (state == STATE_HANDLE && !handleDrag) {
                            setActive(!active)
                        } else if (quick && active) {
                            flushNow()
                            js("click", cnx * width / density, cny * height / density)
                        } else if (active) {
                            flushNow() // 拖动/滚轮收尾：立即派发剩余增量，不等 16ms 队列
                        }
                    }
                    if (state == STATE_HANDLE && handleDrag) {
                        prefs.edit()
                            .putFloat(KEY_HANDLE_X, hx / width)
                            .putFloat(KEY_HANDLE_Y, hy / height)
                            .apply()
                    }
                    state = STATE_IDLE
                    pid1 = -1
                    pid2 = -1
                    invalidate()
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun updatePointer(event: MotionEvent, id: Int) {
        val idx = event.findPointerIndex(id)
        if (idx < 0) return
        if (id == pid1) { p1x = event.getX(idx); p1y = event.getY(idx) }
        else if (id == pid2) { p2x = event.getX(idx); p2y = event.getY(idx) }
    }

    /**
     * Releases pending callbacks when the view is detached from the window.
     */
    override fun onDetachedFromWindow() {
        removeCallbacks(longPressRunnable)
        removeCallbacks(flushRunnable)
        super.onDetachedFromWindow()
    }

    /**
     * Enables or disables the virtual mouse and updates its visible state.
     *
     * @param on Whether the virtual mouse should be enabled.
     */
    private fun setActive(on: Boolean) {
        active = on
        android.util.Log.i("TyranoMouse", "virtual mouse " + if (on) "ON" else "OFF")
        if (on) {
            pendingCssX = cnx * width / density
            pendingCssY = cny * height / density
            flushNow()
        } else {
            pendingCssX = Float.NaN
        }
        invalidate()
    }

    // ============================================================ JS 派发

    private fun scheduleFlush() {
        if (flushPosted) return
        flushPosted = true
        postDelayed(flushRunnable, FLUSH_INTERVAL_MS)
    }

    private fun flushNow() {
        if (!java.lang.Float.isNaN(pendingCssX)) {
            js("move", pendingCssX, pendingCssY)
            pendingCssX = Float.NaN
        }
        if (pendingWheelDelta != 0f) {
            js("wheel", pendingWheelDelta, cnx * width / density, cny * height / density)
            pendingWheelDelta = 0f
        }
    }

    /**
     * Dispatches a mouse operation to `window.__tnMouse`.
     *
     * @param op The mouse operation name.
     * @param args The operation arguments; non-finite values are converted to zero.
     */
    private fun js(op: String, vararg args: Float) {
        val sb = StringBuilder("window.__tnMouse&&window.__tnMouse.").append(op).append('(')
        args.forEachIndexed { i, v ->
            if (i > 0) sb.append(',')
            val f = if (v.isNaN() || v.isInfinite()) 0f else v
            sb.append(String.format(Locale.US, "%.1f", f))
        }
        sb.append(')')
        if (op != "move") {
            android.util.Log.d("TyranoMouse", "dispatch ${sb.substring(sb.indexOf('.') + 1)}")
        }
        dispatchJs(sb.toString())
    }

    private fun inHandle(x: Float, y: Float): Boolean =
        Math.hypot((x - hx).toDouble(), (y - hy).toDouble()) <= handleR + 6f * density
}
