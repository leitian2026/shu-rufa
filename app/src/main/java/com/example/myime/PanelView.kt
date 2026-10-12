package com.example.myime

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.widget.OverScroller

object BarKind {
    const val NORMAL = 0
    const val FUNCTION = 1
    const val PRIMARY = 2
    const val TAB = 3
    const val SPACER = 4
}

/** 面板底部按键条里的一个键。 */
class BarKey(
    val id: Int,
    val label: String,
    val weight: Float,
    val kind: Int,
    val repeatable: Boolean = false,
    val icon: Int = Icon.NONE,
)

/**
 * 表情、剪贴板这类面板的基类:
 *  - 最上面是一排固定的按键条(有标题的面板:标题在左、按键紧凑靠右在同一行),下面是可上下滑动的内容区(带惯性)
 *  - 高度和键盘完全一致,切换时不跳动
 *  - 动画(显示、按键高亮消退、点分类跳转)都用 Motion 里的同一个时长和曲线
 */
abstract class PanelView(context: Context) : View(context) {

    protected var palette = Palette(context)
    protected var padX = 0f
    protected var padTop = 0f
    protected var padBottom = 0f
    protected var barH = 0f
    protected var barGap = 0f
    protected var radius = 0f
    protected var shadowH = 0f
    private var slideY = 0f
    private var fixedHeight = 0
    private var iconW = 0f
    private var iconH = 0f

    protected var offsetY = 0f
    protected val scroller = OverScroller(context)

    protected abstract val barKeys: List<BarKey>
    protected abstract fun contentHeight(): Float

    /** canvas 已平移:y=0 是内容顶部。可见范围是 [visTop, visBottom]。 */
    protected abstract fun drawContent(canvas: Canvas, visTop: Float, visBottom: Float)

    /** x 是视图坐标,y 是内容坐标。 */
    protected abstract fun onContentTap(x: Float, y: Float)
    protected abstract fun onBarKey(id: Int)
    protected open fun onContentLongPress(x: Float, y: Float) {}
    protected open fun isBarSelected(id: Int): Boolean = false
    protected open fun onMetricsChanged() {}
    protected open fun onShown() {}

    /** true:标题在左、按键紧凑靠右排在同一行(剪贴板、功能页、键盘选择);false:按键铺满整行(表情的分类条)。 */
    protected open val titledHeader: Boolean get() = false

    /** 画标题。left 是标题的左边,centerY 是这一行的竖直中心。只有 titledHeader 为 true 时才会调用。 */
    protected open fun drawHeaderTitle(canvas: Canvas, left: Float, centerY: Float) {}

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pressPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val barText = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val tabText = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private var barBase = 0f
    private var tabBase = 0f
    private val shadowRect = RectF()
    private val path = Path()

    private val barRects = ArrayList<RectF>()
    private var barIsDown = BooleanArray(0)
    private var barReleasedAt = LongArray(0)
    private var barDown = -1

    private val uiHandler = Handler(Looper.getMainLooper())
    private var repeatId = -1
    private val repeatRunnable = object : Runnable {
        override fun run() {
            if (repeatId < 0) return
            onBarKey(repeatId)
            uiHandler.postDelayed(this, REPEAT_INTERVAL_MS)
        }
    }

