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

private val NO_SPANS = IntArray(0)
private const val HINT_RED = 0xFFE5484D.toInt()

/** 拼错时每一处错用一种颜色(第一处红色),预览里打错的字母和「改正拼音」里对应的字母用同一种颜色,一眼对得上。 */
private val ERR_COLORS = intArrayOf(0xFFFF1744.toInt(), 0xFFFF9100.toInt(), 0xFFAA00FF.toInt(), 0xFF00BFA5.toInt(), 0xFF2979FF.toInt())

/**
 * 键盘上方的三条白色区域(高度固定,不会跳动):
 *  - 上条:拼音 / 笔画的预览文字
 *  - 中条:整句切成的词块(拼音输入有候选时才有内容),点一块就改那一块的词
 *  - 下条:没有候选时是工具栏图标;有候选时是候选词(第一个蓝色),右边多一个「关闭候选区」的小按钮(没有候选就不显示)
 *
 * 候选内容每次按键都会更新,但"有候选 ↔ 无候选"之间才做动画,避免每个字母触发一次。
 */
class CandidateBarView(
    context: Context,
    private val onPick: (Int) -> Unit,
    private val onTool: (Int) -> Unit,
    private val onClose: () -> Unit,
    private val onChunk: (Int) -> Unit = {},
    private val onHintFix: () -> Unit = {},   // 点了预览行右边的「是不是 xxx」改正提示
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
    private var done: IntArray = IntArray(0)   // 预览里「自动补上的字母」的范围(开始, 结束, 开始, 结束 …),用强调色画
    private var errs: IntArray = IntArray(0)   // 预览里「打错的字母」(开始, 结束, 第几处错, …):按第几处错用不同颜色画,带下划线

    // 候选行最左边的「改正拼音」小块(gui qiu):拼错且能改对时才有,点一下就改;没有时 fixText = null
    private var fixText: String? = null
    private var fixColors: IntArray = IntArray(0)   // fixText 每个字符属于第几处错,-1 = 没错
    private var fixLead = 0f                        // 这一块占的宽度(候选从它右边开始排)
    private val fixPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.LEFT }
    private var hlStart = -1   // 预览里高亮的一段(点中的词块的拼音),-1 = 没有
    private var hlEnd = -1
    private var hint: String? = null   // 出错原因(小红字,显示在预览行右边)
    private var hintFix = false        // 这条提示是「改正拼音」建议:用强调色画,点一下就改(画的时候记下可点范围)
    private var hintTapLeft = -1f      // 可点范围的左边界,-1 = 现在没画出来,点不了
    private var activeTool = -1

    private var contentAlpha = 0f
    private var targetVisible = false
    private var animator: ValueAnimator? = null

    // 尺寸
    private var stripH = 0f
    private var chunkH = 0f      // 中间的词块行
    private var rowH = 0f
    private var candTop = 0f     // 候选行(和没有候选时的工具栏)的上沿 = 预览条 + 词块行

    // 词块行:整句按词切开,一块一个;sel 是点中的那块(-1 = 没有),raw 是没被吃掉的拼音(只显示,不能点)
    private var chunkTexts: List<String> = emptyList()
    private var chunkReads: List<String> = emptyList()   // 每块的读音(音节空格隔开,个数 = 字数才按字排),空串 = 没有
    private val rubyGap get() = context.dpf(2f)
    private var chunkRaw: List<Boolean> = emptyList()
    private var chunkSel = -1
    private var chunkXs = FloatArray(1)
    private var chunkRuby: Array<Ruby?> = emptyArray()
    private var candRuby: Array<Ruby?> = emptyArray()
    private var chunkContentW = 0f
    private var chunkOffset = 0f
    private var scrollingChunks = false   // 这一次手势是在词块行上开始的
    private var inset = 0f
    private var itemPad = 0f
    private var fixedHeight = 0
    private var iconU = 0f
    private var closeW = 0f      // 候选条右边留给关闭按钮的宽度

    private val candPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.LEFT }
    private val chunkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.LEFT }
    private var chunkBase = 0f
    private val composePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.LEFT }
    private val readPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.LEFT }
    private var readBase = 0f
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.RIGHT
        color = HINT_RED
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
            scrollingChunks = chunkTexts.isNotEmpty() && e.y >= stripH && e.y < candTop
            return true
        }

        override fun onScroll(
            e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float,
        ): Boolean {
            if (scrollingChunks) {
                chunkOffset = (chunkOffset + distanceX).coerceIn(0f, chunkMaxOffset())
            } else {
                offset = (offset + distanceX).coerceIn(0f, maxOffset())
            }
            invalidate()
            return true
        }

        override fun onFling(
            e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float,
        ): Boolean {
            if (scrollingChunks) return true   // 词块行只跟手拖动,不做惯性
            scroller.fling(offset.toInt(), 0, (-velocityX).toInt(), 0, 0, maxOffset().toInt(), 0, 0)
            postOnAnimation(flingRunnable)
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            if (!targetVisible || e.y < candTop || e.x >= width - closeW) return
            val x = e.x - inset + offset
            for (i in items.indices) {
                if (x >= xs[i] && x < xs[i + 1]) {
                    infoHandler?.invoke(i)
                    return
                }
            }
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            if (!targetVisible) return false
            if (e.y < stripH) {   // 预览行:只有右边的「改正拼音」提示可点
                if (hintTapLeft >= 0f && e.x >= hintTapLeft) {
                    onHintFix()
                    return true
                }
                return false
            }
            if (e.y < candTop) {   // 词块行
                val cx = e.x - inset + chunkOffset
                for (i in chunkTexts.indices) {
                    if (cx >= chunkXs[i] && cx < chunkXs[i + 1]) {
                        if (!chunkRaw[i]) onChunk(i)
                        return true
                    }
                }
                return false
            }
            if (e.x >= width - closeW) {   // 右边的关闭按钮
                onClose()
                return true
            }
            val x = e.x - inset + offset
            if (fixText != null && x < fixLead) {   // 最左边的「改正拼音」
                onHintFix()
                return true
            }
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
        // SimpleOnGestureListener 自带「双击检测」:第二下点得快(0.3 秒内、位置不远)会被当成双击,不再触发单击,
        // 连点两个候选时第二个就点不上。候选栏没有双击功能,关掉,每一下都按单击处理
        gestures.setOnDoubleTapListener(null)
    }

    private fun baseline(p: Paint): Float {
        val fm = p.fontMetrics
        return -(fm.ascent + fm.descent) / 2f
    }

    private fun applyMetrics() {
        val wide = context.isWide()
        stripH = context.dpf(if (wide) 34f else 24f)
        chunkH = context.chunkRowH()
        candTop = stripH + chunkH
        rowH = context.dpf(if (wide) 46f else 42f)
        inset = context.dpf(if (wide) 31f else 8f)
        itemPad = context.dpf(if (wide) 20f else 14f)
        iconU = context.dpf(if (wide) 11f else 10f)
        closeW = context.dpf(if (wide) 56f else 42f)
        fixedHeight = context.candidateBarHeightPx()

        candPaint.textSize = context.spf(if (wide) 24f else 20f)
        composePaint.textSize = context.spf(if (wide) 22f else 18f)
        fixPaint.textSize = context.spf(if (wide) 22f else 18f)
        iconPaint.strokeWidth = context.dpf(1.8f)
        iconPaint.pathEffect = CornerPathEffect(context.dpf(2.5f))
        candBase = baseline(candPaint)
        composeBase = baseline(composePaint)
        chunkPaint.textSize = context.spf(if (wide) 21f else 18f)
        chunkBase = baseline(chunkPaint)
        hintPaint.textSize = context.spf(if (wide) 14f else 12f)
        hintBase = baseline(hintPaint)
        readPaint.textSize = context.spf(if (wide) 15f else 13.5f)   // 读音(带声调):太小声调看不清
        readBase = baseline(readPaint)
    }

    private fun maxOffset(): Float = (contentWidth + inset + closeW - width).coerceAtLeast(0f)
    private fun chunkMaxOffset(): Float = (chunkContentW + inset * 2 - width).coerceAtLeast(0f)

    /** 每次开始输入时调用:跟随深色模式、屏幕宽度变化。 */
    private var uiSig = ""

    fun refresh() {
        val sig = context.uiSignature()
        if (sig == uiSig && width > 0) return
        uiSig = sig
        palette = Palette(context)
        applyMetrics()
        relayout()
        layoutChunks()
        requestLayout()
        invalidate()
    }

    private var stale = false

    /** 候选正在刷新、旧候选已经不能点了:把它们画淡一点,让人看出来在等(只改显示,不改点击)。 */
    fun setStale(v: Boolean) {
        if (v == stale) return
        stale = v
        if (items.isNotEmpty()) invalidate()
    }

    /** fix = true:这是「改正拼音」的建议(可点),用强调色画;否则是普通出错提示(小红字,不可点)。 */
    fun setHint(text: String?, fix: Boolean = false) {
        val f = fix && text != null
        if (text == hint && f == hintFix) return
        hint = text
        hintFix = f
        invalidate()
    }

    /** hlFrom ~ hlTo:预览里要高亮的一段(点中的那一块的拼音),后面带一根光标线;不要高亮传 -1。 */
    fun setComposing(text: String, hlFrom: Int = -1, hlTo: Int = -1, doneSpans: IntArray = NO_SPANS, errSpans: IntArray = NO_SPANS) {
        val ok = hlFrom >= 0 && hlTo > hlFrom && hlTo <= text.length
        val from = if (ok) hlFrom else -1
        val to = if (ok) hlTo else -1
        if (text == composing && from == hlStart && to == hlEnd && doneSpans.contentEquals(done) && errSpans.contentEquals(errs)) return
        composing = text
        done = doneSpans
        errs = errSpans
        hlStart = from
        hlEnd = to
        invalidate()
    }

    /** 词块行:整句按词切成的文字、哪些是没被吃掉的拼音(只显示)、点中的那块(-1 = 没有)。 */
    /** 词块行排版(字号/读音行变了也要重排,所以单独拿出来)。 */
    private fun layoutChunks() {
        val n = chunkTexts.size
        chunkXs = FloatArray(n + 1)
        chunkRuby = if (chunkReads.isEmpty()) emptyArray() else arrayOfNulls(n)
        var x = 0f
        val pad = itemPad * 0.55f
        for (i in 0 until n) {
            chunkXs[i] = x
            val rb = if (chunkReads.isNotEmpty() && !chunkRaw[i]) buildRuby(chunkTexts[i], chunkReads[i], chunkPaint) else null
            if (rb != null) chunkRuby[i] = rb
            x += (rb?.width ?: chunkPaint.measureText(chunkTexts[i])) + pad * 2 + itemPad * 0.25f
        }
        chunkXs[n] = x
        chunkContentW = x
    }

    fun setChunks(texts: List<String>, raws: List<Boolean>, sel: Int, readings: List<String> = emptyList()) {
        val rd = if (readings.size == texts.size) readings else emptyList()
        if (texts == chunkTexts && raws == chunkRaw && sel == chunkSel && rd == chunkReads) return
        // 块数和「哪些是拼音块」没变(只是某块的文字换了,比如在块里改拼音):保持滚动位置,不要每打一个字母就跳回开头
        val sameList = texts == chunkTexts || (texts.size == chunkTexts.size && raws == chunkRaw)
        chunkTexts = texts
        chunkReads = rd
        chunkRaw = raws
        chunkSel = sel
        layoutChunks()
        if (!sameList) chunkOffset = 0f
        // 点中的那块要看得见:不在屏幕里就滚到它
        if (sel in texts.indices) {
            val l = chunkXs[sel]
            val r = chunkXs[sel + 1]
            val viewW = width - 2 * inset
            if (l < chunkOffset) chunkOffset = l
            else if (r > chunkOffset + viewW) chunkOffset = r - viewW
        }
        chunkOffset = chunkOffset.coerceIn(0f, chunkMaxOffset())
        invalidate()
    }

    /** 高亮当前打开的页面对应的工具栏图标(-1 表示没有)。 */
    fun setActiveTool(tool: Int) {
        if (tool == activeTool) return
        activeTool = tool
        invalidate()
    }

    /** 候选行最左边的「改正拼音」:text 是改正后的拼音(音节之间已加空格),colors 是每个字符属于第几处错(-1 = 没错);text = null 去掉。 */
    fun setFixChip(text: String?, colors: IntArray = NO_SPANS) {
        if (text == fixText && colors.contentEquals(fixColors)) return
        fixText = text
        fixColors = colors
        relayout()
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

    /** 一个词的按字排版结果:排版时算一次存起来,画的时候不再 measureText / split(滑动时每帧都在画)。 */
    private class Ruby(val syls: Array<String>, val cw: FloatArray, val rw: FloatArray, val cell: FloatArray, val width: Float)

    /** 读音按空格切开,个数正好等于字数才能一个字配一个音节;对不上返回 null(整串读音居中放在词上方)。
     *  每个字一格,格宽 = 字宽和它的音节宽中大的那个,音节在字的正上方、字在格里居中。词仍是一个整体。 */
    private fun buildRuby(text: String, read: String, tp: Paint): Ruby? {
        if (read.isEmpty() || text.isEmpty()) return null
        val s = read.split(' ')
        if (s.size != text.length) return null
        val n = text.length
        val cw = FloatArray(n)
        val rw = FloatArray(n)
        val cell = FloatArray(n)
        var w = 0f
        for (k in 0 until n) {
            cw[k] = tp.measureText(text, k, k + 1)
            rw[k] = readPaint.measureText(s[k])
            cell[k] = maxOf(cw[k], rw[k] + rubyGap)
            w += cell[k]
        }
        return Ruby(s.toTypedArray(), cw, rw, cell, w)
    }

    private fun drawRuby(canvas: Canvas, text: String, rb: Ruby, x0: Float, yText: Float, yRead: Float, tp: Paint) {
        var x = x0
        for (k in text.indices) {
            canvas.drawText(text, k, k + 1, x + (rb.cell[k] - rb.cw[k]) / 2f, yText, tp)
            canvas.drawText(rb.syls[k], x + (rb.cell[k] - rb.rw[k]) / 2f, yRead, readPaint)
            x += rb.cell[k]
        }
    }

    private fun relayout() {
        xs = FloatArray(items.size + 1)
        candRuby = if (reads.isEmpty()) emptyArray() else arrayOfNulls(items.size)
        val ft = fixText
        fixLead = if (ft == null) 0f else fixPaint.measureText(ft) + itemPad * 1.2f + itemPad * 0.6f
        var x = fixLead
        val pad = padNow()
        for (i in items.indices) {
            xs[i] = x
            var w = candPaint.measureText(items[i])
            if (reads.isNotEmpty()) {
                val r = reads[i]
                if (r.isNotEmpty()) {
                    val rb = buildRuby(items[i], r, candPaint)
                    candRuby[i] = rb
                    w = if (rb != null) rb.width else maxOf(w, readPaint.measureText(r))
                }
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
            val tx = inset + itemPad * 1.5f
            val ty = stripH / 2f + composeBase
            if (hlStart >= 0 && hlEnd in (hlStart + 1)..composing.length) {
                // 点中了一块:这一块的拼音换成强调色加淡底,末尾一根光标线——打字 / 退格改的就是它
                val before = composing.substring(0, hlStart)
                val mid = composing.substring(hlStart, hlEnd)
                val xMid = tx + composePaint.measureText(before)
                val wMid = composePaint.measureText(mid)
                val gap = context.dpf(2f)
                pillPaint.color = palette.accent
                pillPaint.alpha = 40
                tmp.set(xMid - gap, stripH * 0.10f, xMid + wMid + gap, stripH * 0.90f)
                canvas.drawRoundRect(tmp, context.dpf(6f), context.dpf(6f), pillPaint)
                drawComposeRange(canvas, 0, hlStart, tx, ty, palette.text)
                drawComposeRange(canvas, hlStart, hlEnd, xMid, ty, palette.accent)
                drawComposeRange(canvas, hlEnd, composing.length, xMid + wMid, ty, palette.text)
                pillPaint.alpha = 255
                val cx = xMid + wMid + gap * 0.5f
                tmp.set(cx, stripH * 0.18f, cx + context.dpf(1.5f), stripH * 0.82f)
                canvas.drawRect(tmp, pillPaint)
            } else {
                drawComposeRange(canvas, 0, composing.length, tx, ty, palette.text)
            }
        }
        // 出错 / 状态提示(小字,预览条右边):预览条是空的(词块行已经显示读音)时也要画
        val h = if (composing.isNotEmpty() || chunkTexts.isNotEmpty()) hint else null
        hintTapLeft = -1f
        if (h != null) {
            val left = inset + itemPad * 1.5f + (if (composing.isNotEmpty()) composePaint.measureText(composing) + itemPad else 0f)
            val avail = width - inset - left
            if (avail > hintPaint.textSize * 4) {
                val n = hintPaint.breakText(h, true, avail, null)
                val shown = if (n < h.length) h.substring(0, n) + "…" else h
                hintPaint.color = if (hintFix) palette.accent else HINT_RED
                canvas.drawText(shown, width - inset, stripH / 2f + hintBase, hintPaint)
                if (hintFix) hintTapLeft = width - inset - hintPaint.measureText(shown) - itemPad   // 左边多留一点,好点
            }
        }

        // 中条:词块
        if (chunkTexts.isNotEmpty()) drawChunks(canvas)

        // 下条:工具栏 ⇄ 候选
        if (contentAlpha < 1f) drawToolbar(canvas, 1f - contentAlpha)
        if (items.isNotEmpty() && contentAlpha > 0f) {
            canvas.save()
            canvas.clipRect(0f, candTop, width - closeW, height.toFloat())   // 候选词不压到关闭按钮
            drawCandidates(canvas)
            canvas.restore()
            drawClose(canvas)
        }
    }

    /** 预览第 pos 个字符属于第几处错,没错 -1。 */
    private fun errAt(pos: Int): Int {
        var k = 0
        while (k + 2 < errs.size) {
            if (pos >= errs[k] && pos < errs[k + 1]) return errs[k + 2]
            k += 3
        }
        return -1
    }

    private fun doneAt(pos: Int): Boolean {
        var k = 0
        while (k + 1 < done.size) {
            if (pos >= done[k] && pos < done[k + 1]) return true
            k += 2
        }
        return false
    }

    /** 画预览的 [a, b) 一段:打错的字母按第几处错用不同颜色并加下划线;自动补上的字母(done 里的范围)用强调色,其余用 base;返回画完的 x。 */
    private fun drawComposeRange(canvas: Canvas, a: Int, b: Int, x0: Float, y: Float, base: Int): Float {
        var x = x0
        var pos = a
        while (pos < b) {
            val e = errAt(pos)
            val d = doneAt(pos)
            var stop = pos + 1
            while (stop < b && errAt(stop) == e && doneAt(stop) == d) stop++
            composePaint.color = if (e >= 0) ERR_COLORS[e % ERR_COLORS.size] else if (d) palette.accent else base
            composePaint.isUnderlineText = e >= 0
            canvas.drawText(composing, pos, stop, x, y, composePaint)
            x += composePaint.measureText(composing, pos, stop)
            pos = stop
        }
        composePaint.isUnderlineText = false
        composePaint.color = palette.text
        return x
    }

    /** 候选行最左边的「改正拼音」:淡红底的小块,没错的字母红色,错的字母按第几处错用对应颜色加下划线(和预览里打错的字母同色)。 */
    private fun drawFixChip(canvas: Canvas) {
        val t = fixText ?: return
        val x0 = inset - offset
        val w = fixLead - itemPad * 0.6f
        if (x0 + w < 0f) return
        val a = (contentAlpha * 255f * (if (stale) 0.45f else 1f)).toInt()
        pillPaint.color = HINT_RED
        pillPaint.alpha = (a * 0.14f).toInt()
        tmp.set(x0, candTop + rowH * 0.14f, x0 + w, candTop + rowH * 0.86f)
        canvas.drawRoundRect(tmp, rowH * 0.3f, rowH * 0.3f, pillPaint)
        pillPaint.alpha = 255
        val y = candTop + rowH * 0.5f + baseline(fixPaint)
        var x = x0 + itemPad * 0.6f
        for (k in t.indices) {
            val g = fixColors.getOrElse(k) { -1 }
            fixPaint.color = if (g >= 0) ERR_COLORS[g % ERR_COLORS.size] else HINT_RED
            fixPaint.alpha = a
            fixPaint.isUnderlineText = g >= 0
            canvas.drawText(t, k, k + 1, x, y, fixPaint)
            x += fixPaint.measureText(t, k, k + 1)
        }
        fixPaint.isUnderlineText = false
    }

    /** 圆圈里一个 ×。 */
    private fun drawClose(canvas: Canvas) {
        val cx = width - closeW / 2f
        val cy = candTop + rowH / 2f
        val r = iconU * 0.85f
        val d = r * 0.38f
        iconPaint.color = palette.icon
        iconPaint.alpha = (contentAlpha * 255f).toInt()
        canvas.drawCircle(cx, cy, r, iconPaint)
        canvas.drawLine(cx - d, cy - d, cx + d, cy + d, iconPaint)
        canvas.drawLine(cx + d, cy - d, cx - d, cy + d, iconPaint)
    }

    /** 词块行:一块一个词,点中的那块有一个淡色底;没被吃掉的拼音用浅色字显示。 */
    private fun drawChunks(canvas: Canvas) {
        canvas.save()
        canvas.clipRect(0f, stripH, width.toFloat(), candTop)
        val cy = stripH + chunkH / 2f
        val pad = itemPad * 0.55f
        val left = chunkOffset - inset
        val right = left + width
        for (i in chunkTexts.indices) {
            if (chunkXs[i + 1] < left) continue
            if (chunkXs[i] > right) break
            val x0 = inset + chunkXs[i] - chunkOffset
            val w = chunkXs[i + 1] - chunkXs[i] - itemPad * 0.25f
            if (i == chunkSel) {
                pillPaint.color = palette.accent
                pillPaint.alpha = 40
                tmp.set(x0, cy - chunkH * 0.44f, x0 + w, cy + chunkH * 0.44f)
                canvas.drawRoundRect(tmp, chunkH * 0.3f, chunkH * 0.3f, pillPaint)
            }
            chunkPaint.color = when {
                chunkRaw[i] -> palette.icon
                i == chunkSel -> palette.accent
                else -> palette.text
            }
            if (BarRows.read) {
                // 带读音的词块行:读音在上、字在下(和候选行一样);词块不拆开,每个字上方是自己的音节
                val yt = stripH + chunkH * 0.68f + chunkBase
                val yr = stripH + chunkH * 0.27f + readBase
                val rb = chunkRuby.getOrNull(i)
                if (rb != null) {
                    readPaint.color = if (i == chunkSel) palette.accent else palette.text   // 用正文色,声调符号才看得清
                    readPaint.alpha = 255
                    drawRuby(canvas, chunkTexts[i], rb, x0 + pad, yt, yr, chunkPaint)
                } else {
                    canvas.drawText(chunkTexts[i], x0 + pad, yt, chunkPaint)
                }
            } else {
                canvas.drawText(chunkTexts[i], x0 + pad, cy + chunkBase, chunkPaint)
            }
        }
        canvas.restore()
    }

    private fun drawCandidates(canvas: Canvas) {
        val left = offset - inset
        val right = left + width
        val hasReads = reads.isNotEmpty()
        // 有读音:读音在上、字在下,两行一起居中;没有读音和以前完全一样
        val y = candTop + rowH * (if (hasReads) 0.68f else 0.5f) + candBase
        val ry = candTop + rowH * 0.27f + readBase
        val a = (contentAlpha * 255f * (if (stale) 0.45f else 1f)).toInt()
        drawFixChip(canvas)
        for (i in items.indices) {
            if (xs[i + 1] < left) continue
            if (xs[i] > right) break
            val w = xs[i + 1] - xs[i]
            val first = i == 0
            candPaint.color = if (first) palette.accent else palette.text
            candPaint.alpha = a
            val r = if (hasReads) reads[i] else ""
            val rb = candRuby.getOrNull(i)
            if (rb != null) {
                // 每个字上方是它自己的音节;词不拆开
                readPaint.color = if (first) palette.accent else palette.icon
                readPaint.alpha = a
                drawRuby(canvas, items[i], rb, inset + xs[i] - offset + padNow(), y, ry, candPaint)
                continue
            }
            val tw = candPaint.measureText(items[i])
            canvas.drawText(items[i], inset + xs[i] - offset + (w - tw) / 2f, y, candPaint)
            if (r.isNotEmpty()) {
                readPaint.color = if (first) palette.accent else palette.icon
                readPaint.alpha = a
                val rw = readPaint.measureText(r)
                canvas.drawText(r, inset + xs[i] - offset + (w - rw) / 2f, ry, readPaint)
            }
        }
    }

    private fun drawToolbar(canvas: Canvas, alpha: Float) {
        val n = TOOLS
        val cellW = (width - 2 * inset) / n
        val cy = candTop + rowH / 2f
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
        if (y < candTop) return -1
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
