package com.example.myime

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.CornerPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.OverScroller
import android.widget.PopupWindow
import android.widget.TextView
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable

/**
 * 键盘上方的两条白色区域(高度固定,不会跳动):
 *  - 上条:拼音 / 笔画的预览文字
 *  - 下条:没有候选时是工具栏图标;有候选时是候选词(第一个蓝色),右边多一个「关闭候选区」的小按钮(没有候选就不显示)
 *
 * 候选内容每次按键都会更新,但"有候选 ↔ 无候选"之间才做动画,避免每个字母触发一次。
 */
class CandidateBarView(
    context: Context,
    private val onPick: (Int) -> Unit,
    private val onTool: (Int) -> Unit,
    private val onClose: () -> Unit,
) : View(context) {

    private var palette = Palette(context)

    private var items: List<String> = emptyList()
    private var reads: List<String> = emptyList()   // 每个候选上方的读音(带声调);空 = 没开「显示读音」,候选栏和以前一模一样
    private var xs = FloatArray(1)
    private var contentWidth = 0f
    private var offset = 0f

    /**
     * 长按候选词(中文候选):请求这个词的读音和笔画数。返回 true = 已受理,
     * 结果稍后由调用方用 showInfo 显示(查询在后台线程,词库没加载好时还要等)。
     */
    var infoHandler: ((Int) -> Boolean)? = null
    private var pinPopup: PopupWindow? = null   // 长按弹出的信息条:不会自己消失,只在候选变了 / 点别处 / 键盘收起时收起

    private var composing = ""
    private var hint: String? = null   // 出错原因(小红字,显示在预览行右边)
    private var activeTool = -1

    private var contentAlpha = 0f
    private var targetVisible = false
    private var animator: ValueAnimator? = null

    // 尺寸
    private var stripH = 0f
    private var rowH = 0f
    private var inset = 0f
    private var itemPad = 0f
    private var fixedHeight = 0
    private var iconU = 0f
    private var closeW = 0f      // 候选条右边留给关闭按钮的宽度

    private val candPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.LEFT }
    private val composePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.LEFT }
    private val readPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.LEFT }
    private var readBase = 0f
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.RIGHT
        color = 0xFFE5484D.toInt()
    }
    private var hintBase = 0f
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val tmp = RectF()
    private var candBase = 0f
    private var composeBase = 0f
    private var downTool = -1

    private val scroller = OverScroller(context)
    private val flingRunnable = object : Runnable {
        override fun run() {
            if (scroller.computeScrollOffset()) {
                offset = scroller.currX.toFloat()
                invalidate()
                postOnAnimation(this)
            }
        }
    }

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            scroller.forceFinished(true)
            return true
        }

        override fun onScroll(
            e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float,
        ): Boolean {
            offset = (offset + distanceX).coerceIn(0f, maxOffset())
            invalidate()
            return true
        }

        override fun onFling(
            e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float,
        ): Boolean {
            scroller.fling(offset.toInt(), 0, (-velocityX).toInt(), 0, 0, maxOffset().toInt(), 0, 0)
            postOnAnimation(flingRunnable)
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            if (!targetVisible || e.y < stripH || e.x >= width - closeW) return
            val x = e.x - inset + offset
            for (i in items.indices) {
                if (x >= xs[i] && x < xs[i + 1]) {
                    infoHandler?.invoke(i)
                    return
                }
            }
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            if (!targetVisible || e.y < stripH) return false
            if (e.x >= width - closeW) {   // 右边的关闭按钮
                onClose()
                return true
            }
            val x = e.x - inset + offset
            for (i in items.indices) {
                if (x >= xs[i] && x < xs[i + 1]) {
                    onPick(i)
                    return true
                }
            }
            return false
        }
    })

    init {
        applyMetrics()
    }

    private fun baseline(p: Paint): Float {
        val fm = p.fontMetrics
        return -(fm.ascent + fm.descent) / 2f
    }

    private fun applyMetrics() {
        val wide = context.isWide()
        stripH = context.dpf(if (wide) 37f else 26f)
        rowH = context.dpf(if (wide) 50f else 44f)
        inset = context.dpf(if (wide) 31f else 8f)
        itemPad = context.dpf(if (wide) 20f else 14f)
        iconU = context.dpf(if (wide) 11f else 10f)
        closeW = context.dpf(if (wide) 56f else 42f)
        fixedHeight = context.candidateBarHeightPx()

        candPaint.textSize = context.spf(if (wide) 24f else 20f)
        composePaint.textSize = context.spf(if (wide) 22f else 18f)
        iconPaint.strokeWidth = context.dpf(1.8f)
        iconPaint.pathEffect = CornerPathEffect(context.dpf(2.5f))
        candBase = baseline(candPaint)
        composeBase = baseline(composePaint)
        hintPaint.textSize = context.spf(if (wide) 14f else 12f)
        hintBase = baseline(hintPaint)
        readPaint.textSize = context.spf(if (wide) 12f else 10.5f)
        readBase = baseline(readPaint)
    }

    private fun maxOffset(): Float = (contentWidth + inset + closeW - width).coerceAtLeast(0f)

    /** 每次开始输入时调用:跟随深色模式、屏幕宽度变化。 */
    private var uiSig = ""

    fun refresh() {
        val sig = context.uiSignature()
        if (sig == uiSig && width > 0) return
        uiSig = sig
        palette = Palette(context)
        applyMetrics()
        relayout()
        requestLayout()
        invalidate()
    }

    fun setHint(text: String?) {
        if (text == hint) return
        hint = text
        if (composing.isNotEmpty()) invalidate()
    }

    fun setComposing(text: String) {
        if (text == composing) return
        composing = text
        invalidate()
    }

    /** 高亮当前打开的页面对应的工具栏图标(-1 表示没有)。 */
    fun setActiveTool(tool: Int) {
        if (tool == activeTool) return
        activeTool = tool
        invalidate()
    }

    fun setCandidates(list: List<String>, animate: Boolean = true, readings: List<String>? = null) {
        if (pinPopup != null && list != items) dismissPinPopup()   // 候选变了,弹出的菜单对应的词已经不对了
        if (list.isEmpty()) {
            if (!targetVisible && items.isEmpty()) return
            targetVisible = false
            if (animate && contentAlpha > 0f) {
                fadeTo(0f) {
                    items = emptyList()
                    reads = emptyList()
                    relayout()
                    invalidate()
                }
            } else {
                animator?.cancel()
                contentAlpha = 0f
                items = emptyList()
                reads = emptyList()
                relayout()
                invalidate()
            }
            return
        }

        // 读音和候选一起换:个数对不上、或全是空的就当没有读音
        val rd = if (readings != null && readings.size == list.size && readings.any { it.isNotEmpty() }) readings else emptyList()
        // 内容没变就不重排、不重画、不回滚到开头(也少做一次无谓的绘制)
        if (targetVisible && list == items && rd == reads) return
        items = list
        reads = rd
        relayout()
        scroller.forceFinished(true)
        offset = 0f
        val wasVisible = targetVisible
        targetVisible = true
        if (!wasVisible) {
            if (animate) {
                fadeTo(1f, null)
            } else {
                animator?.cancel()
                contentAlpha = 1f
            }
        }
        invalidate()
    }

    /** 不做淡入淡出:直接切到目标透明度。 */
    private fun fadeTo(target: Float, onEnd: (() -> Unit)?) {
        animator?.cancel()
        contentAlpha = target
        invalidate()
        onEnd?.invoke()
    }

    /** 有读音时每个候选左右留的空白小一点(读音比字宽,不然候选栏一屏放不下几个)。 */
    private fun padNow(): Float = if (reads.isEmpty()) itemPad else itemPad * 0.75f

    private fun relayout() {
        xs = FloatArray(items.size + 1)
        var x = 0f
        val pad = padNow()
        for (i in items.indices) {
            xs[i] = x
            var w = candPaint.measureText(items[i])
            if (reads.isNotEmpty()) {
                val r = reads[i]
                if (r.isNotEmpty()) w = maxOf(w, readPaint.measureText(r))
            }
            x += w + pad * 2
        }
        xs[items.size] = x
        contentWidth = x
    }

    // ---------------------------------------------------------------- 绘制

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), fixedHeight)
    }

    override fun onDraw(canvas: Canvas) {
        // 父布局关了裁剪(为了让气泡能盖住候选栏),drawColor 会铺满无限大,把别的视图(工具栏图标)全盖住:只填自己的范围
        canvas.save()
        canvas.clipRect(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawColor(palette.topBar)
        canvas.restore()

        // 上条:预览文字
        if (composing.isNotEmpty()) {
            composePaint.color = palette.text
            canvas.drawText(composing, inset + itemPad * 1.5f, stripH / 2f + composeBase, composePaint)
            val h = hint
            if (h != null) {
                val left = inset + itemPad * 1.5f + composePaint.measureText(composing) + itemPad
                val avail = width - inset - left
                if (avail > hintPaint.textSize * 4) {
                    val n = hintPaint.breakText(h, true, avail, null)
                    val shown = if (n < h.length) h.substring(0, n) + "…" else h
                    canvas.drawText(shown, width - inset, stripH / 2f + hintBase, hintPaint)
                }
            }
        }

        // 下条:工具栏 ⇄ 候选
        if (contentAlpha < 1f) drawToolbar(canvas, 1f - contentAlpha)
        if (items.isNotEmpty() && contentAlpha > 0f) {
            canvas.save()
            canvas.clipRect(0f, stripH, width - closeW, height.toFloat())   // 候选词不压到关闭按钮
            drawCandidates(canvas)
            canvas.restore()
            drawClose(canvas)
        }
    }

    /** 圆圈里一个 ×。 */
    private fun drawClose(canvas: Canvas) {
        val cx = width - closeW / 2f
        val cy = stripH + rowH / 2f
        val r = iconU * 0.85f
        val d = r * 0.38f
        iconPaint.color = palette.icon
        iconPaint.alpha = (contentAlpha * 255f).toInt()
        canvas.drawCircle(cx, cy, r, iconPaint)
        canvas.drawLine(cx - d, cy - d, cx + d, cy + d, iconPaint)
        canvas.drawLine(cx + d, cy - d, cx - d, cy + d, iconPaint)
    }

    private fun drawCandidates(canvas: Canvas) {
        val left = offset - inset
        val right = left + width
        val hasReads = reads.isNotEmpty()
        // 有读音:读音在上、字在下,两行一起居中;没有读音和以前完全一样
        val y = stripH + rowH * (if (hasReads) 0.68f else 0.5f) + candBase
        val ry = stripH + rowH * 0.27f + readBase
        val a = (contentAlpha * 255f).toInt()
        for (i in items.indices) {
            if (xs[i + 1] < left) continue
            if (xs[i] > right) break
            val w = xs[i + 1] - xs[i]
            val first = i == 0
            candPaint.color = if (first) palette.accent else palette.text
            candPaint.alpha = a
            val tw = candPaint.measureText(items[i])
            canvas.drawText(items[i], inset + xs[i] - offset + (w - tw) / 2f, y, candPaint)
            if (hasReads) {
                val r = reads[i]
                if (r.isNotEmpty()) {
                    readPaint.color = if (first) palette.accent else palette.icon
                    readPaint.alpha = a
                    val rw = readPaint.measureText(r)
                    canvas.drawText(r, inset + xs[i] - offset + (w - rw) / 2f, ry, readPaint)
                }
            }
        }
    }

    private fun drawToolbar(canvas: Canvas, alpha: Float) {
        val n = TOOLS
        val cellW = (width - 2 * inset) / n
        val cy = stripH + rowH / 2f
        val a = (alpha * 255f).toInt()

        for (i in 0 until n) {
            val cx = inset + cellW * (i + 0.5f)

            if (i == activeTool) {
                pillPaint.color = palette.pill
                pillPaint.alpha = a
                tmp.set(cx - cellW * 0.44f, cy - rowH * 0.42f, cx + cellW * 0.44f, cy + rowH * 0.42f)
                canvas.drawRoundRect(tmp, context.dpf(10f), context.dpf(10f), pillPaint)
            }

            iconPaint.color = palette.icon
            iconPaint.alpha = a
            dotPaint.color = palette.icon
            dotPaint.alpha = a
            drawToolIcon(canvas, i, cx, cy)
        }
    }

    private fun drawToolIcon(canvas: Canvas, which: Int, cx: Float, cy: Float) {
        val u = iconU
        path.reset()
        when (which) {
            TOOL_SETTINGS -> { // 圆角六边形 + 圆
                for (k in 0 until 6) {
                    val ang = Math.toRadians(30.0 + 60.0 * k)
                    val px = cx + u * Math.cos(ang).toFloat()
                    val py = cy + u * Math.sin(ang).toFloat()
                    if (k == 0) path.moveTo(px, py) else path.lineTo(px, py)
                }
                path.close()
                canvas.drawPath(path, iconPaint)
                canvas.drawCircle(cx, cy, u * 0.38f, iconPaint)
            }
            TOOL_SWITCH_IME -> { // 键盘:圆角矩形 + 键帽小点 + 空格条
                tmp.set(cx - u * 1.05f, cy - u * 0.72f, cx + u * 1.05f, cy + u * 0.72f)
                canvas.drawRoundRect(tmp, u * 0.25f, u * 0.25f, iconPaint)
                val dr = u * 0.1f
                for (row in 0 until 2) {
                    for (col in 0 until 4) {
                        canvas.drawCircle(cx - u * 0.6f + u * 0.4f * col, cy - u * 0.28f + u * 0.3f * row, dr, dotPaint)
                    }
                }
                canvas.drawLine(cx - u * 0.5f, cy + u * 0.38f, cx + u * 0.5f, cy + u * 0.38f, iconPaint)
            }
            TOOL_EMOJI -> { // 笑脸:圆 + 两只眼 + 嘴
                canvas.drawCircle(cx, cy, u * 0.95f, iconPaint)
                canvas.drawCircle(cx - u * 0.33f, cy - u * 0.25f, u * 0.1f, dotPaint)
                canvas.drawCircle(cx + u * 0.33f, cy - u * 0.25f, u * 0.1f, dotPaint)
                tmp.set(cx - u * 0.52f, cy - u * 0.38f, cx + u * 0.52f, cy + u * 0.52f)
                canvas.drawArc(tmp, 25f, 130f, false, iconPaint)
            }
            TOOL_EDIT -> { // 光标编辑:‹I›
                canvas.drawLine(cx, cy - u * 0.8f, cx, cy + u * 0.8f, iconPaint)
                canvas.drawLine(cx - u * 0.28f, cy - u * 0.8f, cx + u * 0.28f, cy - u * 0.8f, iconPaint)
                canvas.drawLine(cx - u * 0.28f, cy + u * 0.8f, cx + u * 0.28f, cy + u * 0.8f, iconPaint)
                path.moveTo(cx - u * 0.75f, cy - u * 0.38f)
                path.lineTo(cx - u * 1.1f, cy)
                path.lineTo(cx - u * 0.75f, cy + u * 0.38f)
                canvas.drawPath(path, iconPaint)
                path.reset()
                path.moveTo(cx + u * 0.75f, cy - u * 0.38f)
                path.lineTo(cx + u * 1.1f, cy)
                path.lineTo(cx + u * 0.75f, cy + u * 0.38f)
                canvas.drawPath(path, iconPaint)
            }
            TOOL_CLIPBOARD -> { // 剪刀:两个圆环 + 交叉的两刃
                canvas.drawCircle(cx - u * 0.5f, cy + u * 0.55f, u * 0.3f, iconPaint)
                canvas.drawCircle(cx + u * 0.5f, cy + u * 0.55f, u * 0.3f, iconPaint)
                canvas.drawLine(cx - u * 0.38f, cy + u * 0.3f, cx + u * 0.6f, cy - u * 0.85f, iconPaint)
                canvas.drawLine(cx + u * 0.38f, cy + u * 0.3f, cx - u * 0.6f, cy - u * 0.85f, iconPaint)
            }
            else -> { // 收起键盘:向下的 V
                path.moveTo(cx - u * 0.7f, cy - u * 0.25f)
                path.lineTo(cx, cy + u * 0.45f)
                path.lineTo(cx + u * 0.7f, cy - u * 0.25f)
                canvas.drawPath(path, iconPaint)
            }
        }
    }

    // ---------------------------------------------------------------- 触摸

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (targetVisible) return gestures.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> downTool = toolAt(event.x, event.y)
            MotionEvent.ACTION_UP -> {
                val t = toolAt(event.x, event.y)
                if (t >= 0 && t == downTool) onTool(t)
                downTool = -1
            }
            MotionEvent.ACTION_CANCEL -> downTool = -1
        }
        return true
    }

    private fun toolAt(x: Float, y: Float): Int {
        if (y < stripH) return -1
        val cellW = (width - 2 * inset) / TOOLS
        val idx = ((x - inset) / cellW).toInt()
        return if (x >= inset && idx in 0 until TOOLS) idx else -1
    }

    /** 在候选栏上方弹出一行信息(长按候选看读音和笔画数)。不会自动消失;候选一变、点到别处、键盘收起时才收起。 */
    fun showInfo(index: Int, text: String) {
        if (!targetVisible || index !in 0 until items.size) return
        dismissPinPopup()
        val tv = TextView(context)
        tv.text = text
        tv.textSize = 16f
        tv.gravity = Gravity.CENTER
        tv.setTextColor(palette.text)
        tv.setPadding(context.dpi(14f), context.dpi(9f), context.dpi(14f), context.dpi(9f))
        tv.background = GradientDrawable().apply {
            setColor(palette.background)
            cornerRadius = context.dpf(12f)
            setStroke(context.dpi(1f), palette.textFaint)
        }
        popupAt(index, tv)
    }

    /** 把 row 放在第 index 个候选的正上方弹出(超出屏幕就贴边;太宽就换行)。 */
    private fun popupAt(index: Int, row: View) {
        val screenW = context.resources.displayMetrics.widthPixels
        row.measure(
            MeasureSpec.makeMeasureSpec(screenW - context.dpi(16f), MeasureSpec.AT_MOST),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
        )
        val w = row.measuredWidth
        val ph = row.measuredHeight
        val pw = PopupWindow(row, w, ph, false)
        pw.isOutsideTouchable = true
        pw.setBackgroundDrawable(ColorDrawable(0))
        pw.inputMethodMode = PopupWindow.INPUT_METHOD_NOT_NEEDED
        pw.elevation = context.dpf(6f)
        pw.setOnDismissListener {
            pinPopup = null
        }
        val loc = IntArray(2)
        getLocationOnScreen(loc)
        val center = loc[0] + inset + (xs[index] + xs[index + 1]) / 2f - offset
        val x = (center - w / 2f).toInt().coerceIn(0, maxOf(0, screenW - w))
        // 整个气泡落在候选栏的上边缘之上、正对着被长按的那个字(不压住上面那条拼音/笔画预览)。
        // 用 showAsDropDown 按候选栏自己的位置算偏移:showAtLocation 的 y 在输入法窗口里会被状态栏等偏移,
        // 以前气泡会往下掉、盖住候选字。
        pw.isClippingEnabled = false
        val xoff = x - loc[0]
        val yoff = -height - ph - context.dpi(2f)
        try {
            pw.showAsDropDown(this, xoff, yoff, Gravity.NO_GRAVITY)
            pinPopup = pw
        } catch (e: Exception) {
            pinPopup = null
        }
    }

    fun dismissPinPopup() {
        val pw = pinPopup ?: return
        pinPopup = null
        try {
            pw.dismiss()
        } catch (e: Exception) {
        }
    }

    override fun onDetachedFromWindow() {
        dismissPinPopup()
        animator?.cancel()
        removeCallbacks(flingRunnable)
        super.onDetachedFromWindow()
    }

    companion object {
        const val TOOLS = 6
        const val TOOL_SETTINGS = 0
        const val TOOL_SWITCH_IME = 1
        const val TOOL_EMOJI = 2
        const val TOOL_EDIT = 3
        const val TOOL_CLIPBOARD = 4
        const val TOOL_HIDE = 5
    }
}
