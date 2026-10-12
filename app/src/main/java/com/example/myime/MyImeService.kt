package com.example.myime

import android.content.ClipboardManager
import android.content.ComponentCallbacks2
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
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
import android.view.inputmethod.InputConnectionWrapper
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import java.util.concurrent.Executors

class MyImeService : InputMethodService(), KeyboardView.Listener {

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()

    @Volatile private var pinyinEngine: Engine? = null
    @Volatile private var strokeEngine: Engine? = null
    @Volatile private var englishEngine: EnglishEngine? = null
    private val loader = Executors.newSingleThreadExecutor()   // 词库加载单独一个线程,不堵住候选查询
    private var loadedStamp = ""
    private var lastStampCheck = 0L
    private val dictState = IntArray(3)          // 0 没加载 1 加载中 2 已试过
    private var dictGen = 0                      // 词库被换/释放一次就加一,过期的加载结果直接丢掉
    private val releaseLater = Runnable { releaseDicts() }

    private var keyboardView: KeyboardView? = null
    private var candidateBar: CandidateBarView? = null
    private var emojiPanel: EmojiPanelView? = null
    private var symbolPanel: SymbolPanelView? = null
    private var clipPanel: ClipboardPanelView? = null
    private var settingsPanel: SettingsPanelView? = null
    private var choicePanel: SettingsPanelView? = null     // 键盘选择(拼音/笔画/英文)
    private var adjustOverlay: AdjustOverlayView? = null
    private var floatHandle: FloatHandleView? = null
    private var inputRoot: FrameLayout? = null
    private var boxView: FrameLayout? = null
    private var adjusting = false           // 正在「键盘调节」

    // 输入翻译:翻译开着时,键盘打的字都先进翻译框(transBuf);停一下就拿去翻译,译文实时写进目标输入框(当作组合区);
    // 点「完成」/ 回车 = 译文留在目标输入框里,翻译框清空。所有对输入框的操作都经过 getCurrentInputConnection(),
    // 翻译开着时它返回一个 TransIc,把「上屏 / 退格 / 回车」改成对翻译框的操作,其余代码不用改。
    private var transBar: TranslateBarView? = null
    private var transOn = false             // 设置里的开关
    private var transAllowed = false        // 当前输入框能不能用(密码 / 数字 / 邮箱框不能)
    private var transActive = false         // transOn && transAllowed:翻译框显示、打字进翻译框
    private val transBuf = StringBuilder()  // 翻译框里的原文
    private var transSrc = ""               // 目标输入框里现在这份译文对应的原文
    private var transShown = ""             // 目标输入框里现在的译文(组合区)
    private var transInflight: String? = null   // 正在翻译的原文,同一句不重复请求
    @Volatile private var transGen = 0     // 原文每变一次加一,过期的翻译结果直接丢掉(后台线程也读它,排队里的过期请求不会发出去)
    private var transStatus = TranslateBarView.STATUS_NONE
    private var transFinishing = false      // 点了完成但译文还没回来:回来后再收尾
    private var transFilled = false         // 翻译框有没有字(回车键上的「完成」跟着变)
    private var transBypass = false         // true:暂时直接操作目标输入框(翻译框空时的回车 / 光标编辑页)
    private var transPair = 0               // Translator.pairs 的序号
    private var transIc: TransIc? = null
    private var transIcReal: InputConnection? = null
    private val netPool = Executors.newSingleThreadExecutor()   // 联网翻译单独一个线程(不堵候选查询);一次只发一个请求,排队里过期的直接跳过
    private var transNetCb: ConnectivityManager.NetworkCallback? = null   // 没网时临时挂上,网络回来重试一次就摘掉
    private val transRun = Runnable { startTranslate() }

    // 剪贴板历史:最多 500 条,落盘保存(ClipStore),更新版本 / 切换输入法 / 进程被回收都不会丢。
    // 写盘有 2 秒的合并延迟:连续复制只写一次,不为每次复制都唤醒磁盘(省电)。
    private val clips = ArrayList<String>()
    private var clipsLoaded = false         // 磁盘上的历史读回来之前,不写盘,免得用不完整的列表覆盖旧文件
    private var clipsVersion = 0            // 每改一次加一
    @Volatile private var clipsSavedVersion = 0
    private val saveClipsLater = Runnable { saveClipsNow() }
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
    private var englishAssist = false       // 当前输入框是否启用英文补全 / 联想 / 纠错
    private var allowLearn = false          // 当前输入框是否记录我的用词
    private var prevWord: String? = null    // 上一个已上屏的英文词(小写),给联想用
        set(v) {
            field = v
            englishEngine?.prev = v
        }
    private var prevChinese2: String? = null  // 再上一个词(两词上下文,更准)
    private var prevChinese: String? = null   // 上一个上屏的中文词,给中文联想("我常在 X 后面打 Y")和拼音候选排序用
        set(v) {
            prevChinese2 = if (v == null) null else field   // 设新词时,旧的退到"上上个";清空时两个一起清
            field = v
            (pinyinEngine as? PinyinEngine)?.let { it.prev = v; it.prev2 = prevChinese2 }
        }
    private var assocKey: String? = null      // 中文联想:接着往下查词库的前缀
    private var lastOwnEditAt = 0L            // 我们自己最近一次改输入框的时间,用来分辨光标是不是被用户挪了

    /** 联想词显示一会儿就收回去,把工具栏还给用户。 */
    private val assocClear = Runnable {
        if (composing.isEmpty() && candidatesFor.isEmpty() && candidates.isNotEmpty()) {
            queryGen++
            applyCandidates("", emptyList())
        }
    }

