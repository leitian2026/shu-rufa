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
    private var startS = 1f
    private var startL = 0f
    private var startB = 0f

    fun refresh() {
        palette = Palette(context)
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), context.dpi(32f))
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
                val zone = context.dpf(64f)
                drag = when {
                    e.x < zone -> Drag.LEFT
                    e.x > width - zone -> Drag.RIGHT
                    else -> Drag.MOVE
                }
                startX = e.rawX
                startY = e.rawY
                startS = KbSize.scale
                startL = KbSize.leftFrac
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

    /** 键盘是按 refW 宽排好版、再整体缩放的:拖动只改位置,缩放只改一个比例,都不重新排版,所以能跟手。 */
    private fun applyDrag(dx: Float, dy: Float) {
        val dm = resources.displayMetrics
        val sw = dm.widthPixels.toFloat()
        val d = dm.density
        val refW = KbSize.floatRefWidthPx(context).toFloat()
        val boxH = listener.boxHeightPx().toFloat()
        val startVisW = refW * startS
        val startVisH = boxH * startS
        val margin = context.dpf(24f)
        when (drag) {
            Drag.MOVE -> {
                KbSize.leftFrac = (startL + dx / sw).coerceIn(0f, ((sw - startVisW) / sw).coerceAtLeast(0f))
                val maxB = ((dm.heightPixels - startVisH - margin) / d).coerceAtLeast(0f)
                KbSize.bottomDp = (startB - dy / d).coerceIn(0f, maxB)
                listener.onMove()
            }
            Drag.RIGHT, Drag.LEFT -> {
                val sign = if (drag == Drag.RIGHT) 1f else -1f
                // 宽度和高度各算一个比例,取平均:往外拉就变大,往里拉就变小
                val byW = (startVisW + sign * dx) / startVisW
                val byH = (startVisH - dy) / startVisH
                var s = startS * (byW + byH) / 2f
                // 不超出屏幕,也不小于最小比例
                val maxByW = if (drag == Drag.RIGHT) (sw - startL * sw) / refW else (startL * sw + startVisW) / refW
                val maxByH = (dm.heightPixels - margin - startB * d) / boxH
                s = s.coerceIn(KbSize.MIN_SCALE, minOf(KbSize.maxScale(context), maxByW, maxByH).coerceAtLeast(KbSize.MIN_SCALE))
                KbSize.scale = s
                if (drag == Drag.LEFT) {
                    // 右边缘不动,左边缘跟着变
                    KbSize.leftFrac = ((startL * sw + startVisW - refW * s) / sw).coerceAtLeast(0f)
                }
                listener.onResize()
            }
            Drag.NONE -> {}
        }
    }
}
