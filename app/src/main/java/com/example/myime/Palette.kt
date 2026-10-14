package com.example.myime

import android.content.Context
import android.content.res.Configuration

/**
 * 颜色表,浅色取自参考截图的配色,深色为配套的深色版本。
 * 「输入翻译」打开时只有正在用的「翻译微软 / 翻译谷歌」按钮、翻译框、悬浮条用绿色(transAccent),键盘整体配色不变。
 */
class Palette(context: Context) {
    companion object {
        /** 输入翻译正在起作用。由 MyImeService 切换;只有悬浮条读它(不再影响整套配色)。 */
        @Volatile
        var translating = false
    }

    val dark: Boolean =
        (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
    // 键盘区底色,同时也是设置页(主页 / 词库管理 / 备份与还原)的页面底色(浅色 #F4F5F9)。输入翻译打开时键盘配色不变,只有正在用的翻译那一格和翻译框用绿色。
    val background = if (dark) 0xFF17191C.toInt() else 0xFFF4F5F9.toInt()

    // 上方预览条 + 工具栏/候选栏(白底)
    val topBar = if (dark) 0xFF222529.toInt() else 0xFFFFFFFF.toInt()

    // 普通键:白色,底部一条较深的阴影
    val key = if (dark) 0xFF3A3E45.toInt() else 0xFFFFFFFF.toInt()
    val keyShadow = if (dark) 0xFF0E0F11.toInt() else 0xFFBCC3CF.toInt()

    // 功能键:偏深的蓝灰
    val functionKey = if (dark) 0xFF2B2E34.toInt() else 0xFFCBD2DE.toInt()
    val functionShadow = if (dark) 0xFF0E0F11.toInt() else 0xFFA9B1BF.toInt()

    // 「返回」键:灰白浅色
    val backKey = if (dark) 0xFF4A4E56.toInt() else 0xFFF1F2F5.toInt()
    val backShadow = if (dark) 0xFF0E0F11.toInt() else 0xFFC4CAD5.toInt()

    // 蓝色主按钮(返回)、第一候选、选中状态
    val accent = if (dark) 0xFF4C8DFF.toInt() else 0xFF2468F2.toInt()
    val accentShadow = if (dark) 0xFF1D4DB0.toInt() else 0xFF1A4DB8.toInt()

    // 输入翻译专用的绿色:「翻译微软 / 翻译谷歌」按钮打开时的底色 / 阴影、翻译框、悬浮条。只用在这几处,不影响键盘整体配色
    val transAccent = if (dark) 0xFF2BA86A.toInt() else 0xFF1F9D63.toInt()
    val transShadow = if (dark) 0xFF17653F.toInt() else 0xFF157349.toInt()
    val onAccent = 0xFFFFFFFF.toInt()

    val text = if (dark) 0xFFECEFF4.toInt() else 0xFF1D2025.toInt()
    val textFaint = if (dark) 0xFF8B919C.toInt() else 0xFF8D939D.toInt()
    val icon = if (dark) 0xFFB4BAC4.toInt() else 0xFF50555E.toInt()

    // 工具栏被选中项的底色、按下时的叠加色
    val pill = if (dark) 0xFF33373D.toInt() else 0xFFDDE2EA.toInt()
    val pressTint = if (dark) 0xFFFFFFFF.toInt() else 0xFF7C879B.toInt()
    val pressAlpha = if (dark) 40 else 80
}
