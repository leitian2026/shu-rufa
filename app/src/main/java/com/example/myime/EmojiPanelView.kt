package com.example.myime

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint

/**
 * 表情面板:按分类分段、可上下滑动的网格;底部是 返回 / 分类标签 / 删除。
 * 点分类标签会带动画滚动到该分类。"最近使用"只保存在内存里。
 */
class EmojiPanelView(
    context: Context,
    private val listener: Listener,
) : PanelView(context) {

    interface Listener {
        fun onEmoji(emoji: String)
        fun onBack()
        fun onBackspace()
    }

    private class Section(var title: String, val tabIcon: String, var items: List<String>)

    private val sections = ArrayList<Section>()
    private val recents = ArrayList<String>()

    private var tops = FloatArray(0)
    private var totalH = 0f
    private var cols = 8
    private var cell = 0f
    private var minCell = 0f
    private var headerH = 0f
    private var gapH = 0f

    private val emojiPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val headerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.LEFT }
    private var emojiBase = 0f
    private var headerBase = 0f

    override val barKeys: List<BarKey> by lazy {
        val l = ArrayList<BarKey>()
        l.add(BarKey(ID_BACK, "", 1f, BarKind.PRIMARY, icon = Icon.BACK))
        for (i in sections.indices) {
            l.add(BarKey(ID_TAB + i, sections[i].tabIcon, 1f, BarKind.TAB))
        }
        l.add(BarKey(ID_DEL, "", 1f, BarKind.FUNCTION, repeatable = true, icon = Icon.BACKSPACE))
        l
    }

    init {
        sections.add(Section("常用", "🕘", POPULAR))
        loadFromAssets()
        applyEmojiMetrics()
    }

    private fun loadFromAssets() {
        try {
            context.assets.open("emoji.txt").bufferedReader(Charsets.UTF_8).useLines { lines ->
                for (line in lines) {
                    if (line.isEmpty() || line[0] == '#') continue
                    val p = line.split('\t')
                    if (p.size < 3) continue
                    val items = p[2].split(' ').filter { it.isNotEmpty() }
                    if (items.isNotEmpty()) sections.add(Section(p[0], p[1], items))
                }
            }
        } catch (e: Exception) {
            ErrorHint.set("emoji", ErrorHint.of("表情面板读取失败", e))  // 只剩"常用"一类,面板仍可用
        }
    }

    private fun baseline(p: Paint): Float {
        val fm = p.fontMetrics
        return -(fm.ascent + fm.descent) / 2f
    }

    private fun applyEmojiMetrics() {
        val wide = context.isWide()
        minCell = context.dpf(if (wide) 54f else 44f)
        headerH = context.dpf(30f)
        gapH = context.dpf(6f)
        headerPaint.textSize = context.spf(13f)
        headerBase = baseline(headerPaint)
    }

    override fun onMetricsChanged() {
        applyEmojiMetrics()
        relayoutGrid()
    }

    private fun relayoutGrid() {
        if (width == 0) return
        val availW = width - 2 * padX
        cols = maxOf(6, (availW / minCell).toInt())
        cell = availW / cols
        emojiPaint.textSize = cell * 0.58f
        emojiBase = baseline(emojiPaint)
        tops = FloatArray(sections.size)
        var y = 0f
        for (i in sections.indices) {
            tops[i] = y
            val rows = (sections[i].items.size + cols - 1) / cols
            y += headerH + rows * cell + gapH
        }
        totalH = y
    }

    /** 每次打开面板:把最近使用并入第一类,回到顶部。 */
    override fun onShown() {
        val first = sections[0]
        if (recents.isEmpty()) {
            first.title = "常用"
            first.items = POPULAR
        } else {
            first.title = "最近使用"
            first.items = ArrayList(recents)
        }
        relayoutGrid()
    }

    override fun contentHeight(): Float = totalH

    private fun currentSection(): Int {
        var cur = 0
        for (i in tops.indices) {
            if (tops[i] <= offsetY + 1f) cur = i
        }
        return cur
    }

    override fun isBarSelected(id: Int): Boolean = id == ID_TAB + currentSection()

    override fun drawContent(canvas: Canvas, visTop: Float, visBottom: Float) {
        if (cell <= 0f || tops.size != sections.size) return
        emojiPaint.color = palette.text
        headerPaint.color = palette.textFaint
        for (s in sections.indices) {
            val top = tops[s]
            val sec = sections[s]
            val rows = (sec.items.size + cols - 1) / cols
            val bottom = top + headerH + rows * cell
            if (bottom < visTop) continue
            if (top > visBottom) break

            canvas.drawText(sec.title, padX + context.dpf(4f), top + headerH / 2f + headerBase, headerPaint)

            val firstRow = maxOf(0, ((visTop - top - headerH) / cell).toInt())
            val lastRow = minOf(rows - 1, ((visBottom - top - headerH) / cell).toInt())
            for (r in firstRow..lastRow) {
                for (c in 0 until cols) {
                    val idx = r * cols + c
                    if (idx >= sec.items.size) break
                    canvas.drawText(
                        sec.items[idx],
                        padX + cell * (c + 0.5f),
                        top + headerH + cell * (r + 0.5f) + emojiBase,
                        emojiPaint,
                    )
                }
            }
        }
    }

    override val coversCandidateBar: Boolean get() = true

    override fun onBlankTap() {
        listener.onBack()
    }

    override fun onContentTap(x: Float, y: Float): Boolean {
        if (cell <= 0f || tops.size != sections.size) return false
        var s = 0
        for (i in tops.indices) {
            if (tops[i] <= y) s = i
        }
        val sec = sections[s]
        val sy = y - tops[s] - headerH
        if (sy < 0f) return false
        val row = (sy / cell).toInt()
        val col = ((x - padX) / cell).toInt()
        if (col < 0 || col >= cols) return false
        val idx = row * cols + col
        if (idx < 0 || idx >= sec.items.size) return false

        val emoji = sec.items[idx]
        // 最近使用:移到最前,最多 24 个;下次打开面板时才刷新,避免滑动时内容突然移位
        recents.remove(emoji)
        recents.add(0, emoji)
        while (recents.size > 24) recents.removeAt(recents.size - 1)
        listener.onEmoji(emoji)
        return true
    }

    override fun onBarKey(id: Int) {
        when {
            id == ID_BACK -> listener.onBack()
            id == ID_DEL -> listener.onBackspace()
            id >= ID_TAB && id - ID_TAB < tops.size -> scrollToContent(tops[id - ID_TAB])
        }
    }

    private companion object {
        const val ID_BACK = 0
        const val ID_DEL = 1
        const val ID_TAB = 10
        val POPULAR = listOf(
            "😂", "🤣", "😊", "😍", "😘", "😭", "😅", "😁", "🤔", "😎",
            "👍", "🙏", "👏", "💪", "🤝", "❤️", "🎉", "🔥", "✨", "💯",
        )
    }
}