    private val flingRunnable = object : Runnable {
        override fun run() {
            if (scroller.computeScrollOffset()) {
                offsetY = scroller.currY.toFloat().coerceIn(0f, maxOffsetY())
                invalidate()
                postOnAnimation(this)
            }
        }
    }

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            scroller.forceFinished(true)
            return true
        }

        override fun onScroll(
            e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float,
        ): Boolean {
            offsetY = (offsetY + distanceY).coerceIn(0f, maxOffsetY())
            invalidate()
            return true
        }

        override fun onFling(
            e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float,
        ): Boolean {
            scroller.fling(0, offsetY.toInt(), 0, (-velocityY).toInt(), 0, 0, 0, maxOffsetY().toInt())
            postOnAnimation(flingRunnable)
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            if (e.y < contentTop() || e.y > contentBottom()) return
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            onContentLongPress(e.x, e.y - contentTop() + offsetY)
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            if (e.y < contentTop() || e.y > contentBottom()) return false
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            onContentTap(e.x, e.y - contentTop() + offsetY)
            return true
        }
    })

    init {
        applyMetrics()
    }

    protected fun contentTop(): Float = padTop + barH + barGap
    protected fun contentBottom(): Float = height - padBottom

    /** 标题行里最左边那个按键的左边缘,标题别画到它下面。 */
    protected fun barLeftEdge(): Float {
        var l = width.toFloat()
        for (r in barRects) if (r.width() > 0f && r.left < l) l = r.left
        return l
    }
    protected fun viewportHeight(): Float = contentBottom() - contentTop()
    protected fun maxOffsetY(): Float = (contentHeight() - viewportHeight()).coerceAtLeast(0f)

    private fun baseline(p: Paint): Float {
        val fm = p.fontMetrics
        return -(fm.ascent + fm.descent) / 2f
    }

    private fun applyMetrics() {
        val wide = context.isWide()
        padX = context.dpf(if (wide) 42f else 8f)
        padTop = context.dpf(5f)
        padBottom = context.dpf(if (wide) 12f else 6f)
        barH = context.dpf(if (wide) 52f else 44f)
        barGap = context.dpf(if (wide) 10f else 6f)
        radius = context.dpf(if (wide) 10f else 8f)
        shadowH = context.dpf(2f)
        slideY = context.dpf(10f)
        fixedHeight = context.keyboardHeightPx()
        iconW = context.dpf(if (wide) 13f else 11f)
        iconH = context.dpf(if (wide) 9f else 8f)
        barText.textSize = context.spf(if (wide) 17f else 14f)
        tabText.textSize = context.spf(if (wide) 22f else 18f)
        iconPaint.strokeWidth = context.dpf(1.8f)
        barBase = baseline(barText)
        tabBase = baseline(tabText)
    }

    /** 每次显示面板时调用:跟随深色模式、屏幕宽度变化。 */
    fun refresh() {
        palette = Palette(context)
        applyMetrics()
        layoutBar()
        onMetricsChanged()
        requestLayout()
        invalidate()
    }

    /** 每次显示面板时调用。不做任何滑入/淡入动画,直接显示。 */
    fun onShow(animate: Boolean) {
        scroller.forceFinished(true)
        offsetY = 0f
        refresh()
        onShown()
        animate().cancel()
        alpha = 1f
        translationY = 0f
    }

    /** 直接跳到某个内容位置(没有滚动动画)。 */
    protected fun scrollToContent(y: Float) {
        val target = y.coerceIn(0f, maxOffsetY())
        scroller.forceFinished(true)
        offsetY = target
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), fixedHeight)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        layoutBar()
        onMetricsChanged()
    }

    protected fun layoutBar() {
        val keys = barKeys
        if (width == 0 || height == 0) return
        val gx = context.dpf(6f)
        val top = padTop
        barRects.clear()
        if (titledHeader) {
            // 每个键的宽度跟着文字走,整组靠右排;左边留给标题
            val wide = context.isWide()
            val minW = context.dpf(if (wide) 64f else 54f)
            val padW = context.dpf(28f)
            val widths = FloatArray(keys.size)
            var total = 0f
            for (i in keys.indices) {
                val k = keys[i]
                widths[i] = when {
                    k.kind == BarKind.SPACER -> 0f
                    k.icon == Icon.BACK -> context.dpf(if (wide) 44f else 40f)   // 返回只是个小图标
                    else -> maxOf(minW, barText.measureText(k.label) + padW)
                }
                total += widths[i]
                if (i > 0) total += gx
            }
            var x = maxOf(padX, width - padX - total)
            for (i in keys.indices) {
                barRects.add(RectF(x, top, x + widths[i], top + barH))
                x += widths[i] + gx
            }
        } else {
            var total = 0f
            for (k in keys) total += k.weight
            val unit = (width - 2 * padX - gx * (keys.size - 1)) / total
            var x = padX
            for (k in keys) {
                val w = unit * k.weight
                barRects.add(RectF(x, top, x + w, top + barH))
                x += w + gx
            }
        }
        barIsDown = BooleanArray(keys.size)
        barReleasedAt = LongArray(keys.size) { -1L }
        barDown = -1
    }

    // ---------------------------------------------------------------- 绘制

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(palette.background)
        val top = contentTop()
        val bottom = contentBottom()
        canvas.save()
        canvas.clipRect(0f, top, width.toFloat(), bottom)
        canvas.translate(0f, top - offsetY)
        drawContent(canvas, offsetY, offsetY + (bottom - top))
        canvas.restore()
        drawBar(canvas)
        if (titledHeader) drawHeaderTitle(canvas, padX + context.dpf(4f), padTop + barH / 2f)
    }

    /** 按下时高亮,松手立刻恢复,没有渐隐动画。 */
    private fun barPress(i: Int, now: Long): Float = if (barIsDown[i]) 1f else 0f

    private fun drawBar(canvas: Canvas) {
        val keys = barKeys
        if (barRects.size != keys.size || barIsDown.size != keys.size) return
        val now = drawingTime
        var fading = false
        for (i in keys.indices) {
            val k = keys[i]
            if (k.kind == BarKind.SPACER) continue
            val r = barRects[i]
            val selected = k.kind == BarKind.TAB && isBarSelected(k.id)

            val face: Int
            val shadow: Int
            val onFace: Int
            if (k.kind == BarKind.PRIMARY) {
                face = palette.accent
                shadow = palette.accentShadow
                onFace = palette.onAccent
            } else if (k.kind == BarKind.FUNCTION) {
                face = palette.functionKey
                shadow = palette.functionShadow
                onFace = palette.text
            } else {
                face = palette.key
                shadow = palette.keyShadow
                onFace = palette.text
            }

            val plainBack = k.icon == Icon.BACK
            if (!plainBack && (k.kind != BarKind.TAB || selected)) {
                shadowRect.set(r.left, r.top + shadowH, r.right, r.bottom + shadowH)
                fill.color = shadow
                canvas.drawRoundRect(shadowRect, radius, radius, fill)
                fill.color = face
                canvas.drawRoundRect(r, radius, radius, fill)
            }

            val a = barPress(i, now)
            if (a > 0f) {
                pressPaint.color = palette.pressTint
                pressPaint.alpha = (a * palette.pressAlpha).toInt()
                canvas.drawRoundRect(r, radius, radius, pressPaint)
                if (!barIsDown[i]) fading = true
            }

            if (k.icon == Icon.BACKSPACE) {
                drawBackspace(canvas, r.centerX(), r.centerY(), onFace)
            } else if (plainBack) {
                drawBackArrow(canvas, r.centerX(), r.centerY(), palette.accent)
            } else if (k.label.isNotEmpty()) {
                if (k.kind == BarKind.TAB) {
                    tabText.color = onFace
                    canvas.drawText(k.label, r.centerX(), r.centerY() + tabBase, tabText)
                } else {
                    barText.color = onFace
                    canvas.drawText(k.label, r.centerX(), r.centerY() + barBase, barText)
                }
            }
        }
        if (fading) postInvalidateOnAnimation()
    }

    /** 返回:一个小的向左箭头,没有按钮底色。 */
    private fun drawBackArrow(canvas: Canvas, cx: Float, cy: Float, color: Int) {
        iconPaint.color = color
        val w = iconW * 0.8f
        val h = iconH * 0.85f
        path.reset()
        path.moveTo(cx + w, cy)
        path.lineTo(cx - w, cy)
        path.moveTo(cx - w + h, cy - h)
        path.lineTo(cx - w, cy)
        path.lineTo(cx - w + h, cy + h)
        canvas.drawPath(path, iconPaint)
    }

    private fun drawBackspace(canvas: Canvas, cx: Float, cy: Float, color: Int) {
        iconPaint.color = color
        val w = iconW
        val h = iconH
        path.reset()
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

    // ---------------------------------------------------------------- 触摸

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val i = barHit(e.x, e.y)
                if (i >= 0) {
                    barDown = i
                    barIsDown[i] = true
                    barReleasedAt[i] = -1L
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    val k = barKeys[i]
                    onBarKey(k.id)
                    if (k.repeatable) {
                        stopRepeat()
                        repeatId = k.id
                        uiHandler.postDelayed(repeatRunnable, REPEAT_DELAY_MS)
                    }
                    invalidate()
                    return true
                }
                barDown = -1
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (barDown >= 0) {
                    if (barDown < barIsDown.size) {
                        barIsDown[barDown] = false
                        barReleasedAt[barDown] = SystemClock.uptimeMillis()
                    }
                    barDown = -1
                    stopRepeat()
                    postInvalidateOnAnimation()
                    return true
                }
            }
        }
        if (barDown >= 0) return true // 手指按在按键条上,忽略滑动
        return gestures.onTouchEvent(e)
    }

    private fun barHit(x: Float, y: Float): Int {
        val keys = barKeys
        if (barRects.size != keys.size) return -1
        val slop = context.dpf(3f)
        for (i in keys.indices) {
            if (keys[i].kind == BarKind.SPACER) continue
            val r = barRects[i]
            if (x >= r.left - slop && x <= r.right + slop && y >= r.top - slop && y <= r.bottom + slop) return i
        }
        return -1
    }

    private fun stopRepeat() {
        uiHandler.removeCallbacks(repeatRunnable)
        repeatId = -1
    }

    override fun onDetachedFromWindow() {
        stopRepeat()
        removeCallbacks(flingRunnable)
        super.onDetachedFromWindow()
    }

    private companion object {
        const val REPEAT_DELAY_MS = 420L
        const val REPEAT_INTERVAL_MS = 55L
    }
}
