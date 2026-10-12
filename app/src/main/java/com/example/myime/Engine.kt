package com.example.myime

import android.content.Context
import android.util.Log

/**
 * consumed:这个候选消耗了输入串的前几个字符(拼音分段上屏时,剩余部分继续留在组合区)。
 * kind:只有英文用到。0 = 原样输入或补全,1 = 纠错,2 = 联想(下一个词)。
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

    private val compactKeys: Array<String>
    internal val compactOf: Array<String> = compact
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

    override fun query(code: String): List<Candidate> {
        val raw = code.lowercase()
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

        fun add(ids: List<Int>, letters: Int, cap: Int) {
            val consumed = rawConsumed(raw, p.length, letters)
            for (id in ids) {
                if (out.size >= cap) return
                if (seen.add(texts[id])) out.add(Candidate(texts[id], consumed))
            }
        }

        val end0 = p + '\u0000'
        val endF = p + '\uffff'
        add(top(compactKeys, compactIds, p, end0, cuts), p.length, MAX)
        if (p.length >= 2) add(top(initialKeys, initialIds, p, end0, emptyList()), p.length, MAX)
        add(top(compactKeys, compactIds, p, endF, cuts), p.length, MAX)
        if (p.length >= 2) add(top(initialKeys, initialIds, p, endF, emptyList()), p.length, MAX)

        // 分段:找最长的、能精确匹配的前缀
        var q = p.length - 1
        var added = 0
        while (q >= 1 && added == 0) {
            val sub = p.substring(0, q)
            val subCuts = cuts.filter { it <= q }
            val consumed = rawConsumed(raw, p.length, q)
            for (id in top(compactKeys, compactIds, sub, sub + '\u0000', subCuts)) {
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
    ): List<Int> {
        val lo = lowerBound(keys, from)
        val hi = lowerBound(keys, to)
        if (hi <= lo) return emptyList()
        val u = user?.takeIf { it.hasLearned() }   // 没学过任何词就完全不查表
        val bestId = IntArray(MAX)
        val bestKey = LongArray(MAX)
        var size = 0
        for (j in lo until hi) {
            val id = ids[j]
            if (cuts.isNotEmpty() && !boundariesOk(id, cuts)) continue
            val learned = if (u != null) u.score(texts[id], from) else 0
            val key = (learned.toLong() shl 32) or freqs[id].toLong()
            if (size == MAX && key <= bestKey[MAX - 1]) continue
            var pos = if (size < MAX) size else MAX - 1
            while (pos > 0 && bestKey[pos - 1] < key) {
                bestKey[pos] = bestKey[pos - 1]
                bestId[pos] = bestId[pos - 1]
                pos--
            }
            bestKey[pos] = key
            bestId[pos] = id
            if (size < MAX) size++
        }
        val out = ArrayList<Int>(size)
        for (k in 0 until size) out.add(bestId[k])
        return out
    }

    override fun learn(code: String, text: String) {
        user?.learn(code, text)
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
        const val MAX = 30
        const val PARTIAL_MAX = 8
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

        // 只挑最靠前的 SLOTS 个:笔画数刚好等于输入的排前面,然后是我选过的、字频高的、笔画短的
        val bestId = IntArray(SLOTS)
        val bestKey = LongArray(SLOTS)
        var size = 0
        fun offer(id: Int) {
            val len = codeOf[id].length
            val learned = if (u != null) u.score(texts[id], code) else 0
            val key = (if (len == code.length) 1L shl 60 else 0L) or
                (learned.toLong() shl 40) or (freqs[id].toLong() shl 8) or
                (255 - minOf(len, 255)).toLong()
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
            if (seen.add(texts[id])) out.add(Candidate(texts[id], code.length))
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
        Log.w(TAG, "load pinyin failed", e)
        null
    }

    fun loadStroke(context: Context): Engine? = try {
        val src = userFile(context, DictStore.STROKE_FILE)
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
        Log.w(TAG, "load stroke failed", e)
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
        Log.w(TAG, "load english failed", e)
        null
    }
}
