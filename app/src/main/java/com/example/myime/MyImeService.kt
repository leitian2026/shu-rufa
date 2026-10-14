package com.example.myime

import android.content.ClipboardManager
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
    private val worker = Executors.newSingleThreadExecutor()   // 候选查询:只做计算,不碰磁盘
    // 诊断日志默认关闭(不取时间、不拼字符串、不写日志)。打开方式二选一:
    //  1. 应用首页的「调试日志」开关(下次键盘弹出时生效);
    //  2. adb shell setprop log.tag.MyIme DEBUG,再重启输入法。
    @Volatile
    private var diag = false

    private fun refreshDiag() {
        diag = android.util.Log.isLoggable("MyIme", android.util.Log.DEBUG) || DictConfig.debugLog(applicationContext)
    }

    // ---- 「键盘为什么弹出来」诊断:下面这几个变量和三个函数,只在 diag 为真(调试日志开着)时才会被碰到。
    //      开关关了就一行都不跑:不读 EditorInfo 的细节、不取时间、不拼字符串、不写日志。
    private var lastShowReqAt = 0L      // 最近一次系统要求弹键盘的时间
    private var lastShowReqFlags = -1   // 那次要求的标志:0=隐式(焦点落到输入框自动弹)、1=应用显式要求、3=强制
    private var lastShowAt = 0L         // 最近一次 onStartInputView 的时间

    /** 系统(应用)要求显示键盘时调用,在 onStartInputView 之前。flags 能区分是应用主动要的还是焦点自动弹的。 */
    override fun onShowInputRequested(flags: Int, configChange: Boolean): Boolean {
        if (edgeAutoShowBlocked()) return false
        val r = super.onShowInputRequested(flags, configChange)
        if (diag) {
            lastShowReqAt = SystemClock.elapsedRealtime()
            lastShowReqFlags = flags
            AppLog.d("MyIme", "showRequested " + showFlagsName(flags) + " configChange=$configChange accepted=$r")
        }
        return r
    }

    // ---- 压住 Edge 点收藏夹时「弹一下又收起」的键盘(日志实测:所有输入法都会这样,是 Edge 自己的行为)。
    //      Edge 的序列:历史搜索框(文本、回车键=搜索)拿到焦点并要求弹键盘 → 几毫秒后又要求一次,
    //      此时焦点已转到不能输入的容器(inputType=0) → 约 1 秒后 Edge 自己收起。
    //      做法:Edge 里这种搜索框要求弹键盘时先不弹,等 EDGE_WAIT_MS;这段时间里焦点转成「不能输入」就取消(键盘不弹);
    //      没转走(是你自己点了搜索框)就补弹,只晚 EDGE_WAIT_MS。Edge 里 inputType=0 的弹出请求一律不弹。
    //      省电:没有定时轮询、没有额外线程;只在 Edge 里这种搜索框要求弹键盘时排一个 120 毫秒的主线程回调,
    //      别的应用只多一次包名比较;开关关了连这个比较都不做。
    @Volatile private var edgeAllowUntil = 0L   // 补弹键盘时放行一小段时间,避免补弹的请求又被自己拦下

    private val edgeShowLater = Runnable {
        val e = currentInputEditorInfo
        if (e != null && isEdgeSearchField(e) && !isInputViewShown) {
            edgeAllowUntil = SystemClock.elapsedRealtime() + EDGE_ALLOW_MS
            if (diag) AppLog.d("MyIme", "edgeFilter: ${EDGE_WAIT_MS}ms 内焦点没转走,补弹键盘")
            requestShowSelf(0)
        } else if (diag) {
            AppLog.d("MyIme", "edgeFilter: 焦点已转走,这次不弹键盘")
        }
    }

    private fun isEdgePkg(p: String?): Boolean = p != null && (p == "com.microsoft.emmx" || p.startsWith("com.microsoft.emmx."))

    /** Edge 里的原生搜索框:普通文本、回车键是「搜索」(网页里的输入区域回车键不是搜索,不会命中)。 */
    private fun isEdgeSearchField(e: EditorInfo): Boolean =
        isEdgePkg(e.packageName) &&
            (e.inputType and InputType.TYPE_MASK_CLASS) == InputType.TYPE_CLASS_TEXT &&
            (e.imeOptions and EditorInfo.IME_MASK_ACTION) == EditorInfo.IME_ACTION_SEARCH

    /** 返回 true = 这次弹出请求被拦下(onShowInputRequested 直接返回 false)。 */
    private fun edgeAutoShowBlocked(): Boolean {
        val e = currentInputEditorInfo ?: return false
        if (!isEdgePkg(e.packageName)) return false                         // 别的应用:到此为止
        if (!DictConfig.edgeFilter(applicationContext)) return false        // 开关关了:不拦
        if (e.inputType == 0) {                                             // 焦点在不能输入的容器上,弹键盘没有意义
            main.removeCallbacks(edgeShowLater)
            if (diag) AppLog.d("MyIme", "edgeFilter: 拒绝 inputType=0 的弹出请求")
            return true
        }
        if (SystemClock.elapsedRealtime() < edgeAllowUntil) return false    // 自己补弹的请求,放行
        if (!isEdgeSearchField(e)) return false
        main.removeCallbacks(edgeShowLater)
        main.postDelayed(edgeShowLater, EDGE_WAIT_MS)
        if (diag) AppLog.d("MyIme", "edgeFilter: Edge 搜索框要求弹键盘,先等 ${EDGE_WAIT_MS}ms")
        return true
    }

    private fun showFlagsName(flags: Int): String = when {
        flags and android.view.inputmethod.InputMethod.SHOW_FORCED != 0 -> "forced(应用强制要求)"
        flags and android.view.inputmethod.InputMethod.SHOW_EXPLICIT != 0 -> "explicit(应用主动调了 showSoftInput)"
        else -> "implicit(没人显式要求,焦点落到输入框自动弹)"
    }

    /** 键盘弹出这一刻是哪个应用、什么输入框、系统是怎么要求的。只记类型和包名,不记输入的内容;密码框连提示文字都不记。 */
    private fun editorDesc(info: EditorInfo): String {
        val now = SystemClock.elapsedRealtime()
        val type = info.inputType
        val cls = type and InputType.TYPE_MASK_CLASS
        val variation = type and InputType.TYPE_MASK_VARIATION
        val pwd = (cls == InputType.TYPE_CLASS_TEXT &&
            (variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD)) ||
            (cls == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD)
        val clsName = when (cls) {
            InputType.TYPE_CLASS_TEXT -> "TEXT"
            InputType.TYPE_CLASS_NUMBER -> "NUMBER"
            InputType.TYPE_CLASS_PHONE -> "PHONE"
            InputType.TYPE_CLASS_DATETIME -> "DATETIME"
            0 -> "NULL(不是真输入框,多半是容器抢了焦点)"
            else -> "?"
        }
        val action = when (info.imeOptions and EditorInfo.IME_MASK_ACTION) {
            EditorInfo.IME_ACTION_SEARCH -> "SEARCH"
            EditorInfo.IME_ACTION_GO -> "GO"
            EditorInfo.IME_ACTION_SEND -> "SEND"
            EditorInfo.IME_ACTION_NEXT -> "NEXT"
            EditorInfo.IME_ACTION_DONE -> "DONE"
            EditorInfo.IME_ACTION_NONE -> "NONE"
            else -> "UNSPEC"
        }
        val sb = StringBuilder()
        sb.append("pkg=").append(info.packageName)
        sb.append(" inputType=0x").append(Integer.toHexString(type)).append('(').append(clsName).append(')')
        sb.append(" imeOptions=0x").append(Integer.toHexString(info.imeOptions)).append('(').append(action).append(')')
        sb.append(" fieldId=").append(info.fieldId)
        if (!info.fieldName.isNullOrEmpty()) sb.append(" fieldName=").append(info.fieldName)
        if (!pwd) {
            val hint = info.hintText?.toString()?.replace('\n', ' ')?.take(24)
            if (!hint.isNullOrEmpty()) sb.append(" hint=").append(hint)
        }
        sb.append(" viewShown=").append(isInputViewShown)
        sb.append(" hardKb=").append(resources.configuration.keyboard != android.content.res.Configuration.KEYBOARD_NOKEYS)
        if (lastShowReqAt > 0) {
            sb.append(" req=").append(showFlagsName(lastShowReqFlags).substringBefore('(')).append(" +").append(now - lastShowReqAt).append("ms")
        }
        if (lastShowAt > 0) sb.append(" sinceLastShow=").append(now - lastShowAt).append("ms")
        lastShowAt = now
        return sb.toString()
    }
    private val ioPool = Executors.newSingleThreadExecutor()    // 所有写文件(带 fsync,可能几十毫秒)都放这里,不会排在候选查询前面

    @Volatile private var pinyinEngine: Engine? = null
    @Volatile private var strokeEngine: Engine? = null
    @Volatile private var englishEngine: EnglishEngine? = null
    private val loader = Executors.newSingleThreadExecutor()   // 词库加载单独一个线程,不堵住候选查询
    private var loadedStamp = ""
    private var lastStampCheck = 0L
    private val dictState = IntArray(3)          // 0 没加载 1 加载中 2 已试过
    private var dictGen = 0                      // 词库被换/释放一次就加一,过期的加载结果直接丢掉

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

    // 剪贴板历史:最多 500 条。内存里一份,变了就落盘(ClipStore),切换输入法、进程被回收、重启手机后还在。
    private val clips = ArrayList<String>()

    // 上了锁的条目(按文字认):不能被删除,也不会被"超过 500 条挤掉最旧的"挤掉。随剪贴板文件一起落盘。
    private val locked = LinkedHashSet<String>()

    // 回收箱:剪贴板里删掉的内容先放这里,最多 10 条;最新删的在最前(界面最上面),满了挤掉最后一条(最旧的)。同样会落盘。
    private val trash = ArrayList<String>()
    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener { captureClipboardNow() }

    // 总开关「清空系统剪贴板」:开(默认)= 收进历史后清空系统剪贴板,别的 app 读不到;关 = 只收进历史,系统剪贴板照常可用。
    // 存在 clipcfg 设置里(会随备份还原)。进程里只有这一份,切换时同步更新。
    private var clipWipe = true
    private var lastSysClip: String? = null   // 开关关着时:上次从系统剪贴板收进来的文字,键盘再次弹出时同样的内容不重复顶到最前

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
        const val TRASH_MAX = 10          // 回收箱最多放几条
        const val EDGE_WAIT_MS = 120L     // Edge 搜索框要求弹键盘后先等多久(实测焦点转走用了 32~64ms)
        const val EDGE_ALLOW_MS = 800L    // 补弹键盘时放行多久
        const val CLIP_SAVE_DELAY_MS = 2000L   // 新复制/删除之后多久写一次盘(期间的改动合并)
        const val MAX_CLIP_CHARS = 5000   // 单条剪贴板最多保存的字数,更长的不收
        const val TRANS_DEBOUNCE_MS = 600L   // 翻译框停多久才去翻:越长请求越少越省电,越短译文越跟手
    }

    private val composing = StringBuilder()
    private var candidates: List<Candidate> = emptyList()
    private var candidatesFor = ""
    @Volatile private var queryGen = 0   // 后台线程也要读:排队的旧查询靠它判断自己是否已过期

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
        clipWipe = getSharedPreferences("clipcfg", MODE_PRIVATE).getBoolean("wipe", true)
        loadClips()
        AppLog.init(applicationContext)
        refreshDiag()
        if (diag) AppLog.d("MyIme", "service created (进程新启动或服务重建;之后若还要重新加载词库就是冷启动)")
    }

    // ------------------------------------------------------------ 剪贴板历史(内存 + 落盘)

    /** 服务创建时读一次(文件很小);之后只在内容变了时写,没有定时任务。 */
    private fun loadClips() {
        try {
            val d = ClipStore.load(applicationContext, 500, TRASH_MAX)
            clips.clear(); clips.addAll(d.clips)
            locked.clear(); locked.addAll(d.locked.filter { it in d.clips })
            trash.clear(); trash.addAll(d.trash)
        } catch (e: Exception) {
            // 读不出来就当空的
        }
    }

    private var clipsDirty = false
    private var trashDirty = false
    private var clipSavePending = false
    private val clipFlush = Runnable { flushClips() }

    /**
     * 内容变了,只记下哪个文件脏了(写文件要刷盘,又耗电又磨闪存,不能每改一下写一次)。
     * urgent(新复制的内容、删除):内容已经从系统剪贴板清掉,只在内存里,所以 2 秒后写一次,这 2 秒里的多次改动合并成一次;
     * 不紧急的(只是点用后挪到最前、调了顺序):不设定时,等键盘收起时和别的数据一起写。都不是轮询,没有变化就没有任何动作。
     */
    private fun markClips(clipsChanged: Boolean, trashChanged: Boolean, urgent: Boolean) {
        if (clipsChanged) clipsDirty = true
        if (trashChanged) trashDirty = true
        if (urgent && !clipSavePending) {
            clipSavePending = true
            main.postDelayed(clipFlush, CLIP_SAVE_DELAY_MS)
        }
    }

    /** 把脏的文件写出去(只写变了的那个)。主线程拿快照,后台线程写;sync = 服务销毁时直接写。 */
    private fun flushClips(sync: Boolean = false) {
        main.removeCallbacks(clipFlush)
        clipSavePending = false
        if (!clipsDirty && !trashDirty) return
        val c = if (clipsDirty) clips.toList() else null
        val k = if (clipsDirty) locked.toList() else emptyList()
        val t = if (trashDirty) trash.toList() else null
        clipsDirty = false
        trashDirty = false
        val ctx = applicationContext
        if (!sync) {
            try {
                ioPool.execute { ClipStore.save(ctx, c, t, k) }
                return
            } catch (e: Exception) {
                // 写线程已关(服务正在销毁):直接写
            }
        }
        ClipStore.save(ctx, c, t, k)
    }

    /** 剪贴板里删掉的内容放进回收箱。texts 要按它们在剪贴板里的顺序给(上面新、下面旧),原样放到回收箱最前面。 */
    private fun moveToTrash(texts: List<String>) {
        if (texts.isEmpty()) return
        val batch = if (texts.size > TRASH_MAX) texts.subList(0, TRASH_MAX) else texts   // 一次删得比 10 条多:留靠前的(新的),挤掉后面最旧的
        trash.removeAll(batch.toSet())   // 回收箱里已经有同样的内容:挪到最前,不重复
        trash.addAll(0, batch)
        while (trash.size > TRASH_MAX) trash.removeAt(trash.size - 1)
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
            EmojiWords.ensure(ctx)               // 候选旁边的表情对照表(很小),先于词库读好
            val t0 = android.os.SystemClock.elapsedRealtime()
            val engine: Any? = when (kind) {
                KIND_PINYIN -> DictLoader.loadPinyin(ctx)
                KIND_STROKE -> DictLoader.loadStroke(ctx)
                else -> DictLoader.loadEnglish(ctx)
            }
            // 只在 adb logcat 里看得到(标签 MyIme),且只在调试日志打开时:每次加载词库花了多少毫秒
            if (diag) AppLog.d("MyIme", "load dict kind=$kind ${android.os.SystemClock.elapsedRealtime() - t0}ms ok=${engine != null}")
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
                            en?.pins = UserData.pins(applicationContext)
                            englishEngine = en
                        }
                    }
                    dictState[kind] = 2 // 不管成败都算试过一次,不会反复重试
                    if (composing.isNotEmpty()) requery()
                    else if (kind == KIND_PINYIN && assocKey != null) showChineseAssoc()
                }
            }
            // 词库交给主线程之后,接着在这个后台线程把联想用的排序下标建好:
            // 否则第一次出联想词时要现场给整个词库排序,联想词就会慢半拍才出来
            if (kind == KIND_PINYIN && gen == dictGen) (engine as? PinyinEngine)?.prepareFollowers()
        }
    }

    /** 放掉所有词库:词库文件换了,或键盘隐藏很久 / 系统缺内存。下次用到时 warmUp 会重新加载。 */
    private fun releaseDicts() {
        dictGen++
        EmojiWords.release()
        pinyinEngine = null
        strokeEngine = null
        englishEngine = null
        for (i in dictState.indices) dictState[i] = 0
    }

    override fun onWindowHidden() {
        super.onWindowHidden()
        main.removeCallbacks(edgeShowLater)   // 键盘收起了,还没补弹的就作废
        if (diag && lastShowAt > 0) AppLog.d("MyIme", "hidden after ${SystemClock.elapsedRealtime() - lastShowAt}ms")
        // 键盘收起了:没发出的翻译、没回来的请求、挂着的网络回调都放掉,不在后台耗电耗流量
        transBar?.dismissPopup()
        candidateBar?.dismissPinPopup()
        main.removeCallbacks(transRun)
        transGen++
        transInflight = null
        transFinishing = false
        unwatchNetwork()
        main.removeCallbacks(assocClear)
        flushClips()   // 剪贴板有没写的改动,和下面的学习数据一起落盘
        ioPool.execute {
            UserData.saveAll()                          // 学习数据只在键盘隐藏时落盘,打字过程中不写文件
            BackupManager.maybeAuto(applicationContext) // 开着自动备份、数据有变化、距上次够久时,另开线程备份一次
            LearnSync.maybe(applicationContext, true)   // 多机同步:新学的攒够了、距上次上传够久,先拉取合并再上传(另开线程;不满足条件几步判断就返回)
        }
    }

    override fun onWindowShown() {
        super.onWindowShown()
        ioPool.execute { LearnSync.maybe(applicationContext, false) }   // 多机同步:距上次拉取够久就拉一次别的手机的更新(没变化只列一次目录)
        // 收起前翻译框里还有没翻完的字:现在补翻一次
        if (transActive && transBuf.isNotBlank() && transBuf.toString() != transSrc) {
            transStatus = TranslateBarView.STATUS_NONE
            startTranslate()
        }
    }

    override fun onDestroy() {
        try {
            (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).removePrimaryClipChangedListener(clipListener)
        } catch (e: Exception) {
        }
        main.removeCallbacks(assocClear)
        main.removeCallbacks(transRun)
        main.removeCallbacks(edgeShowLater)
        unwatchNetwork()
        netPool.shutdownNow()
        UserData.saveAll()
        flushClips(sync = true)   // 还没写的剪贴板改动同步写掉,服务马上要销毁了
        worker.shutdownNow()
        ioPool.shutdown()   // 不打断正在写的文件,让排队的保存写完
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
                leavePage()          // 点一个符号就输入并回到键盘
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
                if (!pasteFromHistory(text)) return   // 粘贴失败:留在剪贴板页,已弹提示
                // 点过的这一条挪到剪贴板最前面(已经在最前面就什么都不动)
                if (clips.firstOrNull() != text && clips.remove(text)) {
                    clips.add(0, text)
                    markClips(clipsChanged = true, trashChanged = false, urgent = false)   // 只是调了顺序,键盘收起时再写
                }
                leavePage()
            }

            override fun onDeleteMany(texts: List<String>) {
                val gone = texts.toSet() - locked   // 上了锁的不删(界面上本来也选不中,这里再挡一道)
                // 按剪贴板里的顺序放进回收箱(不按点选的先后),这样回收箱最下面那条永远是最旧的
                moveToTrash(clips.filter { it in gone })
                clips.removeAll(gone)
                markClips(clipsChanged = true, trashChanged = true, urgent = true)
                clipPanel?.setItems(clips.toList())
                clipPanel?.setTrash(trash.toList())
            }

            override fun onToggleLock(text: String) {
                if (text !in clips) return
                if (!locked.remove(text)) locked.add(text)
                markClips(clipsChanged = true, trashChanged = false, urgent = true)
                clipPanel?.setLocked(locked.toSet())
            }

            override fun onPasteTrash(text: String) {
                if (!pasteFromHistory(text)) return
                leavePage()
            }

            override fun onTrashDelete(texts: List<String>) {
                trash.removeAll(texts.toSet())
                markClips(clipsChanged = false, trashChanged = true, urgent = true)
                clipPanel?.setTrash(trash.toList())
            }

            override fun onToggleWipe(on: Boolean) {
                clipWipe = on
                lastSysClip = null
                getSharedPreferences("clipcfg", MODE_PRIVATE).edit().putBoolean("wipe", on).apply()
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
        bar.pinHandler = object : CandidateBarView.PinHandler {
            private fun wordAt(index: Int): String? {
                if (mode != Mode.ENGLISH || englishEngine == null) return null
                if (candidatesFor != composing.toString()) return null   // 过期的候选
                val c = candidates.getOrNull(index) ?: return null
                if (c.kind == 3 || c.text.isEmpty()) return null
                for (ch in c.text) if (!(ch in 'a'..'z' || ch in 'A'..'Z' || ch == '\'')) return null
                return c.text
            }

            override fun canPin(index: Int) = wordAt(index) != null

            override fun isPinned(index: Int): Boolean {
                val w = wordAt(index) ?: return false
                return (englishEngine as? EnglishEngine)?.isPinned(w.lowercase()) == true
            }

            override fun onPin(index: Int, pin: Boolean) {
                val w = wordAt(index) ?: return
                val eng = englishEngine as? EnglishEngine ?: return
                if (pin) eng.pin(w) else eng.unpin(w)
                ioPool.execute { UserData.pins(applicationContext).saveIfDirty() }
                if (composing.isNotEmpty()) requery() else showNextWords()   // 立刻按新的固定重排候选
            }
        }
        candidateBar = bar
        keyboardView = kb
        emojiPanel = emoji
        symbolPanel = symbol
        clip.setWipe(clipWipe)
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
        leavePage()   // 点完悬浮自动回到键盘(功能页里该键的颜色在 refreshLayout 里同步,下次打开就是对的)
        applySizeLive()
        floatHandle?.refresh()
        inputRoot?.requestLayout()
    }

    /** 拖动过程中实时生效:位置/宽度走布局参数,高度要让键盘和各面板重新取一次。 */
    private fun applySizeLive() {
        applyBoxSize()
        keyboardView?.refresh()
        candidateBar?.refresh()   // 版式(手机/平板)变了才会真的重算,拖动时指纹没变就直接返回
        transBar?.refresh()
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
            SettingsPanelView.ID_TRANS_MS -> {
                onTransKey(Translator.MS)
                leavePage()   // 点完翻译自动回到键盘
            }
            SettingsPanelView.ID_TRANS_GOOGLE -> {
                onTransKey(Translator.GOOGLE)
                leavePage()
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
        curSelS = minOf(info.initialSelStart, info.initialSelEnd)
        curSelE = maxOf(info.initialSelStart, info.initialSelEnd)
        selAnchor = -1
        selFocus = -1

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
        refreshDiag()   // 首页的开关改了以后,下次键盘弹出就生效
        if (diag) AppLog.d("MyIme", "show restarting=$restarting pinyin=${pinyinEngine != null} stroke=${strokeEngine != null} " + editorDesc(info))
        if (!restarting) captureClipboardNow(fromListener = false)   // 切到别的输入法期间复制的内容,回来时补进历史

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
        ioPool.execute { UserData.saveAll() }
    }

    /** 用户在应用里点别处移动了光标:丢弃我们这边的组合状态。 */
    override fun onUpdateSelection(
        oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int,
        candidatesStart: Int, candidatesEnd: Int,
    ) {
        super.onUpdateSelection(
            oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd,
        )
        // 光标 / 选区位置:光标编辑页的「选择」靠它知道从哪里开始(系统本来就会回调,不额外耗电)。
        // 刚被我们自己挪过的 0.4 秒内的回调可能是旧的,不拿来重新定位
        curSelS = minOf(newSelStart, newSelEnd)
        curSelE = maxOf(newSelStart, newSelEnd)
        if (SystemClock.uptimeMillis() - lastSelSetAt > 400) {
            selAnchor = newSelStart
            selFocus = newSelEnd
        }
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

    // 选择状态:selAnchor 是起点(不动),selFocus 是跟着方向键走的那一端(都是输入框里的绝对位置)
    private var selAnchor = -1
    private var selFocus = -1
    private var curSelS = -1          // 系统最近一次告诉我们的选区(已排好序)
    private var curSelE = -1
    private var lastSelSetAt = 0L

    private fun onEditInner(v: String) {
        val ic = currentInputConnection ?: return
        val meta = if (selecting) KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON else 0
        when (v) {
            "tab" -> docStart(ic)   // 原来的 Tab 键:现在是「页首」,跳到整段文字的开头(选择状态下 = 一路选到开头)
            "copy" -> copySelection(ic, cut = false)
            "paste" -> clips.firstOrNull()?.let { pasteFromHistory(it) }   // 系统剪贴板是空的:粘贴输入法历史里最新的一条
            "cut" -> copySelection(ic, cut = true)
            "all" -> ic.performContextMenuAction(android.R.id.selectAll)
            "up" -> if (!(selecting && extendSelection(ic, "up"))) sendKey(KeyEvent.KEYCODE_DPAD_UP, meta)
            "down" -> if (!(selecting && extendSelection(ic, "down"))) sendKey(KeyEvent.KEYCODE_DPAD_DOWN, meta)
            "left" -> if (!(selecting && extendSelection(ic, "left"))) sendKey(KeyEvent.KEYCODE_DPAD_LEFT, meta)
            "right" -> if (!(selecting && extendSelection(ic, "right"))) sendKey(KeyEvent.KEYCODE_DPAD_RIGHT, meta)
            "home" -> if (!(selecting && extendSelection(ic, "home"))) sendKey(KeyEvent.KEYCODE_MOVE_HOME, meta)
            "end" -> if (!(selecting && extendSelection(ic, "end"))) sendKey(KeyEvent.KEYCODE_MOVE_END, meta)
            "del" -> sendKey(KeyEvent.KEYCODE_FORWARD_DEL)
            "select" -> {
                selecting = !selecting
                if (selecting) {
                    // 从当前光标(或已有的选区)开始选
                    selAnchor = curSelS
                    selFocus = curSelE
                }
                refreshLayout(animate = false)
            }
            "back" -> leavePage()
        }
    }

    /**
     * 编辑页的复制 / 剪切。
     *  - 开关开:用 getSelectedText 读出选中的文字,直接存进输入法历史,不碰系统剪贴板;剪切再删掉选区。
     *  - 开关关,或者读不到文字(密码框、部分网页和自绘编辑器)、超过长度上限:让 app 自己复制 / 剪切,
     *    系统剪贴板里有这段内容(开关开时随后会被收进历史并清空)。
     */
    private fun copySelection(ic: InputConnection, cut: Boolean) {
        if (clipWipe && !secureField) {
            val text = try {
                ic.getSelectedText(0)?.toString()
            } catch (e: Exception) {
                null
            }
            if (text != null && text.isNotBlank() && text.length <= MAX_CLIP_CHARS) {
                addClip(text)
                if (cut) sendKey(KeyEvent.KEYCODE_DEL)
                return
            }
        }
        ic.performContextMenuAction(if (cut) android.R.id.cut else android.R.id.copy)
    }

    /** 把输入法历史里的一条输出到输入框。失败(没有输入连接,或 commitText 返回失败)弹提示,返回 false。 */
    private fun pasteFromHistory(text: String): Boolean {
        val ok = try {
            currentInputConnection?.commitText(text, 1) == true
        } catch (e: Exception) {
            false
        }
        if (!ok) {
            android.widget.Toast.makeText(
                this,
                "这里无法用输入法粘贴。可以在剪贴板页顶部关掉「清空系统剪贴板」,再重新复制。",
                android.widget.Toast.LENGTH_LONG,
            ).show()
        }
        return ok
    }

    private fun setSel(ic: InputConnection, anchor: Int, focus: Int) {
        selAnchor = anchor
        selFocus = focus
        lastSelSetAt = SystemClock.uptimeMillis()
        curSelS = minOf(anchor, focus)
        curSelE = maxOf(anchor, focus)
        ic.setSelection(anchor, focus)
    }

    /** 「页首」:光标跳到整段文字最前面;选择状态下保持起点不动,一路选到最前面。 */
    private fun docStart(ic: InputConnection) {
        if (selecting && selAnchor >= 0) setSel(ic, selAnchor, 0) else setSel(ic, 0, 0)
    }

    /**
     * 选择状态下方向键直接改选区(setSelection),不再靠「按住 Shift 发方向键」(很多应用不认)。
     *  - 左 / 右:选区的活动端挪一个字;上 / 下:挪一行(落在上 / 下一行的同一列,行短就到行尾)
     *  - 开头 / 末尾:活动端挪到本行开头 / 末尾
     * 只在点按时读光标附近各 2000 字,没有定时、没有轮询;读不到文字(应用不支持)时返回 false,走原来的发按键。
     */
    private fun extendSelection(ic: InputConnection, dir: String): Boolean {
        val anchor = selAnchor
        val focus = selFocus
        if (anchor < 0 || focus < 0) return false
        val s = minOf(anchor, focus)
        val e = maxOf(anchor, focus)
        if (e - s > 8000) return false
        val before = ic.getTextBeforeCursor(2000, 0) ?: return false
        val mid = if (e > s) (ic.getSelectedText(0) ?: return false) else ""
        if (mid.length != e - s) return false   // 我们记的位置和输入框对不上,别乱挪
        val after = ic.getTextAfterCursor(2000, 0) ?: return false
        val base = s - before.length
        val t = StringBuilder(before.length + mid.length + after.length).append(before).append(mid).append(after).toString()
        val lf = focus - base
        if (lf < 0 || lf > t.length) return false
        val lineStart = t.lastIndexOf('\n', lf - 1) + 1
        val col = lf - lineStart
        var nf = lf
        when (dir) {
            "left" -> {
                if (lf > 0) {
                    nf = lf - 1
                    if (nf > 0 && Character.isLowSurrogate(t[nf]) && Character.isHighSurrogate(t[nf - 1])) nf--
                }
            }
            "right" -> {
                if (lf < t.length) {
                    nf = lf + 1
                    if (nf < t.length && Character.isLowSurrogate(t[nf]) && Character.isHighSurrogate(t[nf - 1])) nf++
                }
            }
            "home" -> nf = lineStart
            "end" -> {
                val le = t.indexOf('\n', lf)
                nf = if (le < 0) t.length else le
            }
            "up" -> {
                if (lineStart == 0) {
                    if (base > 0) return false     // 这一行太长,上一行不在读到的范围里
                    nf = 0
                } else {
                    val prevEnd = lineStart - 1
                    val prevStart = t.lastIndexOf('\n', prevEnd - 1) + 1
                    if (prevStart == 0 && base > 0) return false
                    nf = prevStart + minOf(col, prevEnd - prevStart)
                }
            }
            "down" -> {
                val le = t.indexOf('\n', lf)
                if (le < 0) {
                    nf = t.length
                } else {
                    val nextStart = le + 1
                    val ne = t.indexOf('\n', nextStart)
                    val nextEnd = if (ne < 0) t.length else ne
                    nf = nextStart + minOf(col, nextEnd - nextStart)
                }
            }
        }
        if (nf != lf) setSel(ic, anchor, base + nf)
        return true
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
            if (gen != queryGen) return@execute   // 排队期间已有更新的输入:这个旧查询不用算了(最新一次的任务一定会跑)
            val dg = diag   // 查询过程中开关被改也不会算出离谱的耗时
            val tq = if (dg) android.os.SystemClock.elapsedRealtime() else 0L
            val result = try {
                engine.query(code)
                    .also { ErrorHint.clear("query") }
            } catch (t: Throwable) {
                ErrorHint.set("query", ErrorHint.of("查询出错", t))
                emptyList()
            }
            if (dg) {
                val dt = android.os.SystemClock.elapsedRealtime() - tq
                if (dt >= 40) AppLog.d("MyIme", "slow query len=${code.length} ${dt}ms")   // 单次查询超过 40ms 才记
            }
            main.post { if (gen == queryGen) applyCandidates(code, result) }
        }
    }

    private fun applyCandidates(code: String, list: List<Candidate>) {
        val shown = withEmoji(list)
        candidatesFor = code
        candidates = shown
        candidateBar?.setCandidates(shown.map { it.text })
        candidateBar?.setHint(
            ErrorHint.get("query") ?: when (mode) {
                Mode.PINYIN -> ErrorHint.get("pinyin")
                Mode.STROKE -> ErrorHint.get("stroke")
                Mode.ENGLISH -> ErrorHint.get("english")
                else -> null
            } ?: ErrorHint.get("learn") ?: ErrorHint.get("pins") ?: ErrorHint.get("clip") ?: ErrorHint.get("emoji"),
        )
        onComposingChanged()
    }

    /**
     * 中文候选(拼音/笔画,含联想词)里,某个词有对应的表情,就把表情(kind=3)紧跟在这个词后面。
     * 表情不会排在第一位,所以空格/标点自动上屏的仍是原来的第一候选;最多插 4 个,免得候选栏被表情占满。
     */
    private fun withEmoji(list: List<Candidate>): List<Candidate> {
        if (list.isEmpty() || (mode != Mode.PINYIN && mode != Mode.STROKE)) return list
        val map = EmojiWords.map ?: return list
        var out: ArrayList<Candidate>? = null
        var added = 0
        for (i in list.indices) {
            val c = list[i]
            val es = if (added < 4 && c.kind != 3) map[c.text] else null
            if (es == null) {
                out?.add(c)
                continue
            }
            if (out == null) {
                out = ArrayList(list.size + 4)
                for (j in 0 until i) out.add(list[j])
            }
            out.add(c)
            for (e in es) {
                if (added >= 4) break
                out.add(Candidate(e, c.consumed, 3))
                added++
            }
        }
        return out ?: list
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
        if (c.kind == 3) {   // 点了表情:直接上屏。不记进用词习惯,也不拿它做联想
            ic.beginBatchEdit()
            commitTo(ic, c.text)
            if (composing.isNotEmpty()) {
                composing.delete(0, c.consumed.coerceIn(1, composing.length))
                if (composing.isNotEmpty()) showComposing()
            }
            ic.endBatchEdit()
            if (composing.isNotEmpty()) {
                prevChinese = null
                assocKey = null
                requery()
            } else {
                breakAssoc()
            }
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

    /**
     * 功能页「翻译微软 / 翻译谷歌」两个键:
     *  - 这个引擎正开着 → 关掉输入翻译;
     *  - 没开,或开着的是另一个引擎 → 把它设为主用并打开(正开着时当前这句用新引擎重翻)。
     * 主用失败时仍会自动换另一个引擎试。
     */
    private fun onTransKey(engine: String) {
        val cur = DictConfig.translateEngine(applicationContext)
        if (transOn && cur == engine) {
            setTransOn(false)
            return
        }
        DictConfig.setTranslateEngine(applicationContext, engine)
        transUsedEngine = null
        if (!transOn) {
            setTransOn(true)
            return
        }
        applyTransState()
        if (transActive && transBuf.isNotBlank()) {
            main.removeCallbacks(transRun)
            transGen++
            transInflight = null
            transSrc = ""
            startTranslate()
        }
    }

    /** 实际翻出上一句的引擎:主用失败换了备用时就是备用;还没翻 / 点了按钮重选 / 关闭后为 null(按主用算)。 */
    private var transUsedEngine: String? = null

    /** 功能页只有一个翻译键是绿的:实际在用的那个引擎(主用失败换了备用,绿色跟着换过去,主用的键变回原样)。 */
    private fun updateTransButtons() {
        val eng = transUsedEngine ?: DictConfig.translateEngine(applicationContext)
        settingsPanel?.filledId = when {
            !transOn -> -1
            eng == Translator.GOOGLE -> SettingsPanelView.ID_TRANS_GOOGLE
            else -> SettingsPanelView.ID_TRANS_MS
        }
    }

    private fun transUi() {
        transBar?.setContent(
            transBuf.toString(), Translator.pairs[transPair].label, transStatus,
            if (transStatus == TranslateBarView.STATUS_FAILED && isOnline()) Translator.lastFail else "",
        )
    }

    /** 翻译开关 / 当前输入框是否可用 变了:显示或隐藏翻译框,悬浮条换色。「翻译微软 / 翻译谷歌」按钮的变色由 settingsPanel.filledId 负责。 */
    private fun applyTransState() {
        warmTranslate()
        updateTransButtons()
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
        transUsedEngine = null
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
        if (r.engine != "cache" && r.engine != transUsedEngine) {
            transUsedEngine = r.engine
            updateTransButtons()
        }
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
        settingsPanel?.accentId = if (KbSize.floating) SettingsPanelView.ID_FLOAT else -1
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
                clipPanel?.setWipe(clipWipe)
                clipPanel?.setLocked(locked.toSet())
                clipPanel?.setItems(clips.toList())
                clipPanel?.setTrash(trash.toList())
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
                candidateBar?.visibility = View.VISIBLE   // 打字页/编辑页/数字页:候选栏照常显示
                kb.setLayout(Layouts.build(mode, kbState(), isWide()), false)
            }
        }
    }

    /** 面板和键盘同一时间只显示一个,高度相同,所以切换时不会跳动。 */
    private fun showPanel(panel: PanelView?, animate: Boolean) {
        if (panel == null) return
        keyboardView?.visibility = View.GONE
        // 盖住候选栏的面板(功能/键盘选择/表情/符号/剪贴板)把候选栏收起来,和整个键盘一样大
        candidateBar?.visibility = if (panel.coversCandidateBar) View.GONE else View.VISIBLE
        for (p in listOf<PanelView?>(emojiPanel, symbolPanel, clipPanel, settingsPanel, choicePanel)) {
            if (p !== panel) p?.visibility = View.GONE
        }
        val wasVisible = panel.visibility == View.VISIBLE
        panel.visibility = View.VISIBLE
        if (!wasVisible) panel.onShow(false)   // 不做滑入/淡入动画,直接显示
    }

    /** 系统剪贴板当前的文字(跳过非文字、被标记为敏感的内容如密码管理器复制的密码、空白和超长的);读不到返回 null。 */
    private fun readClipText(): String? {
        try {
            val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            val clip = cm.primaryClip ?: return null
            if (clip.itemCount == 0) return null
            val desc = clip.description
            if (!desc.hasMimeType("text/*")) return null
            if (desc.extras?.getBoolean("android.content.extra.IS_SENSITIVE", false) == true) return null
            val text = clip.getItemAt(0).coerceToText(this)?.toString() ?: return null
            if (text.isBlank() || text.length > MAX_CLIP_CHARS) return null
            return text
        } catch (e: Exception) {
            return null   // 没有读取权限(例如当前不是默认输入法)时直接忽略
        }
    }

    /**
     * 读取系统剪贴板,收进输入法自己的历史;开关「清空系统剪贴板」开着(默认)时,立刻把系统剪贴板清空:
     * 别的 app(含系统自带的)再去读系统剪贴板,读到的是空白;内容只存在输入法私有目录里,
     * 只有你在剪贴板页点那一条,才会输入到当前输入框。
     * 只清空确实收进来的文字;密码管理器标记为敏感的、超长的、非文字的内容不动(我们没存,清掉就丢了)。
     */
    private fun captureClipboardNow(fromListener: Boolean = true) {
        val text = readClipText() ?: return
        if (!clipWipe && !fromListener && text == lastSysClip) return   // 开关关着:系统剪贴板里还是上次收过的,别又顶到最前
        lastSysClip = if (clipWipe) null else text
        addClip(text)
        if (clipWipe) wipeSystemClip()
    }

    /** 收进历史最前面(已经在最前面就什么都不动),超过 500 条挤掉最旧的。 */
    private fun addClip(text: String) {
        if (clips.firstOrNull() == text) return
        clips.remove(text)
        clips.add(0, text)
        while (clips.size > 500) {
            // 挤掉最旧的没上锁的;500 条全上了锁就只好不收这一条
            val i = clips.indexOfLast { it !in locked }
            clips.removeAt(if (i >= 0) i else 0)
        }
        markClips(clipsChanged = true, trashChanged = false, urgent = true)
        if (mode == Mode.CLIPBOARD) clipPanel?.setItems(clips.toList())
    }

    private fun wipeSystemClip() {
        try {
            val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            if (android.os.Build.VERSION.SDK_INT >= 28) cm.clearPrimaryClip()
            else cm.setPrimaryClip(android.content.ClipData.newPlainText("", ""))   // 清空后系统会再通知一次,读到的是空白,不会重复收录
        } catch (e: Exception) {
            // 清不掉就算了(例如当前不是默认输入法)
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
