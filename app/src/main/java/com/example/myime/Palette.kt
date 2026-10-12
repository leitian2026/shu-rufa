package com.example.myime

import android.content.Context
import android.content.res.Configuration

/**
 * 颜色表,浅色取自参考截图的配色,深色为配套的深色版本。
 * 「输入翻译」打开时(translating = true)整套换成绿色系,一眼就能分辨现在是不是在翻译状态。
 */
class Palette(context: Context) {
    companion object {
        /** 输入翻译正在起作用。由 MyImeService 切换;改了以后各个 View 调 refresh() 才会换色(uiSignature 里带着它)。 */
        @Volatile
        var translating = false
    }

    val dark: Boolean =
        (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
    private val tr = translating

    // 键盘区底色(浅蓝灰;翻译状态:浅绿)
    val background = if (tr) (if (dark) 0xFF16231B.toInt() else 0xFFD8EBDD.toInt())
    else if (dark) 0xFF17191C.toInt() else 0xFFE2E6ED.toInt()

    // 上方预览条 + 工具栏/候选栏(白底)
    val topBar = if (tr) (if (dark) 0xFF1D2D23.toInt() else 0xFFF4FAF6.toInt())
    else if (dark) 0xFF222529.toInt() else 0xFFFFFFFF.toInt()

    // 普通键:白色,底部一条较深的阴影
    val key = if (tr) (if (dark) 0xFF34443A.toInt() else 0xFFFFFFFF.toInt())
    else if (dark) 0xFF3A3E45.toInt() else 0xFFFFFFFF.toInt()
    val keyShadow = if (tr) (if (dark) 0xFF0C130F.toInt() else 0xFFB1C8B8.toInt())
    else if (dark) 0xFF0E0F11.toInt() else 0xFFBCC3CF.toInt()

    // 功能键:偏深的蓝灰(翻译状态:偏深的绿灰)
    val functionKey = if (tr) (if (dark) 0xFF22352A.toInt() else 0xFFB6D9C0.toInt())
    else if (dark) 0xFF2B2E34.toInt() else 0xFFCBD2DE.toInt()
    val functionShadow = if (tr) (if (dark) 0xFF0C130F.toInt() else 0xFF93BBA0.toInt())
    else if (dark) 0xFF0E0F11.toInt() else 0xFFA9B1BF.toInt()

    // 蓝色主按钮(返回)、第一候选、选中状态(翻译状态:绿色)
    val accent = if (tr) (if (dark) 0xFF3DDC84.toInt() else 0xFF1F9D63.toInt())
    else if (dark) 0xFF4C8DFF.toInt() else 0xFF2468F2.toInt()
    val accentShadow = if (tr) (if (dark) 0xFF1F8F52.toInt() else 0xFF157349.toInt())
    else if (dark) 0xFF1D4DB0.toInt() else 0xFF1A4DB8.toInt()
    val onAccent = 0xFFFFFFFF.toInt()

    val text = if (dark) 0xFFECEFF4.toInt() else 0xFF1D2025.toInt()
    val textFaint = if (dark) 0xFF8B919C.toInt() else 0xFF8D939D.toInt()
    val icon = if (dark) 0xFFB4BAC4.toInt() else 0xFF50555E.toInt()

    // 工具栏被选中项的底色、按下时的叠加色
    val pill = if (tr) (if (dark) 0xFF2C3F33.toInt() else 0xFFD3E8D9.toInt())
    else if (dark) 0xFF33373D.toInt() else 0xFFDDE2EA.toInt()
    val pressTint = if (dark) 0xFFFFFFFF.toInt() else 0xFF7C879B.toInt()
    val pressAlpha = if (dark) 40 else 80
}
