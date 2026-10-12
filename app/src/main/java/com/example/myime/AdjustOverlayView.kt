package com.example.myime

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View

/**
 * 「键盘调节」界面:盖在键盘上的一层半透明遮罩。
 *  - 拖左 / 右边的把手:改宽度(键盘的那一侧边缘跟着手指)
 *  - 拖上边的把手:改高度(键盘底部固定,向上长高)
 *  - 拖中间其他位置(或「移动」键):整体移动,可以左右移,也可以往上抬离屏幕底部
 *  - 重置:恢复默认;确认:保存退出
 * 拖动用屏幕坐标(rawX/rawY)算总位移,键盘尺寸变化时不会抖动。
 */
class AdjustOverlayView(
    context: Context,
    private val listener: Listener,
) : View(context) {

    interface Listener {
        fun onAdjust()
        fun onReset()
        fun onConfirm()
    }

    private enum class Drag { NONE, LEFT, RIGHT, TOP, MOVE }

    private val dim = Paint().apply { color = 0xB3000000.toInt() }
    private val white = 0xFFFFFFFF.toInt()
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = white
    }
    private val brace = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.SQUARE
        color = 0xFF2468F2.toInt()
    }
    private val circleFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33FFFFFF }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        color = white
    }
    private val hint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        color = 0xB3FFFFFF.toInt()
    }
    private val path = Path()
    private val arc = RectF()

    // 0 重置  1 移动  2 确认
    private val btn = Array(3) { RectF() }
    private val names = arrayOf("重置", "移动", "确认")
    private var pressed = -1

    private var drag = Drag.NONE
    private var startX = 0f
    private var startY = 0f
    private var startW = 1f
    private var startL = 0f
    private var startH = 0f
    private var startB = 0f

    init {
        isClickable = true
        line.strokeWidth = context.dpf(2.4f)
        brace.strokeWidth = context.dpf(3.5f)
        label.textSize = context.spf(13f)
        hint.textSize = context.spf(12f)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val bw = minOf(context.dpf(76f), w / 3.6f)
        val bh = context.dpf(76f)
        val total = bw * 3
        val left = (w - total) / 2f
        val top = (h - bh) / 2f - context.dpf(8f)
        for (i in 0..2) btn[i].set(left + bw * i, top, left + bw * (i + 1), top + bh)
    }

    // ---------------------------------------------------------------- 绘制

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawRect(0f, 0f, w, h, dim)

        // 四角的蓝色折角
        val len = context.dpf(22f)
        val e = brace.strokeWidth / 2f
        canvas.drawLine(e, e, e + len, e, brace)
        canvas.drawLine(e, e, e, e + len, brace)
        canvas.drawLine(w - e, e, w - e - len, e, brace)
        canvas.drawLine(w - e, e, w - e, e + len, brace)
        canvas.drawLine(e, h - e, e + len, h - e, brace)
        canvas.drawLine(e, h - e, e, h - e - len, brace)
        canvas.drawLine(w - e, h - e, w - e - len, h - e, brace)
        canvas.drawLine(w - e, h - e, w - e, h - e - len, brace)

        // 三条边的把手(双线)
        val cx = w / 2f
        val cy = h / 2f
        val hl = context.dpf(22f)
        val a = context.dpf(14f)
        val b = context.dpf(19f)
        canvas.drawLine(a, cy - hl, a, cy + hl, line)
        canvas.drawLine(b, cy - hl, b, cy + hl, line)
        canvas.drawLine(w - a, cy - hl, w - a, cy + hl, line)
        canvas.drawLine(w - b, cy - hl, w - b, cy + hl, line)
        canvas.drawLine(cx - hl, a, cx + hl, a, line)
        canvas.drawLine(cx - hl, b, cx + hl, b, line)

        // 三个按钮
        val u = context.dpf(13f)
        for (i in 0..2) {
            val r = btn[i]
            val bx = r.centerX()
            val by = r.top + context.dpf(26f)
            if (pressed == i) canvas.drawCircle(bx, by, u * 1.7f, circleFill)
            when (i) {
                0 -> drawReset(canvas, bx, by, u)
                1 -> drawMove(canvas, bx, by, u)
                else -> drawConfirm(canvas, bx, by, u)
            }
            canvas.drawText(names[i], bx, r.top + context.dpf(62f), label)
        }
        canvas.drawText("拖边上的把手改大小,拖其他地方移动", cx, btn[0].bottom + context.dpf(14f), hint)
    }

    private fun arrowHead(canvas: Canvas, ex: Float, ey: Float, dx: Float, dy: Float, size: Float) {
        val px = -dy
        val py = dx
        path.reset()
        path.moveTo(ex - dx * size + px * size * 0.7f, ey - dy * size + py * size * 0.7f)
        path.lineTo(ex, ey)
        path.lineTo(ex - dx * size - px * size * 0.7f, ey - dy * size - py * size * 0.7f)
        canvas.drawPath(path, line)
    }

    private fun drawReset(canvas: Canvas, cx: Float, cy: Float, u: Float) {
        arc.set(cx - u, cy - u, cx + u, cy + u)
        canvas.drawArc(arc, -60f, 300f, false, line)
        // 圆弧终点在 240°,顺时针方向的切线是 (0.87, -0.5)
        val ex = cx + u * Math.cos(Math.toRadians(240.0)).toFloat()
        val ey = cy + u * Math.sin(Math.toRadians(240.0)).toFloat()
        arrowHead(canvas, ex, ey, 0.87f, -0.5f, u * 0.6f)
    }

    private fun drawMove(canvas: Canvas, cx: Float, cy: Float, u: Float) {
        canvas.drawLine(cx - u, cy, cx + u, cy, line)
        canvas.drawLine(cx, cy - u, cx, cy + u, line)
        arrowHead(canvas, cx + u, cy, 1f, 0f, u * 0.4f)
        arrowHead(canvas, cx - u, cy, -1f, 0f, u * 0.4f)
        arrowHead(canvas, cx, cy - u, 0f, -1f, u * 0.4f)
        arrowHead(canvas, cx, cy + u, 0f, 1f, u * 0.4f)
    }

    private fun drawConfirm(canvas: Canvas, cx: Float, cy: Float, u: Float) {
        canvas.drawCircle(cx, cy, u * 1.05f, line)
        path.reset()
        path.moveTo(cx - u * 0.45f, cy)
        path.lineTo(cx - u * 0.1f, cy + u * 0.35f)
        path.lineTo(cx + u * 0.5f, cy - u * 0.3f)
        canvas.drawPath(path, line)
    }

    // ---------------------------------------------------------------- 触摸

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                for (i in 0..2) {
                    if (btn[i].contains(e.x, e.y) && i != 1) {
                        pressed = i
                        invalidate()
                        return true
                    }
                }
                val edge = context.dpf(40f)
                drag = when {
                    e.x < edge -> Drag.LEFT
                    e.x > width - edge -> Drag.RIGHT
                    e.y < edge -> Drag.TOP
                    else -> Drag.MOVE
                }
                startX = e.rawX
                startY = e.rawY
                startW = KbSize.widthFrac
                startL = KbSize.leftFrac
                startH = KbSize.heightDp(context)
                startB = KbSize.bottomDp
                if (drag == Drag.MOVE && btn[1].contains(e.x, e.y)) {
                    pressed = 1
                    invalidate()
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (drag != Drag.NONE) applyDrag(e.rawX - startX, e.rawY - startY)
            }
            MotionEvent.ACTION_UP -> {
                val p = pressed
                pressed = -1
                drag = Drag.NONE
                invalidate()
                if (p == 0 && btn[0].contains(e.x, e.y)) listener.onReset()
                if (p == 2 && btn[2].contains(e.x, e.y)) listener.onConfirm()
            }
            MotionEvent.ACTION_CANCEL -> {
                pressed = -1
                drag = Drag.NONE
                invalidate()
            }
        }
        return true
    }

    private fun applyDrag(dx: Float, dy: Float) {
        val screenW = resources.displayMetrics.widthPixels.toFloat()
        val d = resources.displayMetrics.density
        val df = dx / screenW
        when (drag) {
            Drag.LEFT -> {
                val right = startL + startW
                val l = (startL + df).coerceIn(0f, right - KbSize.MIN_W)
                KbSize.leftFrac = l
                KbSize.widthFrac = right - l
            }
            Drag.RIGHT -> {
                KbSize.widthFrac = (startW + df).coerceIn(KbSize.MIN_W, 1f - startL)
            }
            Drag.TOP -> {
                KbSize.heightDp = (startH - dy / d).coerceIn(KbSize.MIN_H, KbSize.maxHeightDp(context))
            }
            Drag.MOVE -> {
                KbSize.leftFrac = (startL + df).coerceIn(0f, 1f - startW)
                KbSize.bottomDp = (startB - dy / d).coerceIn(0f, KbSize.maxBottomDp(context))
            }
            Drag.NONE -> return
        }
        listener.onAdjust()
    }
}
