package com.example.myime

enum class ActionType {
    CHAR, STROKE, BACKSPACE, ENTER, SPACE,
    LANG,       // 中/英
    METHOD,     // 拼音 ↔ 笔画
    SEPARATOR,  // 分词(拼音输入中插入音节分隔)
    SYMBOL,     // 符号页 / 数字页 / 返回
    SHIFT,
    EDIT,       // 光标编辑页里的各个键
}

enum class Mode { PINYIN, STROKE, ENGLISH, SYMBOL, NUMBER, EDIT }

object Icon {
    const val NONE = 0
    const val BACKSPACE = 1
    const val ENTER = 2
    const val SPACE = 3
    const val UP = 4
    const val DOWN = 5
    const val LEFT = 6
    const val RIGHT = 7
}

/**
 * 一个按键。位置用"格"为单位:x、y 是左上角,w、h 是占的格数,
 * 具体像素由 KeyboardView 按布局的列数/行数和间距换算。
 */
class KeySpec(
    val label: String,
    val type: ActionType,
    val value: String = label,
    val function: Boolean = false,     // 功能键配色(偏深)
    val active: Boolean = false,       // 激活态(蓝底):大写、选择
    val primary: Boolean = false,      // 蓝色主按钮
    val icon: Int = Icon.NONE,         // 用矢量图形代替文字
    val rich: Int = 0,                 // 1:「中」高亮  2:「英」高亮
    val multi: List<KeySpec>? = null,  // 一个键里竖排多个符号(笔画页的标点列)
    val repeatable: Boolean = false,   // 长按连发
) {
    var x = 0f
    var y = 0f
    var w = 1f
    var h = 1f
}

/** gapX/gapY 单位是 dp。 */
class Layout(
    val cols: Float,
    val rows: Float,
    val gapX: Float,
    val gapY: Float,
    val keys: List<KeySpec>,
)

class KbState(
    val enterLabel: String?,   // null 表示用回车图标
    val shift: Boolean,
    val composing: Boolean,
    val selecting: Boolean,
)

object Layouts {

    fun build(mode: Mode, s: KbState, wide: Boolean): Layout = when (mode) {
        Mode.PINYIN, Mode.ENGLISH -> qwerty(mode, s, wide)
        Mode.STROKE -> stroke(s, wide)
        Mode.SYMBOL -> symbol(s, wide)
        Mode.NUMBER -> number(s, wide)
        Mode.EDIT -> edit(s, wide)
    }

    // ---------------------------------------------------------------- 工具

    private fun KeySpec.at(x: Float, y: Float, w: Float = 1f, h: Float = 1f): KeySpec {
        this.x = x
        this.y = y
        this.w = w
        this.h = h
        return this
    }

    private fun ch(t: String) = KeySpec(t, ActionType.CHAR)

    private fun backspace() = KeySpec(
        "", ActionType.BACKSPACE, function = true, icon = Icon.BACKSPACE, repeatable = true,
    )

    private fun enterKey(label: String?) =
        if (label == null) {
            KeySpec("", ActionType.ENTER, function = true, icon = Icon.ENTER)
        } else {
            KeySpec(label, ActionType.ENTER, function = true)
        }

    private fun spaceKey() = KeySpec("", ActionType.SPACE, value = " ", icon = Icon.SPACE)

    private fun langKey(english: Boolean) =
        KeySpec("中/英", ActionType.LANG, function = true, rich = if (english) 2 else 1)

    private fun symKey(label: String, target: String) =
        KeySpec(label, ActionType.SYMBOL, value = target, function = true)

    private val numpad = listOf("1", "2", "3", "+", "4", "5", "6", "-", "7", "8", "9", "/", ",", "0", ".", ":")

    // ---------------------------------------------------------------- 26 键(拼音 / 英文)

