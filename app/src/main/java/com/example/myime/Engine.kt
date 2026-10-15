package com.example.myime

import android.content.Context

/**
 * consumed:这个候选消耗了输入串的前几个字符(拼音分段上屏时,剩余部分继续留在组合区)。
 * kind:只有英文用到。0 = 原样输入或补全,1 = 纠错,2 = 联想(下一个词),3 = 跟在中文候选词后面的表情(拼音/笔画用)。
 */
class Candidate(val text: String, val consumed: Int, val kind: Int = 0)

interface Engine {
    fun query(code: String): List<Candidate>

    /** 用户选了这个候选:code 是选它时已输入的编码(拼音不含分词符),用于词频学习。 */
    fun learn(code: String, text: String) {}
}

private const val TAG = "MyIme"

/** 第一个 >= target 的位置(keys 必须已排序)。 */
internal fun lowerBound(keys: Array<String>, target: String): Int {
    var lo = 0
    var hi = keys.size
    while (lo < hi) {
        val mid = (lo + hi) ushr 1
        if (keys[mid] < target) lo = mid + 1 else hi = mid
    }
    return lo
}

/**
 * 把"消耗了 letters 个字母"换算成原始输入(含分词用的 ')里的字符数。
 * 如果后面紧跟着一个 ',一并消耗掉。
 */
private fun rawConsumed(raw: String, totalLetters: Int, letters: Int): Int {
    if (letters >= totalLetters) return raw.length
    var n = 0
    for (i in raw.indices) {
        if (raw[i] != '\'') {
            n++
            if (n == letters) {
                var j = i + 1
                if (j < raw.length && raw[j] == '\'') j++
                return j
            }
        }
    }
    return raw.length
}

/**
 * 由 compact 和各音节长度算出每条的首字母串。复用一个字符缓冲区,每条只产生一个 String。
 * 文本加载和二进制缓存读取共用。
 */
internal fun buildInitials(compact: Array<String>, sylLens: ByteArray, sylStart: IntArray): Array<String> {
    val out = Array(compact.size) { "" }
    var buf = CharArray(64)
    for (i in compact.indices) {
        val c = compact[i]
        val a = sylStart[i]
        val b = sylStart[i + 1]
        if (b - a > buf.size) buf = CharArray((b - a) * 2)
        var pos = 0
        var n = 0
        for (k in a until b) {
            buf[n++] = c[pos]
            pos += sylLens[k].toInt()
        }
        out[i] = String(buf, 0, n)
    }
    return out
}

/** 可增长的原始类型数组,加载词库时代替 ArrayList<Int> / ArrayList<Byte>,避免装箱。 */
internal class IntBuf(cap: Int = 1024) {
    var a = IntArray(cap)
    var size = 0
    fun add(v: Int) {
        if (size == a.size) a = a.copyOf(size * 2)
        a[size++] = v
    }
    fun toArray(): IntArray = a.copyOf(size)
}

internal class ByteBuf(cap: Int = 4096) {
    var a = ByteArray(cap)
    var size = 0
    fun add(v: Byte) {
        if (size == a.size) a = a.copyOf(size * 2)
        a[size++] = v
    }
    fun toArray(): ByteArray = a.copyOf(size)
}

/**
 * 拼音引擎。
 * 匹配顺序:完整拼音精确 → 首字母精确 → 完整拼音前缀 → 首字母前缀,
 * 最后补充"只消耗输入前半段"的候选,这样可以连续打多个词再逐段选。
 * 输入里的 ' (分词键)表示用户指定的音节边界,候选必须在这些位置有音节分界。
 */
