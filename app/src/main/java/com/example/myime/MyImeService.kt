package com.example.myime

import android.content.ClipboardManager
import android.content.Intent
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import java.util.concurrent.Executors

class MyImeService : InputMethodService(), KeyboardView.Listener {

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()

    @Volatile private var pinyinEngine: Engine? = null
    @Volatile private var strokeEngine: Engine? = null

    private var keyboardView: KeyboardView? = null
    private var candidateBar: CandidateBarView? = null
    private var emojiPanel: EmojiPanelView? = null
    private var clipPanel: ClipboardPanelView? = null
    private var settingsPanel: SettingsPanelView? = null
    private var choicePanel: SettingsPanelView? = null     // 键盘选择(拼音/笔画/英文)
    private var adjustOverlay: AdjustOverlayView? = null
    private var boxView: FrameLayout? = null
    private var adjusting = false           // 正在「键盘调节」

    // 剪贴板历史:只保存在内存里,最多 500 条,进程结束就清空,不写入存储
    private val clips = ArrayList<String>()
    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener { captureClipboardNow() }

    private var textMode = Mode.PINYIN      // 最近使用的文字模式(拼音/笔画/英文)
    private var chineseMode = Mode.PINYIN   // 最近使用的中文方式(拼音/笔画)
    private var mode = Mode.PINYIN          // 当前显示的页面(也可能是符号/数字/编辑)
    private var secureField = false         // 密码框:只允许英文/符号,不使用组合区
    private var shift = false              // 字母显示/输入大写
    private var shiftLock = false           // true = 锁定大写;false 且 shift=true = 只大写下一个字母
    private var selecting = false           // 光标编辑页的「选择」状态
    private var enterLabel: String? = null  // null = 用回车图标
    private var layoutComposing = false

    private val composing = StringBuilder()
    private var candidates: List<Candidate> = emptyList()
    private var candidatesFor = ""
    private var queryGen = 0

    // ------------------------------------------------------------ 生命周期

    override fun onCreate() {
        super.onCreate()
        KbSize.load(this)
        try {
            (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).addPrimaryClipChangedListener(clipListener)
        } catch (e: Exception) {
            // 注册失败只是没有剪贴板历史,不影响输入
        }
        worker.execute {
            pinyinEngine = DictLoader.loadPinyin(applicationContext)
            strokeEngine = DictLoader.loadStroke(applicationContext)
        }
    }

