package com.example.myime

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF

/**
 * 剪贴板面板:按时间倒序列出最近复制过的文字。
 *  - 点一条 = 粘贴到输入框并返回键盘
 *  - 长按一条 = 进入多选:每条前面出现圆圈,点哪条选哪条;标题右边的按键变成「取消 / 全选 / 删除」
 *  - 多选时每条右边出现一个小锁:点锁 = 给这条上锁 / 取消上锁。上了锁的条目删不掉(全选、删除都会跳过它),
 *    也不会被"超过 500 条挤掉最旧的"挤掉;平时(不在多选里)上了锁的条目右边也留着一个实心小锁,点条目照常粘贴。回收箱里没有锁。
 *  - 没有「清空」键(免得手滑);要删全部就长按进多选再「全选」「删除」。离开面板或点「取消」都会退出多选
 *  - 「回收箱」:剪贴板里删掉的内容先放这里(最多 10 条,最新删的在最上面,满了挤掉最下面最旧的)。
 *    回收箱里也能点一条粘贴、长按多选、全选、删除(这里的删除是真删)。回收箱里「返回」/点空白回到剪贴板。
 *  - 顶部有「清空系统剪贴板:开/关」开关(随时能点,不用进设置);剪贴板页(不含回收箱)在标题行下面常驻一行小字,
 *    写明「有的 app 粘贴不了」时怎么办 —— 这一行占一条的位置,所以剪贴板页一屏放 8 条,回收箱仍是 9 条。
 */
