package com.example.myime

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF

/** 格子面板里的一格:glyph 是上面的大符号,label 是下面的小字。 */
class GridItem(val id: Int, val glyph: String, val label: String)

/**
 * 一格一个功能的面板,用在两处:
 *  - 功能页(工具栏第一个按钮)
 *  - 键盘选择(工具栏第二个按钮,带标题,当前使用的那一格显示蓝色)
 * 和剪贴板、表情一样是面板:上面可滑动的内容区,下面一条「返回」。
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

    private var cols = 4
    private var gap = 0f
    private var cellW = 0f
    private var cellH = 0f

    override val barKeys: List<BarKey> by lazy {
        listOf(
            BarKey(ID_BACK_KEY, "返回", 1.6f, BarKind.PRIMARY),
            BarKey(ID_SPACER_KEY, "", 6f, BarKind.SPACER),
        )
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
        top0 = if (title != null) context.dpf(if (wide) 40f else 34f) else 0f
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
        if (title != null) {
            titlePaint.color = palette.text
            canvas.drawText(title, padX + context.dpf(4f), top0 / 2f + titleBase, titlePaint)
        }
        for (i in items.indices) {
            val r = i / cols
            val c = i % cols
            val x = padX + c * (cellW + gap)
            val y = top0 + r * (cellH + gap)
            if (y + cellH < visTop) continue
            if (y > visBottom) break

            tmp.set(x, y + shadowH, x + cellW, y + cellH + shadowH)
            fill.color = palette.keyShadow
            canvas.drawRoundRect(tmp, radius, radius, fill)
            tmp.set(x, y, x + cellW, y + cellH)
            fill.color = palette.key
            canvas.drawRoundRect(tmp, radius, radius, fill)

            val cx = x + cellW / 2f
            val sel = items[i].id == selectedId
            glyphPaint.color = if (sel) palette.accent else palette.text
            canvas.drawText(items[i].glyph, cx, y + cellH * 0.38f + glyphBase, glyphPaint)
            labelPaint.color = if (sel) palette.accent else palette.textFaint
            canvas.drawText(items[i].label, cx, y + cellH * 0.78f + labelBase, labelPaint)
        }
    }

    override fun onContentTap(x: Float, y: Float) {
        if (width == 0) return
        calc()
        val c = ((x - padX) / (cellW + gap)).toInt()
        val r = ((y - top0) / (cellH + gap)).toInt()
        if (x < padX || y < top0 || c !in 0 until cols || r < 0) return
        val i = r * cols + c
        if (i !in items.indices) return
        // 点在格子之间的缝隙里不算
        val left = padX + c * (cellW + gap)
        val top = top0 + r * (cellH + gap)
        if (x > left + cellW || y > top + cellH) return
        listener.onAction(items[i].id)
    }

    override fun onBarKey(id: Int) {
        if (id == ID_BACK_KEY) listener.onBack()
    }

    companion object {
        /** 功能页只放别处没有的功能:中英/拼笔/数字/符号在键盘上有键,表情/编辑/剪贴板/收起在工具栏,切换输入法在「键盘选择」里。 */
        fun settingsItems() = listOf(
            GridItem(ID_ADJUST, "↔↕", "键盘调节"),
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
        const val ID_PINYIN = 21
        const val ID_STROKE = 22
        const val ID_ENGLISH = 23
        const val ID_PICKER = 24

        private const val ID_BACK_KEY = 0
        private const val ID_SPACER_KEY = 1
    }
}
