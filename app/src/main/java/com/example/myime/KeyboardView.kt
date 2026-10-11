package com.example.myime

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.SparseIntArray
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View

/**
 * 整个键盘只有这一个 View,所有按键在同一块 Canvas 上绘制。
 *
 * 流畅度相关的设计:
 *  - onDraw 里不分配对象(Paint、RectF、字体度量都提前算好)
 *  - 按键高亮的消退用"帧时间"计算:每帧只读一次 drawingTime,所有按键同步推进,
 *    并用 postInvalidateOnAnimation 对齐 vsync
 *  - 模式切换只动 alpha / translationY(不触发重新布局),并启用硬件图层
 *  - 所有动画共用 Motion 里的同一个时长和曲线
 */
class KeyboardView(context: Context, private val listener: Listener) : View(context) {

    interface Listener {
        fun onKey(spec: KeySpec)
    }

    private var palette = Palette(context)

    private var rows: List<Row> = emptyList()
    private var keys: Array<KeySpec> = emptyArray()
    private var rects: Array<RectF> = emptyArray()
    private var isDown = BooleanArray(0)
    private var releasedAt = LongArray(0)
    private val pointerKey = SparseIntArray()

    private val gap = context.dpf(5f)
    private val padX = context.dpf(4f)
    private val padY = context.dpf(6f)
    private val radius = context.dpf(8f)
    private val fixedHeight = context.dpi(224f)
    private val subShift = context.dpf(9f)
    private val subY = context.dpf(24f)
    private val slideY = context.dpf(10f)

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pressPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val letterPaint = textPaint(context.spf(22f))
    private val funcPaint = textPaint(context.spf(15f))
    private val strokePaint = textPaint(context.spf(30f))
    private val subPaint = textPaint(context.spf(12f))
    private val letterBase = baseline(letterPaint)
    private val funcBase = baseline(funcPaint)
    private val strokeBase = baseline(strokePaint)
    private val subBase = baseline(subPaint)

    private val uiHandler = Handler(Looper.getMainLooper())
    private var repeatSpec: KeySpec? = null
    private var repeatPointer = -1
    private val repeatRunnable = object : Runnable {
        override fun run() {
            val s = repeatSpec ?: return
            listener.onKey(s)
            uiHandler.postDelayed(this, REPEAT_INTERVAL_MS)
        }
    }

    private fun textPaint(size: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = size
    }

    private fun baseline(p: Paint): Float {
        val fm = p.fontMetrics
        return -(fm.ascent + fm.descent) / 2f
    }

    fun refreshTheme() {
        palette = Palette(context)
        invalidate()
    }

    // ---------------------------------------------------------------- 布局

    fun setLayout(newRows: List<Row>, animate: Boolean) {
        val flat = ArrayList<KeySpec>()
        for (r in newRows) flat.addAll(r.keys)

        // 只是标签/高亮变了(比如大写键),结构不变:保留按压状态,不重算
        if (!animate && sameShape(newRows, flat)) {
            rows = newRows
            keys = flat.toTypedArray()
            invalidate()
            return
        }

        stopRepeat()
        rows = newRows
        keys = flat.toTypedArray()
        rects = Array(keys.size) { RectF() }
        isDown = BooleanArray(keys.size)
        releasedAt = LongArray(keys.size) { -1L }
        pointerKey.clear()
        layoutKeys()
        invalidate()
        if (animate) playSwitchAnimation()
    }

    private fun sameShape(newRows: List<Row>, flat: List<KeySpec>): Boolean {
        if (keys.size != flat.size || rows.size != newRows.size) return false
        for (i in rows.indices) {
            if (rows[i].heightWeight != newRows[i].heightWeight) return false
        }
        for (i in keys.indices) {
            if (keys[i].type != flat[i].type || keys[i].weight != flat[i].weight) return false
        }
        return true
    }

