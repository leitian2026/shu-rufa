package com.example.myime

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF

/**
 * 剪贴板面板:按时间倒序列出最近复制过的文字。
 * 点一条 = 粘贴到输入框并返回键盘;点右侧 × 删除这一条;「清空」删除全部。
 */
class ClipboardPanelView(
    context: Context,
    private val listener: Listener,
) : PanelView(context) {

    interface Listener {
        fun onPaste(text: String)
        fun onDelete(index: Int)
        fun onClear()
        fun onBack()
    }

    private var items: List<String> = emptyList()
    private var lines: List<List<String>> = emptyList()
    private var tops = FloatArray(1)
    private var cardH = FloatArray(0)
    private var totalH = 0f

    private var lineH = 0f
    private var cardPad = 0f
    private var cardGap = 0f
    private var delW = 0f

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.LEFT }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val xPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val tmp = RectF()
    private var textBase = 0f
    private var hintBase = 0f

    override val barKeys: List<BarKey> by lazy {
        listOf(
            BarKey(ID_BACK, "返回", 1.6f, BarKind.PRIMARY),
            BarKey(ID_SPACER, "", 6f, BarKind.SPACER),
            BarKey(ID_CLEAR, "清空", 1.6f, BarKind.FUNCTION),
        )
    }

    init {
        applyClipMetrics()
    }

    private fun baseline(p: Paint): Float {
        val fm = p.fontMetrics
        return -(fm.ascent + fm.descent) / 2f
    }

    private fun applyClipMetrics() {
        val wide = context.isWide()
        textPaint.textSize = context.spf(if (wide) 17f else 15f)
        hintPaint.textSize = context.spf(if (wide) 16f else 14f)
        xPaint.strokeWidth = context.dpf(1.8f)
        lineH = textPaint.fontSpacing
        cardPad = context.dpf(if (wide) 14f else 10f)
        cardGap = context.dpf(if (wide) 10f else 6f)
        delW = context.dpf(if (wide) 56f else 44f)
        textBase = baseline(textPaint)
        hintBase = baseline(hintPaint)
    }

    fun setItems(list: List<String>) {
        items = list
        relayout()
        offsetY = offsetY.coerceIn(0f, maxOffsetY())
        invalidate()
    }

    override fun onMetricsChanged() {
        applyClipMetrics()
        relayout()
    }

    private fun relayout() {
        if (width == 0) return
        val maxTextW = width - 2 * padX - 2 * cardPad - delW
        lines = items.map { wrap(it, maxTextW) }
        tops = FloatArray(items.size + 1)
        cardH = FloatArray(items.size)
        var y = 0f
        for (i in items.indices) {
            tops[i] = y
            val h = cardPad * 2 + lines[i].size * lineH
            cardH[i] = h
            y += h + cardGap
        }
        tops[items.size] = y
        totalH = y
    }

    /** 最多两行,放不下时第二行末尾加省略号。 */
    private fun wrap(text: String, maxW: Float): List<String> {
        val t = text.replace('\n', ' ').replace('\t', ' ').trim()
        val out = ArrayList<String>(2)
        if (t.isEmpty() || maxW <= 0f) return out
        var start = 0
        while (start < t.length && out.size < 2) {
            val n = textPaint.breakText(t, start, t.length, true, maxW, null)
            if (n <= 0) break
            if (out.size == 1 && start + n < t.length) {
                val ell = "…"
                val room = maxW - textPaint.measureText(ell)
                val n2 = textPaint.breakText(t, start, t.length, true, room, null)
                out.add(t.substring(start, start + maxOf(n2, 0)) + ell)
                return out
            }
            out.add(t.substring(start, start + n))
            start += n
        }
        return out
    }

    override fun contentHeight(): Float = totalH

    override fun drawContent(canvas: Canvas, visTop: Float, visBottom: Float) {
        if (items.isEmpty()) {
            hintPaint.color = palette.textFaint
            val cy = viewportHeight() / 2f
            canvas.drawText("还没有复制过内容", width / 2f, cy - lineH * 0.6f + hintBase, hintPaint)
            canvas.drawText("复制文字后会出现在这里", width / 2f, cy + lineH * 0.6f + hintBase, hintPaint)
            return
        }
        if (lines.size != items.size) return

        val left = padX
        val right = width - padX
        for (i in items.indices) {
            val top = tops[i]
            val h = cardH[i]
            if (top + h < visTop) continue
            if (top > visBottom) break

            tmp.set(left, top + shadowH, right, top + h + shadowH)
            fill.color = palette.keyShadow
            canvas.drawRoundRect(tmp, radius, radius, fill)
            tmp.set(left, top, right, top + h)
            fill.color = palette.key
            canvas.drawRoundRect(tmp, radius, radius, fill)

            textPaint.color = palette.text
            for (li in lines[i].indices) {
                canvas.drawText(
                    lines[i][li],
                    left + cardPad,
                    top + cardPad + lineH * (li + 0.5f) + textBase,
                    textPaint,
                )
            }

            // 右侧 ×
            xPaint.color = palette.icon
            val cx = right - delW / 2f
            val cy = top + h / 2f
            val arm = context.dpf(5f)
            canvas.drawLine(cx - arm, cy - arm, cx + arm, cy + arm, xPaint)
            canvas.drawLine(cx + arm, cy - arm, cx - arm, cy + arm, xPaint)
        }
    }

    override fun onContentTap(x: Float, y: Float) {
        if (items.isEmpty() || cardH.size != items.size) return
        for (i in items.indices) {
            if (y >= tops[i] && y <= tops[i] + cardH[i]) {
                if (x > width - padX - delW) listener.onDelete(i) else listener.onPaste(items[i])
                return
            }
        }
    }

    override fun onBarKey(id: Int) {
        when (id) {
            ID_BACK -> listener.onBack()
            ID_CLEAR -> listener.onClear()
        }
    }

    private companion object {
        const val ID_BACK = 0
        const val ID_SPACER = 1
        const val ID_CLEAR = 2
    }
}
