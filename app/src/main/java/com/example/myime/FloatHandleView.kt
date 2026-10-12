package com.example.myime

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View

/**
 * 悬浮键盘顶上的拖动条:
 *  - 中间拖动:把整个键盘拖到屏幕任何位置
 *  - 左 / 右两端的小角标:拖动改大小(右角:右边和上边跟着手指;左角:左边和上边跟着手指,底边不动)
 * 用屏幕坐标(rawX/rawY)算位移,键盘跟着手指移动时不会抖。
 */
class FloatHandleView(
    context: Context,
    private val listener: Listener,
) : View(context) {

    interface Listener {
        fun onMove()
        fun onResize()
        fun onDone()
        fun boxHeightPx(): Int
    }

    private enum class Drag { NONE, MOVE, LEFT, RIGHT }

    private var palette = Palette(context)
    private val bg = Paint()
    private val pill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val grip = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = context.dpf(2f)
    }
    private val rect = RectF()

    private var drag = Drag.NONE
    private var startX = 0f
    private var startY = 0f
    private var startW = 1f
    private var startL = 0f
    private var startH = 0f
    private var startB = 0f

    fun refresh() {
        palette = Palette(context)
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), context.dpi(28f))
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        bg.color = palette.topBar
        canvas.drawRect(0f, 0f, w, h, bg)

        // 中间的小药丸
        pill.color = palette.textFaint
        pill.alpha = 150
        val pw = context.dpf(40f)
        val ph = context.dpf(4f)
        rect.set(w / 2f - pw / 2f, h / 2f - ph / 2f, w / 2f + pw / 2f, h / 2f + ph / 2f)
        canvas.drawRoundRect(rect, ph / 2f, ph / 2f, pill)

        // 两端的缩放角标(靠上的那个角:一条横线 + 一条竖线,再加一条斜线)
        grip.color = palette.icon
        val m = context.dpf(10f)
        val s = context.dpf(9f)
        // 左上
        canvas.drawLine(m, m + s, m, m, grip)
        canvas.drawLine(m, m, m + s, m, grip)
        // 右上
        canvas.drawLine(w - m - s, m, w - m, m, grip)
        canvas.drawLine(w - m, m, w - m, m + s, grip)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val zone = context.dpf(56f)
                drag = when {
                    e.x < zone -> Drag.LEFT
                    e.x > width - zone -> Drag.RIGHT
                    else -> Drag.MOVE
                }
                startX = e.rawX
                startY = e.rawY
                startW = KbSize.widthFrac
                startL = KbSize.leftFrac
                startH = KbSize.heightDp(context)
                startB = KbSize.bottomDp
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (drag != Drag.NONE) applyDrag(e.rawX - startX, e.rawY - startY)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (drag != Drag.NONE) listener.onDone()
                drag = Drag.NONE
                return true
            }
        }
        return true
    }

    private fun applyDrag(dx: Float, dy: Float) {
        val dm = resources.displayMetrics
        val df = dx / dm.widthPixels
        val d = dm.density
        val minW = KbSize.minWidthFrac(context)
        when (drag) {
            Drag.MOVE -> {
                KbSize.leftFrac = (startL + df).coerceIn(0f, 1f - startW)
                val maxB = ((dm.heightPixels - listener.boxHeightPx() - context.dpf(24f)) / d).coerceAtLeast(0f)
                KbSize.bottomDp = (startB - dy / d).coerceIn(0f, maxB)
                listener.onMove()
            }
            Drag.RIGHT -> {
                KbSize.widthFrac = (startW + df).coerceIn(minW, 1f - startL)
                KbSize.heightDp = (startH - dy / d).coerceIn(KbSize.minHeightDp(), KbSize.maxHeightDp(context))
                listener.onResize()
            }
            Drag.LEFT -> {
                val right = startL + startW
                val l = (startL + df).coerceIn(0f, right - minW)
                KbSize.leftFrac = l
                KbSize.widthFrac = right - l
                KbSize.heightDp = (startH - dy / d).coerceIn(KbSize.minHeightDp(), KbSize.maxHeightDp(context))
                listener.onResize()
            }
            Drag.NONE -> {}
        }
    }
}
