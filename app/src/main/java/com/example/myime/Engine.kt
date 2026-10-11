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
 * 拼音引擎。
 * 匹配顺序:完整拼音精确 → 首字母精确 → 完整拼音前缀 → 首字母前缀,
 * 最后补充"只消耗输入前半段"的候选,这样可以连续打多个词再逐段选。
 */
class PinyinEngine(
    private val texts: Array<String>,
    compact: Array<String>,
    initials: Array<String>,
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
        val p = code.lowercase()
        if (p.isEmpty()) return emptyList()

        val out = ArrayList<Candidate>()
        val seen = HashSet<String>()

        fun add(ids: List<Int>, consumed: Int, cap: Int) {
            for (id in ids) {
                if (out.size >= cap) return
                if (seen.add(texts[id])) out.add(Candidate(texts[id], consumed))
            }
        }

        val end0 = p + '\u0000'
        val endF = p + '\uffff'
        add(top(compactKeys, compactIds, p, end0), p.length, MAX)
        if (p.length >= 2) add(top(initialKeys, initialIds, p, end0), p.length, MAX)
        add(top(compactKeys, compactIds, p, endF), p.length, MAX)
        if (p.length >= 2) add(top(initialKeys, initialIds, p, endF), p.length, MAX)

        // 分段:找最长的、能精确匹配的前缀
        var q = p.length - 1
        var added = 0
        while (q >= 1 && added == 0) {
            val sub = p.substring(0, q)
            for (id in top(compactKeys, compactIds, sub, sub + '\u0000')) {
                if (added >= PARTIAL_MAX) break
                if (seen.add(texts[id])) {
                    out.add(Candidate(texts[id], q))
                    added++
                }
            }
            q--
        }
        return out
    }

    private fun top(keys: Array<String>, ids: IntArray, from: String, to: String): List<Int> {
        val lo = lowerBound(keys, from)
        val hi = lowerBound(keys, to)
        if (hi <= lo) return emptyList()
        val list = ArrayList<Int>(hi - lo)
        for (j in lo until hi) list.add(ids[j])
        list.sortWith(Comparator { a, b -> freqs[b].compareTo(freqs[a]) })
        return if (list.size > MAX) list.subList(0, MAX) else list
    }

    private companion object {
        const val MAX = 30
        const val PARTIAL_MAX = 8
    }
}

/** 笔画引擎:输入 h/s/p/n/z 序列,前缀匹配;精确匹配的排前面。 */
class StrokeEngine(
    private val texts: Array<String>,
    codes: Array<String>,
    private val freqs: IntArray,
) : Engine {

    private val keys: Array<String>
    private val ids: IntArray
    private val codeOf: Array<String> = codes

    init {
        val s = texts.indices.sortedBy { codes[it] }
        ids = s.toIntArray()
        keys = Array(s.size) { codes[s[it]] }
    }

    override fun query(code: String): List<Candidate> {
        if (code.isEmpty()) return emptyList()
        val lo = lowerBound(keys, code)
        val hi = lowerBound(keys, code + '\uffff')
        if (hi <= lo) return emptyList()
        val list = ArrayList<Int>(hi - lo)
        for (j in lo until hi) list.add(ids[j])
        list.sortWith(
            compareBy<Int>(
                { if (codeOf[it] == code) 0 else 1 },
                { -freqs[it] },
                { codeOf[it].length },
            )
        )
        val n = minOf(list.size, MAX)
        val out = ArrayList<Candidate>(n)
        val seen = HashSet<String>()
        for (id in list) {
            if (out.size >= n) break
            if (seen.add(texts[id])) out.add(Candidate(texts[id], code.length))
        }
        return out
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
                freqs.add(p.getOrNull(2)?.trim()?.toIntOrNull() ?: 1)
            }
        }
        PinyinEngine(
            texts.toTypedArray(), compact.toTypedArray(),
            initials.toTypedArray(), freqs.toIntArray(),
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