class PinyinEngine(
    internal val texts: Array<String>,
    compact: Array<String>,
    initials: Array<String>,
    internal val sylLens: ByteArray,   // 每个词条的各音节字母数,连着存;sylStart[i] 到 sylStart[i+1] 是第 i 条的
    internal val sylStart: IntArray,
    internal val freqs: IntArray,
    private val user: UserFreq? = null,
    compactOrder: IntArray? = null,   // 已经排好的顺序(来自二进制缓存),给了就不用再排序
    initialOrder: IntArray? = null,
) : Engine {

    /** 上一个、上上个上屏的中文词(联想/排序的上下文),和我的"接词"习惯。服务端设置。 */
    @Volatile var prev: String? = null
    @Volatile var prev2: String? = null
    @Volatile var chain: UserChain? = null

    private val compactKeys: Array<String>
    internal val compactOf: Array<String> = compact

    // 整句输入:第一次要用时才建(只算一个总词频),不输长句就不占任何东西
    private val sentence: SentenceDecoder by lazy {
        SentenceDecoder(compactKeys, compactIds, texts, freqs, sylLens, sylStart, compactOf, initialKeys, initialIds)
    }

    /** 最近一次算出的整句候选和它们对应的输入(选了其中一句时,按词分别学习)。 */
    @Volatile private var lastSentences: List<SentenceDecoder.Result> = emptyList()
    @Volatile private var lastSentenceP: String? = null

    /** 模糊音:哪些规则打开了(Fuzzy.ZH 等按位或),0 = 全关。由键盘服务设置。 */
    @Volatile var fuzzyMask: Int = 0
    @Volatile private var fuzzyIdx: Fuzzy.Index? = null
    @Volatile private var fuzzyFailed = false
    private val fuzzyBuilding = java.util.concurrent.atomic.AtomicBoolean(false)

    private fun buildFuzzy(mask: Int) {
        if (!fuzzyBuilding.compareAndSet(false, true)) return
        try {
            fuzzyIdx = Fuzzy.Index.build(compactOf, sylLens, sylStart, mask)
        } catch (t: Throwable) {
            fuzzyFailed = true   // 内存不够之类:这个词库实例里不再重试
            AppLog.w(TAG, "build fuzzy index failed", t)
        } finally {
            fuzzyBuilding.set(false)
        }
    }

    /**
     * 规范形索引。没开模糊音返回 null(并放掉旧索引);开了但还没建好:在后台线程建,这一次先不用模糊音。
     * 建好后下一次按键起生效。
     */
    private fun fuzzyIndex(): Fuzzy.Index? {
        val mask = fuzzyMask
        if (mask == 0) {
            if (fuzzyIdx != null) fuzzyIdx = null
            return null
        }
        val cur = fuzzyIdx
        if (cur != null && cur.mask == mask) return cur
        if (!fuzzyFailed && !lowPower && !fuzzyBuilding.get()) {
            Thread { buildFuzzy(mask) }.apply {
                isDaemon = true
                priority = Thread.MIN_PRIORITY
            }.start()
        }
        return null
    }

    /** 我自己造的词(连续选了几段拼成的新词);由键盘服务设置,没有就不查。 */
    @Volatile var words: UserWords? = null

    /**
     * 这次查询是不是已经过期(又有了更新的按键):由键盘服务在每次查询前设置。
     * 贵的几步(整句、混拼、拼错容错)开始前看一眼,过期了就不做了——结果反正会被丢掉。
     */
    @Volatile var stale: (() -> Boolean)? = null

    /**
     * 拼音每次最多给几个词候选(整句、联想、表情不算在内),默认 30。由键盘服务按设置页的「候选数量」设置;
     * 少一点:查询时挑选和候选栏都少做事;多一点:能翻到更靠后的词。限制在 10~100。
     */
    @Volatile var maxCandidates: Int = MAX
        set(v) {
            field = v.coerceIn(MIN_CANDIDATES, MAX_CANDIDATES)
        }

    /** 系统开着省电模式:不查多种整句、不拼模糊音整句、拼错容错不拼整句、不在后台建模糊音索引。 */
    @Volatile var lowPower: Boolean = false

    private fun cancelled(): Boolean = stale?.invoke() == true

    /** 词库加载完后在后台线程调用:把第一次要用的东西先备好,第一次输长句时就不会卡一下。 */
    fun warmUp() {
        sentence                              // 建整句解码器(算一次总词频)
        Syllabary.segment("nihaoma")          // 音节表
        Typo.variants("nihoa", 1)
    }

    /**
     * 选了整句候选(typed 是打的拼音,text 是选的文字)时,返回这一句里的每个词;不是整句返回 null。
     * 键盘服务用它把相邻的词两两记进「词接词」记录。
     */
    fun sentenceWords(typed: String, text: String): Array<String>? {
        val sp = lastSentenceP ?: return null
        for (s in lastSentences) {
            if (s.text == text && s.letters <= sp.length && Syllabary.normalize(typed) == sp.substring(0, s.letters)) {
                return s.words
            }
        }
        return null
    }

    /** 词库里有没有「拼音连写正好是 code、文字是 text」的词条;有就返回词条号,没有返回 -1。 */
    private fun dictFind(code: String, text: String): Int {
        var i = lowerBound(compactKeys, code)
        val end = lowerBound(compactKeys, code + '\u0000')
        while (i < end) {
            val id = compactIds[i]
            if (texts[id] == text) return id
            i++
        }
        return -1
    }

    /**
     * 造词:同一次输入里连续选了几段(typedCodes[i] 是第 i 段打的拼音,pieces[i] 是它选的字),拼起来是词库里没有的新词就记下来。
     * 每一段都得是词库里真实的读音和写法(用模糊音、拼错、简拼、联想词打出来的不算,免得记下错的拼音)。
     * 每段都是单字的第一次就可用;有多字词的段,拼成同样的词第二次才可用。放在后台线程调用。
     */
    fun makeWord(typedCodes: List<String>, pieces: List<String>) {
        val w = words ?: return
        if (typedCodes.size < 2 || typedCodes.size != pieces.size) return
        val whole = pieces.joinToString("")
        val chars = whole.codePointCount(0, whole.length)
        if (chars < UserWords.MIN_CHARS || chars > UserWords.MAX_CHARS) return
        var i = 0
        while (i < whole.length) {   // 只记汉字拼成的词
            val cp = whole.codePointAt(i)
            if (Character.UnicodeScript.of(cp) != Character.UnicodeScript.HAN) return
            i += Character.charCount(cp)
        }
        val codes = typedCodes.map { Syllabary.normalize(it) }   // jv 当作 ju 这类别写
        val syls = ArrayList<String>()
        var allSingle = true
        for (k in codes.indices) {
            val id = dictFind(codes[k], pieces[k])
            if (id < 0) return
            val a = sylStart[id]
            val b = sylStart[id + 1]
            if (b - a != pieces[k].codePointCount(0, pieces[k].length)) return   // 音节数和字数对不上的词条不处理
            var pos = 0
            for (x in a until b) {
                syls.add(codes[k].substring(pos, pos + sylLens[x]))
                pos += sylLens[x]
            }
            if (b - a != 1) allSingle = false
        }
        if (dictFind(codes.joinToString(""), whole) >= 0) return   // 词库里本来就有
        w.add(whole, syls.toTypedArray(), allSingle)
    }

    /** 提前建好模糊音索引(在后台线程调用,开着模糊音时才建),第一次输入就不用等。 */
    fun prepareFuzzy() {
        val m = fuzzyMask
        if (m != 0 && !fuzzyFailed && !lowPower && fuzzyIdx?.mask != m) buildFuzzy(m)
    }
    internal val compactIds: IntArray
    private val initialKeys: Array<String>
    internal val initialIds: IntArray

    init {
        val c = compactOrder ?: texts.indices.sortedBy { compact[it] }.toIntArray()
        compactIds = c
        compactKeys = Array(c.size) { compact[c[it]] }
        val i = initialOrder ?: texts.indices.sortedBy { initials[it] }.toIntArray()
        initialIds = i
        initialKeys = Array(i.size) { initials[i[it]] }
    }


    // 联想用:按词本身排序的下标。第一次要联想时才在后台算一次,不用联想就不占内存
    private val textOrder: IntArray by lazy { texts.indices.sortedBy { texts[it] }.toIntArray() }
    private val textKeys: Array<String> by lazy {
        val o = textOrder
        Array(o.size) { texts[o[it]] }
    }

    /** 提前把联想要用的排序下标建好(在后台线程调用),第一次联想就不用现场排序。 */
    fun prepareFollowers() {
        textKeys.size
    }

    /**
     * 词库里以 prefix 开头的词,去掉 prefix 后剩下的部分:我打过的在前(最近打的最靠前),其余按词频从高到低(最多 max 个,去重)。
     * 比如 prefix = "吃" → 饭、惊、力…… 只取剩余 1 到 4 个字的。
     */
    fun followers(prefix: String, max: Int): List<String> {
        if (prefix.isEmpty()) return emptyList()
        val keys = textKeys
        val ord = textOrder
        val lo = lowerBound(keys, prefix)
        val hi = lowerBound(keys, prefix + '\uffff')
        if (hi <= lo) return emptyList()
        val pool = 48
        val bestId = IntArray(pool)
        val bestFreq = LongArray(pool)
        var size = 0
        val u = user?.takeIf { it.hasLearned() }
        for (j in lo until hi) {
            val id = ord[j]
            val rest = texts[id].length - prefix.length
            if (rest < 1 || rest > 4) continue
            // 我打过的词排在前面(最近打的最靠前),其余按词频;键 >= 2^45 的是我打过的,一定比词频大
            val mine = UserFreq.textRank(u, texts[id])
            val f = if (mine != 0L) mine else freqs[id].toLong()
            if (size == pool && f <= bestFreq[pool - 1]) continue
            var pos = if (size < pool) size else pool - 1
            while (pos > 0 && bestFreq[pos - 1] < f) {
                bestFreq[pos] = bestFreq[pos - 1]
                bestId[pos] = bestId[pos - 1]
                pos--
            }
            bestFreq[pos] = f
            bestId[pos] = id
            if (size < pool) size++
        }
        val out = ArrayList<String>()
        val seen = HashSet<String>()
        for (k in 0 until size) {
            val r = texts[bestId[k]].substring(prefix.length)
            if (seen.add(r)) {
                out.add(r)
                if (out.size >= max) break
            }
        }
        return out
    }

    override fun query(code: String): List<Candidate> {
        val raw = code.lowercase()
        // ü 的别写(jv/yve/lue/nue)统一成词库写法;长度不变。绝大多数输入没有别写,直接走一次查询
        val fixed = Syllabary.normalizeRaw(raw)
        if (fixed == raw) return queryRaw(raw)
        // 有别写:先放按词库写法查到的,再补原样查到的(lue 也可能是 lu + e),按词去重
        val first = queryRaw(fixed)
        val second = queryRaw(raw)
        if (second.isEmpty()) return first
        val seenText = HashSet<String>()
        val merged = ArrayList<Candidate>(first.size + second.size)
        for (c in first) if (seenText.add(c.text)) merged.add(c)
        for (c in second) if (seenText.add(c.text)) merged.add(c)
        val capAll = maxCandidates + PARTIAL_MAX
        return if (merged.size > capAll) merged.subList(0, capAll) else merged
    }

    private fun queryRaw(raw: String): List<Candidate> {
        val p = raw.replace("'", "")
        if (p.isEmpty()) return emptyList()

        // 用户标出的音节边界(用 p 中的字符偏移表示)
        val cuts = ArrayList<Int>()
        var n = 0
        for (ch in raw) {
            if (ch == '\'') {
                if (n > 0 && (cuts.isEmpty() || cuts[cuts.size - 1] != n)) cuts.add(n)
            } else {
                n++
            }
        }

        val out = ArrayList<Candidate>()
        val seen = HashSet<String>()
        // 我常在上一个词后面接的词:给这些候选加分(只在有记录时才查)
        val ctx = chain?.ctxRow(prev2, prev)

        fun add(ids: List<Int>, letters: Int, cap: Int) {
            val consumed = rawConsumed(raw, p.length, letters)
            for (id in ids) {
                if (out.size >= cap) return
                if (seen.add(texts[id])) out.add(Candidate(texts[id], consumed))
            }
        }

        val maxC = maxCandidates   // 这次查询用的候选上限(设置页可调)
        val lu = user?.takeIf { it.hasLearned() }
        val cutsArr = cuts.toIntArray()
        // 我自己造的词:整串正好是我的词时排在所有候选最前面
        val uw = words?.snapshot()
        if (uw != null) {
            val whole = rawConsumed(raw, p.length, p.length)
            for (t in uw.exact(p)) if (seen.add(t)) out.add(Candidate(t, whole))
        }
        // 模糊音:有分词符时不用(用户已经手动分好了);索引没建好时这一次先不用
        val fi = if (cuts.isEmpty()) fuzzyIndex() else null
        val seg: IntArray? by lazy { Syllabary.segment(p) }   // 音节切分最多算一次(模糊音、混拼都要用)
        val cn = if (fi != null) Fuzzy.canon(p, fi.mask, seg) else null
        val low = lowPower

        val end0 = p + '\u0000'
        val endF = p + '\uffff'
        add(top(compactKeys, compactIds, p, end0, cuts, ctx), p.length, maxC)
        val hadExact = out.isNotEmpty()   // 整串正好是词库里的一个词
        val exactCount = out.size
        if (fi != null && cn != null) {
            add(top(fi.keys, fi.ids, cn.canon, cn.canon + '\u0000', emptyList(), ctx, p), p.length, maxC)
        }
        if (p.length >= 2) add(top(initialKeys, initialIds, p, end0, emptyList(), ctx), p.length, maxC)
        if (uw != null) {
            // 我造的词里以打出来的开头的(补全),排在词库的补全前面
            val whole = rawConsumed(raw, p.length, p.length)
            for (t in uw.prefix(p, USER_WORD_PREFIX)) if (seen.add(t)) out.add(Candidate(t, whole))
        }
        add(top(compactKeys, compactIds, p, endF, cuts, ctx), p.length, maxC)
        if (p.length >= 2) add(top(initialKeys, initialIds, p, endF, emptyList(), ctx), p.length, maxC)
        if (fi != null && cn != null) {
            add(top(fi.keys, fi.ids, cn.canon, cn.canon + '\uffff', emptyList(), ctx, p), p.length, maxC)
        }

        // 整句:把拼音切成几个词拼成整句(最多 3 句,歧义切分各给一句;模糊音下再加最多 2 句)。
        // 整串不是一个词时放在最前面;整串本来就是词时(输入够长才查)放在这些词后面,只给 2 句
        var sentenceDone = false
        val sents = ArrayList<SentenceDecoder.Result>()
        if (p.length >= SENTENCE_MIN && (!hadExact || (!low && p.length >= SENTENCE_MIN_EXACT)) && !cancelled()) {
            // 省电模式只要最好的一句;相邻的词用我的「词接词」记录加分
            sents.addAll(sentence.decodeTop(p, cutsArr, lu, ctx, if (low) 1 else SENTENCE_K, uw, chain))
        }
        if (!low && fi != null && cn != null && p.length >= SENTENCE_MIN && !cancelled()) {
            sents.addAll(sentence.decodeFuzzyTop(p, cn, fi.keys, fi.ids, lu, ctx, 2))
        }
        if (sents.isNotEmpty()) {
            val limit = if (hadExact) SENT_WITH_EXACT else SENT_SHOWN
            var at = if (hadExact) minOf(exactCount, 3) else 0
            var shown = 0
            val done = HashSet<String>()
            for (s in sents) {
                if (shown >= limit) break
                if (!done.add(s.text)) continue
                if (!seen.add(s.text)) {
                    if (hadExact) continue
                    out.removeAll { it.text == s.text }   // 原来排在后面的同一句,提到前面来
                    if (at > out.size) at = out.size
                }
                out.add(at++, Candidate(s.text, rawConsumed(raw, p.length, s.letters)))
                shown++
            }
            if (shown > 0) {
                lastSentences = sents
                lastSentenceP = p
                sentenceDone = true
            }
        }
        // 混拼:输入里有没法当成完整音节的字母(zgren、wmmtquks),每个音节可以只写声母,最后一个音节可以没打完。
        // 能切成完整音节的输入(绝大多数)不会走到这里
        if (!sentenceDone && p.length >= MIXED_MIN && seg == null && !cancelled()) {
            val m = sentence.decodeMixed(p, cutsArr, lu, ctx)
            if (m != null) {
                val consumed = rawConsumed(raw, p.length, m.letters)
                out.removeAll { it.text == m.text }
                out.add(0, Candidate(m.text, consumed))
                seen.add(m.text)
                var at = 1
                for (alt in m.alternatives) {
                    if (seen.add(alt)) out.add(at++, Candidate(alt, consumed))
                }
                lastSentences = listOf(m)
                lastSentenceP = p
            }
        }

        // 拼错容错:只有上面所有办法都解释不了整串(没有任何候选能吃完整串)时才试,平时一点开销都没有。
        // 只在出错位置附近改一处(写反 / 按错相邻键 / 多打 / 漏打),改完是合法拼音才查词;
        // 查到的放在最前面(此时 out 是空的),空格直接上屏改正后的词。有分词符的输入不改
        if (cuts.isEmpty() && p.length >= TYPO_MIN && out.none { it.consumed >= raw.length } && !cancelled()) {
            var found = 0
            var sentTried = 0
            val whole = rawConsumed(raw, p.length, p.length)
            for (v in Typo.variants(p, TYPO_VARIANTS)) {
                if (found >= TYPO_MAX) break
                // 学习记录按用户实际打的(带错的)写法记,所以下次同样打错也排在前面
                val ids = top(compactKeys, compactIds, v, v + '\u0000', emptyList(), ctx, p)
                for (id in ids) {
                    if (found >= TYPO_MAX) break
                    if (seen.add(texts[id])) {
                        out.add(Candidate(texts[id], whole))
                        found++
                    }
                }
                // 整串不是一个词:改完后拼整句(最多试几个写法,整句比查词贵)
                if (ids.isEmpty() && !low && p.length >= SENTENCE_MIN && sentTried < TYPO_SENT && !cancelled()) {
                    sentTried++
                    val s = sentence.decode(v, null, lu, ctx)
                    if (s != null && s.letters == v.length && seen.add(s.text)) {
                        out.add(Candidate(s.text, whole))
                        found++
                    }
                }
            }
        }

        if (cancelled()) return out   // 已经有更新的按键:这次的结果会被丢掉,后面的分段不用做了

        // 分段:找最长的、能精确匹配的前缀
        var q = p.length - 1
        var added = 0
        while (q >= 1 && added == 0) {
            val sub = p.substring(0, q)
            val subCuts = cuts.filter { it <= q }
            val consumed = rawConsumed(raw, p.length, q)
            for (id in top(compactKeys, compactIds, sub, sub + '\u0000', subCuts, ctx)) {
                if (added >= PARTIAL_MAX) break
                if (seen.add(texts[id])) {
                    out.add(Candidate(texts[id], consumed))
                    added++
                }
            }
            q--
        }
        return out
    }

    /**
     * 只挑出最靠前的 MAX 个(我选过的优先,其余按词频),用一个小的有序数组,不对整个区间排序。
     * 输入 a、zh 这种很短的前缀时,匹配的条目可能有几万条,全排序很费 CPU。
     */
    private fun top(
        keys: Array<String>, ids: IntArray, from: String, to: String, cuts: List<Int>,
        ctx: Map<String, Long>? = null,
        code: String = from,   // 查学习记录用的编码;模糊音查规范形索引时 from 是折叠后的,code 是用户实际打的
    ): List<Int> {
        val lo = lowerBound(keys, from)
        val hi = lowerBound(keys, to)
        if (hi <= lo) return emptyList()
        val u = user?.takeIf { it.hasLearned() }   // 没学过任何词就完全不查表
        val boosted = u != null || ctx != null
        val win = u?.recent(code)   // 这个编码最近几次的选择,整次查询取一次
        val maxC = maxCandidates
        val bestId = IntArray(maxC)
        val bestKey = LongArray(maxC)
        var size = 0
        for (j in lo until hi) {
            val id = ids[j]
            if (cuts.isNotEmpty() && !boundariesOk(id, cuts)) continue
            // 我选过的排在没选过的前面,最近选的最靠前(同音高频字再看最近几次里出现几次,见 UserFreq.rank);
            // 没有任何学习记录的词按词频。没有学习记录时,顺序和纯词频完全一样
            val key = if (!boosted) {
                freqs[id].toLong()
            } else {
                val r = UserFreq.rank(u, code, texts[id], win, ctx?.get(texts[id]) ?: 0L)
                if (r != 0L) r else freqs[id].toLong()
            }
            if (size == maxC && key <= bestKey[maxC - 1]) continue
            var pos = if (size < maxC) size else maxC - 1
            while (pos > 0 && bestKey[pos - 1] < key) {
                bestKey[pos] = bestKey[pos - 1]
                bestId[pos] = bestId[pos - 1]
                pos--
            }
            bestKey[pos] = key
            bestId[pos] = id
            if (size < maxC) size++
        }
        val out = ArrayList<Int>(size)
        for (k in 0 until size) out.add(bestId[k])
        return out
    }

    override fun learn(code: String, text: String) {
        // 和查询用同一种写法记,下次查才对得上(jv 记成 ju)
        val c = Syllabary.normalize(code)
        val sp = lastSentenceP
        if (sp != null) {
            for (s in lastSentences) {
                if (s.text == text && s.letters <= sp.length && c == sp.substring(0, s.letters)) {
                    // 选的是整句:句子里每个词各记一笔(整句本身不在词库里,记不了)
                    for (k in s.words.indices) user?.learn(s.codes[k], s.words[k])
                    return
                }
            }
        }
        user?.learn(c, text)
    }

    /** cuts(升序)里的每个位置,都必须恰好是该词条的一个音节结束处。 */
    private fun boundariesOk(id: Int, cuts: List<Int>): Boolean {
        var count = 0
        var ci = 0
        for (k in sylStart[id] until sylStart[id + 1]) {
            count += sylLens[k].toInt()
            if (ci < cuts.size && cuts[ci] < count) return false
            if (ci < cuts.size && cuts[ci] == count) ci++
        }
        return ci >= cuts.size
    }

    private companion object {
        const val MAX = 30   // 候选上限的默认值
        const val MIN_CANDIDATES = 10
        const val MAX_CANDIDATES = 100
        const val PARTIAL_MAX = 8
        const val SENTENCE_MIN = 5   // 少于这么多字母不拼整句
        const val SENTENCE_MIN_EXACT = 6   // 整串本来就是词库里的词时,至少这么长才再查整句(给歧义切分)
        const val SENTENCE_K = 3     // 整句最多几种切法
        const val USER_WORD_PREFIX = 3   // 我造的词里,补全最多放几个
        const val TYPO_MIN = 3       // 少于这么多字母不做拼错容错(太短改什么都对得上)
        const val TYPO_VARIANTS = 8  // 最多试几个改过的写法
        const val TYPO_MAX = 6       // 容错最多给几个候选
        const val TYPO_SENT = 3      // 最多对几个改过的写法拼整句
        const val SENT_SHOWN = 4     // 整句最多放几个到候选里(含模糊音的)
        const val SENT_WITH_EXACT = 2   // 整串本来就是词时,整句最多放几个
        const val MIXED_MIN = 4      // 少于这么多字母不查混拼(2、3 个字母的纯声母原来就能查)
    }
}

