package com.example.myime

import android.content.Context
import android.util.TypedValue
import android.widget.Button

fun Context.dpf(v: Float): Float =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

fun Context.dpi(v: Float): Int = dpf(v).toInt()

fun Context.spf(v: Float): Float =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

/** 宽屏(平板/横屏):键盘用更大的边距和按键,笔画页左侧显示数字区。 */
fun Context.isWide(): Boolean = resources.configuration.screenWidthDp >= 600

/** 影响键盘尺寸/配色的所有系统条件的指纹:没变就不用重算尺寸和调色板。 */
fun Context.uiSignature(): String {
    val c = resources.configuration
    return "${c.uiMode}:${c.screenWidthDp}:${c.screenHeightDp}:${c.densityDpi}:${c.fontScale}:${keyboardHeightPx()}"
}

/** 键盘区(含各个面板)的统一高度,切换页面时高度不变。可在「键盘调节」里改。 */
fun Context.keyboardHeightPx(): Int = dpi(KbSize.heightDp(this))

/**
 * 用户调节过的键盘尺寸/位置,存在 SharedPreferences 里(只有几个数字)。
 *  - heightDp:键盘区高度(dp),<0 表示用默认值
 *  - widthFrac:键盘宽度占屏幕宽度的比例
 *  - leftFrac:左边距占屏幕宽度的比例
 *  - bottomDp:离屏幕底部的距离(dp),>0 就是悬浮在输入框上方
 *  - floating:悬浮键盘模式(键盘变小,顶上多一条拖动条,可以拖到屏幕任何位置、随意拉大缩小,不顶起应用界面)
 */
object KbSize {
    const val MIN_W = 0.4f
    const val MIN_H = 150f
    private const val FLOAT_MIN_H = 110f
    private const val FILE = "kbsize"

    var floating = false

    /** 悬浮键盘的缩放比例:键盘按 floatRefWidthPx 宽、默认高度排好版,再整体等比缩放,字和按键一起变。 */
    var scale = 0.85f
    const val MIN_SCALE = 0.7f   // 再小字就看不清了

    fun floatRefWidthPx(c: Context): Int =
        minOf(c.resources.displayMetrics.widthPixels, c.dpi(600f))

    fun maxScale(c: Context): Float {
        val sw = c.resources.displayMetrics.widthPixels.toFloat()
        val byW = sw / floatRefWidthPx(c)
        val byH = (c.resources.configuration.screenHeightDp * 0.7f) / (defaultHeightDp(c) + 100f)
        return minOf(1.5f, byW, byH).coerceAtLeast(MIN_SCALE)
    }

    fun minWidthFrac(c: Context): Float = MIN_W

    fun minHeightDp(): Float = MIN_H

    var heightDp = -1f
    var widthFrac = 1f
    var leftFrac = 0f
    var bottomDp = 0f

    fun defaultHeightDp(c: Context): Float = if (c.isWide()) 279f else 224f

    fun heightDp(c: Context): Float =
        if (floating) defaultHeightDp(c)   // 悬浮时版式高度固定,靠缩放变大变小
        else (if (heightDp < 0f) defaultHeightDp(c) else heightDp).coerceIn(minHeightDp(), maxHeightDp(c))

    fun maxHeightDp(c: Context): Float =
        minOf(460f, c.resources.configuration.screenHeightDp * 0.6f).coerceAtLeast(minHeightDp())

    fun maxBottomDp(c: Context): Float =
        (c.resources.configuration.screenHeightDp * 0.35f).coerceAtLeast(0f)

    /** 打开悬浮键盘:如果还是贴底全宽,先缩成一个居中、抬离底边的小键盘。 */
    fun enterFloating(c: Context) {
        floating = true
        heightDp = -1f
        widthFrac = 1f
        scale = (if (c.isWide()) 0.8f else 0.85f).coerceIn(MIN_SCALE, maxScale(c))
        val sw = c.resources.displayMetrics.widthPixels.toFloat()
        leftFrac = ((1f - floatRefWidthPx(c) * scale / sw) / 2f).coerceAtLeast(0f)
        bottomDp = 96f
    }

    /** 关掉悬浮:回到贴底全宽的默认键盘。 */
    fun exitFloating() {
        floating = false
        widthFrac = 1f
        leftFrac = 0f
        bottomDp = 0f
        heightDp = -1f
    }

    fun load(c: Context) {
        val p = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        heightDp = p.getFloat("h", -1f)
        floating = p.getBoolean("f", false)
        scale = p.getFloat("s", 0.85f).coerceIn(MIN_SCALE, maxScale(c))
        widthFrac = p.getFloat("w", 1f).coerceIn(MIN_W, 1f)
        leftFrac = p.getFloat("l", 0f).coerceIn(0f, if (floating) 1f else 1f - widthFrac)
        bottomDp = p.getFloat("b", 0f).coerceAtLeast(0f)
    }

    fun save(c: Context) {
        c.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putFloat("h", heightDp).putFloat("w", widthFrac)
            .putFloat("l", leftFrac).putFloat("b", bottomDp).putBoolean("f", floating).putFloat("s", scale).apply()
    }

    fun reset() {
        floating = false
        heightDp = -1f
        widthFrac = 1f
        leftFrac = 0f
        bottomDp = 0f
    }
}

/** 界面里的小按钮:贴着文字大小,不撑满整行。 */
fun Context.smallButton(label: String? = null): Button = Button(this).apply {
    if (label != null) text = label
    isAllCaps = false
    textSize = 13f
    minWidth = 0
    minimumWidth = 0
    minHeight = 0
    minimumHeight = 0
    setPadding(dpi(14f), dpi(6f), dpi(14f), dpi(6f))
}

/** 小按钮在竖排布局里靠左、不撑满,上下留一点空隙。 */
fun Context.smallButtonParams(): android.widget.LinearLayout.LayoutParams =
    android.widget.LinearLayout.LayoutParams(
        android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = dpi(4f); bottomMargin = dpi(4f); rightMargin = dpi(8f) }
