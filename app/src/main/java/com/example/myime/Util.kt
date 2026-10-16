package com.example.myime

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

fun Context.dpf(v: Float): Float =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

fun Context.dpi(v: Float): Int = dpf(v).toInt()

fun Context.spf(v: Float): Float =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

/**
 * 宽屏(平板/横屏):键盘用更大的边距和按键,笔画页左侧显示数字区。
 * 悬浮键盘永远按手机版式:不分开键盘、不带左边的数字区。
 */
fun Context.isWide(): Boolean = !KbSize.floating && resources.configuration.screenWidthDp >= 600

/** 影响键盘尺寸/配色的所有系统条件的指纹:没变就不用重算尺寸和调色板。 */
fun Context.uiSignature(): String {
    val c = resources.configuration
    return "${c.uiMode}:${c.screenWidthDp}:${c.screenHeightDp}:${c.densityDpi}:${c.fontScale}:${keyboardHeightPx()}:${KbSize.floating}"
}

/** 键盘区(含各个面板)的统一高度,切换页面时高度不变。可在「键盘调节」里改。 */
fun Context.keyboardHeightPx(): Int = dpi(KbSize.heightDp(this))

/** 候选栏(笔画/拼音预览 + 候选词那两行)的高度。整页面板盖住候选栏时,面板高度 = 键盘高度 + 这个值,总高度不变,所以切换不跳动。 */
fun Context.candidateBarHeightPx(): Int {
    val wide = isWide()
    return (dpf(if (wide) 37f else 26f) + dpf(if (wide) 50f else 44f)).toInt()
}

/**
 * 用户调节过的键盘尺寸/位置,存在 SharedPreferences 里(只有几个数字)。
 *  - heightDp:键盘区高度(dp),<0 表示用默认值
 *  - widthFrac:键盘宽度占屏幕宽度的比例
 *  - leftFrac:左边距占屏幕宽度的比例
 *  - bottomDp:离屏幕底部的距离(dp),>0 就是悬浮在输入框上方
 *  - floating:悬浮键盘模式(键盘变小,顶上多一条拖动条,可以拖到屏幕任何位置、随意拉大缩小,不顶起应用界面)
 *
 * 悬浮是"按应用记住"的:在某个应用 / 游戏里开了悬浮、拖到某个位置,下次打开这个应用就还是悬浮、还在那个位置;
 * 没开过悬浮的应用用贴底的普通键盘(普通键盘的尺寸在 kbsize 里,和悬浮互不影响)。
 */
object KbSize {
    const val MIN_W = 0.4f
    const val MIN_H = 150f
    private const val FLOAT_MIN_H = 110f
    private const val FILE = "kbsize"
    private const val APP_FILE = "kbfloat"    // 包名 -> "缩放,左边距比例,离底距离dp",只记开了悬浮的应用

    /** 当前输入框所属的应用包名,onStartInputView 里更新。 */
    var pkg: String? = null

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

    /** 关掉悬浮:回到上次保存的普通键盘(贴底或自己调过的尺寸)。 */
    fun exitFloating(c: Context) {
        floating = false
        loadNormal(c)
    }

    /** 读普通键盘的尺寸/位置。 */
    private fun loadNormal(c: Context) {
        val p = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        if (p.getBoolean("f", false)) {
            // 旧版本把"悬浮"存成全局开关,位置数字也是悬浮的:这里改成默认的贴底键盘,以后按应用单独记
            p.edit().putBoolean("f", false).putFloat("h", -1f).putFloat("w", 1f)
                .putFloat("l", 0f).putFloat("b", 0f).apply()
        }
        heightDp = p.getFloat("h", -1f)
        widthFrac = p.getFloat("w", 1f).coerceIn(MIN_W, 1f)
        leftFrac = p.getFloat("l", 0f).coerceIn(0f, 1f - widthFrac)
        bottomDp = p.getFloat("b", 0f).coerceAtLeast(0f)
    }

    /** 当前应用以前开过悬浮就恢复成悬浮 + 上次的位置和大小;没有就保持普通键盘。 */
    private fun loadAppState(c: Context) {
        val k = pkg ?: return
        val v = c.getSharedPreferences(APP_FILE, Context.MODE_PRIVATE).getString(k, null) ?: return
        val a = v.split(',')
        if (a.size != 3) return
        val s = a[0].toFloatOrNull() ?: return
        val l = a[1].toFloatOrNull() ?: return
        val b = a[2].toFloatOrNull() ?: return
        floating = true
        heightDp = -1f
        widthFrac = 1f
        scale = s.coerceIn(MIN_SCALE, maxScale(c))
        leftFrac = l.coerceIn(0f, 1f)
        bottomDp = b.coerceAtLeast(0f)
    }

    /** 载入当前应用该用的键盘状态:普通键盘的尺寸,再叠上这个应用记住的悬浮状态。 */
    fun load(c: Context) {
        floating = false
        loadNormal(c)
        loadAppState(c)
    }