/** 笔画引擎:输入 h/s/p/n/z 序列,前缀匹配;* 代表任意一笔;精确匹配的排前面。 */
class StrokeEngine(
    internal val texts: Array<String>,
    internal val codeOf: Array<String>,
    internal val freqs: IntArray,
    private val user: UserFreq? = null,
    order: IntArray? = null,   // 已经排好的顺序(来自二进制缓存)
) : Engine {

    private val keys: Array<String>
    internal val ids: IntArray

    init {
        val s = order ?: texts.indices.sortedBy { codeOf[it] }.toIntArray()
        ids = s
        keys = Array(s.size) { codeOf[s[it]] }
    }

    override fun query(code: String): List<Candidate> {
        if (code.isEmpty()) return emptyList()
        val star = code.indexOf('*')
        val prefix = if (star < 0) code else code.substring(0, star)
        val u = user?.takeIf { it.hasLearned() }

        // 只挑最靠前的 SLOTS 个。排序从高到低:
        //   我选过的(最近选的最靠前;同音字再看最近几次里出现几次,见 UserFreq.rank) >
        //   有字频的常用字 > 没字频的生僻字;同一组里笔画数刚好等于输入的 > 还没打完的;再按字频(2 倍一档);精确字频;笔画短的
        val bestId = IntArray(SLOTS)
        val bestKey = LongArray(SLOTS)
        var size = 0
        val win = u?.recent(code)   // 这个输入最近几次的选择,整次查询取一次
        fun offer(id: Int) {
            val len = codeOf[id].length
            val f = freqs[id]
            val r = if (u != null) UserFreq.rank(u, code, texts[id], win, 0L) else 0L
            val key = if (r != 0L) {
                (1L shl 62) or r
            } else {
                val tier = if (f > 1) 1L shl 61 else 0L
                val octave = (32 - Integer.numberOfLeadingZeros(maxOf(f, 0))).toLong()   // 0..32,字频按 2 倍一档
                // 笔画数刚好等于输入的(用户已经打完了整个字)排在还没打完的前面,再按字频。
                // 以前字频排在"笔画数刚好"前面:前缀(如撇折点)后面接着的常用字有几百个,只留前 80 个,
                // 刚好 3 笔的字(么)字频不够高就被挤出去了,往后翻也没有
                val exact = len == code.length
                tier or
                    (if (exact) 1L shl 60 else 0L) or
                    (octave shl 53) or
                    (f.toLong() shl 18) or
                    (255 - minOf(len, 255)).toLong()
            }
            if (size == SLOTS && key <= bestKey[SLOTS - 1]) return
            var pos = if (size < SLOTS) size else SLOTS - 1
            while (pos > 0 && bestKey[pos - 1] < key) {
                bestKey[pos] = bestKey[pos - 1]
                bestId[pos] = bestId[pos - 1]
                pos--
            }
            bestKey[pos] = key
            bestId[pos] = id
            if (size < SLOTS) size++
        }

        if (prefix.isEmpty()) {
            for (j in ids.indices) {
                if (matches(ids[j], code)) offer(ids[j])
            }
        } else {
            val lo = lowerBound(keys, prefix)
            val hi = lowerBound(keys, prefix + '\uffff')
            for (j in lo until hi) {
                val id = ids[j]
                if (star < 0 || matches(id, code)) offer(id)
            }
        }
        if (size == 0) return emptyList()

        val out = ArrayList<Candidate>()
        val seen = HashSet<String>()
        for (k in 0 until size) {
            if (out.size >= MAX) break
            val id = bestId[k]
            // 兼容区的同形字(看起来一模一样、编码不同)按规范化后的字去重
            if (seen.add(java.text.Normalizer.normalize(texts[id], java.text.Normalizer.Form.NFKC))) {
                out.add(Candidate(texts[id], code.length))
            }
        }
        return out
    }

    override fun learn(code: String, text: String) {
        user?.learn(code, text)
    }

    private fun matches(id: Int, code: String): Boolean {
        val c = codeOf[id]
        if (c.length < code.length) return false
        for (i in code.indices) {
            if (code[i] != '*' && code[i] != c[i]) return false
        }
        return true
    }

    private companion object {
        const val MAX = 40
        const val SLOTS = 80   // 同一个字可能有几种笔顺,多取一些再去重
    }
}

