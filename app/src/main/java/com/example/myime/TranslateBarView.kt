package com.example.myime

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.ColorDrawable
import android.os.SystemClock
import android.view.Gravity
import android.widget.PopupWindow
import android.view.MotionEvent
import android.view.View

/**
 * 候选栏上面的「翻译输入框」那一行(输入翻译打开时才显示):
 *   [中›英]  [ 你叫什么名字                 ×]  完成
 *  - 左边的小按钮:翻译方向,点一下向上弹出一个长条,里面列出所有方向,点哪个用哪个
 *  - 中间:要翻译的原文(键盘打的字都先进这里;空的时候显示提示文字)。文字太长时显示末尾,光标在最后
 *  - 输入框里的 ×:清空原文,目标输入框里的译文也一起清掉
 *  - 右边:有原文时是「完成」(译文留在目标输入框里,翻译框清空,输入结束);没有原文时是「关闭」(关掉输入翻译)
 * 不画闪烁的光标、不跑动画,不需要重绘就不重绘。
 */
class TranslateBarView(
    context: Context,
    private val listener: Listener,
) : View(context) {

    interface Listener {
        fun onPickLang(index: Int)
        fun onClear()
        fun onDone()
        fun onClose()
    }

    private var palette = Palette(context)

    private var text = ""
    private var lang = ""
    private var status = STATUS_NONE
    private var detail = ""

    private val bgPaint = Paint()
    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.LEFT }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val btnPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val smallPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.RIGHT }
    private val xPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val tmp = RectF()

    private val langRect = RectF()
    private val boxRect = RectF()
    private val clearRect = RectF()
    private val btnRect = RectF()

    private var rowH = 0f
    private var inset = 0f
    private var textBase = 0f
    private var labelBase = 0f
    private var btnBase = 0f
    private var smallBase = 0f
    private var downZone = ZONE_NONE
    private var popup: PopupWindow? = null
    private var lastDismissAt = 0L
    private var popupWasOpenAtDown = false
    private val autoDismiss = Runnable { dismissPopup() }

    init {
        applyMetrics()
    }

    private fun baseline(p: Paint): Float {
        val fm = p.fontMetrics
        return -(fm.ascent + fm.descent) / 2f
    }

    private fun applyMetrics() {
        val wide = context.isWide()
        rowH = context.dpf(if (wide) 54f else 46f)
        inset = context.dpf(if (wide) 31f else 8f)
        textPaint.textSize = context.spf(if (wide) 19f else 17f)
        labelPaint.textSize = context.spf(if (wide) 17f else 15f)
        btnPaint.textSize = context.spf(if (wide) 18f else 16f)
        smallPaint.textSize = context.spf(if (wide) 14f else 12f)
        linePaint.strokeWidth = context.dpf(1f)
        xPaint.strokeWidth = context.dpf(1.6f)
        textBase = baseline(textPaint)
        labelBase = baseline(labelPaint)
        btnBase = baseline(btnPaint)
        smallBase = baseline(smallPaint)
    }

    /** 方向长条:向上弹出的一列,当前方向高亮,第一个方向在最下面(离按钮最近)。点长条外面 / 选完 / 6 秒没动都会收起,不跑动画。 */
    private inner class PairList : View(context) {
        val n = Translator.pairs.size
        val itemH = context.dpf(if (context.isWide()) 46f else 42f)
        val padV = context.dpf(4f)
        val box = RectF()
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
        val cur = Translator.pairs.indexOfFirst { it.label == lang }

        fun totalH() = itemH * n + padV * 2

        override fun onMeasure(w: Int, h: Int) {
            setMeasuredDimension(MeasureSpec.getSize(w), totalH().toInt())
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            val r = context.dpf(12f)
            box.set(1f, 1f, w - 1f, h - 1f)
            p.style = Paint.Style.FILL
            p.color = palette.topBar
            canvas.drawRoundRect(box, r, r, p)
            p.style = Paint.Style.STROKE
            p.strokeWidth = context.dpf(1f)
            p.color = palette.textFaint
            p.alpha = 160
            canvas.drawRoundRect(box, r, r, p)
            tp.textSize = labelPaint.textSize
            val base = baseline(tp)
            for (i in 0 until n) {
                val bottom = h - padV - i * itemH
                val top = bottom - itemH
                if (i == cur) {
                    p.style = Paint.Style.FILL
                    p.color = palette.transAccent
                    p.alpha = 40
                    tmp.set(context.dpf(4f), top + context.dpf(2f), w - context.dpf(4f), bottom - context.dpf(2f))
                    canvas.drawRoundRect(tmp, r * 0.7f, r * 0.7f, p)
                }
                tp.color = if (i == cur) palette.transAccent else palette.text
                tp.isFakeBoldText = i == cur
                canvas.drawText(Translator.pairs[i].label, w / 2f, (top + bottom) / 2f + base, tp)
            }
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (e.actionMasked == MotionEvent.ACTION_UP) {
                val fromBottom = ((height - padV - e.y) / itemH).toInt()
                if (e.y >= padV && e.y <= height - padV && fromBottom in 0 until n) {
                    dismissPopup()
                    listener.onPickLang(fromBottom)
                }
            }
            return true
        }
    }

    private fun togglePopup() {
        if (popup != null) {
            dismissPopup()
            return
        }
        val list = PairList()
        val w = maxOf(langRect.width(), context.dpf(84f)).toInt()
        val h = list.totalH().toInt()
        val pw = PopupWindow(list, w, h, false)
        pw.isOutsideTouchable = true            // 点外面收起,点击同时照常传给键盘
        pw.setBackgroundDrawable(ColorDrawable(0))
        pw.inputMethodMode = PopupWindow.INPUT_METHOD_NOT_NEEDED
        pw.elevation = context.dpf(6f)
        pw.setOnDismissListener {
            popup = null
            lastDismissAt = SystemClock.uptimeMillis()
            removeCallbacks(autoDismiss)
        }
        val loc = IntArray(2)
        getLocationOnScreen(loc)
        val x = loc[0] + langRect.left.toInt()
        val y = (loc[1] + langRect.top - h - context.dpf(6f)).toInt().coerceAtLeast(0)
        try {
            pw.showAtLocation(this, Gravity.NO_GRAVITY, x, y)
            popup = pw
            postDelayed(autoDismiss, 6000)
        } catch (e: Exception) {
            popup = null
        }
    }

    fun dismissPopup() {
        val pw = popup ?: return
        popup = null
        removeCallbacks(autoDismiss)
        try {
            pw.dismiss()
        } catch (e: Exception) {
        }
    }

    override fun onDetachedFromWindow() {
        dismissPopup()
        super.onDetachedFromWindow()
    }

    fun refresh() {
        dismissPopup()
        palette = Palette(context)
        applyMetrics()
        layoutParts()
        requestLayout()
        invalidate()
    }

    /** 原文、方向按钮上的文字、状态(转圈用的 … / 失败)变了就重画;都没变不重画。 */
    fun setContent(text: String, lang: String, status: Int, detail: String = "") {
        val t = text.replace('\n', ' ')
        if (t == this.text && lang == this.lang && status == this.status && detail == this.detail) return
        this.detail = detail
        // 排版只和「方向文字」「框里有没有字(完成 / 关闭)」有关,打字过程中不用重排
        val relayout = lang != this.lang || t.isEmpty() != this.text.isEmpty()
        this.text = t
        this.lang = lang
        this.status = status
        if (relayout) layoutParts()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), rowH.toInt())
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        layoutParts()
    }

    private fun layoutParts() {
        val w = width.toFloat()
        if (w <= 0f) return
        val gap = context.dpf(8f)
        val pillH = context.dpf(32f)
        val boxH = context.dpf(36f)
        val cy = rowH / 2f

        val langW = maxOf(context.dpf(54f), labelPaint.measureText(lang) + context.dpf(22f))
        langRect.set(inset, cy - pillH / 2f, inset + langW, cy + pillH / 2f)

        val btnLabel = btnLabel()
        val btnW = btnPaint.measureText(btnLabel) + context.dpf(20f)
        btnRect.set(w - inset - btnW, 0f, w - inset + context.dpf(4f), rowH)

        boxRect.set(langRect.right + gap, cy - boxH / 2f, btnRect.left - gap + context.dpf(10f), cy + boxH / 2f)

        val r = context.dpf(9f)
        val cx = boxRect.right - context.dpf(8f) - r
        clearRect.set(cx - r - context.dpf(8f), boxRect.top, boxRect.right, boxRect.bottom)
    }

    private fun btnLabel() = if (text.isEmpty()) "关闭" else "完成"

    override fun onDraw(canvas: Canvas) {
        bgPaint.color = palette.topBar
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

        val cy = rowH / 2f
        val radius = context.dpf(18f)

        // 方向按钮:描边小圆角框
        linePaint.color = palette.textFaint
        linePaint.alpha = 160
        canvas.drawRoundRect(langRect, context.dpf(16f), context.dpf(16f), linePaint)
        labelPaint.color = palette.text
        canvas.drawText(lang, langRect.centerX(), cy + labelBase, labelPaint)

        // 输入框:空的时候是浅灰底;有字时换成主题色的浅底 + 描边
        val filled = text.isNotEmpty()
        if (filled) {
            boxPaint.color = palette.transAccent
            boxPaint.alpha = 34
        } else {
            boxPaint.color = palette.pill
            boxPaint.alpha = 255
        }
        canvas.drawRoundRect(boxRect, radius, radius, boxPaint)
        if (filled) {
            linePaint.color = palette.transAccent
            linePaint.alpha = 255
            canvas.drawRoundRect(boxRect, radius, radius, linePaint)
        }

        val padL = context.dpf(14f)
        val rightReserve = if (filled) context.dpf(36f) else context.dpf(10f)
        val innerL = boxRect.left + padL
        val innerR = boxRect.right - rightReserve
        val innerW = (innerR - innerL).coerceAtLeast(0f)

        if (!filled) {
            textPaint.color = palette.textFaint
            canvas.drawText("点击输入要翻译的语句", innerL, cy + textBase, textPaint)
        } else {
            textPaint.color = palette.text
            val tw = textPaint.measureText(text)
            val caretGap = context.dpf(3f)
            // 文字比框宽:往左挪,让末尾(光标处)一直看得见
            val shift = minOf(0f, innerW - tw - caretGap)
            canvas.save()
            canvas.clipRect(innerL, boxRect.top, innerR, boxRect.bottom)
            canvas.drawText(text, innerL + shift, cy + textBase, textPaint)
            // 光标:一根静止的竖线
            val cx = innerL + shift + tw + caretGap
            linePaint.color = palette.transAccent
            linePaint.alpha = 255
            canvas.drawLine(cx, cy - context.dpf(10f), cx, cy + context.dpf(10f), linePaint)
            canvas.restore()

            // 状态:翻译中 / 失败(画在 × 的左边)
            if (status != STATUS_NONE) {
                smallPaint.color = if (status == STATUS_FAILED) 0xFFD93025.toInt() else palette.textFaint
                canvas.drawText(if (status == STATUS_FAILED) (if (detail.isEmpty()) "翻译失败" else "失败:$detail") else "…", innerR - context.dpf(2f), cy + smallBase, smallPaint)
            }

            // × 清空
            val r = context.dpf(9f)
            val cxx = boxRect.right - context.dpf(8f) - r
            boxPaint.color = palette.textFaint
            boxPaint.alpha = 200
            canvas.drawCircle(cxx, cy, r, boxPaint)
            xPaint.color = 0xFFFFFFFF.toInt()
            val d = r * 0.42f
            canvas.drawLine(cxx - d, cy - d, cxx + d, cy + d, xPaint)
            canvas.drawLine(cxx + d, cy - d, cxx - d, cy + d, xPaint)
        }

        // 右边的「完成 / 关闭」
        btnPaint.color = if (filled) palette.transAccent else palette.text
        canvas.drawText(btnLabel(), btnRect.left + (btnRect.width() - context.dpf(4f)) / 2f, cy + btnBase, btnPaint)
    }

    // ---------------------------------------------------------------- 触摸

    private fun zoneAt(x: Float, y: Float): Int {
        return when {
            btnRect.contains(x, y) -> ZONE_BTN
            text.isNotEmpty() && clearRect.contains(x, y) -> ZONE_CLEAR
            langRect.contains(x, y) -> ZONE_LANG
            else -> ZONE_NONE
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downZone = zoneAt(e.x, e.y)
                // 长条开着时点方向按钮 = 收起它;长条外面的点击会先把它收掉,所以也看一下刚收起的时间,免得又弹出来
                popupWasOpenAtDown = popup != null || SystemClock.uptimeMillis() - lastDismissAt < 200
            }
            MotionEvent.ACTION_UP -> {
                val z = zoneAt(e.x, e.y)
                if (z != ZONE_NONE && z == downZone) {
                    when (z) {
                        ZONE_LANG -> if (popupWasOpenAtDown) dismissPopup() else togglePopup()
                        ZONE_CLEAR -> listener.onClear()
                        ZONE_BTN -> if (text.isEmpty()) listener.onClose() else listener.onDone()
                    }
                }
                downZone = ZONE_NONE
            }
            MotionEvent.ACTION_CANCEL -> downZone = ZONE_NONE
        }
        return true
    }

    companion object {
        const val STATUS_NONE = 0
        const val STATUS_LOADING = 1
        const val STATUS_FAILED = 2

        private const val ZONE_NONE = 0
        private const val ZONE_LANG = 1
        private const val ZONE_CLEAR = 2
        private const val ZONE_BTN = 3
    }
}
