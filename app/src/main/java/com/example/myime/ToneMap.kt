package com.example.myime

import android.content.Context

/**
 * 汉字 → 带声调的拼音(assets/tones.txt,每行「字<读音>,<读音>…」,第一个是最常用的读音)。
 * 词库里的拼音不带声调,这里负责补上:
 *  - 单字:用输入的音节 / 词库里的音节在这个字的几个读音里挑出对得上的那个(长 zhang → zhǎng,chang → cháng);
 *  - 词:每个字各挑一个,整词的音节来自输入或词库,挑不出就用这个字最常用的读音。
 * 表只在用到(开了「显示读音」,或长按候选)时才在后台线程读一次(约 170KB),不用就不占内存;
 * 关掉开关时释放。查表只做字符串比较,不分配大对象,不起定时器,不耗电。
 */
object ToneMap {
    private const val FIRST = 0x4E00
    private const val LAST = 0x9FFF

    private class Table(val blob: String, val start: IntArray, val end: IntArray)

    @Volatile
    private var table: Table? = null

    fun ensure(context: Context) {
        if (table != null) return
        val t = try {
            val text = context.assets.open("tones.txt").bufferedReader(Charsets.UTF_8).use { it.readText() }
            val n = LAST - FIRST + 1
            val st = IntArray(n) { -1 }
            val en = IntArray(n)
            var i = 0
            val len = text.length
            while (i < len) {
                var j = text.indexOf('\n', i)
                if (j < 0) j = len
                if (j > i + 1) {
                    val k = text[i].code - FIRST
                    if (k in 0 until n) {
                        st[k] = i + 1
                        en[k] = j
                    }
                }
                i = j + 1
            }
            Table(text, st, en)
        } catch (t: Throwable) {
            ErrorHint.set("tone", ErrorHint.of("声调表读取失败", t))   // 读音只是辅助,不影响打字
            null
        }
        table = t ?: Table("", IntArray(LAST - FIRST + 1) { -1 }, IntArray(LAST - FIRST + 1))
    }

    fun release() {
        table = null
    }

    val ready: Boolean get() = table != null

    /** 带声调的读音 → 词库写法(无声调,ü 写 v):nǚ → nv,lüè → lve,zhōng → zhong。 */
    fun plain(s: String): String {
        var sb: StringBuilder? = null
        for (i in s.indices) {
            val c = s[i]
            val r = when (c) {
                'ā', 'á', 'ǎ', 'à' -> 'a'
                'ē', 'é', 'ě', 'è', 'ê' -> 'e'
                'ī', 'í', 'ǐ', 'ì' -> 'i'
                'ō', 'ó', 'ǒ', 'ò' -> 'o'
                'ū', 'ú', 'ǔ', 'ù' -> 'u'
                'ǖ', 'ǘ', 'ǚ', 'ǜ', 'ü' -> 'v'
                'ń', 'ň', 'ǹ' -> 'n'
                'ḿ' -> 'm'
                else -> c
            }
            if (r != c && sb == null) {
                sb = StringBuilder(s.length)
                sb.append(s, 0, i)
            }
            sb?.append(r)
        }
        return sb?.toString() ?: s
    }

    private fun isHan(c: Char) = c.code in FIRST..LAST

    /** 这个字的第一个(最常用的)读音;表里没有返回 null。 */
    private fun firstOf(t: Table, c: Char): String? {
        if (!isHan(c)) return null
        val k = c.code - FIRST
        val a = t.start[k]
        if (a < 0) return null
        var b = t.blob.indexOf(',', a)
        if (b < 0 || b > t.end[k]) b = t.end[k]
        return t.blob.substring(a, b)
    }

    /** 这个字的读音里,去掉声调后等于 syl 的那个;没有返回 null。 */
    private fun matchOf(t: Table, c: Char, syl: String): String? {
        if (!isHan(c)) return null
        val k = c.code - FIRST
        val a = t.start[k]
        if (a < 0) return null
        val e = t.end[k]
        var from = a
        while (from < e) {
            var to = t.blob.indexOf(',', from)
            if (to < 0 || to > e) to = e
            val r = t.blob.substring(from, to)
            if (plain(r) == syl) return r
            from = to + 1
        }
        return null
    }

    /** 这个字所有读音(最多 max 个),表没读好或没有这个字返回空。长按看读音时,词库没有读音的字拿它兜底。 */
    fun allOf(c: Char, max: Int = 4): List<String> {
        val t = table ?: return emptyList()
        if (!isHan(c)) return emptyList()
        val k = c.code - FIRST
        val a = t.start[k]
        if (a < 0) return emptyList()
        return t.blob.substring(a, t.end[k]).split(',').take(max)
    }

    /**
     * 给 text 配上带声调的读音,音节之间用空格隔开(如「你好」→「nǐ hǎo」)。
     * sources:可能的音节来源(每个是和 text 逐字对应的无声调音节列表),按可信度从高到低:
     * 先是输入的拼音,再是词库里的读音。第一个能让每个字都对上的来源胜出;都对不上就用第一个来源里对得上的字,
     * 其余字用最常用的读音。表里没有的字(生僻字、符号)用来源里的无声调音节,没有来源就跳过。
     */
    fun annotate(text: String, sources: List<List<String>>): String {
        val t = table ?: return ""
        val n = text.length
        if (n == 0 || n > 16) return ""
        var usable: List<String>? = null
        for (src in sources) {
            if (src.size != n) continue
            if (usable == null) usable = src
            var all = true
            for (i in 0 until n) {
                if (matchOf(t, text[i], src[i]) == null) {
                    all = false
                    break
                }
            }
            if (all) return join(t, text, src, strict = true)
        }
        return join(t, text, usable, strict = false)
    }

    private fun join(t: Table, text: String, src: List<String>?, strict: Boolean): String {
        val sb = StringBuilder(text.length * 5)
        for (i in text.indices) {
            val c = text[i]
            val r = (if (src != null) matchOf(t, c, src[i]) else null)
                ?: if (strict) null else firstOf(t, c)
                ?: src?.get(i)
            if (r.isNullOrEmpty()) continue
            if (sb.isNotEmpty()) sb.append(' ')
            sb.append(r)
        }
        return sb.toString()
    }

    /**
     * 长按看读音:词库给的读音(无声调,多音字几个,每个是空格隔开的音节)逐个补上声调。
     * 词库里没有这个词时,单字用表里的读音兜底;补不出来的读音原样保留(无声调)。
     */
    fun tonedList(text: String, dictReadings: List<String>?): List<String> {
        val out = ArrayList<String>(4)
        if (dictReadings != null) {
            for (d in dictReadings) {
                val syl = d.split(' ')
                val s = annotate(text, listOf(syl)).ifEmpty { d }
                if (s !in out) out.add(s)
            }
        }
        if (out.isEmpty() && text.length == 1) out.addAll(allOf(text[0]))
        return out
    }
}