/**
 * 词库格式(UTF-8,Tab 分隔,# 开头为注释):
 *   pinyin.txt:  词 <Tab> pin yin <Tab> 词频(可省略)
 *   stroke.txt:  字 <Tab> hspnz 笔画串 <Tab> 字频(可省略)
 *   english.txt: 词 <Tab> 权重
 *   english_bigram.txt: 前词 <Tab> 后词 <Tab> 权重
 * 前两个和 Rime 的 .dict.yaml 正文列顺序一致。
 * 优先读 DictStore 文件夹里的词库(词库管理下载的,或手动放进去的);没有就用 assets 里的内置小样本。
 */
object DictLoader {

    private fun openDict(context: Context, name: String): java.io.InputStream {
        val f = DictStore.file(context, name)
        return if (f.isFile && f.length() > 0) f.inputStream() else context.assets.open(name)
    }

    /** 词库文件夹里的文件(有才返回);没有就是用 assets 样本,不做缓存。 */
    private fun userFile(context: Context, name: String): java.io.File? {
        val f = DictStore.file(context, name)
        return if (f.isFile && f.length() > 0) f else null
    }

    fun loadPinyin(context: Context): Engine? = try {
        val src = userFile(context, DictStore.PINYIN_FILE)
        if (src == null) ErrorHint.set("pinyin", "没装拼音词库,现在只有内置小样本(词库管理里下载)") else ErrorHint.clear("pinyin")
        val user = UserData.pinyin(context)
        val cached = if (src != null) DictCache.loadPinyin(context, src, user) else null
        if (cached != null) {
            cached
        } else {
            val texts = ArrayList<String>()
            val compact = ArrayList<String>()
            val freqs = IntBuf()
            val sylLens = ByteBuf()
            val sylStart = IntBuf()
            val sb = StringBuilder()
            openDict(context, DictStore.PINYIN_FILE).bufferedReader(Charsets.UTF_8).useLines { lines ->
                for (line in lines) {
                    if (line.isEmpty() || line[0] == '#') continue
                    val t1 = line.indexOf('\t')
                    if (t1 <= 0) continue
                    val t2 = line.indexOf('\t', t1 + 1)
                    val text = line.substring(0, t1).trim()
                    if (text.isEmpty()) continue
                    val pyEnd = if (t2 < 0) line.length else t2
                    val t3 = if (t2 < 0) -1 else line.indexOf('\t', t2 + 1)
                    // 逐字符扫描拼音列:记下每个音节的长度,拼出不带空格的 compact;遇到非 a-z 的字符整条丢弃
                    sb.setLength(0)
                    val startLens = sylLens.size
                    var cur = 0
                    var ok = true
                    for (idx in t1 + 1 until pyEnd) {
                        val ch = line[idx].lowercaseChar()
                        if (ch in 'a'..'z') {
                            sb.append(ch)
                            cur++
                            if (cur > 127) {   // 音节长度用一个字节存,不可能这么长,当作坏行
                                ok = false
                                break
                            }
                        } else if (ch == ' ' || ch == '\t' || ch == '\r') {
                            if (cur > 0) {
                                sylLens.add(cur.toByte())
                                cur = 0
                            }
                        } else {
                            ok = false
                            break
                        }
                    }
                    if (ok && cur > 0) sylLens.add(cur.toByte())
                    if (!ok || sylLens.size == startLens || sylLens.size - startLens > 255) {   // 缓存里音节数用一个字节存
                        sylLens.size = startLens   // 丢掉这一条已经记下的音节
                        continue
                    }
                    sylStart.add(startLens)
                    texts.add(text)
                    compact.add(sb.toString())
                    freqs.add(
                        if (t2 < 0) 1
                        else line.substring(t2 + 1, if (t3 < 0) line.length else t3).trim().toIntOrNull() ?: 1,
                    )
                }
            }
            sylStart.add(sylLens.size)
            val compactArr = compact.toTypedArray()
            val lensArr = sylLens.toArray()
            val startArr = sylStart.toArray()
            val e = PinyinEngine(
                texts.toTypedArray(), compactArr, buildInitials(compactArr, lensArr, startArr),
                lensArr, startArr, freqs.toArray(), user,
            )
            if (src != null) DictCache.savePinyin(context, src, e)
            e
        }
    } catch (e: Throwable) {
        AppLog.w(TAG, "load pinyin failed", e)
        ErrorHint.set("pinyin", ErrorHint.of("拼音词库加载失败", e))
        null
    }