    private fun qwerty(mode: Mode, s: KbState, wide: Boolean): Layout {
        val english = mode == Mode.ENGLISH
        val upper = !english || s.shift      // 拼音时键帽显示大写,和参考一致
        val keys = ArrayList<KeySpec>()
        val rows = arrayOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
        val startX = floatArrayOf(0f, 0.5f, 1.5f)
        for (r in rows.indices) {
            for (i in rows[r].indices) {
                val c = rows[r][i]
                val label = (if (upper) c.uppercaseChar() else c).toString()
                val value = if (english) label else c.toString()  // 拼音:永远是小写字母
                keys.add(KeySpec(label, ActionType.CHAR, value = value).at(startX[r] + i, r.toFloat()))
            }
        }

        val left = when {
            english -> KeySpec("大写", ActionType.SHIFT, function = true, active = s.shift)
            s.composing -> KeySpec("分词", ActionType.SEPARATOR, function = true)
            else -> KeySpec("笔画", ActionType.METHOD, function = true)
        }
        keys.add(left.at(0f, 2f, 1.5f))
        keys.add(backspace().at(8.5f, 2f, 1.5f))

        val cn = !english
        keys.add(symKey("123", "number").at(0f, 3f, 1.4f))
        keys.add(symKey("符", "symbol").at(1.4f, 3f, 1.4f))
        keys.add(ch(if (cn) "，" else ",").at(2.8f, 3f, 1f))
        keys.add(spaceKey().at(3.8f, 3f, 2.4f))
        keys.add(ch(if (cn) "。" else ".").at(6.2f, 3f, 1f))
        keys.add(langKey(english).at(7.2f, 3f, 1.4f))
        keys.add(enterKey(s.enterLabel).at(8.6f, 3f, 1.4f))
        return Layout(10f, 4f, if (wide) 10.5f else 5f, if (wide) 13f else 8f, keys)
    }

    // ---------------------------------------------------------------- 笔画

    private fun stroke(s: KbState, wide: Boolean): Layout {
        val keys = ArrayList<KeySpec>()
        var x0 = 0f
        if (wide) {
            // 宽屏:左侧数字区
            for (i in numpad.indices) {
                keys.add(ch(numpad[i]).at((i % 4).toFloat(), (i / 4).toFloat()))
            }
            x0 = 4f
        }
        val pw = if (wide) 1.1f else 0.7f   // 标点列宽
        val rw = if (wide) 1f else 0.7f     // 右侧功能列宽
        val punct = KeySpec(
            "", ActionType.CHAR,
            multi = listOf("，", "。", "？", "！", "…").map { ch(it) },
        )
        keys.add(punct.at(x0, 0f, pw, 4f))

        val x1 = x0 + pw
        val glyphs = listOf("一" to "h", "丨" to "s", "丿" to "p", "丶" to "n", "乙" to "z")
        for (i in glyphs.indices) {
            val col = i % 3
            val row = i / 3
            keys.add(
                KeySpec(glyphs[i].first, ActionType.STROKE, value = glyphs[i].second)
                    .at(x1 + col, row * 1.5f, 1f, 1.5f)
            )
        }
        keys.add(KeySpec("ab", ActionType.METHOD).at(x1 + 2f, 1.5f, 1f, 1.5f))

        val xr = x1 + 3f
        keys.add(backspace().at(xr, 0f, rw, 1f))
        keys.add(KeySpec("*", ActionType.STROKE, value = "*", function = true).at(xr, 1f, rw, 1f))
        val sym = if (wide) symKey("符", "symbol") else symKey("123", "number")
        keys.add(sym.at(xr, 2f, rw, 1f))
        keys.add(enterKey(if (s.composing) "清除" else s.enterLabel).at(xr, 3f, rw, 1f))

        if (wide) {
            keys.add(spaceKey().at(x1, 3f, 2f, 1f))
        } else {
            keys.add(symKey("符", "symbol").at(x1, 3f, 1f, 1f))
            keys.add(spaceKey().at(x1 + 1f, 3f, 1f, 1f))
        }
        keys.add(langKey(false).at(x1 + 2f, 3f, 1f, 1f))
        return Layout(xr + rw, 4f, if (wide) 10.5f else 5f, if (wide) 6.5f else 5f, keys)
    }

