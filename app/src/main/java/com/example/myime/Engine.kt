package com.example.myime

import android.content.Context
import android.util.Log

/** consumed:这个候选消耗了输入串的前几个字符(拼音分段上屏时,剩余部分继续留在组合区)。 */
class Candidate(val text: String, val consumed: Int)

interface Engine {
    fun query(code: String): List<Candidate>
}

private const val TAG = "MyIme"

/** 第一个 >= target 的位置(keys 必须已排序)。 */
private fun lowerBound(keys: Array<String>, target: String): Int {
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
 * 拼音引擎。
 * 匹配顺序:完整拼音精确 → 首字母精确 → 完整拼音前缀 → 首字母前缀,
 * 最后补充"只消耗输入前半段"的候选,这样可以连续打多个词再逐段选。
 * 输入里的 ' (分词键)表示用户指定的音节边界,候选必须在这些位置有音节分界。
 */
class PinyinEngine(
    private val texts: Array<String>,
    compact: Array<String>,
    initials: Array<String>,
    private val spaced: Array<String>,
    private val freqs: IntArray,
) : Engine {

    private val compactKeys: Array<String>
    private val compactIds: IntArray
    private val initialKeys: Array<String>
    private val initialIds: IntArray

    init {
        val c = texts.indices.sortedBy { compact[it] }
        compactIds = c.toIntArray()
        compactKeys = Array(c.size) { compact[c[it]] }
        val i = texts.indices.sortedBy { initials[it] }
        initialIds = i.toIntArray()
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

    private fun top(
        keys: Array<String>, ids: IntArray, from: String, to: String, cuts: List<Int>,
    ): List<Int> {
        val lo = lowerBound(keys, from)
        val hi = lowerBound(keys, to)
        if (hi <= lo) return emptyList()
        val list = ArrayList<Int>(hi - lo)
        for (j in lo until hi) {
            val id = ids[j]
            if (cuts.isNotEmpty() && !boundariesOk(id, cuts)) continue
            list.add(id)
        }
        list.sortWith(Comparator { a, b -> freqs[b].compareTo(freqs[a]) })
        return if (list.size > MAX) list.subList(0, MAX) else list
    }

    /** cuts(升序)里的每个位置,都必须恰好是该词条的一个音节结束处。 */
    private fun boundariesOk(id: Int, cuts: List<Int>): Boolean {
        val s = spaced[id]
        var count = 0
        var ci = 0
        for (idx in 0..s.length) {
            val atEnd = idx == s.length || s[idx] == ' '
            if (atEnd) {
                if (ci < cuts.size && cuts[ci] < count) return false
                if (ci < cuts.size && cuts[ci] == count) ci++
            } else {
                count++
            }
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
    private val texts: Array<String>,
    private val codeOf: Array<String>,
    private val freqs: IntArray,
) : Engine {

    private val keys: Array<String>
    private val ids: IntArray

    init {
        val s = texts.indices.sortedBy { codeOf[it] }
        ids = s.toIntArray()
        keys = Array(s.size) { codeOf[s[it]] }
    }

    override fun query(code: String): List<Candidate> {
        if (code.isEmpty()) return emptyList()
        val star = code.indexOf('*')
        val prefix = if (star < 0) code else code.substring(0, star)

        val list = ArrayList<Int>()
        if (prefix.isEmpty()) {
            for (j in ids.indices) {
                if (matches(ids[j], code)) list.add(ids[j])
            }
        } else {
            val lo = lowerBound(keys, prefix)
            val hi = lowerBound(keys, prefix + '\uffff')
            for (j in lo until hi) {
                val id = ids[j]
                if (star < 0 || matches(id, code)) list.add(id)
            }
        }
        if (list.isEmpty()) return emptyList()

        list.sortWith(
            compareBy<Int>(
                { if (codeOf[it].length == code.length) 0 else 1 },
                { -freqs[it] },
                { codeOf[it].length },
            )
        )
        val out = ArrayList<Candidate>()
        val seen = HashSet<String>()
        for (id in list) {
            if (out.size >= MAX) break
            if (seen.add(texts[id])) out.add(Candidate(texts[id], code.length))
        }
        return out
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
    }
}

/**
 * 词库格式(UTF-8,Tab 分隔,# 开头为注释):
 *   pinyin.txt:  词 <Tab> pin yin <Tab> 词频(可省略)
 *   stroke.txt:  字 <Tab> hspnz 笔画串 <Tab> 字频(可省略)
 * 两者都和 Rime 的 .dict.yaml 正文列顺序一致,可用 tools/convert_rime_dict.py 转换。
 */
object DictLoader {

    fun loadPinyin(context: Context): Engine? = try {
        val texts = ArrayList<String>()
        val compact = ArrayList<String>()
        val initials = ArrayList<String>()
        val spaced = ArrayList<String>()
        val freqs = ArrayList<Int>()
        context.assets.open("pinyin.txt").bufferedReader(Charsets.UTF_8).useLines { lines ->
            for (line in lines) {
                if (line.isEmpty() || line[0] == '#') continue
                val p = line.split('\t')
                if (p.size < 2) continue
                val text = p[0].trim()
                if (text.isEmpty()) continue
                val syll = p[1].trim().lowercase().split(' ').filter { it.isNotEmpty() }
                if (syll.isEmpty()) continue
                val joined = syll.joinToString("")
                if (!joined.all { it in 'a'..'z' }) continue
                texts.add(text)
                compact.add(joined)
                initials.add(syll.map { it[0] }.joinToString(""))
                spaced.add(syll.joinToString(" "))
                freqs.add(p.getOrNull(2)?.trim()?.toIntOrNull() ?: 1)
            }
        }
        PinyinEngine(
            texts.toTypedArray(), compact.toTypedArray(), initials.toTypedArray(),
            spaced.toTypedArray(), freqs.toIntArray(),
        )
    } catch (e: Exception) {
        Log.w(TAG, "load pinyin failed", e)
        null
    }

    fun loadStroke(context: Context): Engine? = try {
        val texts = ArrayList<String>()
        val codes = ArrayList<String>()
        val freqs = ArrayList<Int>()
        context.assets.open("stroke.txt").bufferedReader(Charsets.UTF_8).useLines { lines ->
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
        StrokeEngine(texts.toTypedArray(), codes.toTypedArray(), freqs.toIntArray())
    } catch (e: Exception) {
        Log.w(TAG, "load stroke failed", e)
        null
    }
}