    fun loadStroke(context: Context): Engine? = try {
        val src = userFile(context, DictStore.STROKE_FILE)
        if (src == null) ErrorHint.set("stroke", "没装笔画词库,现在只有内置小样本(词库管理里下载)") else ErrorHint.clear("stroke")
        val user = UserData.stroke(context)
        val cached = if (src != null) DictCache.loadStroke(context, src, user) else null
        if (cached != null) {
            cached
        } else {
            val texts = ArrayList<String>()
            val codes = ArrayList<String>()
            val freqs = ArrayList<Int>()
            openDict(context, DictStore.STROKE_FILE).bufferedReader(Charsets.UTF_8).useLines { lines ->
                for (line in lines) {
                    if (line.isEmpty() || line[0] == '#') continue
                    val p = line.split('\t')
                    if (p.size < 2) continue
                    val text = p[0].trim()
                    val code = p[1].trim().lowercase()
                    if (text.isEmpty() || code.isEmpty()) continue
                    if (!code.all { it == 'h' || it == 's' || it == 'p' || it == 'n' || it == 'z' }) continue
                    texts.add(text)
                    codes.add(code)
                    freqs.add(p.getOrNull(2)?.trim()?.toIntOrNull() ?: 1)
                }
            }
            val e = StrokeEngine(texts.toTypedArray(), codes.toTypedArray(), freqs.toIntArray(), user)
            if (src != null) DictCache.saveStroke(context, src, e)
            e
        }
    } catch (e: Throwable) {
        AppLog.w(TAG, "load stroke failed", e)
        ErrorHint.set("stroke", ErrorHint.of("笔画词库加载失败", e))
        null
    }