class ClipboardPanelView(
    context: Context,
    private val listener: Listener,
) : PanelView(context) {

    interface Listener {
        fun onPaste(text: String)
        fun onDeleteMany(texts: List<String>)

        /** 点了某条右边的小锁:上锁 ↔ 取消上锁。 */
        fun onToggleLock(text: String)
        fun onBack()
        fun onPasteTrash(text: String)
        fun onTrashDelete(texts: List<String>)

        /** 点了顶部的「清空系统剪贴板」开关,on 是点完之后的状态。 */
        fun onToggleWipe(on: Boolean)
    }

    private var clipItems: List<String> = emptyList()
    private var locked: Set<String> = emptySet()
    private var trashItems: List<String> = emptyList()
    private var inTrash = false
    private var wipeOn = true
    private var hintTop = 0f      // 常驻小字这一行占的高度(含下面的间距);回收箱里没有,为 0
    private var hintH = 0f

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
    private var curPad = 0f      // 实际用的卡片上下内边距、间距:短内容一屏放 9 条,按屏高调整(字体不变)
    private var curGap = 0f
    private var checkW = 0f      // 多选时每条左边圆圈占的宽度
    private var lockW = 0f       // 每条右边小锁占的宽度(也是点锁的有效区域宽度)
    private val lockPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private var lockBase = 0f

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.LEFT }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.LEFT
        isFakeBoldText = true
    }
    private val countPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.LEFT }
    private val noteText = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
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
    override val flatBar: Boolean get() = true   // 顶部按键扁平:返回箭头、「回收箱」等只有图标/文字,没有按钮底色

    private fun normalBar() =
        if (inTrash) {
            listOf(BarKey(ID_BACK, "返回", 1.2f, BarKind.BACK))
        } else {
            listOf(
                BarKey(ID_BACK, "返回", 1.2f, BarKind.BACK),
                BarKey(ID_WIPE, if (wipeOn) "清空系统剪贴板:开" else "清空系统剪贴板:关", 3.2f, BarKind.FUNCTION),
                BarKey(ID_TRASH, if (trashItems.isEmpty()) "回收箱" else "回收箱(${trashItems.size})", 1.8f, BarKind.FUNCTION),
            )
        }

    private fun selectBar() = listOf(
        BarKey(ID_CANCEL, "取消", 1.6f, BarKind.PRIMARY),
        BarKey(
            ID_ALL,
            if (selectable().isNotEmpty() && selected.size == selectable().size) "取消全选" else "全选",
            2f, BarKind.FUNCTION,
        ),
        BarKey(ID_DEL, if (selected.isEmpty()) "删除" else "删除(${selected.size})", 2f, BarKind.FUNCTION),
    )

    init {
        applyClipMetrics()
    }

    /**
     * 标题行压紧:按键和左边的标题文字一样高,上下留白收小,把空间让给下面的列表。
     * (基类构造时调用,所以只用 context,不碰本类的字段。)
     */
    override fun adjustMetrics() {
        val wide = context.isWide()
        val h = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = context.spf(if (wide) 18f else 16f)
            isFakeBoldText = true
        }.fontSpacing
        headerBtnH = h
        padX = context.dpf(8f)   // 左右只留一小条边,长条几乎占满输入法两边(平板上基类默认留得很宽)
        padTop = context.dpf(3f)
        padBottom = context.dpf(4f)   // 底下只留一点点,9 条才能铺满
        barH = h + context.dpf(4f)
        barGap = context.dpf(3f)
    }

    private fun baseline(p: Paint): Float {
        val fm = p.fontMetrics
        return -(fm.ascent + fm.descent) / 2f
    }

    private fun applyClipMetrics() {
        val wide = context.isWide()
        textPaint.textSize = context.spf(if (wide) 17f else 15f)
        hintPaint.textSize = context.spf(if (wide) 16f else 14f)
        lockPaint.textSize = context.spf(if (wide) 16f else 14f)   // 比条目文字(17 / 15)小一号
        titlePaint.textSize = context.spf(if (wide) 18f else 16f)
        countPaint.textSize = context.spf(if (wide) 15f else 13f)
        noteText.textSize = context.spf(if (wide) 14f else 12f)
        ring.strokeWidth = context.dpf(1.8f)
        lineH = textPaint.fontSpacing
        cardPad = context.dpf(if (wide) 14f else 10f)
        cardGap = context.dpf(if (wide) 10f else 6f)
        checkW = context.dpf(if (wide) 40f else 34f)
        lockW = context.dpf(if (wide) 48f else 42f)
        textBase = baseline(textPaint)
        hintBase = baseline(hintPaint)
        lockBase = baseline(lockPaint)
        titleBase = baseline(titlePaint)
    }

    /** 开关的当前状态(服务创建面板、每次打开面板时调用)。 */
    fun setWipe(on: Boolean) {
        if (wipeOn == on) return
        wipeOn = on
        updateBar()
    }

    /** 上了锁的条目(服务创建面板、每次打开面板、点了锁之后调用)。 */
    fun setLocked(set: Set<String>) {
        locked = set
        selected.removeAll(set)   // 上了锁的不能同时处于选中
        relayout()
        updateBar()
    }

    /** 多选时能选的条目:没上锁的。 */
    private fun selectable(): List<String> = if (locked.isEmpty()) items else items.filter { it !in locked }

    /** 这一条右边要不要留出小锁的位置:多选时每条都有(回收箱除外);平时只有上了锁的。 */
    private fun hasLock(t: String): Boolean = !inTrash && (selecting || t in locked)

    fun setItems(list: List<String>) {
        clipItems = list
        onListChanged()
    }

    fun setTrash(list: List<String>) {
        trashItems = list
        onListChanged()
    }

    private fun onListChanged() {
        if (selected.isNotEmpty()) selected.retainAll(items.toSet())   // 只有多选时才有选中的,平时不用建集合
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
        if (first !in locked) selected.add(first)
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
        val baseTextW = width - 2 * padX - 2 * cardPad - (if (selecting) checkW else 0f)
        // 一屏正好 9 个位置(第 1 个到第 9 个刚好铺满内容区,不留空):间距固定,每个位置的高度按比例拉高,字体不动。
        // 剪贴板页第 1 个位置给常驻小字,所以只放 8 条;回收箱 9 条。
        val total = contentBottom() - (padTop + barH + barGap)
        val n = ROWS_PER_SCREEN.toInt()
        if (total <= 0f) {
            curPad = cardPad
            curGap = cardGap
            hintH = 0f
            hintTop = 0f
        } else {
            curGap = if (2 * cardPad + lineH + cardGap <= total / ROWS_PER_SCREEN) cardGap else minOf(cardGap, context.dpf(3f))
            val h = (total - curGap * (n - 1)) / ROWS_PER_SCREEN
            curPad = ((h - lineH) / 2f).coerceAtLeast(context.dpf(2f))
            hintH = if (inTrash) 0f else h
            hintTop = if (inTrash) 0f else h + curGap
        }
        lines = items.map { wrap(it, baseTextW - (if (hasLock(it)) lockW else 0f)) }
        tops = FloatArray(items.size + 1)
        cardH = FloatArray(items.size)
        var y = 0f
        for (i in items.indices) {
            tops[i] = y
            val h = curPad * 2 + maxOf(lines[i].size, 1) * lineH
            cardH[i] = h
            y += h + curGap
        }
        tops[items.size] = y
        totalH = y
    }

    override fun extraTop(): Float = hintTop

    /** 标题行下面常驻的小字:不随列表滚动。一行放不下就把字缩小,再不行才截断。 */
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (inTrash || hintH <= 0f) return
        val top = padTop + barH + barGap
        val avail = width - 2 * padX - 2 * context.dpf(4f)
        if (avail <= 0f) return
        noteText.color = palette.textFaint
        val base = noteText.textSize
        var text = NOTE
        val w = noteText.measureText(text)
        if (w > avail) noteText.textSize = maxOf(base * avail / w, context.spf(8f))
        if (noteText.measureText(text) > avail) {
            val n = noteText.breakText(text, true, avail - noteText.measureText("…"), null)
            text = text.substring(0, n) + "…"
        }
        canvas.drawText(text, width / 2f, top + hintH / 2f + baseline(noteText), noteText)
        noteText.textSize = base
    }

    /** 最多两行,放不下时第二行末尾加省略号。 */
    private fun wrap(text: String, maxW: Float): List<String> {
        // 一条最长 10000 字,但只画两行:只取开头一段来处理,不然 500 条每次重排版都要把全部文字扫一遍
        val truncated = text.length > PREVIEW_CHARS
        val t = (if (truncated) text.substring(0, PREVIEW_CHARS) else text).replace('\n', ' ').replace('\t', ' ').trim()
        val out = ArrayList<String>(2)
        if (t.isEmpty() || maxW <= 0f) return out
        var start = 0
        while (start < t.length && out.size < 2) {
            val n = textPaint.breakText(t, start, t.length, true, maxW, null)
            if (n <= 0) break
            if (out.size == 1 && (start + n < t.length || truncated)) {
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
        val cardR = context.dpf(4f)   // 小圆角
        val textLeft = left + cardPad + (if (selecting) checkW else 0f)
        for (i in items.indices) {
            val top = tops[i]
            val h = cardH[i]
            if (top + h < visTop) continue
            if (top > visBottom) break

            // 扁平:不画下方的立体阴影,只画一块纯色圆角底
            tmp.set(left, top, right, top + h)
            fill.color = palette.key
            canvas.drawRoundRect(tmp, cardR, cardR, fill)

            textPaint.color = palette.text
            for (li in lines[i].indices) {
                canvas.drawText(
                    lines[i][li],
                    textLeft,
                    top + curPad + lineH * (li + 0.5f) + textBase,
                    textPaint,
                )
            }

            if (hasLock(items[i])) {
                val isLocked = items[i] in locked
                drawLock(canvas, right - lockW / 2f, top + h / 2f, isLocked)
            }

            if (selecting && items[i] !in locked) {   // 上了锁的选不了,不画圆圈
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

    /** 小锁:用表情里那个金色的 🔒(上了锁);没上锁(多选时每条都有)画 🔓,淡一点。字号比条目文字小一号。cx、cy 是图标中心。 */
    private fun drawLock(canvas: Canvas, cx: Float, cy: Float, closed: Boolean) {
        lockPaint.alpha = if (closed) 255 else 110
        canvas.drawText(if (closed) "\uD83D\uDD12" else "\uD83D\uDD13", cx, cy + lockBase, lockPaint)
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
            if (!inTrash && x >= width - padX - lockW) listener.onToggleLock(items[i]) else toggle(items[i])
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
        if (t in locked) return   // 上了锁的不能选(也就删不掉)
        if (!selected.remove(t)) selected.add(t)
        updateBar()
    }

    override fun onBarKey(id: Int) {
        when (id) {
            ID_BACK -> if (inTrash) setTrashView(false) else listener.onBack()
            ID_TRASH -> setTrashView(true)
            ID_WIPE -> {
                wipeOn = !wipeOn
                listener.onToggleWipe(wipeOn)
                updateBar()
            }
            ID_CANCEL -> exitSelect()
            ID_ALL -> {
                val can = selectable()
                if (selected.size == can.size) selected.clear() else selected.addAll(can)
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
        const val ID_WIPE = 7
        const val NOTE = "有的 app 粘贴不了:关掉上面的开关,点条目输出到输入框,再选中复制一次"
        const val ROWS_PER_SCREEN = 9f
        const val PREVIEW_CHARS = 400   // 两行最多放得下的字数远小于这个
    }
}
