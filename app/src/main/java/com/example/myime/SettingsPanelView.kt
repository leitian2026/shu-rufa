package com.example.myime

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF

/** 格子面板里的一格:glyph 是上面的大符号,label 是下面的小字。 */
class GridItem(val id: Int, var glyph: String, var label: String)

/**
 * 一格一个功能的面板,用在两处:
 *  - 功能页(工具栏第一个按钮)
 *  - 键盘选择(工具栏第二个按钮,带标题,当前使用的那一格显示蓝色)
 * 和剪贴板、表情一样是面板:最上面一行是标题,右边是「返回」,下面是可滑动的内容区。
 */
class SettingsPanelView(
    context: Context,
    private val items: List<GridItem>,
    private val title: String?,
    private val listener: Listener,
) : PanelView(context) {

    interface Listener {
        fun onAction(id: Int)
        fun onBack()
        fun onExtra(id: Int) {}
    }

    private val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.LEFT
        isFakeBoldText = true
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tmp = RectF()
    private var glyphBase = 0f
    private var labelBase = 0f
    private var titleBase = 0f
    private var top0 = 0f

    /** 当前选中的格子(显示蓝色),-1 表示没有。 */
    var selectedId = -1
        set(v) {
            if (field == v) return
            field = v
            invalidate()
        }

    /** 功能页里「翻译微软 / 翻译谷歌」正在起作用的那一格(整格绿底白字),-1 表示都没开。 */
    var filledId = -1
        set(v) {
            if (field == v) return
            field = v
            invalidate()
        }

    /** 同时改某一格的大符号和小字(简 / 繁切换键用)。 */
    fun setItemText(id: Int, glyph: String, label: String) {
        val item = items.firstOrNull { x -> x.id == id } ?: return
        if (item.glyph == glyph && item.label == label) return
        item.glyph = glyph
        item.label = label
        invalidate()
    }

    /** 改某一格下面的小字。 */
    fun setItemLabel(id: Int, label: String) {
        val item = items.firstOrNull { x -> x.id == id } ?: return
        if (item.label == label) return
        item.label = label
        invalidate()
    }

    private var cols = 4
    private var gap = 0f
    private var cellW = 0f
    private var cellH = 0f

    /** 标题行右边在「返回」后面再多放一个小按钮(键盘选择页用来设默认输入方式);null = 不放。 */
    var extraLabel: String? = null
        set(v) {
            if (field == v) return
            field = v
            barList = buildBar()
            layoutBar()
            invalidate()
        }

    private fun buildBar(): List<BarKey> {
        val l = ArrayList<BarKey>()
        l.add(BarKey(ID_BACK_KEY, "返回", 1.4f, BarKind.BACK))
        extraLabel?.let { l.add(BarKey(ID_EXTRA_KEY, it, 1f, BarKind.FUNCTION)) }
        return l
    }

    private var barList: List<BarKey> = buildBar()
    override val barKeys: List<BarKey> get() = barList

    override val titledHeader: Boolean get() = true

    override fun drawHeaderTitle(canvas: Canvas, left: Float, centerY: Float) {
        if (title == null) return
        titlePaint.color = palette.text
        canvas.drawText(title, left, centerY + titleBase, titlePaint)
    }

    /** 开着的开关(悬浮键盘、繁体优先):整格蓝底白字,一眼看出现在是开的;空 = 都没开。 */
    var accentIds: Set<Int> = emptySet()
        set(v) {
            if (field == v) return
            field = v
            invalidate()
        }

    init {
        applySettingsMetrics()
    }

    private fun baseline(p: Paint): Float {
        val fm = p.fontMetrics
        return -(fm.ascent + fm.descent) / 2f
    }

    private fun applySettingsMetrics() {
        val wide = context.isWide()
        glyphPaint.textSize = context.spf(if (wide) 24f else 20f)
        labelPaint.textSize = context.spf(if (wide) 14f else 12f)
        glyphBase = baseline(glyphPaint)
        labelBase = baseline(labelPaint)
        titlePaint.textSize = context.spf(if (wide) 18f else 16f)
        titleBase = baseline(titlePaint)
    }

    override fun onMetricsChanged() {
        applySettingsMetrics()
    }

    private fun calc() {
        val wide = context.isWide()
        cols = if (wide) 6 else 4
        gap = context.dpf(if (wide) 10f else 8f)
        cellW = (width - 2 * padX - gap * (cols - 1)) / cols
        cellH = minOf(cellW * 0.8f, context.dpf(if (wide) 84f else 70f))
        top0 = 0f   // 标题已经在最上面的按键行里,内容区直接从格子开始
    }

    override fun contentHeight(): Float {
        if (width == 0) return 0f
        calc()
        val rows = (items.size + cols - 1) / cols
        return top0 + rows * (cellH + gap)
    }

    override fun drawContent(canvas: Canvas, visTop: Float, visBottom: Float) {
        if (width == 0) return
        calc()
        for (i in items.indices) {
            val r = i / cols
            val c = i % cols
            val x = padX + c * (cellW + gap)
            val y = top0 + r * (cellH + gap)
            if (y + cellH < visTop) continue
            if (y > visBottom) break

            val sel = items[i].id == selectedId
            // 「翻译微软 / 翻译谷歌」哪个正在用,哪一格整格变绿底白字(只有这一格变,键盘配色不变)
            val on = items[i].id in accentIds
            val filled = items[i].id == filledId || on
            tmp.set(x, y + shadowH, x + cellW, y + cellH + shadowH)
            fill.color = if (on) palette.accentShadow else if (filled) palette.transShadow else palette.keyShadow
            canvas.drawRoundRect(tmp, radius, radius, fill)
            tmp.set(x, y, x + cellW, y + cellH)
            fill.color = if (on) palette.accent else if (filled) palette.transAccent else palette.key
            canvas.drawRoundRect(tmp, radius, radius, fill)

            val cx = x + cellW / 2f
            glyphPaint.color = if (filled) palette.onAccent else if (sel) palette.accent else palette.text
            canvas.drawText(items[i].glyph, cx, y + cellH * 0.38f + glyphBase, glyphPaint)
            labelPaint.color = if (filled) palette.onAccent else if (sel) palette.accent else palette.text   // 小字和大字一样黑
            canvas.drawText(items[i].label, cx, y + cellH * 0.78f + labelBase, labelPaint)
        }
    }

    override val coversCandidateBar: Boolean get() = true

    override fun onBlankTap() {
        listener.onBack()
    }

    override fun onContentTap(x: Float, y: Float): Boolean {
        if (width == 0) return false
        calc()
        val c = ((x - padX) / (cellW + gap)).toInt()
        val r = ((y - top0) / (cellH + gap)).toInt()
        if (x < padX || y < top0 || c !in 0 until cols || r < 0) return false
        val i = r * cols + c
        if (i !in items.indices) return false
        // 点在格子之间的缝隙里算空白
        val left = padX + c * (cellW + gap)
        val top = top0 + r * (cellH + gap)
        if (x > left + cellW || y > top + cellH) return false
        listener.onAction(items[i].id)
        return true
    }

    override fun onBarKey(id: Int) {
        if (id == ID_BACK_KEY) listener.onBack() else if (id == ID_EXTRA_KEY) listener.onExtra(id)
    }

    companion object {
        /** 功能页只放别处没有的功能:中英/拼笔/数字/符号在键盘上有键,表情/编辑/剪贴板/收起在工具栏,切换输入法在「键盘选择」里。 */
        fun settingsItems() = listOf(
            GridItem(ID_TRANS_MS, "微", "翻译微软"),
            GridItem(ID_TRANS_GOOGLE, "谷", "翻译谷歌"),
            GridItem(ID_FLOAT, "浮", "悬浮键盘"),
            GridItem(ID_TRAD, "简", "简体优先"),
            GridItem(ID_READING, "音", "显示读音"),
            GridItem(ID_ADJUST, "↔↕", "键盘调节"),
            GridItem(ID_DICT, "库", "词库管理"),
            GridItem(ID_APP, "设", "输入法设置"),
        )

        fun keyboardItems() = listOf(
            GridItem(ID_PINYIN, "拼", "拼音26键"),
            GridItem(ID_STROKE, "笔", "笔画输入"),
            GridItem(ID_ENGLISH, "EN", "英文26键"),
            GridItem(ID_PICKER, "换", "切换输入法"),
        )

        const val ID_LANG = 1
        const val ID_METHOD = 2
        const val ID_NUMBER = 3
        const val ID_SYMBOL = 4
        const val ID_EMOJI = 5
        const val ID_CLIP = 6
        const val ID_EDIT = 7
        const val ID_IME = 8
        const val ID_ADJUST = 9
        const val ID_APP = 10
        const val ID_HIDE = 11
        const val ID_DICT = 12
        const val ID_FLOAT = 13
        const val ID_TRANS_MS = 14       // 用微软翻译:点一下打开(绿色),再点关闭
        const val ID_TRANS_GOOGLE = 15   // 用谷歌翻译:点一下打开(绿色),再点关闭;正开着微软时点它 = 直接换成谷歌
        const val ID_TRAD = 16           // 笔画页字形:简体优先(默认)↔ 繁体优先,开着(繁)时整格变蓝
        const val ID_READING = 17        // 候选上方显示读音:点一下开 / 关,开着时整格变蓝,点完自动回键盘
        const val ID_PINYIN = 21
        const val ID_STROKE = 22
        const val ID_ENGLISH = 23
        const val ID_PICKER = 24

        private const val ID_BACK_KEY = 0
        private const val ID_EXTRA_KEY = 1
    }
}
