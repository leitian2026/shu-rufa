package com.example.myime

import android.view.View

/** 按键震动的总开关。键盘每次弹出时从设置读一次(见 MyImeService.onStartInputView),设置页改了会立刻同步。 */
object Haptics {
    @Volatile
    @JvmField
    var enabled: Boolean = true

    fun tap(v: View, kind: Int) {
        if (enabled) v.performHapticFeedback(kind)
    }
}
