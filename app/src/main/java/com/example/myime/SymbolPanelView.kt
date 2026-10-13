package com.example.myime

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.ViewConfiguration
import kotlin.math.abs

/**
 * 符号面板:顶上左边是可左右拖动的分类条(常用 / 中文 / 英文 / 网址 / 数学 ……),右边是 返回 和 删除;
 * 下面是当前分类的符号网格,可上下滑动,点一个就输入一个,面板不关(连续输入),点「返回」回到键盘。
 *
 * 省电:没有定时器、没有动画;只在触摸时重绘。同一时间只在内存里留当前这一类的符号。
 */
class SymbolPanelView(
    context: Context,
    private val listener: Listener,
) : PanelView(context) {

    interface Listener {
        fun onSymbol(text: String)
        fun onBack()
        fun onBackspace()
    }

    private var cat = 0
    private var items: List<String> = SymbolData.build(0)

    private var cols = 6
    private var cellW = 0f
    private var cellH = 0f
    private var minCell = 0f
    private var gap = 0f
    private var totalH = 0f

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val tabPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tmp = RectF()
    private var baseSize = 0f
    private var tabBase = 0f
    private var tabH = 0f

    // 分类条:每个标签的左边位置和宽度(从 0 起),以及拖动的偏移
    private var tabX = FloatArray(0)
    private var tabW = FloatArray(0)
    private var tabTotal = 0f
    private var tabScroll = 0f

    // 分类条上的触摸
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var stripDown = false
    private var stripMoved = false
    private var stripDownX = 0f
    private var stripLastX = 0f

    override val barKeys: List<BarKey> = listOf(
        BarKey(ID_BACK, "返回", 1.4f, BarKind.BACK),
        BarKey(ID_DEL, "", 1f, BarKind.FUNCTION, repeatable = true, icon = Icon.BACKSPACE),
    )
    override val titledHeader: Boolean get() = true

    init {
        applyMetrics()
    }

    private fun centerBase(p: Paint): Float {
        val fm = p.fontMetrics
        return -(fm.ascent + fm.descent) / 2f
    }

    private fun applyMetrics() {
        val wide = context.isWide()
        minCell = context.dpf(if (wide) 64f else 52f)
        cellH = context.dpf(if (wide) 54f else 46f)
        gap = context.dpf(if (wide) 4f else 3f)
        tabH = context.dpf(if (wide) 36f else 32f)
        textPaint.textSize = context.spf(if (wide) 24f else 21f)
        baseSize = textPaint.textSize
        tabPaint.textSize = context.spf(if (wide) 17f else 15f)
        tabBase = centerBase(tabPaint)

        val n = SymbolData.NAMES.size
        tabX = FloatArray(n)
        tabW = FloatArray(n)
        val pad = context.dpf(11f)
        val g = context.dpf(2f)
        var x = 0f
        for (i in 0 until n) {
            val w = tabPaint.measureText(SymbolData.NAMES[i]) + pad * 2
            tabX[i] = x
            tabW[i] = w
            x += w + g
        }
        tabTotal = x - g
    }

    override fun onMetricsChanged() {
        applyMetrics()
        relayout()
        tabScroll = tabScroll.coerceIn(0f, maxTabScroll())
    }

    private fun relayout() {
        if (width == 0) return
        val availW = width - 2 * padX
        val long = items.any { it.length > 2 }   // 网址 / 邮箱这类长条目:列数少一点,格子宽一点
        cols = if (long) maxOf(2, (availW / context.dpf(104f)).toInt()) else maxOf(4, (availW / minCell).toInt())
        cellW = availW / cols
        val rows = (items.size + cols - 1) / cols
        totalH = rows * cellH + context.dpf(4f)
        offsetY = offsetY.coerceIn(0f, maxOffsetY())
    }

    override fun contentHeight(): Float = totalH

    // ---------------------------------------------------------------- 分类条

    private fun stripLeft(): Float = padX + context.dpf(4f)
    private fun stripRight(): Float = barLeftEdge() - context.dpf(8f)
    private fun maxTabScroll(): Float = (tabTotal - (stripRight() - stripLeft())).coerceAtLeast(0f)

    override fun drawHeaderTitle(canvas: Canvas, left: Float, centerY: Float) {
        val right = stripRight()
        if (right <= left) return
        canvas.save()
        canvas.clipRect(left - context.dpf(2f), 0f, right, centerY * 2f)
        canvas.translate(left - tabScroll, 0f)
        val top = centerY - tabH / 2f
        for (i in tabX.indices) {
            val x0 = tabX[i]
            val w = tabW[i]
            if (x0 + w - tabScroll < 0f || x0 - tabScroll > right - left) continue   // 屏幕外的不画
            val selected = i == cat
            if (selected) {
                tmp.set(x0, top + shadowH, x0 + w, top + tabH + shadowH)
                fill.color = palette.keyShadow
                canvas.drawRoundRect(tmp, radius, radius, fill)
                tmp.set(x0, top, x0 + w, top + tabH)
                fill.color = palette.key
                canvas.drawRoundRect(tmp, radius, radius, fill)
            }
            tabPaint.color = if (selected) palette.text else palette.textFaint
            canvas.drawText(SymbolData.NAMES[i], x0 + w / 2f, centerY + tabBase, tabPaint)
        }
        canvas.restore()
    }

    private fun selectCat(i: Int) {
        if (i == cat || i < 0 || i >= SymbolData.NAMES.size) return
        cat = i
        items = SymbolData.build(i)
        relayout()
        scrollToContent(0f)
        // 让选中的标签完整露出来
        val sw = stripRight() - stripLeft()
        if (tabX[i] < tabScroll) {
            tabScroll = tabX[i]
        } else if (tabX[i] + tabW[i] > tabScroll + sw) {
            tabScroll = tabX[i] + tabW[i] - sw
        }
        tabScroll = tabScroll.coerceIn(0f, maxTabScroll())
        invalidate()
    }

    private fun inStrip(x: Float, y: Float): Boolean =
        y < contentTop() && x >= stripLeft() - context.dpf(4f) && x <= stripRight()

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (inStrip(e.x, e.y)) {
                    stripDown = true
                    stripMoved = false
                    stripDownX = e.x
                    stripLastX = e.x
                    return true
                }
            }
            MotionEvent.ACTION_MOVE -> if (stripDown) {
                if (!stripMoved && abs(e.x - stripDownX) > slop) stripMoved = true
                if (stripMoved) {
                    tabScroll = (tabScroll + stripLastX - e.x).coerceIn(0f, maxTabScroll())
                    invalidate()
                }
                stripLastX = e.x
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (stripDown) {
                stripDown = false
                if (!stripMoved && e.actionMasked == MotionEvent.ACTION_UP) {
                    val lx = e.x - stripLeft() + tabScroll
                    for (i in tabX.indices) {
                        if (lx >= tabX[i] - 1f && lx <= tabX[i] + tabW[i] + 1f) {
                            performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                            selectCat(i)
                            break
                        }
                    }
                }
                return true
            }
        }
        return super.onTouchEvent(e)
    }

    // ---------------------------------------------------------------- 符号网格

    override fun drawContent(canvas: Canvas, visTop: Float, visBottom: Float) {
        if (cellW <= 0f || items.isEmpty()) return
        val rows = (items.size + cols - 1) / cols
        val first = maxOf(0, (visTop / cellH).toInt())
        val last = minOf(rows - 1, (visBottom / cellH).toInt())
        val maxTextW = cellW - gap * 2 - context.dpf(6f)
        textPaint.color = palette.text
        for (r in first..last) {
            for (c in 0 until cols) {
                val idx = r * cols + c
                if (idx >= items.size) break
                val l = padX + c * cellW + gap
                val rt = padX + (c + 1) * cellW - gap
                val t = r * cellH + gap
                val b = (r + 1) * cellH - gap
                tmp.set(l, t + shadowH, rt, b + shadowH)
                fill.color = palette.keyShadow
                canvas.drawRoundRect(tmp, radius, radius, fill)
                tmp.set(l, t, rt, b)
                fill.color = palette.key
                canvas.drawRoundRect(tmp, radius, radius, fill)

                val s = items[idx]
                textPaint.textSize = baseSize
                val w = textPaint.measureText(s)
                if (w > maxTextW) textPaint.textSize = baseSize * maxTextW / w   // 太长的缩小放进格子
                canvas.drawText(s, (l + rt) / 2f, (t + b) / 2f + centerBase(textPaint), textPaint)
            }
        }
        textPaint.textSize = baseSize
    }

    override val coversCandidateBar: Boolean get() = true

    override fun onBlankTap() {
        listener.onBack()
    }

    override fun onContentTap(x: Float, y: Float): Boolean {
        if (cellW <= 0f) return false
        val col = ((x - padX) / cellW).toInt()
        val row = (y / cellH).toInt()
        if (col < 0 || col >= cols || y < 0f) return false
        val idx = row * cols + col
        if (idx < 0 || idx >= items.size) return false
        listener.onSymbol(items[idx])
        return true
    }

    override fun onBarKey(id: Int) {
        when (id) {
            ID_BACK -> listener.onBack()
            ID_DEL -> listener.onBackspace()
        }
    }

    private companion object {
        const val ID_BACK = 0
        const val ID_DEL = 1
    }
}
