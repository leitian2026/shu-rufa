package com.example.myime

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF

/**
 * 剪贴板面板:按时间倒序列出最近复制过的文字。
 * 点一条 = 粘贴到输入框并返回键盘;长按一条 = 展开全文并出现「删除」;「清空」删除全部。
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
    private var headerH = 0f
    private var btnRowH = 0f
    private var expanded = -1
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.LEFT
        isFakeBoldText = true
    }
    private val countPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.LEFT }
    private val btnPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private var titleBase = 0f

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.LEFT }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
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
        titlePaint.textSize = context.spf(if (wide) 18f else 16f)
        countPaint.textSize = context.spf(if (wide) 15f else 13f)
        btnPaint.textSize = context.spf(if (wide) 15f else 14f)
        titleBase = baseline(titlePaint)
        headerH = lineH * 1.9f
        btnRowH = lineH * 2.0f
        lineH = textPaint.fontSpacing
        cardPad = context.dpf(if (wide) 14f else 10f)
        cardGap = context.dpf(if (wide) 10f else 6f)
        textBase = baseline(textPaint)
        hintBase = baseline(hintPaint)
    }

    fun setItems(list: List<String>) {
        items = list
        if (expanded >= list.size) expanded = -1
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
        val maxTextW = width - 2 * padX - 2 * cardPad
        if (expanded >= items.size) expanded = -1
        lines = items.mapIndexed { i, t -> if (i == expanded) wrapFull(t, maxTextW) else wrap(t, maxTextW) }
        tops = FloatArray(items.size + 1)
        cardH = FloatArray(items.size)
        var y = headerH
        for (i in items.indices) {
            tops[i] = y
            var h = cardPad * 2 + lines[i].size * lineH
            if (i == expanded) h += btnRowH
            cardH[i] = h
            y += h + cardGap
        }
        tops[items.size] = y
        totalH = y
    }

    /** 展开时显示全文,保留换行(最多 60 行)。 */
    private fun wrapFull(text: String, maxW: Float): List<String> {
        val out = ArrayList<String>()
        if (maxW <= 0f) return out
        for (para in text.replace("\r", "").replace('\t', ' ').split('\n')) {
            if (para.isEmpty()) { out.add(""); continue }
            var start = 0
            while (start < para.length && out.size < 60) {
                val n = textPaint.breakText(para, start, para.length, true, maxW, null)
                if (n <= 0) break
                out.add(para.substring(start, start + n))
                start += n
            }
            if (out.size >= 60) break
        }
        while (out.isNotEmpty() && out.last().isEmpty()) out.removeAt(out.size - 1)
        return out
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
        // 标题行:剪贴板  n/500
        titlePaint.color = palette.text
        countPaint.color = palette.textFaint
        val title = "剪贴板"
        canvas.drawText(title, padX + cardPad * 0.5f, headerH / 2f + titleBase, titlePaint)
        canvas.drawText(
            "${items.size}/500",
            padX + cardPad * 0.5f + titlePaint.measureText(title) + context.dpf(10f),
            headerH / 2f + baseline(countPaint),
            countPaint,
        )
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

            if (i == expanded) {
                val r = deleteRect(i)
                fill.color = palette.functionKey
                canvas.drawRoundRect(r, radius, radius, fill)
                btnPaint.color = palette.text
                canvas.drawText("删除", r.centerX(), r.centerY() + baseline(btnPaint), btnPaint)
            }
        }
    }

    private fun deleteRect(i: Int): RectF {
        val right = width - padX - cardPad
        val bottom = tops[i] + cardH[i] - cardPad * 0.5f
        val w = context.dpf(64f)
        val h = btnRowH - cardPad * 0.5f - context.dpf(4f)
        return RectF(right - w, bottom - h, right, bottom)
    }

    override fun onContentTap(x: Float, y: Float) {
        if (items.isEmpty() || cardH.size != items.size) return
        for (i in items.indices) {
            if (y >= tops[i] && y <= tops[i] + cardH[i]) {
                if (i == expanded && deleteRect(i).contains(x, y)) {
                    expanded = -1
                    listener.onDelete(i)
                } else {
                    listener.onPaste(items[i])
                }
                return
            }
        }
    }

    /** 长按一条:展开全文并出现「删除」;再长按收起。 */
    override fun onContentLongPress(x: Float, y: Float) {
        if (items.isEmpty() || cardH.size != items.size) return
        for (i in items.indices) {
            if (y >= tops[i] && y <= tops[i] + cardH[i]) {
                expanded = if (expanded == i) -1 else i
                relayout()
                offsetY = offsetY.coerceIn(0f, maxOffsetY())
                invalidate()
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
