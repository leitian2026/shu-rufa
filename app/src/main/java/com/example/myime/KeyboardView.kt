package com.example.myime

import android.content.Context
import android.graphics.Canvas
import android.graphics.CornerPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.SparseArray
import android.util.SparseIntArray
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View

/**
 * 整个键盘只有这一个 View,所有按键在同一块 Canvas 上绘制。
 *
 * 流畅度相关的设计:
 *  - onDraw 里不分配对象(Paint、Path、RectF、字体度量都提前准备好)
 *  - 按键高亮的消退用"帧时间"计算:每帧只读一次 drawingTime,所有按键同步推进,
 *    并用 postInvalidateOnAnimation 对齐 vsync
 *  - 模式切换只动 alpha / translationY(不触发重新布局),并启用硬件图层
 *  - 所有动画共用 Motion 里的同一个时长和曲线
 */
class KeyboardView(context: Context, private val listener: Listener) : View(context) {

    interface Listener {
        fun onKey(spec: KeySpec)
    }

    private var palette = Palette(context)

    private var layout: Layout? = null
    private var keys: Array<KeySpec> = emptyArray()
    private var rects: Array<RectF> = emptyArray()
    private var isDown = BooleanArray(0)
    private var releasedAt = LongArray(0)
    private var subIndex = IntArray(0)
    private val pointerKey = SparseIntArray()

