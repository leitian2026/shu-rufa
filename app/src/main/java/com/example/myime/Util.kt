package com.example.myime

import android.content.Context
import android.util.TypedValue

fun Context.dpf(v: Float): Float =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

fun Context.dpi(v: Float): Int = dpf(v).toInt()

fun Context.spf(v: Float): Float =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

/** 宽屏(平板/横屏):键盘用更大的边距和按键,笔画页左侧显示数字区。 */
fun Context.isWide(): Boolean = resources.configuration.screenWidthDp >= 600

/** 键盘区(含各个面板)的统一高度,切换页面时高度不变。可在「键盘调节」里改。 */
fun Context.keyboardHeightPx(): Int = dpi(KbSize.heightDp(this))

/**
 * 用户调节过的键盘尺寸/位置,存在 SharedPreferences 里(只有几个数字)。
 *  - heightDp:键盘区高度(dp),<0 表示用默认值
 *  - widthFrac:键盘宽度占屏幕宽度的比例
 *  - leftFrac:左边距占屏幕宽度的比例
 *  - bottomDp:离屏幕底部的距离(dp),>0 就是悬浮在输入框上方
 */
object KbSize {
    const val MIN_W = 0.4f
    const val MIN_H = 150f
    private const val FILE = "kbsize"

    var heightDp = -1f
    var widthFrac = 1f
    var leftFrac = 0f
    var bottomDp = 0f

    fun defaultHeightDp(c: Context): Float = if (c.isWide()) 279f else 224f

    fun heightDp(c: Context): Float =
        (if (heightDp < 0f) defaultHeightDp(c) else heightDp).coerceIn(MIN_H, maxHeightDp(c))

    fun maxHeightDp(c: Context): Float =
        minOf(460f, c.resources.configuration.screenHeightDp * 0.6f).coerceAtLeast(MIN_H)

    fun maxBottomDp(c: Context): Float =
        (c.resources.configuration.screenHeightDp * 0.35f).coerceAtLeast(0f)

    fun load(c: Context) {
        val p = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        heightDp = p.getFloat("h", -1f)
        widthFrac = p.getFloat("w", 1f).coerceIn(MIN_W, 1f)
        leftFrac = p.getFloat("l", 0f).coerceIn(0f, 1f - widthFrac)
        bottomDp = p.getFloat("b", 0f).coerceAtLeast(0f)
    }

    fun save(c: Context) {
        c.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putFloat("h", heightDp).putFloat("w", widthFrac)
            .putFloat("l", leftFrac).putFloat("b", bottomDp).apply()
    }

    fun reset() {
        heightDp = -1f
        widthFrac = 1f
        leftFrac = 0f
        bottomDp = 0f
    }
}