    private companion object {
        const val KIND_PINYIN = 0
        const val KIND_STROKE = 1
        const val KIND_ENGLISH = 2
        const val MAX_CLIP_CHARS = 5000   // 单条剪贴板最多保存的字数,更长的不收
        const val TRANS_DEBOUNCE_MS = 600L   // 翻译框停多久才去翻:越长请求越少越省电,越短译文越跟手
    }

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
        loadedStamp = DictStore.stamp(applicationContext)
        loadClips()
    }

    // ------------------------------------------------------------ 剪贴板历史落盘

    /** 后台读回上次保存的历史,和这期间新复制的合并(新的在前、去重、最多 500 条)。 */
    private fun loadClips() {
        val ctx = applicationContext
        worker.execute {
            val saved = ClipStore.load(ctx)
            main.post {
                if (clipsLoaded) return@post
                clipsLoaded = true
                if (saved.isNotEmpty()) {
                    val seen = HashSet<String>(clips)
                    for (t in saved) {
                        if (clips.size >= 500) break
                        if (seen.add(t)) clips.add(t)
                    }
                    if (mode == Mode.CLIPBOARD) clipPanel?.setItems(clips.toList())
                }
                if (clips != saved) clipsChanged()   // 加载期间有新复制的,补写一次
            }
        }
    }

    /** 历史改了:2 秒后合并写一次盘。 */
    private fun clipsChanged() {
        clipsVersion++
        if (!clipsLoaded) return
        main.removeCallbacks(saveClipsLater)
        main.postDelayed(saveClipsLater, 2000)
    }

    /** 立刻在后台线程写盘(键盘隐藏时调用)。 */
    private fun saveClipsNow() {
        main.removeCallbacks(saveClipsLater)
        if (!clipsLoaded || clipsSavedVersion == clipsVersion) return
        val v = clipsVersion
        val snapshot = ArrayList(clips)
        val ctx = applicationContext
        try {
            worker.execute {
                ClipStore.save(ctx, snapshot)
                clipsSavedVersion = v
            }
        } catch (e: Exception) {
            // 线程池已关闭:onDestroy 里会同步补写
        }
    }

    // ------------------------------------------------------------ 词库:按需加载、闲置释放

    /** 当前页面要用哪个词库,就加载哪个(只加载一次)。没用到的词库不读,不占内存。 */
    private fun warmUp() {
        when (mode) {
            Mode.PINYIN -> loadKind(KIND_PINYIN)
            Mode.STROKE -> loadKind(KIND_STROKE)
            Mode.ENGLISH -> if (englishAssist) loadKind(KIND_ENGLISH)
            else -> {}
        }
    }

    private fun loadKind(kind: Int) {
        if (dictState[kind] != 0) return
        dictState[kind] = 1
        val gen = dictGen
        loader.execute {
            if (gen != dictGen) return@execute   // 排队期间词库已被换/释放:不用再读了
            val ctx = applicationContext
            val engine: Any? = when (kind) {
                KIND_PINYIN -> DictLoader.loadPinyin(ctx)
                KIND_STROKE -> DictLoader.loadStroke(ctx)
                else -> DictLoader.loadEnglish(ctx)
            }
            main.post {
                if (gen == dictGen) {
                    when (kind) {
                        KIND_PINYIN -> {
                            pinyinEngine = engine as Engine?
                            (engine as? PinyinEngine)?.let {
                                it.chain = UserData.chain(applicationContext)
                                it.prev = prevChinese
                                it.prev2 = prevChinese2
                            }
                        }
                        KIND_STROKE -> strokeEngine = engine as Engine?
                        else -> {
                            val en = engine as EnglishEngine?
                            en?.prev = prevWord
                            englishEngine = en
                        }
                    }
                    dictState[kind] = 2 // 不管成败都算试过一次,不会反复重试
                    if (composing.isNotEmpty()) requery()
                    else if (kind == KIND_PINYIN && assocKey != null) showChineseAssoc()
                }
            }
        }
    }

    /** 放掉所有词库:词库文件换了,或键盘隐藏很久 / 系统缺内存。下次用到时 warmUp 会重新加载。 */
    private fun releaseDicts() {
        dictGen++
        pinyinEngine = null
        strokeEngine = null
        englishEngine = null
        for (i in dictState.indices) dictState[i] = 0
    }

    override fun onWindowHidden() {
        super.onWindowHidden()
        // 键盘收起了:没发出的翻译、没回来的请求、挂着的网络回调都放掉,不在后台耗电耗流量
        transBar?.dismissPopup()
        main.removeCallbacks(transRun)
        transGen++
        transInflight = null
        transFinishing = false
        unwatchNetwork()
        main.removeCallbacks(assocClear)
        worker.execute { UserData.saveAll() }          // 学习数据只在键盘隐藏时落盘,打字过程中不写文件
        saveClipsNow()
        main.removeCallbacks(releaseLater)
        main.postDelayed(releaseLater, 90_000)         // 隐藏 90 秒还没回来,就把词库内存还给系统
    }

    override fun onWindowShown() {
        super.onWindowShown()
        main.removeCallbacks(releaseLater)
        // 收起前翻译框里还有没翻完的字:现在补翻一次
        if (transActive && transBuf.isNotBlank() && transBuf.toString() != transSrc) {
            transStatus = TranslateBarView.STATUS_NONE
            startTranslate()
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND && !isInputViewShown) releaseDicts()
    }

    override fun onDestroy() {
        try {
            (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).removePrimaryClipChangedListener(clipListener)
        } catch (e: Exception) {
        }
        main.removeCallbacks(assocClear)
        main.removeCallbacks(saveClipsLater)
        main.removeCallbacks(transRun)
        unwatchNetwork()
        netPool.shutdownNow()
        UserData.saveAll()
        // 还有没写盘的剪贴板记录:这里同步写完(线程池马上要关,排在里面的写盘任务会被丢掉)
        if (clipsLoaded && clipsSavedVersion != clipsVersion) {
            ClipStore.save(applicationContext, ArrayList(clips))
            clipsSavedVersion = clipsVersion
        }
        worker.shutdownNow()
        loader.shutdownNow()
        super.onDestroy()
    }

    override fun onEvaluateFullscreenMode(): Boolean = false

    /**
     * 输入翻译开着时,返回一个「假」的输入连接:上屏 / 退格 / 回车都落到翻译框里,而不是目标输入框。
     * 本类里所有的 currentInputConnection、sendDownUpKeyEvents 都会走到这里,所以拼音 / 笔画 / 英文 / 表情 / 符号 / 剪贴板
     * 不用各自处理。要直接操作目标输入框时用 realIc(),或临时把 transBypass 置 true。
     */
    override fun getCurrentInputConnection(): InputConnection? {
        val real = super.getCurrentInputConnection() ?: return null
        if (!transActive || transBypass) return real
        val w = transIc
        if (w != null && transIcReal === real) return w
        val n = TransIc(real)
        transIc = n
        transIcReal = real
        return n
    }

    private fun realIc(): InputConnection? = super.getCurrentInputConnection()

    override fun onCreateInputView(): View {
        val bar = CandidateBarView(
            this,
            { index -> onCandidatePicked(index) },
            { tool -> onTool(tool) },
            { closeCandidates() },
        )
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
        val symbol = SymbolPanelView(this, object : SymbolPanelView.Listener {
            override fun onSymbol(text: String) {
                val ic = currentInputConnection ?: return
                commitTo(ic, text)
                prevWord = null      // 打了符号:英文联想 / 中文联想都断开
                breakAssoc()
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
                clipsChanged()
                clipPanel?.setItems(clips.toList())
            }

            override fun onClear() {
                clips.clear()
                clipsChanged()
                clipPanel?.setItems(emptyList())
            }

            override fun onBack() {
                leavePage()
            }
        })
        val settings = SettingsPanelView(this, SettingsPanelView.settingsItems(), "功能", object : SettingsPanelView.Listener {
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

            override fun onExtra(id: Int) {
                cycleDefaultMode()
            }

            override fun onBack() {
                leavePage()
            }
        })
        val tbar = TranslateBarView(this, object : TranslateBarView.Listener {
            override fun onPickLang(index: Int) {
                transSetLang(index)
            }

            override fun onClear() {
                transClear()
            }

            override fun onDone() {
                val ic = currentInputConnection
                if (composing.isNotEmpty() && ic != null) {
                    // 还有没选字的拼音/英文:先按最佳候选放进翻译框
                    ic.beginBatchEdit()
                    flushComposition(ic)
                    ic.endBatchEdit()
                }
                transFinish()
            }

            override fun onClose() {
                setTransOn(false)
            }
        })
        tbar.visibility = View.GONE
        transBar = tbar
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
        symbol.visibility = View.GONE
        clip.visibility = View.GONE
        settings.visibility = View.GONE
        choice.visibility = View.GONE
        overlay.visibility = View.GONE
        candidateBar = bar
        keyboardView = kb
        emojiPanel = emoji
        symbolPanel = symbol
        clipPanel = clip
        settingsPanel = settings
        choicePanel = choice
        choice.extraLabel = defaultModeLabel()
        adjustOverlay = overlay

        val handle = FloatHandleView(this, object : FloatHandleView.Listener {
            override fun onMove() {
                applyBoxSize()
            }

            override fun onResize() {
                applyBoxSize()   // 只是缩放,不用重新排版
            }

            override fun onDone() {
                KbSize.save(this@MyImeService)
                inputRoot?.requestLayout()   // 让系统按新位置重算「哪里接收触摸」
            }

            override fun boxHeightPx(): Int = boxView?.height ?: 0
        })
        floatHandle = handle
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                handle,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            addView(
                tbar,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            addView(
                bar,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            for (v in listOf<View>(kb, emoji, symbol, clip, settings, choice)) {
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
        inputRoot = root
        box.addOnLayoutChangeListener { v, _, top, _, bottom, _, _, _, _ -> v.pivotY = (bottom - top).toFloat() }
        applyBoxSize()
        refreshLayout(animate = false)
        return root
    }

    // ------------------------------------------------------------ 键盘调节

    /**
     * 把 KbSize 应用到 box 上。
     *  - 贴底模式:宽度、左边距、离底距离走布局参数。
     *  - 悬浮模式:键盘按固定宽度排好版,再用缩放 + 平移摆到指定位置(不重新排版,所以拖动、缩放都能跟手)。
     */
    private fun applyBoxSize() {
        val box = boxView ?: return
        val root = inputRoot
        val dm = resources.displayMetrics
        val sw = dm.widthPixels
        val lp = box.layoutParams as FrameLayout.LayoutParams
        if (KbSize.floating) {
            val refW = KbSize.floatRefWidthPx(this)
            val s = KbSize.scale.coerceIn(KbSize.MIN_SCALE, KbSize.maxScale(this))
            val visW = refW * s
            val visH = box.height * s
            val left = (sw * KbSize.leftFrac).coerceIn(0f, (sw - visW).coerceAtLeast(0f))
            var bottom = dpf(KbSize.bottomDp)
            if (box.height > 0) bottom = bottom.coerceIn(0f, (dm.heightPixels - visH - dpf(24f)).coerceAtLeast(0f))
            if (lp.width != refW || lp.leftMargin != 0 || lp.bottomMargin != 0) {
                lp.width = refW
                lp.leftMargin = 0
                lp.bottomMargin = 0
                lp.gravity = Gravity.START or Gravity.BOTTOM
                box.layoutParams = lp
            }
            // 窗口撑到整屏高,键盘在里面平移;窗口其余部分在 onComputeInsets 里设成不接收触摸
            if (root != null && root.minimumHeight != dm.heightPixels) root.minimumHeight = dm.heightPixels
            box.pivotX = 0f
            box.pivotY = box.height.toFloat()
            box.scaleX = s
            box.scaleY = s
            box.translationX = left
            box.translationY = -bottom
        } else {
            box.scaleX = 1f
            box.scaleY = 1f
            box.translationX = 0f
            box.translationY = 0f
            // 调节键盘大小的过程中,窗口固定成整屏高,键盘在里面变大变小;
            // 否则每一帧都要让系统重新给窗口定尺寸,画面就会一跳一跳的
            val wantMin = if (adjusting) dm.heightPixels else 0
            if (root != null && root.minimumHeight != wantMin) root.minimumHeight = wantMin
            val w = (sw * KbSize.widthFrac).toInt().coerceAtLeast(1)
            val l = (sw * KbSize.leftFrac).toInt()
            val b = dpi(KbSize.bottomDp)
            if (lp.width != w || lp.leftMargin != l || lp.bottomMargin != b) {
                lp.width = w
                lp.leftMargin = l
                lp.bottomMargin = b
                lp.gravity = Gravity.START or Gravity.BOTTOM
                box.layoutParams = lp
            }
        }
        floatHandle?.visibility = if (KbSize.floating) View.VISIBLE else View.GONE
    }

    /**
     * 悬浮键盘:只有键盘那一块接收触摸,其余地方的点击直接落到下面的应用上;
     * 也不把应用界面往上顶(键盘盖在应用上面,想放哪放哪)。贴底的普通模式照旧。
     */
    override fun onComputeInsets(outInsets: InputMethodService.Insets) {
        super.onComputeInsets(outInsets)
        val box = boxView ?: return
        val root = inputRoot ?: return
        if (box.width == 0 || box.height == 0 || root.height == 0) return
        if (!KbSize.floating && !adjusting) return
        val decorH = window?.window?.decorView?.height ?: return
        val loc = IntArray(2)
        root.getLocationInWindow(loc)
        val s = if (KbSize.floating) box.scaleX else 1f
        val w = (box.width * s).toInt()
        val h = (box.height * s).toInt()
        val lp = box.layoutParams as FrameLayout.LayoutParams
        val left = loc[0] + lp.leftMargin + box.translationX.toInt()
        val bottom = loc[1] + root.height - lp.bottomMargin + box.translationY.toInt()
        outInsets.contentTopInsets = decorH
        outInsets.visibleTopInsets = decorH
        outInsets.touchableInsets = InputMethodService.Insets.TOUCHABLE_INSETS_REGION
        outInsets.touchableRegion.set(left, bottom - h, left + w, bottom)
    }

    private fun toggleFloating() {
        if (KbSize.floating) KbSize.exitFloating(this) else KbSize.enterFloating(this)
        KbSize.save(this)
        leavePage()
        applySizeLive()
        floatHandle?.refresh()
        inputRoot?.requestLayout()
    }

    /** 拖动过程中实时生效:位置/宽度走布局参数,高度要让键盘和各面板重新取一次。 */
    private fun applySizeLive() {
        applyBoxSize()
        keyboardView?.refresh()
        for (p in listOf<PanelView?>(emojiPanel, symbolPanel, clipPanel, settingsPanel, choicePanel)) {
            if (p != null && p.visibility == View.VISIBLE) p.refresh()
        }
    }

    private fun enterAdjust() {
        leavePage()                // 回到打字键盘,在它上面调
        adjusting = true
        adjustOverlay?.visibility = View.VISIBLE
        applyBoxSize()
        inputRoot?.requestLayout()
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
        }
        applySizeLive()   // 窗口恢复成按键盘实际高度,应用界面这时才跟着调整一次
        inputRoot?.requestLayout()
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
        refreshLayout(animate = true)
    }

    private fun defaultModeLabel(): String = when (DictConfig.defaultMode(this)) {
        "pinyin" -> "默认:拼音"
        "stroke" -> "默认:笔画"
        "english" -> "默认:英文"
        else -> "默认:上次"
    }

    /** 点一下换一种:拼音 → 笔画 → 英文 → 上次用的。下次打开输入法时生效。 */
    private fun cycleDefaultMode() {
        val next = when (DictConfig.defaultMode(this)) {
            "pinyin" -> "stroke"
            "stroke" -> "english"
            "english" -> "last"
            else -> "pinyin"
        }
        DictConfig.setDefaultMode(applicationContext, next)
        choicePanel?.extraLabel = defaultModeLabel()
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
            SettingsPanelView.ID_FLOAT -> toggleFloating()
            SettingsPanelView.ID_TRANS -> setTransOn(!transOn)
            SettingsPanelView.ID_TRANS_ENGINE -> {
                // 微软 ⇄ 谷歌:选中的是主用,主用失败时会自动换另一个
                val next = Translator.other(DictConfig.translateEngine(applicationContext))
                DictConfig.setTranslateEngine(applicationContext, next)
                settingsPanel?.setItemLabel(SettingsPanelView.ID_TRANS_ENGINE, transEngineLabel())
                if (transActive && transBuf.isNotBlank()) {
                    // 换了引擎,当前这句用新引擎重翻一次
                    main.removeCallbacks(transRun)
                    transGen++
                    transInflight = null
                    transSrc = ""
                    startTranslate()
                }
            }
            SettingsPanelView.ID_ADJUST -> {
                // 键盘调节是给贴底模式用的,悬浮模式用顶上的拖动条
                if (KbSize.floating) {
                    KbSize.exitFloating(this)
                    KbSize.save(this)
                    applySizeLive()
                }
                enterAdjust()
            }
            SettingsPanelView.ID_APP -> {
                startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            SettingsPanelView.ID_DICT -> {
                startActivity(Intent(this, DictManagerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
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
        prevWord = null
        prevChinese = null
        assocKey = null
        main.removeCallbacks(assocClear)
        main.removeCallbacks(releaseLater)

        // 悬浮键盘按应用记忆:这个应用 / 游戏上次开了悬浮,这次还是悬浮、还在上次的位置
        var sizeChanged = false
        val appPkg = info.packageName
        if (!restarting || appPkg != KbSize.pkg) {
            val f0 = KbSize.floating
            val l0 = KbSize.leftFrac
            val b0 = KbSize.bottomDp
            val s0 = KbSize.scale
            val h0 = KbSize.heightDp
            val w0 = KbSize.widthFrac
            KbSize.pkg = appPkg
            KbSize.load(this)
            sizeChanged = f0 != KbSize.floating || l0 != KbSize.leftFrac || b0 != KbSize.bottomDp ||
                s0 != KbSize.scale || h0 != KbSize.heightDp || w0 != KbSize.widthFrac
        }
        if (!restarting) captureClipboardNow()   // 切到别的输入法期间复制的内容,回来时补进历史

        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastStampCheck > 5000) {   // 连续切换输入框时不必每次都去查文件
            lastStampCheck = now
            val stamp = DictStore.stamp(applicationContext)
            if (stamp != loadedStamp) {   // 词库被下载 / 手动换过
                loadedStamp = stamp
                releaseDicts()
            }
        }

        val cls = info.inputType and InputType.TYPE_MASK_CLASS
        val variation = info.inputType and InputType.TYPE_MASK_VARIATION
        secureField = false
        var uriField = false
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
                } else if (variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS ||
                    variation == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS
                ) {
                    forced = Mode.ENGLISH
                } else if (variation == InputType.TYPE_TEXT_VARIATION_URI) {
                    // 浏览器首页的搜索框和地址栏常是合在一起的,既要搜中文也要输网址:
                    // 不强制英文,按我设置的默认(或上次用的)输入方式
                    uriField = true
                }
            }
        }

        // 英文补全只用在普通文本框;密码、网址、邮箱、数字框不用。应用声明"不要建议/不要个性化学习"时只是不记录我的用词
        val noSuggest = cls == InputType.TYPE_CLASS_TEXT &&
            (info.inputType and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS) != 0
        val noLearn = (info.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0
        // 网页输入框(GPT 等)常带"不要建议"标记,但这只是应用的提示:候选栏照样给英文补全 / 联想,
        // 是否记录我的用词仍由下面的 allowLearn 单独控制(和中文联想的做法一致)
        englishAssist = cls == InputType.TYPE_CLASS_TEXT && !secureField && forced != Mode.ENGLISH && !uriField
        allowLearn = !secureField && !noSuggest && !noLearn && DictConfig.learn(applicationContext)

        enterLabel = enterLabelFor(info)
        if (!restarting) {
            when (DictConfig.defaultMode(applicationContext)) {
                "pinyin" -> { textMode = Mode.PINYIN; chineseMode = Mode.PINYIN }
                "stroke" -> { textMode = Mode.STROKE; chineseMode = Mode.STROKE }
                "english" -> textMode = Mode.ENGLISH
            }
        }
        mode = forced ?: textMode
        shift = false       // 进入英文不默认大写
        shiftLock = false

        // 输入翻译:只在普通文本框里用;换了输入框就结束上一次翻译(译文留在原来的框里)
        transAllowed = cls == InputType.TYPE_CLASS_TEXT && !secureField && forced == null
        if (!restarting) {
            transReset(finishTarget = false)
            transPair = DictConfig.translatePair(applicationContext)
        }
        transOn = DictConfig.translateOn(applicationContext)
        transActive = transOn && transAllowed
        applyTransState()

        applyBoxSize()
        if (sizeChanged) {
            floatHandle?.refresh()
            inputRoot?.requestLayout()   // 让系统按新的悬浮状态 / 位置重算「哪里接收触摸」
        }
        keyboardView?.refresh()
        candidateBar?.refresh()
        candidateBar?.setComposing("")
        candidateBar?.setCandidates(emptyList(), animate = false)
        refreshLayout(animate = false)
    }

    override fun onFinishInput() {
        super.onFinishInput()
        exitAdjust(save = false)
        transReset(finishTarget = true)
        transUi()
        composing.clear()
        queryGen++
        applyCandidates("", emptyList())
        prevWord = null
        prevChinese = null
        assocKey = null
        main.removeCallbacks(assocClear)
        worker.execute { UserData.saveAll() }
    }

    /** 用户在应用里点别处移动了光标:丢弃我们这边的组合状态。 */
    override fun onUpdateSelection(
        oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int,
        candidatesStart: Int, candidatesEnd: Int,
    ) {
        super.onUpdateSelection(
            oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd,
        )
        if (transActive && transShown.isNotEmpty() && SystemClock.uptimeMillis() - lastOwnEditAt > 500 &&
            (candidatesStart < 0 || newSelEnd < candidatesStart || newSelEnd > candidatesEnd)
        ) {
            // 用户在应用里点了别处 / 译文被应用改掉:译文留在输入框里,翻译框里的原文放弃,不再往那里写
            transReset(finishTarget = false)
            transUi()
            refreshLayout(animate = false)   // 回车键上的「完成」标签收回去
        }
        if (composing.isNotEmpty() && candidatesStart >= 0 &&
            (newSelEnd < candidatesStart || newSelEnd > candidatesEnd)
        ) {
            composing.clear()
            queryGen++
            applyCandidates("", emptyList())
            prevWord = null
            prevChinese = null
            assocKey = null
            currentInputConnection?.finishComposingText()
        } else if ((newSelStart != oldSelStart || newSelEnd != oldSelEnd) &&
            SystemClock.uptimeMillis() - lastOwnEditAt > 500
        ) {
            // 不是我们自己改的、光标却变了。笔画的组合区不在输入框里(没有 candidatesStart 可比),这里一并处理
            if (mode == Mode.STROKE && composing.isNotEmpty()) {
                composing.clear()
                queryGen++
                applyCandidates("", emptyList())
            }
            if (assocKey != null && composing.isEmpty()) breakAssoc()
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
        if (englishActive()) {
            val ch = if (t.length == 1) t[0] else ' '
            val isLetter = ch in 'a'..'z' || ch in 'A'..'Z'
            if (isLetter || (ch == '\'' && composing.isNotEmpty())) {
                main.removeCallbacks(assocClear)
                composing.append(t)
                showComposing()
                requery()
                afterCommit()
                return
            }
        }
        ic.beginBatchEdit()
        flushComposition(ic)
        commitTo(ic, t)
        ic.endBatchEdit()
        if (mode != Mode.ENGLISH) breakAssoc()
        if (mode == Mode.ENGLISH) {
            // 打了标点/数字:联想断开
            prevWord = null
            main.removeCallbacks(assocClear)
            clearCandidates()
        }
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
            if (mode == Mode.ENGLISH) afterCommit()
        } else {
            if (mode == Mode.ENGLISH) {
                prevWord = null
                main.removeCallbacks(assocClear)
                clearCandidates()
            } else {
                breakAssoc()
            }
            sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
            afterCommit()
        }
    }

    private fun space() {
        val ic = currentInputConnection ?: return
        if (composing.isNotEmpty() && mode == Mode.ENGLISH) {
            ic.beginBatchEdit()
            commitEnglishWord(ic, auto = true, trailing = " ")
            ic.endBatchEdit()
            showNextWords()
            afterCommit()
            return
        }
        if (composing.isNotEmpty()) {
            ic.beginBatchEdit()
            val c = bestCandidate()
            if (c == null) {
                if (mode == Mode.PINYIN) commitTo(ic, composing.toString()) else commitTo(ic, "")
                composing.clear()
                prevChinese = null
                assocKey = null
            } else {
                learnChinese(c)
                commitTo(ic, c.text)
                noteChineseCommit(c.text, false)
                composing.delete(0, c.consumed.coerceIn(1, composing.length))
                if (composing.isNotEmpty()) showComposing()
            }
            ic.endBatchEdit()
            afterChineseCommit()
            return
        }
        commitTo(ic, " ")
        if (mode == Mode.ENGLISH) {
            prevWord = null
            main.removeCallbacks(assocClear)
            clearCandidates()
        } else {
            breakAssoc()
        }
        afterCommit()
    }

    private fun enter() {
        if (transActive && composing.isEmpty()) {
            if (transBuf.isEmpty()) {
                // 翻译框里没有字:回车 / 搜索 / 发送 照常作用在目标输入框上
                transBypass = true
                try {
                    enterNormal()
                } finally {
                    transBypass = false
                }
            } else {
                transFinish()   // 回车键此时显示「完成」
            }
            return
        }
        enterNormal()
    }

    private fun enterNormal() {
        val ic = currentInputConnection ?: return
        if (composing.isNotEmpty() && mode != Mode.ENGLISH) {
            ic.beginBatchEdit()
            flushRaw(ic)
            ic.endBatchEdit()
            return
        }
        if (mode == Mode.ENGLISH) {
            // 英文:先把正在打的词上屏,再执行回车动作;联想断开
            if (composing.isNotEmpty()) {
                ic.beginBatchEdit()
                commitEnglishWord(ic, auto = false, trailing = "")
                ic.endBatchEdit()
            }
            prevWord = null
            main.removeCallbacks(assocClear)
            clearCandidates()
        }
        if (mode != Mode.ENGLISH) breakAssoc()
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

    /** 光标编辑页:翻译开着时也是直接编辑目标输入框(里面的译文),不进翻译框。 */
    private fun onEdit(v: String) {
        transBypass = transActive
        try {
            onEditInner(v)
        } finally {
            transBypass = false
        }
    }

    private fun onEditInner(v: String) {
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
        Mode.ENGLISH -> if (englishAssist) englishEngine else null
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

    /** 上屏文字,并记下时间(笔画不进输入框,光标位置的变化要靠这个时间来区分是谁挪的)。 */
    private fun commitTo(ic: InputConnection, text: String) {
        lastOwnEditAt = SystemClock.uptimeMillis()
        ic.commitText(text, 1)
    }

    // ------------------------------------------------------------ 中文联想

    /** 记一笔:这个词刚上屏。extend = 点的是联想词,联想前缀接着往后长。 */
    private fun noteChineseCommit(text: String, extend: Boolean) {
        if (text.isEmpty() || mode == Mode.ENGLISH) return
        val prev = prevChinese
        if (allowLearn && prev != null && prev.length <= 8 && text.length <= 8) {
            val ctx = applicationContext
            val prev2 = prevChinese2
            worker.execute {
                val c = UserData.chain(ctx)
                c.learn(prev, text)
                if (prev2 != null && prev2.length <= 8) c.learn(prev2 + UserChain.SEP + prev, text)
            }
        }
        val key = assocKey
        assocKey = if (extend && key != null && key.length + text.length <= 6) key + text else text
        prevChinese = text
    }

    /** 联想断开:打了标点/空格/回车/退格,或者换了输入框、挪了光标。 */
    private fun breakAssoc() {
        prevChinese = null
        assocKey = null
        if (mode != Mode.ENGLISH && composing.isEmpty()) {
            main.removeCallbacks(assocClear)
            clearCandidates()
        }
    }

    /**
     * 拼音/笔画:上屏一个词之后,在候选栏给出联想:我自己接过的词在前,词库里以它开头的词的后半截在后。
     * 词库没加载就先加载(加载完会再来一次)。查询放在词库线程,不堵住候选查询。
     */
    private fun showChineseAssoc(): Boolean {
        main.removeCallbacks(assocClear)
        val key = assocKey
        // 中文联想只在密码框关闭;网址/搜索框、声明"不要建议"的输入框(GPT、浏览器地址栏等)照样要联想。
        // 是否记录我的用词仍由 allowLearn 单独控制。
        if (key == null || secureField || composing.isNotEmpty() ||
            (mode != Mode.PINYIN && mode != Mode.STROKE)
        ) {
            return false
        }
        if (dictState[KIND_PINYIN] == 0) loadKind(KIND_PINYIN)
        val prev = prevChinese
        val prev2 = prevChinese2
        val learnOn = allowLearn
        val ctx = applicationContext
        val gen = ++queryGen
        loader.execute {
            val list = ArrayList<Candidate>()
            val seen = HashSet<String>()
            try {
                if (learnOn && prev != null) {
                    for (w in UserData.chain(ctx).next(prev2, prev)) if (seen.add(w)) list.add(Candidate(w, 0, 2))
                }
                val eng = pinyinEngine as? PinyinEngine
                if (eng != null) {
                    for (w in eng.followers(key, 12)) if (seen.add(w)) list.add(Candidate(w, 0, 2))
                }
            } catch (t: Throwable) {
                // 查不出来就不显示联想
            }
            val shown = if (list.size > 12) ArrayList(list.subList(0, 12)) else list
            main.post {
                if (gen == queryGen && composing.isEmpty() && assocKey == key) {
                    applyCandidates("", shown)
                    if (shown.isNotEmpty()) {
                        main.removeCallbacks(assocClear)
                        main.postDelayed(assocClear, 6000)
                    }
                }
            }
        }
        return true
    }

    /**
     * 上屏一个词之后的收尾。还有没上屏的拼音/笔画就接着查候选;组合区空了就查联想。
     * 联想结果回来之前不先把候选栏清空,回来后直接替换,这样不会出现 候选 → 工具栏 → 联想 的一闪。
     */
    private fun afterChineseCommit() {
        if (composing.isNotEmpty()) {
            requery()
            return
        }
        queryGen++
        onComposingChanged()
        val started = showChineseAssoc()
        // 没有联想可查,或词库还没加载好(要等一会儿):照旧先清空,免得旧候选摆在那里点不动
        if (!started || pinyinEngine == null) applyCandidates("", emptyList())
    }

    private fun showComposing() {
        // 笔画不放进输入框(光标处什么都不显示),只在候选栏上方显示;拼音和英文仍用输入框的组合区
        if (mode == Mode.STROKE) return
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
        // 本来就是空的(英文没装词库时每个键都会走到这里)就不用刷新界面
        if (candidates.isEmpty() && candidatesFor.isEmpty() && !layoutComposing && composing.isEmpty()) return
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
        if (mode == Mode.ENGLISH) {
            pickEnglish(ic, c)
            return
        }
        if (composing.isEmpty()) {
            if (c.kind == 2) {   // 点了联想词:上屏,联想前缀接着往后长
                ic.beginBatchEdit()
                commitTo(ic, c.text)
                noteChineseCommit(c.text, true)
                ic.endBatchEdit()
                showChineseAssoc()
            }
            return
        }
        learnChinese(c)
        ic.beginBatchEdit()
        commitTo(ic, c.text)
        noteChineseCommit(c.text, false)
        composing.delete(0, c.consumed.coerceIn(1, composing.length))
        if (composing.isNotEmpty()) showComposing()
        ic.endBatchEdit()
        afterChineseCommit()
        afterCommit()
    }

    /**
     * 候选栏右边的关闭键:收起候选区。
     * 正在打的拼音/笔画直接放弃;英文正在打的词按原样留在输入框里;联想词就是收起并断开联想。
     */
    private fun closeCandidates() {
        val ic = currentInputConnection
        main.removeCallbacks(assocClear)
        if (composing.isNotEmpty() && ic != null) {
            ic.beginBatchEdit()
            if (mode == Mode.ENGLISH) {
                commitEnglishWord(ic, auto = false, trailing = "")
            } else {
                if (mode == Mode.PINYIN) ic.setComposingText("", 1)   // 拼音字母在输入框的组合区里,一起删掉
                ic.finishComposingText()
                composing.clear()
            }
            ic.endBatchEdit()
        }
        prevChinese = null
        assocKey = null
        prevWord = null
        clearCandidates()
        afterCommit()
    }

    /** 标点等输入前:把已有的拼音/笔画按最佳候选上屏。 */
    private fun flushComposition(ic: InputConnection) {
        if (mode == Mode.ENGLISH) {
            if (composing.isNotEmpty()) commitEnglishWord(ic, auto = true, trailing = "")
            clearCandidates()
            return
        }
        var guard = 0
        while (composing.isNotEmpty() && guard++ < 64) {
            val c = bestCandidate()
            if (c == null) {
                if (mode == Mode.PINYIN) commitTo(ic, composing.toString()) else commitTo(ic, "")
                composing.clear()
                break
            }
            commitTo(ic, c.text)
            composing.delete(0, c.consumed.coerceIn(1, composing.length))
        }
        composing.clear()
        clearCandidates()
    }

    /** 切换模式/回车前:拼音字母原样上屏,笔画直接丢弃。 */
    private fun flushRaw(ic: InputConnection) {
        if (composing.isNotEmpty()) {
            if (mode == Mode.ENGLISH) {
                commitEnglishWord(ic, auto = false, trailing = "")
            } else {
                if (mode == Mode.PINYIN) commitTo(ic, composing.toString()) else commitTo(ic, "")
                composing.clear()
            }
        }
        if (mode == Mode.ENGLISH) {
            prevWord = null
            main.removeCallbacks(assocClear)
        }
        prevChinese = null
        assocKey = null
        clearCandidates()
    }

    // ------------------------------------------------------------ 英文输入(补全 / 联想 / 纠错)与词频学习

    private fun englishActive(): Boolean = mode == Mode.ENGLISH && englishAssist && englishEngine != null

    /**
     * 把正在输入的英文词上屏。第一个候选是"原样输入"(单独的 i 变成 I)。
     * auto=true 且设置里开了自动纠错时,不在词库里的词换成最可能的纠正。
     * 调用者负责之后刷新候选栏(showNextWords / clearCandidates)。
     */
    private fun commitEnglishWord(ic: InputConnection, auto: Boolean, trailing: String) {
        val typed = composing.toString()
        var text = typed
        if (candidatesFor == typed && candidates.isNotEmpty()) {
            val first = candidates[0]
            if (first.kind == 0) text = first.text
            if (auto && typed.length >= 3 && DictConfig.autoCorrect(applicationContext)) {
                val fix = candidates.firstOrNull { it.kind == 1 }
                val known = englishEngine?.isKnown(typed.lowercase()) ?: true
                if (fix != null && !known) text = fix.text
            }
        }
        commitTo(ic, text + trailing)
        composing.clear()
        learnEnglish(text)
        candidates = emptyList()
        candidatesFor = ""
        queryGen++
    }

    /** 点了英文候选(补全、纠错或联想):上屏并跟一个空格,然后给出下一个词的联想。 */
    private fun pickEnglish(ic: InputConnection, c: Candidate) {
        ic.beginBatchEdit()
        commitTo(ic, c.text + " ")
        composing.clear()
        learnEnglish(c.text)
        candidates = emptyList()
        candidatesFor = ""
        queryGen++
        ic.endBatchEdit()
        showNextWords()
        afterCommit()
    }

    /** 记录这个词(和它跟在上一个词后面这件事),并把它记为"上一个词"。非字母内容会让联想断开。 */
    private fun learnEnglish(word: String) {
        var letters = word.isNotEmpty()
        for (ch in word) {
            if (!(ch in 'a'..'z' || ch in 'A'..'Z' || ch == '\'')) {
                letters = false
                break
            }
        }
        if (!letters) {
            prevWord = null
            return
        }
        val lower = word.lowercase()
        if (allowLearn) {
            val known = englishEngine?.isKnown(lower) ?: false
            UserData.english(applicationContext).learn(prevWord, word, known)
        }
        prevWord = lower
    }

    /** 显示上一个词之后的联想词;没有就把候选栏清空(工具栏回来)。 */
    private fun showNextWords() {
        main.removeCallbacks(assocClear)
        val eng = englishEngine
        val prev = prevWord
        if (eng == null || prev == null || composing.isNotEmpty()) {
            applyCandidates("", emptyList())
            return
        }
        val gen = ++queryGen
        worker.execute {
            val list = try {
                eng.nextWords(prev)
            } catch (t: Throwable) {
                emptyList()
            }
            main.post {
                if (gen == queryGen && composing.isEmpty()) {
                    applyCandidates("", list)
                    if (list.isNotEmpty()) {
                        main.removeCallbacks(assocClear)
                        main.postDelayed(assocClear, 6000)
                    }
                }
            }
        }
    }

    /** 拼音/笔画:选了这个候选,记一笔(选得越多排得越靠前)。 */
    private fun learnChinese(c: Candidate) {
        if (!allowLearn || composing.isEmpty()) return
        val e = currentEngine() ?: return
        val typed = composing.substring(0, c.consumed.coerceIn(1, composing.length)).replace("'", "")
        if (typed.isNotEmpty()) {
            e.learn(typed, c.text)
        }
    }

    // ------------------------------------------------------------ 输入翻译

    /** 翻译开着时的假输入连接:只实现本输入法会用到的几个操作,其余(读文字等)交给真的输入连接。 */
    private inner class TransIc(target: InputConnection) : InputConnectionWrapper(target, false) {
        override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
            transBuf.append(text)
            transChanged()
            return true
        }

        // 拼音字母 / 正在打的英文词只显示在候选栏上方的预览条里,不进翻译框,也不进目标输入框
        override fun setComposingText(text: CharSequence, newCursorPosition: Int): Boolean = true

        override fun setComposingRegion(start: Int, end: Int): Boolean = true

        override fun finishComposingText(): Boolean = true

        override fun beginBatchEdit(): Boolean = true

        override fun endBatchEdit(): Boolean = true

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            // 翻译框已经空了(例如点过「完成」):退格直接删目标输入框里的内容,不用关掉翻译
            if (transBuf.isEmpty()) return super.deleteSurroundingText(beforeLength, afterLength)
            transDeleteBack(beforeLength)
            return true
        }

        override fun sendKeyEvent(event: KeyEvent): Boolean {
            if (event.keyCode == KeyEvent.KEYCODE_DEL && transBuf.isEmpty()) return super.sendKeyEvent(event)
            if (event.action != KeyEvent.ACTION_DOWN) return true
            when (event.keyCode) {
                KeyEvent.KEYCODE_DEL -> transDeleteBack(1)
                KeyEvent.KEYCODE_ENTER -> transFinish()
            }
            return true   // 方向键等在翻译框里没有意义
        }

        override fun performEditorAction(editorAction: Int): Boolean {
            transFinish()
            return true
        }

        override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence? =
            transBuf.takeLast(n.coerceAtLeast(0)).toString()

        override fun getTextAfterCursor(n: Int, flags: Int): CharSequence? = ""

        /** 英文自动大写:翻译框里一句话结束(. ! ? 后面跟着空格)了,下一个字母才大写。 */
        override fun getCursorCapsMode(reqModes: Int): Int {
            var i = transBuf.length - 1
            if (i < 0) return 0
            var spaces = 0
            while (i >= 0 && (transBuf[i] == ' ' || transBuf[i] == '\n')) {
                i--
                spaces++
            }
            if (i < 0 || spaces == 0) return 0
            val c = transBuf[i]
            val end = c == '.' || c == '!' || c == '?' || c == '。' || c == '！' || c == '？'
            return if (end) android.text.TextUtils.CAP_MODE_SENTENCES and reqModes else 0
        }
    }

    private fun isOnline(): Boolean {
        return try {
            val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
            val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false)
            caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (e: Exception) {
            true   // 查不了就当有网,真发请求试试
        }
    }

    /** 没网时才挂:系统在网络可用的那一刻回调一次(不轮询),回来后重试当前这句,然后立刻摘掉。 */
    private fun watchNetwork() {
        if (transNetCb != null) return
        try {
            val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
            val cb = object : ConnectivityManager.NetworkCallback() {
                private fun retry() {
                    main.post {
                        if (transNetCb == null || !isOnline()) return@post   // 还没真正联网:接着等,不重复挂
                        unwatchNetwork()
                        if (transActive && transBuf.isNotBlank() && transStatus == TranslateBarView.STATUS_FAILED) {
                            startTranslate()
                        }
                    }
                }

                override fun onAvailable(network: Network) = retry()

                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = retry()
            }
            transNetCb = cb
            cm.registerDefaultNetworkCallback(cb)
        } catch (e: Exception) {
            transNetCb = null
        }
    }

    private fun unwatchNetwork() {
        val cb = transNetCb ?: return
        transNetCb = null
        try {
            (getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager).unregisterNetworkCallback(cb)
        } catch (e: Exception) {
            // 已经摘掉了
        }
    }

    private fun transEngineLabel(): String = "翻译:" + Translator.engineLabel(DictConfig.translateEngine(applicationContext))

    private fun transUi() {
        transBar?.setContent(
            transBuf.toString(), Translator.pairs[transPair].label, transStatus,
            if (transStatus == TranslateBarView.STATUS_FAILED && isOnline()) Translator.lastFail else "",
        )
    }

    /** 翻译开关 / 当前输入框是否可用 变了:显示或隐藏翻译框,悬浮条换色。「译」按钮的变色由 settingsPanel.selectedId 负责。 */
    private fun applyTransState() {
        warmTranslate()
        settingsPanel?.selectedId = if (transOn) SettingsPanelView.ID_TRANS else -1
        settingsPanel?.setItemLabel(SettingsPanelView.ID_TRANS_ENGINE, transEngineLabel())
        transUi()
        val changed = Palette.translating != transActive
        val barVisible = transBar?.visibility == View.VISIBLE
        if (!changed && barVisible == transActive) return   // 没变化(每次打开键盘都会走到这里):不重算、不重画
        Palette.translating = transActive
        transBar?.visibility = if (transActive) View.VISIBLE else View.GONE
        // 键盘配色不再随翻译状态变,所以不用重算键盘 / 候选栏 / 各面板(省电);只刷新翻译框和悬浮条
        transBar?.refresh()
        floatHandle?.refresh()
        // 多出 / 少了一行,键盘整体高度变了:悬浮模式要重新摆一次位置
        boxView?.post {
            applyBoxSize()
            inputRoot?.requestLayout()
        }
    }

    /**
     * 翻译打开时,提前在后台把到翻译服务器的连接(域名解析 + TLS 握手)建好并放进连接池,
     * 这样第一句翻译不用再等握手。只发一个很小的 HEAD 请求,4 分钟内最多一次;没开翻译 / 没网时不发。
     */
    private fun warmTranslate() {
        if (!transActive || !isOnline()) return
        val primary = DictConfig.translateEngine(applicationContext)
        Translator.load(applicationContext)
        Translator.warm(primary)
    }

    private fun setTransOn(on: Boolean) {
        if (on == transOn) return
        DictConfig.setTranslateOn(applicationContext, on)
        transOn = on
        val was = transActive
        transActive = on && transAllowed
        if (was && !transActive) {
            // 关掉时:已经翻出来的译文留在输入框里,翻译框里没翻的原文放弃
            transReset(finishTarget = true)
        }
        applyTransState()
        if (was != transActive) refreshLayout(animate = false)   // 回车键标签跟着变
    }

    /** 翻译框里的原文变了(上屏 / 退格):停 0.6 秒再翻,连续打字不会每个字都联网。同一句翻过的直接用缓存。 */
    private fun transChanged() {
        transGen++
        transInflight = null
        main.removeCallbacks(transRun)
        val filled = transBuf.isNotEmpty()
        if (filled != transFilled) {
            transFilled = filled
            refreshLayout(animate = false)
        }
        if (transBuf.isBlank()) {
            transStatus = TranslateBarView.STATUS_NONE
            clearShown()
            transUi()
            return
        }
        val src = transBuf.toString()
        val hit = Translator.cached(src, Translator.pairs[transPair])
        if (hit != null) {
            transStatus = TranslateBarView.STATUS_NONE
            transSrc = src
            writeShown(hit)
        } else {
            main.postDelayed(transRun, TRANS_DEBOUNCE_MS)
        }
        transUi()
    }

    private fun transDeleteBack(count: Int) {
        var n = count
        var changed = false
        while (n > 0 && transBuf.isNotEmpty()) {
            val len = transBuf.length
            // 别把一个表情 / 生僻字(两个 char)删成半个
            val cut = if (len >= 2 && Character.isLowSurrogate(transBuf[len - 1]) && Character.isHighSurrogate(transBuf[len - 2])) 2 else 1
            transBuf.setLength(len - cut)
            changed = true
            n--
        }
        if (changed) transChanged()
    }

    /** 把译文写进目标输入框,当作组合区(带下划线),下一次译文来了直接替换它。 */
    private fun writeShown(t: String) {
        val real = realIc() ?: return
        lastOwnEditAt = SystemClock.uptimeMillis()   // 这次改动是我们自己的,别当成用户挪了光标
        real.setComposingText(t, 1)
        transShown = t
    }

    private fun clearShown() {
        if (transShown.isNotEmpty()) {
            val real = realIc()
            if (real != null) {
                lastOwnEditAt = SystemClock.uptimeMillis()
                real.setComposingText("", 1)
            }
        }
        transShown = ""
        transSrc = ""
    }

    private fun startTranslate() {
        if (!transActive) return
        val src = transBuf.toString()
        if (src.isBlank() || transInflight == src) return
        val pair = Translator.pairs[transPair]
        val hit = Translator.cached(src, pair)
        if (hit != null) {
            onTranslated(src, TransResult(hit, "cache"))
            return
        }
        if (!isOnline()) {
            // 没网:不发请求(省得空等超时),标「翻译失败」,等网络回来自动重试一次
            transStatus = TranslateBarView.STATUS_FAILED
            transFinishing = false
            transUi()
            watchNetwork()
            return
        }
        val gen = ++transGen
        transInflight = src
        transStatus = TranslateBarView.STATUS_LOADING
        transUi()
        val primary = DictConfig.translateEngine(applicationContext)
        Translator.load(applicationContext)
        try {
            netPool.execute {
                if (gen != transGen) return@execute   // 排队期间原文已经变了
                val r = try {
                    Translator.translate(src, pair, primary) { gen != transGen }
                } catch (t: Throwable) {
                    null
                }
                main.post { if (gen == transGen && transActive) onTranslated(src, r) }
            }
        } catch (e: Exception) {
            // 线程池已关闭(服务正在销毁)
            transInflight = null
            transFinishing = false
            transStatus = TranslateBarView.STATUS_NONE
        }
    }

    private fun onTranslated(src: String, r: TransResult?) {
        transInflight = null
        if (r == null) {
            // 微软和谷歌都没通:保留原来的译文和翻译框里的原文,点「完成」会再试一次
            transStatus = TranslateBarView.STATUS_FAILED
            transFinishing = false
            transUi()
            return
        }
        transStatus = TranslateBarView.STATUS_NONE
        transSrc = src
        writeShown(r.text)
        transUi()
        if (transFinishing) {
            transFinishing = false
            transFinishNow()
        }
    }

    /** 点了「完成」/ 回车:译文已经是最新的就直接收尾;还没翻出来(刚打完字就点)就等它回来再收尾。 */
    private fun transFinish() {
        if (!transActive) return
        main.removeCallbacks(transRun)
        val src = transBuf.toString()
        if (src.isBlank() || (src == transSrc && transShown.isNotEmpty())) {
            transFinishNow()
            return
        }
        transFinishing = true
        startTranslate()   // 命中缓存会在里面直接收尾;否则等 onTranslated 回来再收尾
    }

    /** 译文留在目标输入框里(取消组合区的下划线),翻译框清空,输入结束。 */
    private fun transFinishNow() {
        unwatchNetwork()
        main.removeCallbacks(transRun)
        transGen++
        transInflight = null
        transFinishing = false
        if (transShown.isNotEmpty()) {
            val real = realIc()
            if (real != null) {
                lastOwnEditAt = SystemClock.uptimeMillis()
                real.finishComposingText()
            }
        }
        transBuf.clear()
        transShown = ""
        transSrc = ""
        transStatus = TranslateBarView.STATUS_NONE
        if (transFilled) {
            transFilled = false
            refreshLayout(animate = false)
        }
        transUi()
    }

    /** 翻译框里的 ×:原文和目标输入框里的译文一起清掉。 */
    private fun transClear() {
        unwatchNetwork()
        main.removeCallbacks(transRun)
        transGen++
        transInflight = null
        transFinishing = false
        if (composing.isNotEmpty()) {
            composing.clear()
            queryGen++
            applyCandidates("", emptyList())
        }
        transBuf.clear()
        clearShown()
        transStatus = TranslateBarView.STATUS_NONE
        if (transFilled) {
            transFilled = false
            refreshLayout(animate = false)
        }
        transUi()
    }

    /** 不碰界面地结束这一次翻译:换了输入框 / 键盘关了 / 开关关了 / 用户点了别处。finishTarget:顺便取消目标框里译文的组合状态。 */
    private fun transReset(finishTarget: Boolean) {
        unwatchNetwork()
        main.removeCallbacks(transRun)
        transGen++
        transInflight = null
        transFinishing = false
        if (finishTarget && transShown.isNotEmpty()) {
            try {
                realIc()?.finishComposingText()
            } catch (e: Exception) {
                // 输入连接已经失效
            }
        }
        transBuf.clear()
        transShown = ""
        transSrc = ""
        transStatus = TranslateBarView.STATUS_NONE
        transFilled = false
    }

    /** 翻译框左边的方向长条里选了一个方向:框里有字就马上按新方向重翻。 */
    private fun transSetLang(index: Int) {
        if (index !in Translator.pairs.indices || index == transPair) return
        transPair = index
        DictConfig.setTranslatePair(applicationContext, transPair)
        main.removeCallbacks(transRun)
        transGen++
        transInflight = null
        transSrc = ""
        transStatus = TranslateBarView.STATUS_NONE
        transUi()
        if (transBuf.isNotBlank()) startTranslate()
    }

    // ------------------------------------------------------------ 其他

    private fun kbState() = KbState(
        if (transActive && transBuf.isNotEmpty()) "完成" else enterLabel,   // 翻译框有字时,回车键 = 完成
        shift, composing.isNotEmpty(), selecting, shiftLock,
    )

    private fun refreshLayout(animate: Boolean) {
        warmUp()
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
            Mode.SYMBOL -> showPanel(symbolPanel, animate)
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
                symbolPanel?.visibility = View.GONE
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
        for (p in listOf<PanelView?>(emojiPanel, symbolPanel, clipPanel, settingsPanel, choicePanel)) {
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
            if (text.isBlank() || text.length > MAX_CLIP_CHARS) return
            if (clips.firstOrNull() == text) return   // 已经在最前面,什么都不用动(也不用写盘)
            clips.remove(text)
            clips.add(0, text)
            while (clips.size > 500) clips.removeAt(clips.size - 1)
            clipsChanged()
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
        // 输入框是空的(或光标在最前面)不自动大写;只在打完一句话之后下一个字母才自动大写
        if (ic.getTextBeforeCursor(1, 0).isNullOrEmpty()) return false
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