    /** 英文没有内置样本:词库管理里没下载(文件夹里也没有 english.txt)就返回 null,英文页保持直接上屏。 */
    fun loadEnglish(context: Context): EnglishEngine? = try {
        val f = userFile(context, DictStore.ENGLISH_FILE)
        if (f == null) {
            null
        } else {
            // 双词表很小,每次读文本
            val succ = HashMap<String, Array<String>>()
            val bf = userFile(context, DictStore.ENGLISH_BIGRAM_FILE)
            if (bf != null) {
                val tmp = HashMap<String, ArrayList<Pair<String, Int>>>()
                bf.bufferedReader(Charsets.UTF_8).useLines { lines ->
                    for (line in lines) {
                        if (line.isEmpty() || line[0] == '#') continue
                        val p = line.split('\t')
                        if (p.size < 3) continue
                        val c = p[2].trim().toIntOrNull() ?: continue
                        tmp.getOrPut(p[0]) { ArrayList() }.add(Pair(p[1], c))
                    }
                }
                for ((k, list) in tmp) {
                    list.sortByDescending { it.second }
                    succ[k] = Array(list.size) { list[it].first }
                }
            }
            val user = UserData.english(context)
            val cached = DictCache.loadEnglish(context, f, succ, user)
            if (cached != null) {
                cached
            } else {
                val words = ArrayList<String>()
                val freqs = ArrayList<Int>()
                f.bufferedReader(Charsets.UTF_8).useLines { lines ->
                    for (line in lines) {
                        if (line.isEmpty() || line[0] == '#') continue
                        val t = line.indexOf('\t')
                        if (t <= 0) continue
                        val w = line.substring(0, t).trim()
                        if (w.isEmpty()) continue
                        words.add(w)
                        freqs.add(line.substring(t + 1).trim().toIntOrNull() ?: 1)
                    }
                }
                if (words.isEmpty()) {
                    null
                } else {
                    val e = EnglishEngine(words.toTypedArray(), freqs.toIntArray(), succ, user)
                    DictCache.saveEnglish(context, f, e)
                    e
                }
            }
        }
    } catch (e: Throwable) {
        AppLog.w(TAG, "load english failed", e)
        ErrorHint.set("english", ErrorHint.of("英文词库加载失败", e))
        null
    }
}
