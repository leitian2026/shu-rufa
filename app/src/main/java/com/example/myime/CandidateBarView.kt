package com.example.myime

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.OverScroller

/**
 * 候选栏:自绘、可横向滑动。
 * 候选内容每次按键都会更新,但"有候选 ↔ 无候选"之间才做淡入淡出,
 * 避免每个字母都触发一次动画。
 */
class CandidateBarView(
    context: Context,
    private val onPick: (Int) -> Unit,
) : View(context) {

    private var palette = Palette(context)
    private var items: List<String> = emptyList()
    private var xs = FloatArray(1)
    private var contentWidth = 0f

    private var contentAlpha = 0f
    private var targetVisible = false
    private var animator: ValueAnimator? = null

    private val itemPad = context.dpf(18f)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = context.spf(20f)
        textAlign = Paint.Align.LEFT
    }
    private val baseOffset: Float = run {
        val fm = paint.fontMetrics
        -(fm.ascent + fm.descent) / 2f
    }

    private val scroller = OverScroller(context)
    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            scroller.forceFinished(true)
            return true
        }

        override fun onScroll(
            e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float,
        ): Boolean {
            val nx = (scrollX + distanceX).coerceIn(0f, maxScroll())
            scrollTo(nx.toInt(), 0)
            return true
        }

        override fun onFling(
            e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float,
        ): Boolean {
            scroller.fling(scrollX, 0, (-velocityX).toInt(), 0, 0, maxScroll().toInt(), 0, 0)
            postInvalidateOnAnimation()
            return true
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            if (!targetVisible) return false
            val x = e.x + scrollX
            for (i in items.indices) {
                if (x >= xs[i] && x < xs[i + 1]) {
                    onPick(i)
                    return true
                }
            }
            return false
        }
    })

    private fun maxScroll(): Float = (contentWidth - width).coerceAtLeast(0f)

    fun refreshTheme() {
        palette = Palette(context)
        invalidate()
    }

    fun setCandidates(list: List<String>, animate: Boolean = true) {
        if (list.isEmpty()) {
            if (!targetVisible && items.isEmpty()) return
            targetVisible = false
            if (animate && contentAlpha > 0f) {
                fadeTo(0f) {
                    items = emptyList()
                    relayout()
                    invalidate()
                }
            } else {
                animator?.cancel()
                contentAlpha = 0f
                items = emptyList()
                relayout()
                invalidate()
            }
            return
        }

        items = list
        relayout()
        scroller.forceFinished(true)
        scrollTo(0, 0)
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

    private fun fadeTo(target: Float, onEnd: (() -> Unit)?) {
        animator?.cancel()
        val a = ValueAnimator.ofFloat(contentAlpha, target)
        a.duration = Motion.DURATION_MS
        a.interpolator = Motion.interpolator
        a.addUpdateListener {
            contentAlpha = it.animatedValue as Float
            invalidate()
        }
        if (onEnd != null) {
            a.addListener(object : AnimatorListenerAdapter() {
                private var canceled = false
                override fun onAnimationCancel(animation: Animator) {
                    canceled = true
                }

                override fun onAnimationEnd(animation: Animator) {
                    if (!canceled) onEnd()
                }
            })
        }
        animator = a
        a.start()
    }

    private fun relayout() {
        xs = FloatArray(items.size + 1)
        var x = 0f
        for (i in items.indices) {
            xs[i] = x
            x += paint.measureText(items[i]) + itemPad * 2
        }
        xs[items.size] = x
        contentWidth = x
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(palette.bar)
        if (items.isEmpty() || contentAlpha <= 0f) return
        val left = scrollX.toFloat()
        val right = left + width
        val y = height / 2f + baseOffset
        val a = (contentAlpha * 255f).toInt()
        for (i in items.indices) {
            if (xs[i + 1] < left) continue
            if (xs[i] > right) break
            paint.color = if (i == 0) palette.accent else palette.text
            paint.alpha = a
            canvas.drawText(items[i], xs[i] + itemPad, y, paint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean = gestures.onTouchEvent(event)

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollTo(scroller.currX, 0)
            postInvalidateOnAnimation()
        }
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        super.onDetachedFromWindow()
    }
}