    override fun onDestroy() {
        try {
            (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).removePrimaryClipChangedListener(clipListener)
        } catch (e: Exception) {
        }
        worker.shutdownNow()
        super.onDestroy()
    }

    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onCreateInputView(): View {
        val bar = CandidateBarView(this, { index -> onCandidatePicked(index) }, { tool -> onTool(tool) })
        val kb = KeyboardView(this, this)
        val emoji = EmojiPanelView(this, object : EmojiPanelView.Listener {
            override fun onEmoji(emoji: String) {
                currentInputConnection?.commitText(emoji, 1)
            }

            override fun onBack() {
                leavePage()
            }

            override fun onBackspace() {
                sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
            }
        })
        val clip = ClipboardPanelView(this, object : ClipboardPanelView.Listener {
            override fun onPaste(text: String) {
                currentInputConnection?.commitText(text, 1)
                leavePage()
            }

            override fun onDeleteMany(texts: List<String>) {
                clips.removeAll(texts.toSet())
                clipPanel?.setItems(clips.toList())
            }

            override fun onClear() {
                clips.clear()
                clipPanel?.setItems(emptyList())
            }

            override fun onBack() {
                leavePage()
            }
        })
        val settings = SettingsPanelView(this, SettingsPanelView.settingsItems(), null, object : SettingsPanelView.Listener {
            override fun onAction(id: Int) {
                onSettingsAction(id)
            }

            override fun onBack() {
                leavePage()
            }
        })
        val choice = SettingsPanelView(this, SettingsPanelView.keyboardItems(), "键盘选择", object : SettingsPanelView.Listener {
            override fun onAction(id: Int) {
                onChoiceAction(id)
            }

            override fun onBack() {
                leavePage()
            }
        })
        val overlay = AdjustOverlayView(this, object : AdjustOverlayView.Listener {
            override fun onAdjust() {
                applySizeLive()
            }

            override fun onReset() {
                KbSize.reset()
                applySizeLive()
            }

            override fun onConfirm() {
                exitAdjust(save = true)
            }
        })
        emoji.visibility = View.GONE
        clip.visibility = View.GONE
        settings.visibility = View.GONE
        choice.visibility = View.GONE
        overlay.visibility = View.GONE
        candidateBar = bar
        keyboardView = kb
        emojiPanel = emoji
        clipPanel = clip
        settingsPanel = settings
        choicePanel = choice
        adjustOverlay = overlay

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                bar,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            for (v in listOf<View>(kb, emoji, clip, settings, choice)) {
                addView(
                    v,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ),
                )
            }
        }
        // box 是整个键盘(候选栏 + 键盘/面板 + 调节遮罩),宽度、左边距、离底距离由「键盘调节」决定
        val box = FrameLayout(this).apply {
            addView(
                col,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            addView(
                overlay,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
        }
        boxView = box
        val root = FrameLayout(this).apply {
            addView(
                box,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.START or Gravity.BOTTOM,
                ),
            )
        }
        applyBoxSize()
        refreshLayout(animate = false)
        return root
    }

    // ------------------------------------------------------------ 键盘调节

    /** 把 KbSize 里的宽度、左边距、离底距离应用到 box 上。 */
    private fun applyBoxSize() {
        val box = boxView ?: return
        val sw = resources.displayMetrics.widthPixels
        val lp = box.layoutParams as FrameLayout.LayoutParams
        lp.width = (sw * KbSize.widthFrac).toInt().coerceAtLeast(1)
        lp.leftMargin = (sw * KbSize.leftFrac).toInt()
        lp.bottomMargin = dpi(KbSize.bottomDp)
        lp.gravity = Gravity.START or Gravity.BOTTOM
        box.layoutParams = lp
    }

    /** 拖动过程中实时生效:位置/宽度走布局参数,高度要让键盘和各面板重新取一次。 */
    private fun applySizeLive() {
        applyBoxSize()
        keyboardView?.refresh()
        for (p in listOf<PanelView?>(emojiPanel, clipPanel, settingsPanel, choicePanel)) {
            if (p != null && p.visibility == View.VISIBLE) p.refresh()
        }
    }

    private fun enterAdjust() {
        leavePage()                // 回到打字键盘,在它上面调
        adjusting = true
        adjustOverlay?.visibility = View.VISIBLE
    }

    /** save=false:放弃这次拖动,恢复到上次保存的尺寸。 */
    private fun exitAdjust(save: Boolean) {
        if (!adjusting) return
        adjusting = false
        adjustOverlay?.visibility = View.GONE
        if (save) {
            KbSize.save(this)
        } else {
            KbSize.load(this)
            applySizeLive()
        }
    }

    // ------------------------------------------------------------ 功能页

    /** 切到指定的文字模式(拼音 / 笔画 / 英文),并回到键盘页。密码框只允许英文。 */
    private fun setTextMode(m: Mode) {
        if (secureField && m != Mode.ENGLISH) {
            leavePage()
            return
        }
        flushRawNow()
        selecting = false
        textMode = m
        if (m != Mode.ENGLISH) chineseMode = m
        mode = m
        shift = false
        shiftLock = false
        if (m == Mode.ENGLISH) shift = capsWanted(currentInputEditorInfo)
        refreshLayout(animate = true)
    }

    private fun onChoiceAction(id: Int) {
        when (id) {
            SettingsPanelView.ID_PINYIN -> setTextMode(Mode.PINYIN)
            SettingsPanelView.ID_STROKE -> setTextMode(Mode.STROKE)
            SettingsPanelView.ID_ENGLISH -> setTextMode(Mode.ENGLISH)
            SettingsPanelView.ID_PICKER -> {
                leavePage()
                (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
            }
        }
    }

    private fun onSettingsAction(id: Int) {
        when (id) {
            SettingsPanelView.ID_LANG -> {
                leavePage()
                toggleLang()
            }
            SettingsPanelView.ID_METHOD -> {
                leavePage()
                if (!secureField) {
                    if (mode == Mode.ENGLISH) {
                        // 英文下点「拼音笔画」:回到中文,并换成另一种中文方式
                        chineseMode = if (chineseMode == Mode.STROKE) Mode.PINYIN else Mode.STROKE
                        toggleLang()
                    } else {
                        switchMethod()
                    }
                }
            }
            SettingsPanelView.ID_NUMBER -> gotoPage(Mode.NUMBER)
            SettingsPanelView.ID_SYMBOL -> gotoPage(Mode.SYMBOL)
            SettingsPanelView.ID_EMOJI -> gotoPage(Mode.EMOJI)
            SettingsPanelView.ID_CLIP -> gotoPage(Mode.CLIPBOARD)
            SettingsPanelView.ID_EDIT -> gotoPage(Mode.EDIT)
            SettingsPanelView.ID_IME -> {
                leavePage()
                (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
            }
            SettingsPanelView.ID_ADJUST -> enterAdjust()
            SettingsPanelView.ID_APP -> {
                startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            SettingsPanelView.ID_HIDE -> requestHideSelf(0)
        }
    }

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        composing.clear()
        queryGen++
        candidates = emptyList()
        candidatesFor = ""
        layoutComposing = false
        selecting = false

        val cls = info.inputType and InputType.TYPE_MASK_CLASS
        val variation = info.inputType and InputType.TYPE_MASK_VARIATION
        secureField = false
        var forced: Mode? = null
        when (cls) {
            InputType.TYPE_CLASS_NUMBER -> {
                forced = Mode.NUMBER
                if (variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD) secureField = true
            }
            InputType.TYPE_CLASS_PHONE, InputType.TYPE_CLASS_DATETIME -> forced = Mode.NUMBER
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
        shiftLock = false
        if (mode == Mode.ENGLISH) shift = capsWanted(info)

        applyBoxSize()
        keyboardView?.refresh()
        candidateBar?.refresh()
        candidateBar?.setComposing("")
        candidateBar?.setCandidates(emptyList(), animate = false)
        refreshLayout(animate = false)
    }

    override fun onFinishInput() {
        super.onFinishInput()
        exitAdjust(save = false)
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
            ActionType.LANG -> toggleLang()
            ActionType.METHOD -> switchMethod()
            ActionType.SEPARATOR -> typeSeparator()
            ActionType.SYMBOL -> when (spec.value) {
                "symbol" -> gotoPage(Mode.SYMBOL)
                "number" -> gotoPage(Mode.NUMBER)
                else -> leavePage()
            }
            ActionType.SHIFT -> {
                // 小写 → 锁定大写 → 下一个字母大写(打一个就自动变回小写) → 小写
                if (!shift) {
                    shift = true
                    shiftLock = true
                } else if (shiftLock) {
                    shiftLock = false
                } else {
                    shift = false
                }
                refreshLayout(animate = false)
            }
            ActionType.EDIT -> onEdit(spec.value)
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

    /** 分词键:在拼音输入里插入音节边界。 */
    private fun typeSeparator() {
        if (mode != Mode.PINYIN || composing.isEmpty()) return
        if (composing[composing.length - 1] == '\'') return
        composing.append('\'')
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

    // ------------------------------------------------------------ 页面切换

    private fun flushRawNow() {
        currentInputConnection?.let { ic ->
            ic.beginBatchEdit()
            flushRaw(ic)
            ic.endBatchEdit()
        }
    }

    /** 中/英:在最近用的中文方式和英文之间切换。 */
    private fun toggleLang() {
        if (secureField) return
        if (mode != Mode.PINYIN && mode != Mode.STROKE && mode != Mode.ENGLISH) return
        flushRawNow()
        if (mode == Mode.ENGLISH) {
            textMode = chineseMode
        } else {
            chineseMode = mode
            textMode = Mode.ENGLISH
        }
        mode = textMode
        shift = false
        shiftLock = false
        if (mode == Mode.ENGLISH) shift = capsWanted(currentInputEditorInfo)
        refreshLayout(animate = true)
    }

    /** 拼音 ↔ 笔画。 */
    private fun switchMethod() {
        if (secureField) return
        if (mode != Mode.PINYIN && mode != Mode.STROKE) return
        flushRawNow()
        val next = if (mode == Mode.STROKE) Mode.PINYIN else Mode.STROKE
        chineseMode = next
        textMode = next
        mode = next
        refreshLayout(animate = true)
    }

    private fun gotoPage(target: Mode) {
        flushRawNow()
        selecting = false
        if (target == Mode.CLIPBOARD) captureClipboardNow()
        mode = target
        refreshLayout(animate = true)
    }

    private fun leavePage() {
        selecting = false
        mode = if (secureField) Mode.ENGLISH else textMode
        shift = false
        shiftLock = false
        if (mode == Mode.ENGLISH) shift = capsWanted(currentInputEditorInfo)
        refreshLayout(animate = true)
    }

    private fun onTool(tool: Int) {
        when (tool) {
            CandidateBarView.TOOL_SETTINGS -> {
                if (mode == Mode.SETTINGS) leavePage() else gotoPage(Mode.SETTINGS)
            }
            CandidateBarView.TOOL_SWITCH_IME -> {
                if (mode == Mode.KEYBOARD) leavePage() else gotoPage(Mode.KEYBOARD)
            }
            CandidateBarView.TOOL_EMOJI -> {
                if (mode == Mode.EMOJI) leavePage() else gotoPage(Mode.EMOJI)
            }
            CandidateBarView.TOOL_EDIT -> {
                if (mode == Mode.EDIT) leavePage() else gotoPage(Mode.EDIT)
            }
            CandidateBarView.TOOL_CLIPBOARD -> {
                if (mode == Mode.CLIPBOARD) leavePage() else gotoPage(Mode.CLIPBOARD)
            }
            CandidateBarView.TOOL_HIDE -> requestHideSelf(0)
        }
    }

    // ------------------------------------------------------------ 光标编辑页

    private fun sendKey(code: Int, meta: Int = 0) {
        val ic = currentInputConnection ?: return
        val t = SystemClock.uptimeMillis()
        ic.sendKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0, meta))
        ic.sendKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0, meta))
    }

    private fun onEdit(v: String) {
        val ic = currentInputConnection ?: return
        val meta = if (selecting) KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON else 0
        when (v) {
            "tab" -> sendKey(KeyEvent.KEYCODE_TAB)
            "copy" -> ic.performContextMenuAction(android.R.id.copy)
            "paste" -> ic.performContextMenuAction(android.R.id.paste)
            "cut" -> ic.performContextMenuAction(android.R.id.cut)
            "all" -> ic.performContextMenuAction(android.R.id.selectAll)
            "up" -> sendKey(KeyEvent.KEYCODE_DPAD_UP, meta)
            "down" -> sendKey(KeyEvent.KEYCODE_DPAD_DOWN, meta)
            "left" -> sendKey(KeyEvent.KEYCODE_DPAD_LEFT, meta)
            "right" -> sendKey(KeyEvent.KEYCODE_DPAD_RIGHT, meta)
            "home" -> sendKey(KeyEvent.KEYCODE_MOVE_HOME, meta)
            "end" -> sendKey(KeyEvent.KEYCODE_MOVE_END, meta)
            "del" -> sendKey(KeyEvent.KEYCODE_FORWARD_DEL)
            "select" -> {
                selecting = !selecting
                refreshLayout(animate = false)
            }
            "back" -> leavePage()
        }
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
        '*' -> '*'
        else -> 'ㄥ'
    }

    private fun showComposing() {
        currentInputConnection?.setComposingText(displayComposing(), 1)
    }

    /** 组合内容变了:更新预览条;「分词/笔画」「清除/回车」这类键的文字也随之切换。 */
    private fun onComposingChanged() {
        candidateBar?.setComposing(displayComposing())
        val c = composing.isNotEmpty()
        if (c != layoutComposing) {
            layoutComposing = c
            refreshLayout(animate = false)
        }
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
        onComposingChanged()
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
        onComposingChanged()
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

    private fun kbState() = KbState(enterLabel, shift, composing.isNotEmpty(), selecting, shiftLock)

    private fun refreshLayout(animate: Boolean) {
        val kb = keyboardView ?: return
        candidateBar?.setActiveTool(
            when (mode) {
                Mode.EMOJI -> CandidateBarView.TOOL_EMOJI
                Mode.EDIT -> CandidateBarView.TOOL_EDIT
                Mode.CLIPBOARD -> CandidateBarView.TOOL_CLIPBOARD
                Mode.SETTINGS -> CandidateBarView.TOOL_SETTINGS
                Mode.KEYBOARD -> CandidateBarView.TOOL_SWITCH_IME
                else -> -1
            },
        )
        when (mode) {
            Mode.EMOJI -> showPanel(emojiPanel, animate)
            Mode.CLIPBOARD -> {
                clipPanel?.setItems(clips.toList())
                showPanel(clipPanel, animate)
            }
            Mode.SETTINGS -> showPanel(settingsPanel, animate)
            Mode.KEYBOARD -> {
                choicePanel?.selectedId = when (textMode) {
                    Mode.STROKE -> SettingsPanelView.ID_STROKE
                    Mode.ENGLISH -> SettingsPanelView.ID_ENGLISH
                    else -> SettingsPanelView.ID_PINYIN
                }
                showPanel(choicePanel, animate)
            }
            else -> {
                emojiPanel?.visibility = View.GONE
                clipPanel?.visibility = View.GONE
                settingsPanel?.visibility = View.GONE
                choicePanel?.visibility = View.GONE
                kb.visibility = View.VISIBLE
                kb.setLayout(Layouts.build(mode, kbState(), isWide()), false)
            }
        }
    }

    /** 面板和键盘同一时间只显示一个,高度相同,所以切换时不会跳动。 */
    private fun showPanel(panel: PanelView?, animate: Boolean) {
        if (panel == null) return
        keyboardView?.visibility = View.GONE
        for (p in listOf<PanelView?>(emojiPanel, clipPanel, settingsPanel, choicePanel)) {
            if (p !== panel) p?.visibility = View.GONE
        }
        val wasVisible = panel.visibility == View.VISIBLE
        panel.visibility = View.VISIBLE
        if (!wasVisible) panel.onShow(false)   // 不做滑入/淡入动画,直接显示
    }

    /** 读取当前剪贴板并加入历史(跳过被标记为敏感的内容,如密码管理器复制的密码)。 */
    private fun captureClipboardNow() {
        try {
            val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            val clip = cm.primaryClip ?: return
            if (clip.itemCount == 0) return
            val desc = clip.description
            if (!desc.hasMimeType("text/*")) return
            if (desc.extras?.getBoolean("android.content.extra.IS_SENSITIVE", false) == true) return
            val text = clip.getItemAt(0).coerceToText(this)?.toString() ?: return
            if (text.isBlank() || text.length > 5000) return
            clips.remove(text)
            clips.add(0, text)
            while (clips.size > 500) clips.removeAt(clips.size - 1)
            if (mode == Mode.CLIPBOARD) clipPanel?.setItems(clips.toList())
        } catch (e: Exception) {
            // 没有读取权限(例如当前不是默认输入法)时直接忽略
        }
    }

    private fun afterCommit() {
        if (mode != Mode.ENGLISH) return
        if (shiftLock) return   // 锁定大写:一直大写,不自动变回
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

    /** 返回 null 表示没有特殊动作,键盘上显示回车图标。 */
    private fun enterLabelFor(info: EditorInfo): String? {
        val custom = info.actionLabel?.toString()
        if (!custom.isNullOrBlank()) return custom
        if ((info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0) return null
        return when (info.imeOptions and EditorInfo.IME_MASK_ACTION) {
            EditorInfo.IME_ACTION_GO -> "前往"
            EditorInfo.IME_ACTION_SEARCH -> "搜索"
            EditorInfo.IME_ACTION_SEND -> "发送"
            EditorInfo.IME_ACTION_NEXT -> "下一项"
            EditorInfo.IME_ACTION_PREVIOUS -> "上一项"
            EditorInfo.IME_ACTION_DONE -> "完成"
            else -> null
        }
    }
}
