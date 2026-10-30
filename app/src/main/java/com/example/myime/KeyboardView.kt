package com.example.myime

import android.content.Context
import android.graphics.Canvas
import android.graphics.CornerPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
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
    // 键帽右上角的小字,三档:数字(大一号 + 粗体)、@ # $ % &(中粗)、其余符号(最粗)
    private val hintNumPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.RIGHT }
    private val hintMidPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.RIGHT }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.RIGHT }       // 键帽上方标的数字/符号(长按打出),黑色(跟随主题的正文色),不用浅色
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
    private var hintTopPad = 0f      // 小字离键顶 / 离键右边的距离:尺寸变了才重算,不在每帧每个键里算
    private var hintRightPad = 0f
    private var symW = 0f            // 「符」字的宽度
    private var iconStroke = 0f

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

    // 长按气泡:按下带单字符备选的键时弹出,时长 = HOLD_MS,正好在长按触发、字符上屏的那一刻结束;
    // 只是画一层,不改 HOLD_MS,所以长按出字的速度和以前完全一样。提前松手(当普通键打)就立刻撤掉
    private var bubbleKey = -1
    private var bubbleText = ""
    private var bubbleStart = 0L
    private val bubbleDirty = android.graphics.Rect()   // 气泡会到的最大范围(视图坐标,顶排会是负的):只重绘这一小块,并保证擦得干净

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
        hintMidPaint.textSize = hintPaint.textSize
        hintNumPaint.textSize = context.spf(if (wide) 16f else 13f)   // 数字比符号大一号
        hintNumPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        hintNumPaint.isFakeBoldText = false
        // 中粗:有字重支持(Android 9+)用 600,没有就用普通字体加一点描边
        if (Build.VERSION.SDK_INT >= 28) {
            hintMidPaint.typeface = Typeface.create(Typeface.DEFAULT, 600, false)
            hintMidPaint.isFakeBoldText = false
        } else {
            hintMidPaint.typeface = Typeface.DEFAULT
            hintMidPaint.isFakeBoldText = true
        }
        // 最粗:粗体再加一层
        hintPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        hintPaint.isFakeBoldText = true
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
        symW = richPaint.measureText("符")
        hintTopPad = context.dpf(2f)
        hintRightPad = context.dpf(if (wide) 6f else 4f)
        iconStroke = context.dpf(1.8f)
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
        // 父布局关了裁剪(为了让气泡能盖住候选栏),drawColor 会铺满无限大,把别的视图(工具栏图标)全盖住:只填自己的范围
        canvas.save()
        canvas.clipRect(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawColor(palette.keyboardBackground)
        canvas.restore()
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
        // 气泡动画跟随屏幕刷新率(60/90/120Hz):每个 vsync 重绘一次,只重绘气泡那一小块
        if (drawHoldBubble(canvas)) {
            postInvalidateOnAnimation(bubbleDirty.left, bubbleDirty.top, bubbleDirty.right, bubbleDirty.bottom)
        }
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

    /** 长按气泡:前 55% 从键上弹大,后面渐隐,到 HOLD_MS 时恰好消失(此刻字符已上屏)。返回 true = 还要继续画。 */
    private fun drawHoldBubble(canvas: Canvas): Boolean {
        val i = bubbleKey
        if (i < 0 || i >= rects.size) return false
        val t = (SystemClock.uptimeMillis() - bubbleStart).toFloat() / HOLD_MS
        if (t >= 1f) {
            bubbleKey = -1
            return false
        }
        val r = rects[i]
        val popT = (t / 0.55f).coerceIn(0f, 1f)
        val ease = 1f - (1f - popT) * (1f - popT)               // 先快后慢地弹出
        val scale = 0.5f + 0.5f * ease
        val alpha = if (t < 0.55f) 1f else 1f - (t - 0.55f) / 0.45f
        val bw = r.width() * 0.9f * scale
        val bh = r.height() * 0.9f * scale
        val cx = r.centerX().coerceIn(bw / 2f, maxOf(bw / 2f, width - bw / 2f))
        val bottom = r.top - r.height() * BUBBLE_GAP * ease          // 间距也按键高算,键盘高矮变了比例不变
        val top = bottom - bh   // 顶排的键:气泡直接画到键盘视图外面,盖住上面的候选栏/标题(父布局已关掉裁剪)
        popRect.set(cx - bw / 2f, top, cx + bw / 2f, top + bh)
        val a = (alpha * 255).toInt().coerceIn(0, 255)
        val oldFill = fill.color
        val oldText = letterPaint.color
        fill.color = palette.accent
        fill.alpha = a
        val cr = minOf(bw, bh) * BUBBLE_CORNER
        canvas.drawRoundRect(popRect, cr, cr, fill)
        letterPaint.color = palette.onAccent
        letterPaint.alpha = a
        val oldSize = letterPaint.textSize
        letterPaint.textSize = minOf(bw, bh) * BUBBLE_TEXT   // 字号跟气泡大小走,键盘调大调小时和气泡保持同一比例
        canvas.drawText(bubbleText, popRect.centerX(), popRect.centerY() + baseline(letterPaint), letterPaint)
        letterPaint.textSize = oldSize
        fill.color = oldFill
        letterPaint.color = oldText
        return true
    }

    /** 撤掉气泡并把它占过的那一块擦掉(气泡在键盘视图外面的部分,普通 invalidate() 擦不到)。 */
    private fun stopBubble() {
        if (bubbleKey >= 0) {
            bubbleKey = -1
            invalidate(bubbleDirty)
        }
    }

    private fun prepareBubble(i: Int) {
        val r = rects[i]
        val m = r.height() * 0.05f
        val bw = r.width() * 0.9f
        val bh = r.height() * 0.9f
        val cx = r.centerX().coerceIn(bw / 2f, maxOf(bw / 2f, width - bw / 2f))
        bubbleDirty.set(
            (cx - bw / 2f - m).toInt(),
            (r.top - r.height() * BUBBLE_GAP - bh - m).toInt(),
            (cx + bw / 2f + m).toInt() + 1,
            r.top.toInt() + 1,
        )
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
        if (hint != null) {
            // 小字在键帽右上角,字母保持居中原位
            val hp = when {
                hint[0].isDigit() -> hintNumPaint
                hint[0] in "@#$%&" -> hintMidPaint
                else -> hintPaint
            }
            hp.color = if (hint[0].isDigit()) palette.hintText else palette.hintSym   // 数字稍浅,符号更深
            val hs = hp.textSize
            val hy = r.top + hintTopPad + hs * 0.72f
            canvas.drawText(hint, r.right - hintRightPad, hy, hp)   // 右上角
        }
        if (label == "符") {
            // 和旁边「中/英」一样大
            richPaint.isFakeBoldText = false
            richPaint.color = color
            canvas.drawText(label, cx - symW / 2f, cy + richBase, richPaint)
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
        canvas.drawText(label, cx, cy + base, paint)
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
        iconPaint.style = Paint.Style.STROKE
        iconPaint.strokeWidth = iconStroke
        iconPaint.color = color
        iconPaint.alpha = 255
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
        stopBubble()
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
        Haptics.tap(this@KeyboardView, HapticFeedbackConstants.LONG_PRESS)
        stopBubble()   // 气泡到点了,和字符上屏同一刻收掉
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
            Haptics.tap(this@KeyboardView, HapticFeedbackConstants.CLOCK_TICK)
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
        stopBubble()
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
            Haptics.tap(this@KeyboardView, HapticFeedbackConstants.KEYBOARD_TAP)
            holdPending.put(id, spec)
            holdPointer = id
            holdDownX = x
            val only = holdOpts.singleOrNull()
            if (only != null) {
                prepareBubble(i)
                bubbleKey = i
                bubbleText = only
                bubbleStart = SystemClock.uptimeMillis()
            }
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
        Haptics.tap(this@KeyboardView, HapticFeedbackConstants.KEYBOARD_TAP)
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
            stopBubble()
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
        const val BUBBLE_GAP = 0.09f      // 气泡离键帽的间距 / 键高
        const val BUBBLE_CORNER = 0.25f   // 圆角 / 气泡短边
        const val BUBBLE_TEXT = 0.66f     // 字号 / 气泡短边
    }
}
