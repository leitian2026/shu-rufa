package com.example.myime

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF

/**
 * 剪贴板面板:按时间倒序列出最近复制过的文字。
 *  - 点一条 = 粘贴到输入框并返回键盘
 *  - 长按一条 = 进入多选:每条前面出现圆圈,点哪条选哪条;标题右边的按键变成「取消 / 全选 / 删除」
 *  - 没有「清空」键(免得手滑);要删全部就长按进多选再「全选」「删除」。离开面板或点「取消」都会退出多选
 *  - 「回收箱」:剪贴板里删掉的内容先放这里(最多 10 条,最新删的在最上面,满了挤掉最下面最旧的)。
 *    回收箱里也能点一条粘贴、长按多选、全选、删除(这里的删除是真删)。回收箱里「返回」/点空白回到剪贴板。
 */
class ClipboardPanelView(
    context: Context,
    private val listener: Listener,
) : PanelView(context) {

    interface Listener {
        fun onPaste(text: String)
        fun onDeleteMany(texts: List<String>)
        fun onBack()
        fun onPasteTrash(text: String)
        fun onTrashDelete(texts: List<String>)
    }

    private var clipItems: List<String> = emptyList()
    private var trashItems: List<String> = emptyList()
    private var inTrash = false

    /** 当前页面显示的列表:剪贴板或回收箱。 */
    private val items: List<String> get() = if (inTrash) trashItems else clipItems
    private var lines: List<List<String>> = emptyList()
    private var tops = FloatArray(1)
    private var cardH = FloatArray(0)
    private var totalH = 0f

    private var selecting = false
    private val selected = LinkedHashSet<String>()

    private var lineH = 0f
    private var cardPad = 0f
    private var cardGap = 0f
    private var checkW = 0f      // 多选时每条左边圆圈占的宽度

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.LEFT }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.LEFT
        isFakeBoldText = true
    }
    private val countPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.LEFT }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val tmp = RectF()
    private var textBase = 0f
    private var hintBase = 0f
    private var titleBase = 0f

    private var barList: List<BarKey> = normalBar()
    override val barKeys: List<BarKey> get() = barList
    override val titledHeader: Boolean get() = true

    private fun normalBar() =
        if (inTrash) {
            listOf(BarKey(ID_BACK, "", 0.7f, BarKind.PRIMARY, icon = Icon.BACK))
        } else {
            listOf(
                BarKey(ID_BACK, "", 0.7f, BarKind.PRIMARY, icon = Icon.BACK),
                BarKey(ID_TRASH, if (trashItems.isEmpty()) "回收箱" else "回收箱(${trashItems.size})", 1.8f, BarKind.FUNCTION),
            )
        }

    private fun selectBar() = listOf(
        BarKey(ID_CANCEL, "取消", 1.6f, BarKind.PRIMARY),
        BarKey(
            ID_ALL,
            if (items.isNotEmpty() && selected.size == items.size) "取消全选" else "全选",
            2f, BarKind.FUNCTION,
        ),
        BarKey(ID_DEL, if (selected.isEmpty()) "删除" else "删除(${selected.size})", 2f, BarKind.FUNCTION),
    )

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
        ring.strokeWidth = context.dpf(1.8f)
        lineH = textPaint.fontSpacing
        cardPad = context.dpf(if (wide) 14f else 10f)
        cardGap = context.dpf(if (wide) 10f else 6f)
        checkW = context.dpf(if (wide) 40f else 34f)
        textBase = baseline(textPaint)
        hintBase = baseline(hintPaint)
        titleBase = baseline(titlePaint)
    }

    fun setItems(list: List<String>) {
        clipItems = list
        onListChanged()
    }

    fun setTrash(list: List<String>) {
        trashItems = list
        onListChanged()
    }

    private fun onListChanged() {
        selected.retainAll(items.toSet())
        relayout()
        offsetY = offsetY.coerceIn(0f, maxOffsetY())
        updateBar()   // 回收箱按键上的条数、多选的「删除(n)」都跟着变
    }

    override fun onMetricsChanged() {
        applyClipMetrics()
        relayout()
    }

    /** 每次打开面板都从普通状态(剪贴板、非多选)开始。 */
    override fun onShown() {
        selecting = false
        selected.clear()
        inTrash = false
        offsetY = 0f
        relayout()
        updateBar()
    }

    private fun setTrashView(on: Boolean) {
        inTrash = on
        selecting = false
        selected.clear()
        offsetY = 0f
        relayout()
        updateBar()
    }

    private fun updateBar() {
        barList = if (selecting) selectBar() else normalBar()
        layoutBar()
        invalidate()
    }

    private fun enterSelect(first: String) {
        selecting = true
        selected.clear()
        selected.add(first)
        relayout()
        updateBar()
    }

    private fun exitSelect() {
        selecting = false
        selected.clear()
        relayout()
        updateBar()
    }

    private fun relayout() {
        if (width == 0) return
        val maxTextW = width - 2 * padX - 2 * cardPad - (if (selecting) checkW else 0f)
        lines = items.map { wrap(it, maxTextW) }
        tops = FloatArray(items.size + 1)
        cardH = FloatArray(items.size)
        var y = 0f
        for (i in items.indices) {
            tops[i] = y
            val h = cardPad * 2 + maxOf(lines[i].size, 1) * lineH
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

    /** 标题行:左边是标题和条数,右边是按键(由基类排)。条数放不下就不画,免得压到按键。 */
    override fun drawHeaderTitle(canvas: Canvas, left: Float, centerY: Float) {
        titlePaint.color = palette.text
        countPaint.color = palette.textFaint
        val title = if (selecting) "已选 ${selected.size} 项" else if (inTrash) "回收箱" else "剪贴板"
        canvas.drawText(title, left, centerY + titleBase, titlePaint)
        val count = if (selecting) "共 ${items.size} 条" else if (inTrash) "${items.size}/10" else "${items.size}/500"
        val cx = left + titlePaint.measureText(title) + context.dpf(10f)
        if (cx + countPaint.measureText(count) + context.dpf(8f) <= barLeftEdge()) {
            canvas.drawText(count, cx, centerY + baseline(countPaint), countPaint)
        }
    }

    override fun drawContent(canvas: Canvas, visTop: Float, visBottom: Float) {
        if (items.isEmpty()) {
            hintPaint.color = palette.textFaint
            val cy = viewportHeight() / 2f
            val l1 = if (inTrash) "回收箱是空的" else "还没有复制过内容"
            val l2 = if (inTrash) "剪贴板里删掉的内容会先放在这里,最多 10 条" else "复制文字后会出现在这里"
            canvas.drawText(l1, width / 2f, cy - lineH * 0.6f + hintBase, hintPaint)
            canvas.drawText(l2, width / 2f, cy + lineH * 0.6f + hintBase, hintPaint)
            return
        }
        if (lines.size != items.size) return

        val left = padX
        val right = width - padX
        val textLeft = left + cardPad + (if (selecting) checkW else 0f)
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
                    textLeft,
                    top + cardPad + lineH * (li + 0.5f) + textBase,
                    textPaint,
                )
            }

            if (selecting) {
                val cx = left + cardPad + checkW * 0.4f
                val cy = top + h / 2f
                val r = context.dpf(10f)
                if (items[i] in selected) {
                    fill.color = palette.accent
                    canvas.drawCircle(cx, cy, r, fill)
                    ring.color = palette.onAccent
                    canvas.drawLine(cx - r * 0.45f, cy, cx - r * 0.1f, cy + r * 0.38f, ring)
                    canvas.drawLine(cx - r * 0.1f, cy + r * 0.38f, cx + r * 0.5f, cy - r * 0.35f, ring)
                } else {
                    ring.color = palette.icon
                    canvas.drawCircle(cx, cy, r, ring)
                }
            }
        }
    }

    private fun hit(y: Float): Int {
        if (items.isEmpty() || cardH.size != items.size) return -1
        for (i in items.indices) {
            if (y >= tops[i] && y <= tops[i] + cardH[i]) return i
        }
        return -1
    }

    override val coversCandidateBar: Boolean get() = true

    override fun onBlankTap() {
        if (selecting) return   // 多选中:没有返回键,空白处也不返回,免得误触丢掉已选的
        if (inTrash) setTrashView(false) else listener.onBack()   // 和返回键一样:回收箱里回到剪贴板,剪贴板里回到键盘
    }

    override fun onContentTap(x: Float, y: Float): Boolean {
        val i = hit(y)
        if (i < 0) return false
        if (selecting) {
            toggle(items[i])
        } else if (inTrash) {
            listener.onPasteTrash(items[i])
        } else {
            listener.onPaste(items[i])
        }
        return true
    }

    /** 长按:进入多选并选中这一条;已在多选时,长按等于点一下。 */
    override fun onContentLongPress(x: Float, y: Float) {
        val i = hit(y)
        if (i < 0) return
        if (selecting) toggle(items[i]) else enterSelect(items[i])
    }

    private fun toggle(t: String) {
        if (!selected.remove(t)) selected.add(t)
        updateBar()
    }

    override fun onBarKey(id: Int) {
        when (id) {
            ID_BACK -> if (inTrash) setTrashView(false) else listener.onBack()
            ID_TRASH -> setTrashView(true)
            ID_CANCEL -> exitSelect()
            ID_ALL -> {
                if (selected.size == items.size) selected.clear() else selected.addAll(items)
                updateBar()
            }
            ID_DEL -> {
                if (selected.isEmpty()) return
                val gone = selected.toList()
                exitSelect()
                if (inTrash) listener.onTrashDelete(gone) else listener.onDeleteMany(gone)
            }
        }
    }

    private companion object {
        const val ID_BACK = 0
        const val ID_CANCEL = 3
        const val ID_ALL = 4
        const val ID_DEL = 5
        const val ID_TRASH = 6
    }
}
