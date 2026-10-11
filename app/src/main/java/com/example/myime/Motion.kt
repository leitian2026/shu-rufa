package com.example.myime

import android.view.animation.Interpolator
import android.view.animation.PathInterpolator

/**
 * 全 App 唯一的动画参数。
 * 所有动画(模式切换、候选栏淡入淡出、按键高亮消退)都用同一个时长和同一条曲线,
 * 这样每个操作的"帧跨度"一致,不会有的快有的慢。
 *
 * 时长取 360ms,落在 300–450ms 区间内;曲线是 Material 标准缓动,两端都平缓。
 * 想整体调快/调慢,只改这里的 DURATION_MS。
 */
object Motion {
    const val DURATION_MS = 360L
    val interpolator: Interpolator = PathInterpolator(0.4f, 0f, 0.2f, 1f)
}
