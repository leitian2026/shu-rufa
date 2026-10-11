package com.example.myime

import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.LinearLayout
import java.util.concurrent.Executors

class MyImeService : InputMethodService(), KeyboardView.Listener {

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()

    @Volatile private var pinyinEngine: Engine? = null
    @Volatile private var strokeEngine: Engine? = null

    private var keyboardView: KeyboardView? = null
    private var candidateBar: CandidateBarView? = null

    private var textMode = Mode.PINYIN   // 用户选的文字模式
    private var mode = Mode.PINYIN       // 当前显示的模式(可能是符号)
    private var secureField = false      // 密码框:只允许英文/符号,不使用组合区
    private var shift = false
    private var enterLabel = "回车"

    private val composing = StringBuilder()
    private var candidates: List<Candidate> = emptyList()
    private var candidatesFor = ""
    private var queryGen = 0

    // ------------------------------------------------------------ 生命周期

    override fun onCreate() {
        super.onCreate()
        worker.execute {
            pinyinEngine = DictLoader.loadPinyin(applicationContext)
            strokeEngine = DictLoader.loadStroke(applicationContext)
        }
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }

    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onCreateInputView(): View {
        val bar = CandidateBarView(this) { index -> onCandidatePicked(index) }
        val kb = KeyboardView(this, this)
        kb.setLayout(Layouts.build(mode, enterLabel, shift), animate = false)
        candidateBar = bar
        keyboardView = kb
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpi(44f)))
            addView(
                kb,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
    }

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        composing.clear()
        queryGen++
        candidates = emptyList()
        candidatesFor = ""

        val cls = info.inputType and InputType.TYPE_MASK_CLASS
        val variation = info.inputType and InputType.TYPE_MASK_VARIATION
        secureField = false
        var forced: Mode? = null
        when (cls) {
            InputType.TYPE_CLASS_NUMBER -> {
                forced = Mode.SYMBOL
                if (variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD) secureField = true
            }
            InputType.TYPE_CLASS_PHONE, InputType.TYPE_CLASS_DATETIME -> forced = Mode.SYMBOL
            InputType.TYPE_CLASS_TEXT -> {
                if (variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                    variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
                    variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                ) {
                    secureField = true
                    forced = Mode.ENGLISH
                } else if (variation == InputType.TYPE_TEXT_VARIATION_URI ||
                    variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS ||
                    variation == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS
                ) {
                    forced = Mode.ENGLISH
                }
            }
        }

        enterLabel = enterLabelFor(info)
        mode = forced ?: textMode
        shift = false
        if (mode == Mode.ENGLISH) shift = capsWanted(info)

        keyboardView?.refreshTheme()
        candidateBar?.refreshTheme()
        keyboardView?.setLayout(Layouts.build(mode, enterLabel, shift), animate = false)
        candidateBar?.setCandidates(emptyList(), animate = false)
    }

    override fun onFinishInput() {
        super.onFinishInput()
        composing.clear()
        queryGen++
        applyCandidates("", emptyList())
    }

    /** 用户在应用里点别处移动了光标:丢弃我们这边的组合状态。 */
    override fun onUpdateSelection(
        oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int,
        candidatesStart: Int, candidatesEnd: Int,
    ) {
        super.onUpdateSelection(
            oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd,
        )
        if (composing.isNotEmpty() && candidatesStart >= 0 &&
            (newSelEnd < candidatesStart || newSelEnd > candidatesEnd)
        ) {
            composing.clear()
            queryGen++
            applyCandidates("", emptyList())
            currentInputConnection?.finishComposingText()
        }
    }

    // ------------------------------------------------------------ 按键分发

    override fun onKey(spec: KeySpec) {
        when (spec.type) {
            ActionType.CHAR -> typeChar(spec.value)
            ActionType.STROKE -> typeStroke(spec.value[0])
            ActionType.BACKSPACE -> backspace()
            ActionType.ENTER -> enter()
            ActionType.SPACE -> space()
            ActionType.MODE -> cycleMode()
            ActionType.SYMBOL -> toggleSymbol()
            ActionType.SHIFT -> {
                shift = !shift
                refreshLayout(animate = false)
            }
            ActionType.SPACER -> Unit
        }
    }

    private fun typeChar(t: String) {
        val ic = currentInputConnection ?: return
        if (mode == Mode.PINYIN && t.length == 1 && t[0] in 'a'..'z') {
            composing.append(t)
            showComposing()
            requery()
            return
        }
        ic.beginBatchEdit()
        flushComposition(ic)
        ic.commitText(t, 1)
        ic.endBatchEdit()
        afterCommit()
    }

    private fun typeStroke(c: Char) {
        if (mode != Mode.STROKE) return
        composing.append(c)
        showComposing()
        requery()
    }

    private fun backspace() {
        if (composing.isNotEmpty()) {
            composing.deleteCharAt(composing.length - 1)
            showComposing()
            requery()
        } else {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
            afterCommit()
        }
    }

    private fun space() {
        val ic = currentInputConnection ?: return
        if (composing.isNotEmpty()) {
            ic.beginBatchEdit()
            val c = bestCandidate()
            if (c == null) {
                if (mode == Mode.PINYIN) ic.commitText(composing.toString(), 1) else ic.commitText("", 1)
                composing.clear()
            } else {
                ic.commitText(c.text, 1)
                composing.delete(0, c.consumed.coerceIn(1, composing.length))
                if (composing.isNotEmpty()) showComposing()
            }
            ic.endBatchEdit()
            requery()
            return
        }
        ic.commitText(" ", 1)
        afterCommit()
    }

    private fun enter() {
        val ic = currentInputConnection ?: return
        if (composing.isNotEmpty()) {
            ic.beginBatchEdit()
            flushRaw(ic)
            ic.endBatchEdit()
            return
        }
        val info = currentInputEditorInfo
        val options = info?.imeOptions ?: 0
        val action = options and EditorInfo.IME_MASK_ACTION
        val noAction = (options and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0
        if (info == null || noAction ||
            action == EditorInfo.IME_ACTION_NONE || action == EditorInfo.IME_ACTION_UNSPECIFIED
        ) {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
        } else {
            ic.performEditorAction(action)
        }
        afterCommit()
    }

    private fun cycleMode() {
        if (secureField) return
        currentInputConnection?.let { ic ->
            ic.beginBatchEdit()
            flushRaw(ic)
            ic.endBatchEdit()
        }
        textMode = when (textMode) {
            Mode.PINYIN -> Mode.STROKE
            Mode.STROKE -> Mode.ENGLISH
            else -> Mode.PINYIN
        }
        mode = textMode
        shift = false
        if (mode == Mode.ENGLISH) shift = capsWanted(currentInputEditorInfo)
        refreshLayout(animate = true)
    }

    private fun toggleSymbol() {
        if (mode == Mode.SYMBOL) {
            mode = if (secureField) Mode.ENGLISH else textMode
            shift = false
            if (mode == Mode.ENGLISH) shift = capsWanted(currentInputEditorInfo)
        } else {
            currentInputConnection?.let { ic ->
                ic.beginBatchEdit()
                flushRaw(ic)
                ic.endBatchEdit()
            }
            mode = Mode.SYMBOL
            shift = false
        }
        refreshLayout(animate = true)
    }

    // ------------------------------------------------------------ 组合与候选

    private fun currentEngine(): Engine? = when (mode) {
        Mode.PINYIN -> pinyinEngine
        Mode.STROKE -> strokeEngine
        else -> null
    }

    private fun displayComposing(): String =
        if (mode == Mode.STROKE) {
            composing.map { strokeGlyph(it) }.joinToString("")
        } else {
            composing.toString()
        }

    private fun strokeGlyph(c: Char): Char = when (c) {
        'h' -> '一'
        's' -> '丨'
        'p' -> '丿'
        'n' -> '丶'
        else -> '乙'
    }

    private fun showComposing() {
        currentInputConnection?.setComposingText(displayComposing(), 1)
    }

    /** 查询放后台线程;用代数丢弃过期结果。 */
    private fun requery() {
        val code = composing.toString()
        val gen = ++queryGen
        if (code.isEmpty()) {
            applyCandidates("", emptyList())
            return
        }
        val engine = currentEngine()
        if (engine == null) {
            applyCandidates(code, emptyList())
            return
        }
        worker.execute {
            val result = try {
                engine.query(code)
            } catch (t: Throwable) {
                emptyList()
            }
            main.post { if (gen == queryGen) applyCandidates(code, result) }
        }
    }

    private fun applyCandidates(code: String, list: List<Candidate>) {
        candidatesFor = code
        candidates = list
        candidateBar?.setCandidates(list.map { it.text })
    }

    private fun clearCandidates() {
        queryGen++
        applyCandidates("", emptyList())
    }

    private fun bestCandidate(): Candidate? {
        if (composing.isEmpty()) return null
        val code = composing.toString()
        if (candidatesFor == code) return candidates.firstOrNull()
        return currentEngine()?.query(code)?.firstOrNull() // 候选还没回来,同步查一次
    }

    private fun onCandidatePicked(index: Int) {
        val ic = currentInputConnection ?: return
        if (candidatesFor != composing.toString()) return // 过期的点击
        val c = candidates.getOrNull(index) ?: return
        ic.beginBatchEdit()
        ic.commitText(c.text, 1)
        composing.delete(0, c.consumed.coerceIn(1, composing.length))
        if (composing.isNotEmpty()) showComposing()
        ic.endBatchEdit()
        requery()
        afterCommit()
    }

    /** 标点等输入前:把已有的拼音/笔画按最佳候选上屏。 */
    private fun flushComposition(ic: InputConnection) {
        var guard = 0
        while (composing.isNotEmpty() && guard++ < 64) {
            val c = bestCandidate()
            if (c == null) {
                if (mode == Mode.PINYIN) ic.commitText(composing.toString(), 1) else ic.commitText("", 1)
                composing.clear()
                break
            }
            ic.commitText(c.text, 1)
            composing.delete(0, c.consumed.coerceIn(1, composing.length))
        }
        composing.clear()
        clearCandidates()
    }

    /** 切换模式/回车前:拼音字母原样上屏,笔画直接丢弃。 */
    private fun flushRaw(ic: InputConnection) {
        if (composing.isNotEmpty()) {
            if (mode == Mode.PINYIN) ic.commitText(composing.toString(), 1) else ic.commitText("", 1)
            composing.clear()
        }
        clearCandidates()
    }

    // ------------------------------------------------------------ 其他

    private fun refreshLayout(animate: Boolean) {
        keyboardView?.setLayout(Layouts.build(mode, enterLabel, shift), animate)
    }

    private fun afterCommit() {
        if (mode != Mode.ENGLISH) return
        val want = capsWanted(currentInputEditorInfo)
        if (want != shift) {
            shift = want
            refreshLayout(animate = false)
        }
    }

    private fun capsWanted(info: EditorInfo?): Boolean {
        if (info == null || secureField) return false
        val ic = currentInputConnection ?: return false
        return ic.getCursorCapsMode(info.inputType) != 0
    }

    private fun enterLabelFor(info: EditorInfo): String {
        val custom = info.actionLabel?.toString()
        if (!custom.isNullOrBlank()) return custom
        if ((info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0) return "回车"
        return when (info.imeOptions and EditorInfo.IME_MASK_ACTION) {
            EditorInfo.IME_ACTION_GO -> "前往"
            EditorInfo.IME_ACTION_SEARCH -> "搜索"
            EditorInfo.IME_ACTION_SEND -> "发送"
            EditorInfo.IME_ACTION_NEXT -> "下一项"
            EditorInfo.IME_ACTION_PREVIOUS -> "上一项"
            EditorInfo.IME_ACTION_DONE -> "完成"
            else -> "回车"
        }
    }
}