    // 尺寸(refresh() 里按是否宽屏重新计算)
    private var padX = 0f
    private var padTop = 0f
    private var padBottom = 0f
    private var radius = 0f
    private var shadowH = 0f
    private var slideY = 0f
    private var hitSlopX = 0f
    private var hitSlopY = 0f
    private var fixedHeight = 0
    private var iconW = 0f
    private var iconH = 0f

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pressPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val letterPaint = centered()
    private val funcPaint = centered()
    private val strokePaint = centered()
    private val multiPaint = centered()
    private val hintPaint = centered()       // 键帽上方标的数字/符号(长按打出),黑色(跟随主题的正文色),不用浅色
    private val richPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.LEFT }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private var letterBase = 0f
    private var funcBase = 0f
    private var strokeBase = 0f
    private var multiBase = 0f
    private var hintBase = 0f
    private var richBase = 0f
    private var richZ = 0f
    private var richS = 0f
    private var richE = 0f

    private val shadowRect = RectF()
    private val segRect = RectF()
    private val path = Path()

    private val uiHandler = Handler(Looper.getMainLooper())
    private var repeatSpec: KeySpec? = null
    private var repeatPointer = -1
    private val repeatRunnable = object : Runnable {
        override fun run() {
            val s = repeatSpec ?: return
            listener.onKey(s)
            uiHandler.postDelayed(this, REPEAT_INTERVAL_MS)
        }
    }

    // 长按弹出备选:有 hold 的键按下时先不上屏,松手(没长按)才当普通键打出;长按够久弹出备选,滑到哪个松手就打哪个
    private val holdPending = SparseArray<KeySpec>()   // 指针 id -> 等着松手再上屏的键
    private var holdPointer = -1
    private var holdKey = -1
    private var holdOptions: List<String>? = null       // 非空 = 备选条正显示
    private var holdSel = 0
    private var holdDownX = 0f
    private var holdLeft = 0f
    private var holdCell = 0f
    private var holdTop = 0f
    private var holdFlip = false                        // 键在右半边:备选条向左展开,第一个备选在最右(贴着键)
    private val popRect = RectF()
    private val holdRunnable = Runnable { startHold() }

    init {
        applyMetrics()
    }

    private fun centered() = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }

    private fun baseline(p: Paint): Float {
        val fm = p.fontMetrics
        return -(fm.ascent + fm.descent) / 2f
    }

    private fun applyMetrics() {
        val wide = context.isWide()
        padX = context.dpf(4f)   // 平板也只留 4dp,左右占满
        padTop = context.dpf(5f)
        padBottom = context.dpf(if (wide) 12f else 6f)
        radius = context.dpf(if (wide) 10f else 8f)
        shadowH = context.dpf(2f)
        slideY = context.dpf(10f)
        fixedHeight = context.keyboardHeightPx()
        iconW = context.dpf(if (wide) 13f else 11f)
        iconH = context.dpf(if (wide) 9f else 8f)

        letterPaint.textSize = context.spf(if (wide) 26f else 21f)
        funcPaint.textSize = context.spf(if (wide) 17f else 14f)
        strokePaint.textSize = context.spf(if (wide) 26f else 21f)   // 和数字键一样大
        multiPaint.textSize = context.spf(if (wide) 20f else 17f)
        hintPaint.textSize = context.spf(if (wide) 14f else 11f)
        hintPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        hintPaint.isFakeBoldText = true   // 最粗:在系统粗体上再加粗一层
        richPaint.textSize = context.spf(if (wide) 21f else 16f)
        iconPaint.strokeWidth = context.dpf(1.8f)
        iconPaint.pathEffect = CornerPathEffect(context.dpf(1.6f))

        letterBase = baseline(letterPaint)
        funcBase = baseline(funcPaint)
        strokeBase = baseline(strokePaint)
        multiBase = baseline(multiPaint)
        hintBase = baseline(hintPaint)
        richBase = baseline(richPaint)
        richPaint.isFakeBoldText = false
        richZ = richPaint.measureText("中")
        richS = richPaint.measureText("/")
        richE = richPaint.measureText("英")
    }

    /** 每次开始输入时调用:跟随深色模式、屏幕宽度变化。 */
    private var uiSig = ""

    fun refresh() {
        val sig = context.uiSignature()
        if (sig == uiSig && width > 0) return   // 深色模式/屏幕/字号/高度都没变:不重算
        uiSig = sig
        palette = Palette(context)
        applyMetrics()
        layoutKeys()
        requestLayout()
        invalidate()
    }

    // ---------------------------------------------------------------- 布局

    fun setLayout(l: Layout, animate: Boolean) {
        // 只是标签/高亮变了(大写键、分词键等),几何不变:保留按压状态,不重算
        if (!animate && sameShape(l)) {
            layout = l
            keys = l.keys.toTypedArray()
            invalidate()
            return
        }

        stopRepeat()
        clearHold()
        layout = l
        keys = l.keys.toTypedArray()
        rects = Array(keys.size) { RectF() }
        isDown = BooleanArray(keys.size)
        releasedAt = LongArray(keys.size) { -1L }
        subIndex = IntArray(keys.size)
        pointerKey.clear()
        layoutKeys()
        invalidate()
    }

    private fun sameShape(l: Layout): Boolean {
        val old = layout ?: return false
        if (old.cols != l.cols || old.rows != l.rows || old.gapX != l.gapX || old.gapY != l.gapY || old.padX != l.padX) return false
        if (keys.size != l.keys.size) return false
        for (i in keys.indices) {
            val a = keys[i]
            val b = l.keys[i]
            if (a.x != b.x || a.y != b.y || a.w != b.w || a.h != b.h) return false
        }
        return true
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), fixedHeight)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        layoutKeys()
    }

    private fun curPadX(): Float = layout?.padX?.let { context.dpf(it) } ?: padX

    private fun layoutKeys() {
        val l = layout ?: return
        if (width == 0 || height == 0 || rects.size != keys.size) return
        val gx = context.dpf(l.gapX)
        val gy = context.dpf(l.gapY)
        val padX = curPadX()
        val pitchX = (width - 2 * padX + gx) / l.cols
        val pitchY = (height - padTop - padBottom + gy) / l.rows
        for (i in keys.indices) {
            val k = keys[i]
            val left = padX + k.x * pitchX
            val top = padTop + k.y * pitchY
            rects[i].set(left, top, left + k.w * pitchX - gx, top + k.h * pitchY - gy)
        }
        hitSlopX = gx / 2f
        hitSlopY = gy / 2f
    }

    // ---------------------------------------------------------------- 绘制

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(palette.keyboardBackground)
        val now = drawingTime
        var fading = false
        for (i in keys.indices) {
            val k = keys[i]
            val r = rects[i]

            val face: Int
            val shadow: Int
            val onFace: Int
            if (k.value == "back") {
                face = palette.backKey
                shadow = palette.backShadow
                onFace = palette.text
            } else if (k.primary || k.active) {
                face = palette.accent
                shadow = palette.accentShadow
                onFace = palette.onAccent
            } else if (k.function) {
                face = palette.functionKey
                shadow = palette.functionShadow
                onFace = palette.text
            } else {
                face = palette.key
                shadow = palette.keyShadow
                onFace = palette.text
            }

            shadowRect.set(r.left, r.top + shadowH, r.right, r.bottom + shadowH)
            fill.color = shadow
            canvas.drawRoundRect(shadowRect, radius, radius, fill)
            fill.color = face
            canvas.drawRoundRect(r, radius, radius, fill)

            val a = pressAmount(i, now)
            if (a > 0f) {
                pressPaint.color = palette.pressTint
                pressPaint.alpha = (a * palette.pressAlpha).toInt()
                val m = k.multi
                if (m != null) {
                    val seg = r.height() / m.size
                    segRect.set(r.left, r.top + seg * subIndex[i], r.right, r.top + seg * (subIndex[i] + 1))
                    canvas.drawRoundRect(segRect, radius, radius, pressPaint)
                } else {
                    canvas.drawRoundRect(r, radius, radius, pressPaint)
                }
                if (!isDown[i]) fading = true
            }
            drawLabel(canvas, k, r, onFace)
        }
        drawHoldPopup(canvas)
        if (fading) postInvalidateOnAnimation()
    }

    private fun drawHoldPopup(canvas: Canvas) {
        val opts = holdOptions ?: return
        val n = opts.size
        val h = if (holdKey in rects.indices) rects[holdKey].height() else return
        popRect.set(holdLeft, holdTop + shadowH, holdLeft + holdCell * n, holdTop + h + shadowH)
        fill.color = palette.functionShadow
        canvas.drawRoundRect(popRect, radius, radius, fill)
        popRect.set(holdLeft, holdTop, holdLeft + holdCell * n, holdTop + h)
        fill.color = palette.functionKey
        canvas.drawRoundRect(popRect, radius, radius, fill)
        for (j in 0 until n) {
            val k = if (holdFlip) n - 1 - j else j
            val cx = holdLeft + holdCell * (j + 0.5f)
            if (k == holdSel) {
                popRect.set(holdLeft + holdCell * j, holdTop, holdLeft + holdCell * (j + 1), holdTop + h)
                fill.color = palette.accent
                canvas.drawRoundRect(popRect, radius, radius, fill)
                letterPaint.color = palette.onAccent
            } else {
                letterPaint.color = palette.text
            }
            canvas.drawText(opts[k], cx, holdTop + h / 2f + letterBase, letterPaint)
        }
    }

    /** 按下时高亮,松手立刻恢复,没有渐隐动画。 */
    private fun pressAmount(i: Int, now: Long): Float = if (isDown[i]) 1f else 0f

    private fun drawLabel(canvas: Canvas, k: KeySpec, r: RectF, color: Int) {
        val cx = r.centerX()
        val cy = r.centerY()

        val multi = k.multi
        if (multi != null) {
            multiPaint.color = color
            val seg = r.height() / multi.size
            for (j in multi.indices) {
                canvas.drawText(multi[j].label, cx, r.top + seg * (j + 0.5f) + multiBase, multiPaint)
            }
            return
        }
        if (k.icon != Icon.NONE) {
            drawIcon(canvas, k.icon, cx, cy, color)
            return
        }
        if (k.rich != 0) {
            drawRich(canvas, k.rich, cx, cy + richBase)
            return
        }
        val label = k.label
        if (label.isEmpty()) return
        val hint = k.hold?.firstOrNull()
        var letterY = Float.NaN
        if (hint != null) {
            // 小字贴在键帽顶部;字母的基线按「小字底部 + 间隙 + 字母最高处(b d f h k l 的竖笔)」算出来,保证不重叠
            hintPaint.color = color
            val hs = hintPaint.textSize
            val hy = r.top + context.dpf(2f) + hs * 0.72f
            canvas.drawText(hint, cx, hy, hintPaint)
            val ls = letterPaint.textSize
            val need = hy + hs * 0.2f + context.dpf(4f) + ls * 0.76f
            val maxY = r.bottom - context.dpf(1.5f) - ls * 0.22f   // 别让 g j p q y 的下伸部分出键
            letterY = minOf(need, maxY)
        }
        if (label == "符") {
            // 和旁边「中/英」一样大
            richPaint.isFakeBoldText = false
            richPaint.color = color
            canvas.drawText(label, cx - richPaint.measureText(label) / 2f, cy + richBase, richPaint)
            return
        }

        val paint: Paint
        val base: Float
        if (k.type == ActionType.STROKE && label != "*") {
            paint = strokePaint
            base = strokeBase
        } else if (label == "*") {
            paint = letterPaint
            base = letterBase
        } else if (k.function || label.length > 1) {
            paint = funcPaint
            base = funcBase
        } else {
            paint = letterPaint
            base = letterBase
        }
        paint.color = color
        val y = if (letterY.isNaN()) cy + base else maxOf(cy + base, letterY)
        canvas.drawText(label, cx, y, paint)
    }

    /** 「中/英」:当前语言加粗,另一个变淡。 */
    private fun drawRich(canvas: Canvas, which: Int, cx: Float, baseY: Float) {
        val wZ = richZ
        val wS = richS
        val wE = richE
        val x = cx - (wZ + wS + wE) / 2f

        richPaint.isFakeBoldText = which == 1
        richPaint.color = if (which == 1) palette.text else palette.textFaint
        canvas.drawText("中", x, baseY, richPaint)

        richPaint.isFakeBoldText = false
        richPaint.color = palette.textFaint
        canvas.drawText("/", x + wZ, baseY, richPaint)

        richPaint.isFakeBoldText = which == 2
        richPaint.color = if (which == 2) palette.text else palette.textFaint
        canvas.drawText("英", x + wZ + wS, baseY, richPaint)
        richPaint.isFakeBoldText = false
    }

    private fun drawIcon(canvas: Canvas, kind: Int, cx: Float, cy: Float, color: Int) {
        iconPaint.color = color
        val w = iconW
        val h = iconH
        path.reset()
        when (kind) {
            Icon.BACKSPACE -> {
                path.moveTo(cx - w, cy)
                path.lineTo(cx - w * 0.35f, cy - h)
                path.lineTo(cx + w, cy - h)
                path.lineTo(cx + w, cy + h)
                path.lineTo(cx - w * 0.35f, cy + h)
                path.close()
                canvas.drawPath(path, iconPaint)
                canvas.drawLine(cx - w * 0.1f, cy - h * 0.4f, cx + w * 0.55f, cy + h * 0.4f, iconPaint)
                canvas.drawLine(cx + w * 0.55f, cy - h * 0.4f, cx - w * 0.1f, cy + h * 0.4f, iconPaint)
            }
            Icon.ENTER -> {
                path.moveTo(cx + w * 0.8f, cy - h * 0.7f)
                path.lineTo(cx + w * 0.8f, cy + h * 0.2f)
                path.lineTo(cx - w * 0.8f, cy + h * 0.2f)
                canvas.drawPath(path, iconPaint)
                canvas.drawLine(cx - w * 0.8f, cy + h * 0.2f, cx - w * 0.8f + h * 0.55f, cy + h * 0.2f - h * 0.55f, iconPaint)
                canvas.drawLine(cx - w * 0.8f, cy + h * 0.2f, cx - w * 0.8f + h * 0.55f, cy + h * 0.2f + h * 0.55f, iconPaint)
            }
            Icon.SPACE -> {
                val sw = w * 1.4f
                path.moveTo(cx - sw, cy - h * 0.35f)
                path.lineTo(cx - sw, cy + h * 0.35f)
                path.lineTo(cx + sw, cy + h * 0.35f)
                path.lineTo(cx + sw, cy - h * 0.35f)
                canvas.drawPath(path, iconPaint)
            }
            Icon.SHIFT, Icon.SHIFT_ON, Icon.SHIFT_LOCK -> {
                val lock = kind == Icon.SHIFT_LOCK
                val oy = if (lock) -h * 0.2f else 0f
                val aw = w * 0.85f
                val ah = h * 0.95f
                val sw = aw * 0.42f
                path.moveTo(cx, cy + oy - ah)
                path.lineTo(cx + aw, cy + oy)
                path.lineTo(cx + sw, cy + oy)
                path.lineTo(cx + sw, cy + oy + ah * 0.85f)
                path.lineTo(cx - sw, cy + oy + ah * 0.85f)
                path.lineTo(cx - sw, cy + oy)
                path.lineTo(cx - aw, cy + oy)
                path.close()
                if (kind != Icon.SHIFT) iconPaint.style = Paint.Style.FILL_AND_STROKE
                canvas.drawPath(path, iconPaint)
                iconPaint.style = Paint.Style.STROKE
                if (lock) {
                    canvas.drawLine(cx - sw, cy + oy + ah * 1.3f, cx + sw, cy + oy + ah * 1.3f, iconPaint)
                }
            }
            Icon.UP -> {
                path.moveTo(cx - w * 0.55f, cy + h * 0.3f)
                path.lineTo(cx, cy - h * 0.35f)
                path.lineTo(cx + w * 0.55f, cy + h * 0.3f)
                canvas.drawPath(path, iconPaint)
            }
            Icon.DOWN -> {
                path.moveTo(cx - w * 0.55f, cy - h * 0.3f)
                path.lineTo(cx, cy + h * 0.35f)
                path.lineTo(cx + w * 0.55f, cy - h * 0.3f)
                canvas.drawPath(path, iconPaint)
            }
            Icon.LEFT -> {
                path.moveTo(cx + h * 0.3f, cy - h * 0.75f)
                path.lineTo(cx - h * 0.4f, cy)
                path.lineTo(cx + h * 0.3f, cy + h * 0.75f)
                canvas.drawPath(path, iconPaint)
            }
            Icon.BACK -> {
                val bw = w * 0.8f
                val bh = h * 0.85f
                path.moveTo(cx + bw, cy)
                path.lineTo(cx - bw, cy)
                path.moveTo(cx - bw + bh, cy - bh)
                path.lineTo(cx - bw, cy)
                path.lineTo(cx - bw + bh, cy + bh)
                canvas.drawPath(path, iconPaint)
            }
            Icon.RIGHT -> {
                path.moveTo(cx - h * 0.3f, cy - h * 0.75f)
                path.lineTo(cx + h * 0.4f, cy)
                path.lineTo(cx - h * 0.3f, cy + h * 0.75f)
                canvas.drawPath(path, iconPaint)
            }
        }
    }

    // ---------------------------------------------------------------- 触摸

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val idx = e.actionIndex
                pointerDown(e.getPointerId(idx), e.getX(idx), e.getY(idx))
            }
            MotionEvent.ACTION_MOVE -> {
                if (holdOptions != null) {
                    val idx = e.findPointerIndex(holdPointer)
                    if (idx >= 0) moveHold(e.getX(idx))
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                pointerUp(e.getPointerId(e.actionIndex))
            }
            MotionEvent.ACTION_CANCEL -> releaseAll()
        }
        return true
    }

    private fun hit(x0: Float, y0: Float): Int {
        val x = x0.coerceIn(curPadX(), width - curPadX())
        val y = y0.coerceIn(padTop, height - padBottom)
        for (i in rects.indices) {
            val r = rects[i]
            if (x >= r.left - hitSlopX && x <= r.right + hitSlopX &&
                y >= r.top - hitSlopY && y <= r.bottom + hitSlopY
            ) return i
        }
        return -1
    }

    /** 又按下了别的键:前一个还在等松手的字母立刻按普通键打出,保证先后顺序不乱(备选条显示时不动)。 */
    private fun flushPendingHold() {
        if (holdOptions != null || holdPending.size() == 0) return
        uiHandler.removeCallbacks(holdRunnable)
        val list = ArrayList<KeySpec>(holdPending.size())
        for (j in 0 until holdPending.size()) list.add(holdPending.valueAt(j))
        holdPending.clear()
        holdPointer = -1
        for (sp in list) listener.onKey(sp)
    }

    private fun startHold() {
        val id = holdPointer
        val spec = holdPending.get(id) ?: return
        val opts = spec.hold ?: return
        val i = pointerKey.get(id, -1)
        if (i < 0 || i >= rects.size) return
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        if (opts.size == 1) {
            // 只有一个备选:长按直接打出来,松手不再重复
            holdPending.remove(id)
            holdPointer = -1
            val alt = opts[0]
            listener.onKey(KeySpec(alt, ActionType.CHAR, value = alt))
            invalidate()
            return
        }
        val r = rects[i]
        val n = opts.size
        holdKey = i
        holdOptions = opts
        holdSel = 0
        holdCell = r.width()
        holdFlip = r.centerX() > width / 2f
        var left = if (holdFlip) r.right - holdCell * n else r.left
        left = left.coerceIn(0f, maxOf(0f, width - holdCell * n))
        holdLeft = left
        holdTop = maxOf(0f, r.top - r.height() - context.dpf(6f))
        invalidate()
    }

    private fun moveHold(x: Float) {
        val opts = holdOptions ?: return
        if (kotlin.math.abs(x - holdDownX) < context.dpf(6f)) {
            if (holdSel != 0) {
                holdSel = 0
                invalidate()
            }
            return
        }
        val n = opts.size
        val j = ((x - holdLeft) / holdCell).toInt().coerceIn(0, n - 1)
        val k = if (holdFlip) n - 1 - j else j
        if (k != holdSel) {
            holdSel = k
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            invalidate()
        }
    }

    private fun clearHold() {
        uiHandler.removeCallbacks(holdRunnable)
        holdPending.clear()
        holdPointer = -1
        holdKey = -1
        holdOptions = null
        holdSel = 0
    }

    private fun pointerDown(id: Int, x: Float, y: Float) {
        val i = hit(x, y)
        if (i < 0) return
        flushPendingHold()
        var spec = keys[i]
        val holdOpts = spec.hold
        if (holdOpts != null && spec.multi == null) {
            isDown[i] = true
            releasedAt[i] = -1L
            pointerKey.put(id, i)
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            holdPending.put(id, spec)
            holdPointer = id
            holdDownX = x
            uiHandler.postDelayed(holdRunnable, HOLD_MS)
            invalidate()
            return
        }
        val multi = spec.multi
        if (multi != null) {
            val r = rects[i]
            val idx = ((y - r.top) / r.height() * multi.size).toInt().coerceIn(0, multi.size - 1)
            subIndex[i] = idx
            spec = multi[idx]
        }
        isDown[i] = true
        releasedAt[i] = -1L
        pointerKey.put(id, i)
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        listener.onKey(spec) // 可能触发 setLayout,会重置上面的状态
        // onKey 可能触发 setLayout 把按压状态清空;这时手指已不在跟踪里,抬起时收不到停止信号,
        // 连删会停不下来(把打的字全删光),所以只在仍被跟踪时才启动连发
        if (spec.repeatable && pointerKey.get(id, -1) >= 0) startRepeat(spec, id)
        invalidate()
    }

    private fun pointerUp(id: Int) {
        if (repeatPointer == id) stopRepeat() // 先停连发,不能被下面的提前 return 跳过
        // 带备选的键:松手时才上屏。备选条开着 = 打选中的那个;没长按 = 打键本身
        var fire: KeySpec? = null
        val pend = holdPending.get(id)
        if (pend != null) {
            uiHandler.removeCallbacks(holdRunnable)
            val opts = holdOptions
            fire = if (opts != null) {
                val alt = opts[holdSel.coerceIn(0, opts.size - 1)]
                KeySpec(alt, ActionType.CHAR, value = alt)
            } else {
                pend
            }
            holdPending.remove(id)
            holdOptions = null
            holdKey = -1
            holdPointer = -1
            holdSel = 0
        }
        val i = pointerKey.get(id, -1)
        if (i < 0) {
            fire?.let { listener.onKey(it) }
            return
        }
        pointerKey.delete(id)
        if (i < isDown.size) {
            isDown[i] = false
            releasedAt[i] = SystemClock.uptimeMillis()
        }
        if (repeatPointer == id) stopRepeat()
        postInvalidateOnAnimation()
        fire?.let { listener.onKey(it) }   // 放最后:onKey 可能触发 setLayout,会重置上面的按压状态
    }

    private fun releaseAll() {
        clearHold()
        val now = SystemClock.uptimeMillis()
        for (i in isDown.indices) {
            if (isDown[i]) {
                isDown[i] = false
                releasedAt[i] = now
            }
        }
        pointerKey.clear()
        stopRepeat()
        postInvalidateOnAnimation()
    }

    private fun startRepeat(spec: KeySpec, pointerId: Int) {
        stopRepeat()
        repeatSpec = spec
        repeatPointer = pointerId
        uiHandler.postDelayed(repeatRunnable, REPEAT_DELAY_MS)
    }

    private fun stopRepeat() {
        uiHandler.removeCallbacks(repeatRunnable)
        repeatSpec = null
        repeatPointer = -1
    }

    override fun onDetachedFromWindow() {
        stopRepeat()
        clearHold()
        super.onDetachedFromWindow()
    }

    private companion object {
        const val REPEAT_DELAY_MS = 420L
        const val REPEAT_INTERVAL_MS = 55L
        const val HOLD_MS = 330L
    }
}
