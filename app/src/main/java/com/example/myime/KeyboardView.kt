package com.example.myime

import android.content.Context
import android.graphics.Canvas
import android.graphics.CornerPathEffect
import android.graphics.Paint
import android.graphics.Path
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
 *  - onDraw 里不分配对象(Paint、Path、RectF、字体度量都提前准备好)
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

    private var layout: Layout? = null
    private var keys: Array<KeySpec> = emptyArray()
    private var rects: Array<RectF> = emptyArray()
    private var isDown = BooleanArray(0)
    private var releasedAt = LongArray(0)
    private var subIndex = IntArray(0)
    private val pointerKey = SparseIntArray()

    // 尺寸(refresh() 里按是否宽屏重新计算)
    private var padX = 0f
    private var padTop = 0f
    private var padBottom = 0f
    private var radius = 0f
    private var shadowH = 0f
    private var slideY = 0f
    private var hitSlopX = 0f
    private var hitSlopY = 0f
    private var fixedHeight = 0
    private var iconW = 0f
    private var iconH = 0f

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pressPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val letterPaint = centered()
    private val funcPaint = centered()
    private val strokePaint = centered()
    private val multiPaint = centered()
    private val richPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.LEFT }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private var letterBase = 0f
    private var funcBase = 0f
    private var strokeBase = 0f
    private var multiBase = 0f
    private var richBase = 0f

    private val shadowRect = RectF()
    private val segRect = RectF()
    private val path = Path()

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

    init {
        applyMetrics()
    }

    private fun centered() = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }

    private fun baseline(p: Paint): Float {
        val fm = p.fontMetrics
        return -(fm.ascent + fm.descent) / 2f
    }

    private fun applyMetrics() {
        val wide = context.isWide()
        padX = context.dpf(if (wide) 42f else 4f)
        padTop = context.dpf(5f)
        padBottom = context.dpf(if (wide) 12f else 6f)
        radius = context.dpf(if (wide) 10f else 8f)
        shadowH = context.dpf(2f)
        slideY = context.dpf(10f)
        fixedHeight = context.dpi(if (wide) 279f else 224f)
        iconW = context.dpf(if (wide) 13f else 11f)
        iconH = context.dpf(if (wide) 9f else 8f)

        letterPaint.textSize = context.spf(if (wide) 26f else 21f)
        funcPaint.textSize = context.spf(if (wide) 17f else 14f)
        strokePaint.textSize = context.spf(if (wide) 34f else 28f)
        multiPaint.textSize = context.spf(if (wide) 20f else 17f)
        richPaint.textSize = context.spf(if (wide) 21f else 16f)
        iconPaint.strokeWidth = context.dpf(1.8f)
        iconPaint.pathEffect = CornerPathEffect(context.dpf(1.6f))

        letterBase = baseline(letterPaint)
        funcBase = baseline(funcPaint)
        strokeBase = baseline(strokePaint)
        multiBase = baseline(multiPaint)
        richBase = baseline(richPaint)
    }

    /** 每次开始输入时调用:跟随深色模式、屏幕宽度变化。 */
    fun refresh() {
        palette = Palette(context)
        applyMetrics()
        layoutKeys()
        requestLayout()
        invalidate()
    }

    // ---------------------------------------------------------------- 布局

    fun setLayout(l: Layout, animate: Boolean) {
        // 只是标签/高亮变了(大写键、分词键等),几何不变:保留按压状态,不重算
        if (!animate && sameShape(l)) {
            layout = l
            keys = l.keys.toTypedArray()
            invalidate()
            return
        }

        stopRepeat()
        layout = l
        keys = l.keys.toTypedArray()
        rects = Array(keys.size) { RectF() }
        isDown = BooleanArray(keys.size)
        releasedAt = LongArray(keys.size) { -1L }
        subIndex = IntArray(keys.size)
        pointerKey.clear()
        layoutKeys()
        invalidate()
        if (animate) playSwitchAnimation()
    }

    private fun sameShape(l: Layout): Boolean {
        val old = layout ?: return false
        if (old.cols != l.cols || old.rows != l.rows || old.gapX != l.gapX || old.gapY != l.gapY) return false
        if (keys.size != l.keys.size) return false
        for (i in keys.indices) {
            val a = keys[i]
            val b = l.keys[i]
            if (a.x != b.x || a.y != b.y || a.w != b.w || a.h != b.h) return false
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
        val l = layout ?: return
        if (width == 0 || height == 0 || rects.size != keys.size) return
        val gx = context.dpf(l.gapX)
        val gy = context.dpf(l.gapY)
        val pitchX = (width - 2 * padX + gx) / l.cols
        val pitchY = (height - padTop - padBottom + gy) / l.rows
        for (i in keys.indices) {
            val k = keys[i]
            val left = padX + k.x * pitchX
            val top = padTop + k.y * pitchY
            rects[i].set(left, top, left + k.w * pitchX - gx, top + k.h * pitchY - gy)
        }
        hitSlopX = gx / 2f
        hitSlopY = gy / 2f
    }

    // ---------------------------------------------------------------- 绘制

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(palette.background)
        val now = drawingTime
        var fading = false
        for (i in keys.indices) {
            val k = keys[i]
            val r = rects[i]

            val face: Int
            val shadow: Int
            val onFace: Int
            if (k.primary || k.active) {
                face = palette.accent
                shadow = palette.accentShadow
                onFace = palette.onAccent
            } else if (k.function) {
                face = palette.functionKey
                shadow = palette.functionShadow
                onFace = palette.text
            } else {
                face = palette.key
                shadow = palette.keyShadow
                onFace = palette.text
            }

            shadowRect.set(r.left, r.top + shadowH, r.right, r.bottom + shadowH)
            fill.color = shadow
            canvas.drawRoundRect(shadowRect, radius, radius, fill)
            fill.color = face
            canvas.drawRoundRect(r, radius, radius, fill)

            val a = pressAmount(i, now)
            if (a > 0f) {
                pressPaint.color = palette.pressTint
                pressPaint.alpha = (a * palette.pressAlpha).toInt()
                val m = k.multi
                if (m != null) {
                    val seg = r.height() / m.size
                    segRect.set(r.left, r.top + seg * subIndex[i], r.right, r.top + seg * (subIndex[i] + 1))
                    canvas.drawRoundRect(segRect, radius, radius, pressPaint)
                } else {
                    canvas.drawRoundRect(r, radius, radius, pressPaint)
                }
                if (!isDown[i]) fading = true
            }
            drawLabel(canvas, k, r, onFace)
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

    private fun drawLabel(canvas: Canvas, k: KeySpec, r: RectF, color: Int) {
        val cx = r.centerX()
        val cy = r.centerY()

        val multi = k.multi
        if (multi != null) {
            multiPaint.color = color
            val seg = r.height() / multi.size
            for (j in multi.indices) {
                canvas.drawText(multi[j].label, cx, r.top + seg * (j + 0.5f) + multiBase, multiPaint)
            }
            return
        }
        if (k.icon != Icon.NONE) {
            drawIcon(canvas, k.icon, cx, cy, color)
            return
        }
        if (k.rich != 0) {
            drawRich(canvas, k.rich, cx, cy + richBase)
            return
        }
        val label = k.label
        if (label.isEmpty()) return

        val paint: Paint
        val base: Float
        if (k.type == ActionType.STROKE && label != "*") {
            paint = strokePaint
            base = strokeBase
        } else if (label == "*") {
            paint = letterPaint
            base = letterBase
        } else if (k.function || label.length > 1) {
            paint = funcPaint
            base = funcBase
        } else {
            paint = letterPaint
            base = letterBase
        }
        paint.color = color
        canvas.drawText(label, cx, cy + base, paint)
    }

    /** 「中/英」:当前语言加粗,另一个变淡。 */
    private fun drawRich(canvas: Canvas, which: Int, cx: Float, baseY: Float) {
        val wZ = richPaint.measureText("中")
        val wS = richPaint.measureText("/")
        val wE = richPaint.measureText("英")
        val x = cx - (wZ + wS + wE) / 2f

        richPaint.isFakeBoldText = which == 1
        richPaint.color = if (which == 1) palette.text else palette.textFaint
        canvas.drawText("中", x, baseY, richPaint)

        richPaint.isFakeBoldText = false
        richPaint.color = palette.textFaint
        canvas.drawText("/", x + wZ, baseY, richPaint)

        richPaint.isFakeBoldText = which == 2
        richPaint.color = if (which == 2) palette.text else palette.textFaint
        canvas.drawText("英", x + wZ + wS, baseY, richPaint)
        richPaint.isFakeBoldText = false
    }

    private fun drawIcon(canvas: Canvas, kind: Int, cx: Float, cy: Float, color: Int) {
        iconPaint.color = color
        val w = iconW
        val h = iconH
        path.reset()
        when (kind) {
            Icon.BACKSPACE -> {
                path.moveTo(cx - w, cy)
                path.lineTo(cx - w * 0.35f, cy - h)
                path.lineTo(cx + w, cy - h)
                path.lineTo(cx + w, cy + h)
                path.lineTo(cx - w * 0.35f, cy + h)
                path.close()
                canvas.drawPath(path, iconPaint)
                canvas.drawLine(cx - w * 0.1f, cy - h * 0.4f, cx + w * 0.55f, cy + h * 0.4f, iconPaint)
                canvas.drawLine(cx + w * 0.55f, cy - h * 0.4f, cx - w * 0.1f, cy + h * 0.4f, iconPaint)
            }
            Icon.ENTER -> {
                path.moveTo(cx + w * 0.8f, cy - h * 0.7f)
                path.lineTo(cx + w * 0.8f, cy + h * 0.2f)
                path.lineTo(cx - w * 0.8f, cy + h * 0.2f)
                canvas.drawPath(path, iconPaint)
                canvas.drawLine(cx - w * 0.8f, cy + h * 0.2f, cx - w * 0.8f + h * 0.55f, cy + h * 0.2f - h * 0.55f, iconPaint)
                canvas.drawLine(cx - w * 0.8f, cy + h * 0.2f, cx - w * 0.8f + h * 0.55f, cy + h * 0.2f + h * 0.55f, iconPaint)
            }
            Icon.SPACE -> {
                val sw = w * 1.4f
                path.moveTo(cx - sw, cy - h * 0.35f)
                path.lineTo(cx - sw, cy + h * 0.35f)
                path.lineTo(cx + sw, cy + h * 0.35f)
                path.lineTo(cx + sw, cy - h * 0.35f)
                canvas.drawPath(path, iconPaint)
            }
            Icon.UP -> {
                path.moveTo(cx - w * 0.55f, cy + h * 0.3f)
                path.lineTo(cx, cy - h * 0.35f)
                path.lineTo(cx + w * 0.55f, cy + h * 0.3f)
                canvas.drawPath(path, iconPaint)
            }
            Icon.DOWN -> {
                path.moveTo(cx - w * 0.55f, cy - h * 0.3f)
                path.lineTo(cx, cy + h * 0.35f)
                path.lineTo(cx + w * 0.55f, cy - h * 0.3f)
                canvas.drawPath(path, iconPaint)
            }
            Icon.LEFT -> {
                path.moveTo(cx + h * 0.3f, cy - h * 0.75f)
                path.lineTo(cx - h * 0.4f, cy)
                path.lineTo(cx + h * 0.3f, cy + h * 0.75f)
                canvas.drawPath(path, iconPaint)
            }
            Icon.RIGHT -> {
                path.moveTo(cx - h * 0.3f, cy - h * 0.75f)
                path.lineTo(cx + h * 0.4f, cy)
                path.lineTo(cx - h * 0.3f, cy + h * 0.75f)
                canvas.drawPath(path, iconPaint)
            }
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
        val y = y0.coerceIn(padTop, height - padBottom)
        for (i in rects.indices) {
            val r = rects[i]
            if (x >= r.left - hitSlopX && x <= r.right + hitSlopX &&
                y >= r.top - hitSlopY && y <= r.bottom + hitSlopY
            ) return i
        }
        return -1
    }

    private fun pointerDown(id: Int, x: Float, y: Float) {
        val i = hit(x, y)
        if (i < 0) return
        var spec = keys[i]
        val multi = spec.multi
        if (multi != null) {
            val r = rects[i]
            val idx = ((y - r.top) / r.height() * multi.size).toInt().coerceIn(0, multi.size - 1)
            subIndex[i] = idx
            spec = multi[idx]
        }
        isDown[i] = true
        releasedAt[i] = -1L
        pointerKey.put(id, i)
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        listener.onKey(spec) // 可能触发 setLayout,会重置上面的状态
        if (spec.repeatable) startRepeat(spec, id)
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
