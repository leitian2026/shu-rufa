package com.example.myime

import android.content.Context
import android.content.res.Configuration

/** 颜色表,跟随系统深色模式。 */
class Palette(context: Context) {
    val dark: Boolean =
        (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    val background = if (dark) 0xFF1B1D1F.toInt() else 0xFFE8EBEE.toInt()
    val bar = if (dark) 0xFF232628.toInt() else 0xFFF4F6F7.toInt()
    val key = if (dark) 0xFF3B3F43.toInt() else 0xFFFFFFFF.toInt()
    val functionKey = if (dark) 0xFF2A2D30.toInt() else 0xFFCDD3D8.toInt()
    val text = if (dark) 0xFFECEFF1.toInt() else 0xFF1F2328.toInt()
    val accent = if (dark) 0xFF64B5F6.toInt() else 0xFF1E6FD9.toInt()
    val onAccent = if (dark) 0xFF0B1E33.toInt() else 0xFFFFFFFF.toInt()
}
