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

/** 键盘区(含各个面板)的统一高度,切换页面时高度不变。 */
fun Context.keyboardHeightPx(): Int = dpi(if (isWide()) 279f else 224f)