    // ---------------------------------------------------------------- 符号页(符)

    private fun symbol(s: KbState, wide: Boolean): Layout {
        val keys = ArrayList<KeySpec>()
        val r0 = "1234567890"
        for (i in r0.indices) keys.add(ch(r0[i].toString()).at(i.toFloat(), 0f))
        val r1 = listOf("-", "/", ":", ";", "(", ")", "¥", "&", "@", "\"")
        for (i in r1.indices) keys.add(ch(r1[i]).at(i.toFloat(), 1f))
        val r2 = listOf("。", "，", "、", "？", "！", ".", ",", "~")
        for (i in r2.indices) keys.add(ch(r2[i]).at(i.toFloat(), 2f))
        keys.add(backspace().at(8f, 2f, 2f))

        keys.add(symKey("返回", "back").at(0f, 3f, 1.5f))
        keys.add(ch(",").at(1.5f, 3f))
        keys.add(spaceKey().at(2.5f, 3f, 5f))
        keys.add(ch(".").at(7.5f, 3f))
        keys.add(enterKey(s.enterLabel).at(8.5f, 3f, 1.5f))
        return Layout(10f, 4f, if (wide) 10.5f else 5f, if (wide) 13f else 8f, keys)
    }

    // ---------------------------------------------------------------- 数字页(123)

    private fun number(s: KbState, wide: Boolean): Layout {
        val keys = ArrayList<KeySpec>()
        for (i in numpad.indices) {
            keys.add(ch(numpad[i]).at((i % 4).toFloat(), (i / 4).toFloat()))
        }
        keys.add(backspace().at(4f, 0f))
        keys.add(spaceKey().at(4f, 1f))
        keys.add(enterKey(s.enterLabel).at(4f, 2f))
        keys.add(symKey("返回", "back").at(4f, 3f))
        return Layout(5f, 4f, if (wide) 10.5f else 5f, if (wide) 7f else 6f, keys)
    }

    // ---------------------------------------------------------------- 光标编辑页

    private fun edit(s: KbState, wide: Boolean): Layout {
        val keys = ArrayList<KeySpec>()
        fun e(
            label: String, v: String, fn: Boolean = false, icon: Int = Icon.NONE,
            rep: Boolean = false, active: Boolean = false, primary: Boolean = false,
        ) = KeySpec(
            label, ActionType.EDIT, value = v, function = fn, icon = icon,
            repeatable = rep, active = active, primary = primary,
        )

        val cx = floatArrayOf(0f, 0.83f, 1.83f, 2.83f, 3.83f)
        val cw = floatArrayOf(0.83f, 1f, 1f, 1f, 0.83f)
        val grid = arrayOf(
            arrayOf(
                e("Tab", "tab", fn = true), e("复制", "copy"),
                e("", "up", icon = Icon.UP, rep = true), e("粘贴", "paste"), backspace(),
            ),
            arrayOf(
                e("开头", "home", fn = true), e("", "left", icon = Icon.LEFT, rep = true),
                e("选择", "select", active = s.selecting),
                e("", "right", icon = Icon.RIGHT, rep = true), e("Del", "del", fn = true, rep = true),
            ),
            arrayOf(
                e("末尾", "end", fn = true), e("全选", "all"),
                e("", "down", icon = Icon.DOWN, rep = true), e("剪切", "cut"),
                e("返回", "back", primary = true),
            ),
        )
        for (r in grid.indices) {
            for (c in grid[r].indices) {
                keys.add(grid[r][c].at(cx[c], r.toFloat(), cw[c], 1f))
            }
        }
        return Layout(4.66f, 3f, if (wide) 11.5f else 6f, if (wide) 7.5f else 6f, keys)
    }
}
