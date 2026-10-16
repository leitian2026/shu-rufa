package com.example.myime

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView

/**
 * 设置页用的几个通用控件:分组标题、白色圆角卡片、带箭头的列表行、开关行、输入框底色、主按钮。
 * 全部用 Palette 的颜色(浅色 / 深色模式自动跟着变),不用任何资源文件和第三方库。
 */

/** 卡片:白色(深色模式下深灰)圆角底,里面竖排放行。 */
fun Context.settingsCard(): LinearLayout {
    val pal = Palette(this)
    return LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            cornerRadius = dpf(16f)
            setColor(pal.topBar)
        }
        setPadding(dpi(16f), dpi(6f), dpi(16f), dpi(6f))
    }
}

/** 卡片在页面竖排布局里的参数:撑满宽度。 */
fun Context.cardParams(): LinearLayout.LayoutParams =
    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

/** 卡片上方的小标题(灰色、加粗)。 */
fun Context.sectionHeader(label: String): TextView {
    val pal = Palette(this)
    return TextView(this).apply {
        text = label
        textSize = 13f
        setTextColor(pal.textFaint)
        setTypeface(typeface, Typeface.BOLD)
        setPadding(dpi(6f), dpi(22f), 0, dpi(8f))
    }
}

/** 卡片里两行之间的细分隔线。 */
fun Context.rowDivider(): View {
    val pal = Palette(this)
    return View(this).apply {
        setBackgroundColor(if (pal.dark) 0x22FFFFFF else 0x1A000000)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
    }
}

/** 列表行:左边标题(可带一行灰色小字),右边一个「›」,整行可点,按下有水波纹。 */
fun Context.settingsRow(title: String, sub: String?, onClick: () -> Unit): LinearLayout {
    val pal = Palette(this)
    val ctx = this
    return LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dpi(56f)
        setPadding(0, dpi(8f), 0, dpi(8f))
        background = RippleDrawable(ColorStateList.valueOf(0x22808896), null, ColorDrawable(Color.WHITE))
        isClickable = true
        setOnClickListener { onClick() }
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(ctx).apply {
                text = title
                textSize = 16f
                setTextColor(pal.text)
            })
            if (sub != null) {
                addView(TextView(ctx).apply {
                    text = sub
                    textSize = 12.5f
                    setTextColor(pal.textFaint)
                    setPadding(0, dpi(2f), 0, 0)
                })
            }
        }
        addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(TextView(ctx).apply {
            text = "›"
            textSize = 24f
            setTextColor(pal.textFaint)
            setPadding(dpi(8f), 0, dpi(4f), 0)
        })
    }
}

/** 一个配好颜色的开关。初始状态不触发回调,之后每次切换回调一次。 */
fun Context.styledSwitch(checked: Boolean, onChange: (Boolean) -> Unit): Switch {
    val pal = Palette(this)
    return Switch(this).apply {
        isChecked = checked
        val on = (pal.accent and 0x00FFFFFF) or 0x66000000
        val off = if (pal.dark) 0x44FFFFFF else 0x33000000
        thumbTintList = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(pal.accent, if (pal.dark) 0xFFB4BAC4.toInt() else 0xFFFFFFFF.toInt()),
        )
        trackTintList = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(on, off),
        )
        setOnCheckedChangeListener { _, v -> onChange(v) }
    }
}

/** 开关行:左边标题(可带一行灰色小字),右边开关,整行可点。开关本体由调用方给(需要读它的状态时用)。 */
fun Context.switchRowFor(title: String, sub: String?, sw: Switch): LinearLayout {
    val pal = Palette(this)
    val ctx = this
    return LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dpi(56f)
        setPadding(0, dpi(8f), 0, dpi(8f))
        isClickable = true
        setOnClickListener { sw.toggle() }
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(ctx).apply {
                text = title
                textSize = 16f
                setTextColor(pal.text)
            })
            if (sub != null) {
                addView(TextView(ctx).apply {
                    text = sub
                    textSize = 12.5f
                    setTextColor(pal.textFaint)
                    setPadding(0, dpi(2f), 0, 0)
                })
            }
        }
        addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(sw, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            leftMargin = dpi(12f)
        })
    }
}

/** 开关行(不需要读开关状态时用这个)。 */
fun Context.switchRow(title: String, checked: Boolean, sub: String? = null, onChange: (Boolean) -> Unit): LinearLayout =
    switchRowFor(title, sub, styledSwitch(checked, onChange))

/** 输入框底色:比卡片深一点点的圆角底(放在白色卡片里用)。 */
fun Context.fieldBackground(): GradientDrawable {
    val pal = Palette(this)
    return GradientDrawable().apply {
        cornerRadius = dpf(10f)
        setColor(if (pal.dark) 0xFF2B2E34.toInt() else 0xFFF1F3F8.toInt())
    }
}

/** 主按钮:蓝底白字(不可用时变灰),其余和 smallButton 一样大小。 */
fun Context.primaryButton(label: String): Button {
    val pal = Palette(this)
    val ctx = this
    return smallButton(label).apply {
        fun shape(c: Int) = GradientDrawable().apply {
            cornerRadius = ctx.dpf(10f)
            setColor(c)
        }
        val states = StateListDrawable().apply {
            addState(intArrayOf(-android.R.attr.state_enabled), shape(if (pal.dark) 0xFF2B2E34.toInt() else 0xFFE9EBF0.toInt()))
            addState(intArrayOf(), shape(pal.accent))
        }
        background = RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), states, null)
        setTextColor(
            ColorStateList(
                arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
                intArrayOf(pal.textFaint, pal.onAccent),
            ),
        )
    }
}

/** 设置页的滚动外壳:手机上撑满宽度;平板 / 横屏(屏宽 ≥ 680dp)内容限宽 640dp 居中。 */
fun Context.pageScroll(content: View): ScrollView {
    val scroll = ScrollView(this).apply { isFillViewport = true }
    val wideScreen = resources.configuration.screenWidthDp >= 680
    val lp = FrameLayout.LayoutParams(
        if (wideScreen) dpi(640f) else ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { gravity = Gravity.CENTER_HORIZONTAL }
    scroll.addView(content, lp)
    return scroll
}
