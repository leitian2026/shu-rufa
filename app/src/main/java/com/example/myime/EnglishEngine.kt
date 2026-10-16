package com.example.myime

import java.util.PriorityQueue
import kotlin.math.abs

/**
 * 英文引擎。
 *  - 补全:输入的字母当前缀,在词表里二分查找;按 词频 + 上一个词的联想加分 + 我的用词加分 排序。
 *  - 纠错:输入不是词、又补不出几个词时,试"少打/多打/打错/打反一个字母"(相邻键打错加分),
 *    都不行再试差两个字母(只对 5~10 个字母的词)。
 *  - 联想:[nextWords] 给出上一个词之后最可能的词(词库的双词表 + 我自己的习惯)。
 * 候选第一个永远是"原样输入"(单独的 i 会变成 I),空格默认按它上屏。
 * kind:0 = 原样输入或补全,1 = 纠错,2 = 联想。
 */
class EnglishEngine(
    internal val words: Array<String>,                // 词的写法,如 I、don't
    internal val freqs: IntArray,
    private val succ: HashMap<String, Array<String>>, // 前词(小写) -> 最常见的后词(小写,按常见程度排好)
    private val user: UserEnglish?,
    order: IntArray? = null,                          // 已经排好的顺序(来自二进制缓存)
) : Engine {

    private val lowerById: Array<String> = Array(words.size) { words[it].lowercase() }
    private val keys: Array<String>
    internal val ids: IntArray
    private val exact = HashMap<String, Int>(words.size * 2)

    /** 上一个已经上屏的词(小写),给补全加联想分。 */
    @Volatile
    var prev: String? = null

    /**
     * 我选过的词(点过哪个候选):和拼音 / 笔画同一套规则(UserFreq),选过的排在没选过的前面,最近选的最靠前。
     * 服务在词库加载好后设置。没有就不排。
     */
    @Volatile
    var pick: UserFreq? = null

    init {
        val ord = order ?: words.indices.sortedBy { lowerById[it] }.toIntArray()
        ids = ord
        keys = Array(ord.size) { lowerById[ord[it]] }
        for (i in words.indices) exact[lowerById[i]] = i
    }

    fun isKnown(lower: String): Boolean =
        exact.containsKey(lower) || (user?.hasWord(lower) == true)

    private fun displayOf(lower: String): String {
        val id = exact[lower]
        if (id != null) return words[id]
        return user?.displayOf(lower) ?: lower
    }

    private fun userBoost(lower: String, context: String?): Int {
        val u = user ?: return 0
        var b = minOf(u.wordScore(lower), 10) * 120
        if (context != null) b += minOf(u.bigramScore(context, lower), 10) * 150
        return b
    }

    override fun query(code: String): List<Candidate> {
        if (code.isEmpty()) return emptyList()
        val typed = code
        val lower = typed.lowercase()
        val n = typed.length
        val context = prev

        val rank = HashMap<String, Int>()
        if (context != null) {
            val s = succ[context]
            if (s != null) for (i in s.indices) rank[s[i]] = i
        }

        // 1. 词表前缀补全:先按 词频+联想 选出一批
        val lo = lowerBound(keys, lower)
        val hi = lowerBound(keys, lower + '\uffff')
        val heap = PriorityQueue<Long>()
        val useRank = rank.isNotEmpty()
        for (j in lo until hi) {
            var s = freqs[ids[j]]
            if (useRank) {
                val r = rank[keys[j]]
                if (r != null) s += 700 - 90 * r
            }
            heap.add((s.toLong() shl 32) or ids[j].toLong())
            if (heap.size > POOL) heap.poll()
        }
        val scored = ArrayList<Pair<String, Int>>()
        val inPool = HashSet<String>()
        for (v in heap) {
            val id = (v and 0xFFFFFFFFL).toInt()
            val s = (v shr 32).toInt()
            val lw = lowerById[id]
            inPool.add(lw)
            scored.add(Pair(lw, s + userBoost(lw, context)))
        }
        // 我学过的、以这个前缀开头的词(可能是词库里没有的新词,也可能没进上面那批)
        val u = user
        if (u != null) {
            for ((lw, _) in u.prefixWords(lower)) {
                if (inPool.contains(lw)) continue
                val id = exact[lw]
                val base = if (id != null) freqs[id] else USER_WORD_BASE
                scored.add(Pair(lw, base + userBoost(lw, context)))
            }
        }
        scored.sortByDescending { it.second }

        // 我点选过的词排在最前面(和拼音 / 笔画一样):这个前缀下点过的最靠前,别的前缀下点过的其次,都是最近点的在前;
        // 没点过的还是按上面的 词频 + 联想 + 用词 排。
        val pk = pick?.takeIf { it.hasLearned() }
        val order = ArrayList<String>(scored.size)
        if (pk != null) {
            val win = pk.recent(lower)
            val front = ArrayList<Pair<String, Long>>()
            val rest = ArrayList<String>()
            for (p in scored) {
                val r = UserFreq.rank(pk, lower, p.first, win, 0L)
                if (r != 0L) front.add(Pair(p.first, r)) else rest.add(p.first)
            }
            front.sortByDescending { it.second }
            for (f in front) order.add(f.first)
            order.addAll(rest)
        } else {
            for (p in scored) order.add(p.first)
        }

        val out = ArrayList<Candidate>()
        val seen = HashSet<String>()
        out.add(Candidate(literalOf(typed, lower), n, 0))
        seen.add(lower)
        var completions = 0
        for (lw in order) {
            if (completions >= MAX_COMPLETIONS) break
            if (!seen.add(lw)) continue
            out.add(Candidate(applyCase(typed, displayOf(lw)), n, 0))
            completions++
        }

        // 2. 纠错:不是词,而且补不出几个词的时候
        if (n >= 2 && !isKnown(lower) && completions < 3) {
            val fixes = corrections(lower, context)
            val fixOrder = if (pk != null) {
                val win = pk.recent(lower)
                fixes.sortedByDescending { UserFreq.rank(pk, lower, it.first, win, 0L) }   // 以前打成这样、点过的改正排前面(同分保持原顺序)
            } else {
                fixes
            }
            for (p in fixOrder) {
                if (!seen.add(p.first)) continue
                out.add(Candidate(applyCase(typed, displayOf(p.first)), n, 1))
            }
        }
        return out
    }

    /**
     * 上一个词之后最可能的词:词库的双词表(越靠前越常见)和我自己的习惯合在一起打分,
     * 我连续选得多了就排到前面,但偶然一次不会压过词库里最常见的搭配。
     */
    fun nextWords(prevLower: String): List<Candidate> {
        val score = HashMap<String, Int>()
        val s = succ[prevLower]
        if (s != null) for (i in s.indices) score[s[i]] = 700 - 60 * i
        val u = user
        if (u != null) {
            for ((w, c) in u.nextWords(prevLower)) score[w] = (score[w] ?: 0) + minOf(c, 12) * 250
        }
        if (score.isEmpty()) return emptyList()
        val out = ArrayList<Candidate>()
        for (e in score.entries.sortedByDescending { it.value }) {
            out.add(Candidate(displayOf(e.key), 0, 2))
            if (out.size >= 8) break
        }
        return out
    }

    /** 全小写输入的 i / i'm / i'll 等,按词表里的写法(大写 I)。 */
    private fun literalOf(typed: String, lower: String): String {
        if (typed == lower && lower[0] == 'i') {
            val id = exact[lower]
            if (id != null) {
                val d = words[id]
                if (d.length == lower.length && d[0] == 'I') return d
            }
        }
        return typed
    }

    /** 跟着用户输入的大小写走:首字母大写 → 候选首字母大写;全大写 → 候选全大写。 */
    private fun applyCase(typed: String, word: String): String {
        if (typed.isEmpty() || word.isEmpty()) return word
        var allUpper = typed.length >= 2
        for (ch in typed) {
            if (ch != '\'' && ch !in 'A'..'Z') {
                allUpper = false
                break
            }
        }
        if (allUpper) return word.uppercase()
        if (typed[0] in 'A'..'Z') {
            for (ch in word) if (ch in 'A'..'Z') return word // 词表里本来就有大写(I、iPhone 之类)
            return word.substring(0, 1).uppercase() + word.substring(1)
        }
        return word
    }

    // ------------------------------------------------------------ 纠错

    private fun corrections(w: String, context: String?): List<Pair<String, Int>> {
        val best = HashMap<String, Int>()
        val first = w[0]

        fun consider(cand: String, penalty: Int, bonus: Int) {
            if (cand.isEmpty() || cand == w) return
            val id = exact[cand] ?: return
            var s = freqs[id] - penalty + bonus
            if (cand[0] != first) s -= 250 // 第一个字母很少打错
            val old = best[cand]
            if (old == null || s > old) best[cand] = s
        }

        forEachEdit(w) { cand, penalty, bonus -> consider(cand, penalty, bonus) }

        // 差两个字母:不生成字符串,直接在"同一首字母、长度相差不超过 2"的词里算编辑距离(不产生临时对象)
        if (best.isEmpty() && w.length in 4..12) {
            val lo = lowerBound(keys, first.toString())
            val hi = lowerBound(keys, (first.code + 1).toChar().toString())
            val rows = Array(3) { IntArray(w.length + 3) }
            for (j in lo until hi) {
                val k = keys[j]
                if (abs(k.length - w.length) > 2) continue
                if (withinTwo(w, k, rows)) consider(k, 700, 0)
            }
        }
        return best.entries
            .sortedByDescending { it.value + userBoost(it.key, context) }
            .take(3)
            .map { Pair(it.key, it.value) }
    }

    /** a 和 b 的编辑距离(含相邻互换)是否 <= 2。rows 是 3 行复用的数组,每行长度至少是 b.length + 1。 */
    private fun withinTwo(a: String, b: String, rows: Array<IntArray>): Boolean {
        var p2 = rows[0]
        var p1 = rows[1]
        var cur = rows[2]
        val m = b.length
        for (j in 0..m) p1[j] = j
        for (i in 1..a.length) {
            cur[0] = i
            var rowMin = i
            for (j in 1..m) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                var v = minOf(p1[j] + 1, cur[j - 1] + 1, p1[j - 1] + cost)
                if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) {
                    v = minOf(v, p2[j - 2] + 1)
                }
                cur[j] = v
                if (v < rowMin) rowMin = v
            }
            if (rowMin > 2) return false
            val t = p2
            p2 = p1
            p1 = cur
            cur = t
        }
        return p1[m] <= 2
    }

    /** 和 w 差一个字母的所有写法。f(新写法, 扣分, 加分)。 */
    private inline fun forEachEdit(w: String, f: (String, Int, Int) -> Unit) {
        val len = w.length
        // 多打了一个字母
        for (i in 0 until len) f(w.substring(0, i) + w.substring(i + 1), 30, 0)
        // 相邻两个字母打反了
        for (i in 0 until len - 1) {
            if (w[i] == w[i + 1]) continue
            f(w.substring(0, i) + w[i + 1] + w[i] + w.substring(i + 2), 0, 250)
        }
        // 打错了一个字母(相邻键加分)
        for (i in 0 until len) {
            val orig = w[i]
            for (c in ALPHA) {
                if (c == orig) continue
                f(w.substring(0, i) + c + w.substring(i + 1), 100, if (adjacent(orig, c)) 200 else 0)
            }
        }
        // 少打了一个字母
        for (i in 0..len) {
            for (c in ALPHA) f(w.substring(0, i) + c + w.substring(i), 60, 0)
        }
    }

    private companion object {
        const val POOL = 40
        const val MAX_COMPLETIONS = 12
        const val USER_WORD_BASE = 1200
        val ALPHA = "abcdefghijklmnopqrstuvwxyz'".toCharArray()

        // 26 键的位置,用来判断两个字母是不是相邻键
        val KEY_X = FloatArray(26)
        val KEY_Y = FloatArray(26)

        init {
            val rows = arrayOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
            val off = floatArrayOf(0f, 0.5f, 1.5f)
            for (r in rows.indices) {
                for (i in rows[r].indices) {
                    val k = rows[r][i] - 'a'
                    KEY_X[k] = off[r] + i
                    KEY_Y[k] = r.toFloat()
                }
            }
        }

        fun adjacent(a: Char, b: Char): Boolean {
            if (a !in 'a'..'z' || b !in 'a'..'z' || a == b) return false
            val dx = abs(KEY_X[a - 'a'] - KEY_X[b - 'a'])
            val dy = abs(KEY_Y[a - 'a'] - KEY_Y[b - 'a'])
            return dx <= 1.01f && dy <= 1f
        }
    }
}