    /** 保存当前状态:悬浮 → 记到当前应用名下;普通 → 存普通键盘尺寸,并清掉当前应用的悬浮记录。 */
    fun save(c: Context) {
        val k = pkg
        if (floating) {
            if (k != null) {
                c.getSharedPreferences(APP_FILE, Context.MODE_PRIVATE).edit()
                    .putString(k, "$scale,$leftFrac,$bottomDp").apply()
            }
        } else {
            c.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
                .putFloat("h", heightDp).putFloat("w", widthFrac)
                .putFloat("l", leftFrac).putFloat("b", bottomDp).putBoolean("f", false).apply()
            if (k != null) {
                c.getSharedPreferences(APP_FILE, Context.MODE_PRIVATE).edit().remove(k).apply()
            }
        }
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
    textSize = 15f
    minWidth = 0
    minimumWidth = dpi(64f)
    minHeight = 0
    minimumHeight = dpi(44f)   // 够手指点;横向不放太宽,词库卡片里一行要放下三个按钮
    setPadding(dpi(12f), dpi(10f), dpi(12f), dpi(10f))
    // 白底圆角(深色模式下用键盘的深灰键色),按下有水波纹,不可用时变灰
    val pal = Palette(this@smallButton)
    fun shape(c: Int) = GradientDrawable().apply {
        cornerRadius = dpf(10f)
        setColor(c)
    }
    val states = StateListDrawable().apply {
        addState(intArrayOf(-android.R.attr.state_enabled), shape(if (pal.dark) 0xFF2B2E34.toInt() else 0xFFE9EBF0.toInt()))
        addState(intArrayOf(), shape(pal.key))
    }
    background = RippleDrawable(ColorStateList.valueOf(0x33808896), states, null)
    setTextColor(
        ColorStateList(
            arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
            intArrayOf(pal.textFaint, pal.text),
        ),
    )
}

/**
 * 设置页统一的外观:页面底色(和键盘面板底色一致)+ 沉浸式状态栏 / 导航栏。
 * 去掉系统自带的深色标题栏(各页自己有大标题),状态栏和导航栏透明,页面底色一直铺到屏幕边缘;
 * 状态栏 / 导航栏的图标按浅色 / 深色模式自动换深浅。内容用系统栏(和输入法弹出时)的高度做内边距,不会被盖住。
 * 在 setContentView 之前调用(各页 onCreate 里 super.onCreate 之后)。
 */
fun Activity.applyPageBackground() {
    val pal = Palette(this)
    window.setBackgroundDrawable(ColorDrawable(pal.background))
    actionBar?.hide()
    window.statusBarColor = Color.TRANSPARENT
    window.navigationBarColor = Color.TRANSPARENT
    if (Build.VERSION.SDK_INT >= 29) {
        window.isStatusBarContrastEnforced = false
        window.isNavigationBarContrastEnforced = false
    }
    if (Build.VERSION.SDK_INT >= 30) {
        window.setDecorFitsSystemWindows(false)
        val bars = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        window.insetsController?.setSystemBarsAppearance(if (pal.dark) 0 else bars, bars)
    } else {
        var f = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULL_SCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        if (!pal.dark) {
            f = f or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            f = f or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }
        window.decorView.systemUiVisibility = f
    }
    val content = findViewById<View>(android.R.id.content)
    content.setOnApplyWindowInsetsListener { v, insets ->
        if (Build.VERSION.SDK_INT >= 30) {
            val b = insets.getInsets(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime(),
            )
            v.setPadding(b.left, b.top, b.right, b.bottom)
        } else {
            v.setPadding(
                insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                insets.systemWindowInsetRight, insets.systemWindowInsetBottom,
            )
        }
        insets
    }
    content.requestApplyInsets()
}

/** 对话框也用同一个底色(圆角)。必须在 show() 之前调用。 */
fun AlertDialog.stylePage(): AlertDialog {
    val pal = Palette(context)
    window?.setBackgroundDrawable(GradientDrawable().apply {
        cornerRadius = context.dpf(20f)
        setColor(pal.background)
    })
    return this
}

/** 等于 create() + 统一底色 + show()。 */
fun AlertDialog.Builder.showPage(): AlertDialog = create().stylePage().also { it.show() }

/**
 * 可折叠的说明:默认只显示一行「▸ 说明」,点一下展开、再点收起(状态不保存)。
 * selectable = true 时展开后的文字可以长按选中复制(比如文件夹路径)。
 */
fun Context.foldNote(content: String, title: String = "说明", sp: Float = 13f, selectable: Boolean = false): LinearLayout {
    val pal = Palette(this)
    val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    val body = TextView(this).apply {
        text = content
        textSize = sp
        alpha = 0.7f
        visibility = View.GONE
        if (selectable) setTextIsSelectable(true)
        setPadding(0, 0, 0, dpi(6f))
    }
    val head = TextView(this).apply {
        text = "▸ $title"
        textSize = sp + 1f
        setTextColor(pal.accent)
        gravity = Gravity.CENTER_VERTICAL
        minHeight = dpi(36f)
        isClickable = true
        setOnClickListener {
            val show = body.visibility != View.VISIBLE
            body.visibility = if (show) View.VISIBLE else View.GONE
            text = (if (show) "▾ " else "▸ ") + title
        }
    }
    box.addView(head)
    box.addView(body)
    return box
}

/** 小按钮在竖排布局里靠左、不撑满,上下留一点空隙。 */
fun Context.smallButtonParams(): android.widget.LinearLayout.LayoutParams =
    android.widget.LinearLayout.LayoutParams(
        android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = dpi(4f); bottomMargin = dpi(4f); rightMargin = dpi(8f) }