    private fun playSwitchAnimation() {
        animate().cancel()
        alpha = 0.35f
        translationY = slideY
        animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(Motion.DURATION_MS)
            .setInterpolator(Motion.interpolator)
            .withLayer()
            .start()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), fixedHeight)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        layoutKeys()
    }

    private fun layoutKeys() {
        if (width == 0 || height == 0 || rows.isEmpty()) return
        val totalUnits = rows.sumOf { it.heightWeight.toDouble() }.toFloat()
        val unitH = (height - 2 * padY - gap * (totalUnits - 1f)) / totalUnits
        var y = padY
        var k = 0
        for (row in rows) {
            val rowH = unitH * row.heightWeight + gap * (row.heightWeight - 1f)
            val s = row.keys.sumOf { it.weight.toDouble() }.toFloat()
            val unitW = (width - 2 * padX - gap * (s - 1f)) / s
            var x = padX
            for (key in row.keys) {
                val w = unitW * key.weight + gap * (key.weight - 1f)
                rects[k].set(x, y, x + w, y + rowH)
                x += w + gap
                k++
            }
            y += rowH + gap
        }
    }

    // ---------------------------------------------------------------- 绘制

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(palette.background)
        val now = drawingTime
        var fading = false
        for (i in keys.indices) {
            val k = keys[i]
            if (k.type == ActionType.SPACER) continue
            val r = rects[i]

            fill.color = when {
                k.active -> palette.accent
                k.function -> palette.functionKey
                else -> palette.key
            }
            canvas.drawRoundRect(r, radius, radius, fill)

            val a = pressAmount(i, now)
            if (a > 0f) {
                pressPaint.color = palette.accent
                pressPaint.alpha = (a * 110f).toInt()
                canvas.drawRoundRect(r, radius, radius, pressPaint)
                if (!isDown[i]) fading = true
            }
            drawLabel(canvas, k, r)
        }
        if (fading) postInvalidateOnAnimation()
    }

    private fun pressAmount(i: Int, now: Long): Float {
        if (isDown[i]) return 1f
        val t0 = releasedAt[i]
        if (t0 < 0L) return 0f
        val t = (now - t0).coerceAtLeast(0L).toFloat() / Motion.DURATION_MS
        if (t >= 1f) {
            releasedAt[i] = -1L
            return 0f
        }
        return 1f - Motion.interpolator.getInterpolation(t)
    }

    private fun drawLabel(canvas: Canvas, k: KeySpec, r: RectF) {
        val label = k.label
        if (label.isEmpty()) return
        val paint: Paint
        val base: Float
        if (k.type == ActionType.STROKE) {
            paint = strokePaint
            base = strokeBase
        } else if (k.function || k.type == ActionType.SPACE || label.length > 1) {
            paint = funcPaint
            base = funcBase
        } else {
            paint = letterPaint
            base = letterBase
        }
        val color = if (k.active) palette.onAccent else palette.text
        val cx = r.centerX()
        val cy = r.centerY()
        val sub = k.sub
        paint.color = color
        canvas.drawText(label, cx, cy + base - (if (sub != null) subShift else 0f), paint)
        if (sub != null) {
            subPaint.color = color
            subPaint.alpha = 150
            canvas.drawText(sub, cx, cy + subY + subBase, subPaint)
        }
    }

    // ---------------------------------------------------------------- 触摸

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val idx = e.actionIndex
                pointerDown(e.getPointerId(idx), e.getX(idx), e.getY(idx))
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                pointerUp(e.getPointerId(e.actionIndex))
            }
            MotionEvent.ACTION_CANCEL -> releaseAll()
        }
        return true
    }

    private fun hit(x0: Float, y0: Float): Int {
        val x = x0.coerceIn(padX, width - padX)
        val y = y0.coerceIn(padY, height - padY)
        val h = gap / 2f
        for (i in rects.indices) {
            if (keys[i].type == ActionType.SPACER) continue
            val r = rects[i]
            if (x >= r.left - h && x <= r.right + h && y >= r.top - h && y <= r.bottom + h) return i
        }
        return -1
    }

    private fun pointerDown(id: Int, x: Float, y: Float) {
        val i = hit(x, y)
        if (i < 0) return
        val spec = keys[i]
        isDown[i] = true
        releasedAt[i] = -1L
        pointerKey.put(id, i)
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        listener.onKey(spec) // 可能触发 setLayout,会重置上面的状态
        if (spec.type == ActionType.BACKSPACE) startRepeat(spec, id)
        invalidate()
    }

    private fun pointerUp(id: Int) {
        val i = pointerKey.get(id, -1)
        if (i < 0) return
        pointerKey.delete(id)
        if (i < isDown.size) {
            isDown[i] = false
            releasedAt[i] = SystemClock.uptimeMillis()
        }
        if (repeatPointer == id) stopRepeat()
        postInvalidateOnAnimation()
    }

    private fun releaseAll() {
        val now = SystemClock.uptimeMillis()
        for (i in isDown.indices) {
            if (isDown[i]) {
                isDown[i] = false
                releasedAt[i] = now
            }
        }
        pointerKey.clear()
        stopRepeat()
        postInvalidateOnAnimation()
    }

    private fun startRepeat(spec: KeySpec, pointerId: Int) {
        stopRepeat()
        repeatSpec = spec
        repeatPointer = pointerId
        uiHandler.postDelayed(repeatRunnable, REPEAT_DELAY_MS)
    }

    private fun stopRepeat() {
        uiHandler.removeCallbacks(repeatRunnable)
        repeatSpec = null
        repeatPointer = -1
    }

    override fun onDetachedFromWindow() {
        stopRepeat()
        super.onDetachedFromWindow()
    }

    private companion object {
        const val REPEAT_DELAY_MS = 420L
        const val REPEAT_INTERVAL_MS = 55L
    }
}
