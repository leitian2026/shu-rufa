package com.example.myime

enum class ActionType { CHAR, STROKE, BACKSPACE, ENTER, SPACE, MODE, SYMBOL, SHIFT, SPACER }

enum class Mode(val title: String) {
    PINYIN("拼音"),
    STROKE("笔画"),
    ENGLISH("English"),
    SYMBOL("符号"),
}

class KeySpec(
    val label: String,
    val type: ActionType,
    val value: String = label,
    val weight: Float = 1f,
    val function: Boolean = false,
    val sub: String? = null,
    val active: Boolean = false,
)

/** heightWeight:行高占几个"单位行"。所有布局的总高度都是 4 个单位,切换模式键盘高度不变。 */
class Row(val keys: List<KeySpec>, val heightWeight: Float = 1f)

object Layouts {

    fun build(mode: Mode, enterLabel: String, shift: Boolean): List<Row> = when (mode) {
        Mode.PINYIN -> listOf(
            Row(letters("qwertyuiop", false)),
            Row(listOf(spacer(0.5f)) + letters("asdfghjkl", false) + spacer(0.5f)),
            Row(listOf(spacer(1.5f)) + letters("zxcvbnm", false) + backspace(1.5f)),
            bottom(mode, enterLabel),
        )

        Mode.ENGLISH -> listOf(
            Row(letters("qwertyuiop", shift)),
            Row(listOf(spacer(0.5f)) + letters("asdfghjkl", shift) + spacer(0.5f)),
            Row(
                listOf(KeySpec("大写", ActionType.SHIFT, weight = 1.5f, function = true, active = shift)) +
                    letters("zxcvbnm", shift) + backspace(1.5f)
            ),
            bottom(mode, enterLabel),
        )

        Mode.STROKE -> listOf(
            Row(
                listOf(
                    KeySpec("一", ActionType.STROKE, value = "h", sub = "横"),
                    KeySpec("丨", ActionType.STROKE, value = "s", sub = "竖"),
                    KeySpec("丿", ActionType.STROKE, value = "p", sub = "撇"),
                    KeySpec("丶", ActionType.STROKE, value = "n", sub = "点"),
                    KeySpec("乙", ActionType.STROKE, value = "z", sub = "折"),
                ),
                heightWeight = 2f,
            ),
            Row(listOf("，", "。", "？", "！", "、").map { c(it) } + backspace(1.5f)),
            bottom(mode, enterLabel),
        )

        Mode.SYMBOL -> listOf(
            Row("1234567890".map { c(it.toString()) }),
            Row(listOf("-", "/", ":", ";", "(", ")", "¥", "&", "@", "\"").map { c(it) }),
            Row(listOf("。", "，", "、", "？", "！", ".", ",", "~").map { c(it) } + backspace(2f)),
            bottom(mode, enterLabel),
        )
    }

    private fun c(t: String, w: Float = 1f) = KeySpec(t, ActionType.CHAR, weight = w)

    private fun letters(s: String, upper: Boolean): List<KeySpec> = s.map { ch ->
        val t = (if (upper) ch.uppercaseChar() else ch).toString()
        KeySpec(t, ActionType.CHAR)
    }

    private fun spacer(w: Float) = KeySpec("", ActionType.SPACER, weight = w)

    private fun backspace(w: Float) =
        KeySpec("删除", ActionType.BACKSPACE, weight = w, function = true)

    private fun bottom(mode: Mode, enterLabel: String): Row {
        val cn = mode == Mode.PINYIN || mode == Mode.STROKE
        val comma = if (cn) "，" else ","
        val period = if (cn) "。" else "."
        val spaceLabel = if (mode == Mode.SYMBOL) "空格" else mode.title
        return Row(
            listOf(
                KeySpec("切换", ActionType.MODE, weight = 1.5f, function = true),
                KeySpec(
                    if (mode == Mode.SYMBOL) "返回" else "123",
                    ActionType.SYMBOL, weight = 1.5f, function = true,
                ),
                KeySpec(comma, ActionType.CHAR),
                KeySpec(spaceLabel, ActionType.SPACE, value = " ", weight = 3.5f),
                KeySpec(period, ActionType.CHAR),
                KeySpec(enterLabel, ActionType.ENTER, weight = 1.5f, function = true),
            )
        )
    }
}
