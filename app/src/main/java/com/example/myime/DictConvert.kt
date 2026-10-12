package com.example.myime

import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.ln

/** 进度回调:(说明文字, 0..1)。 */
typealias Progress = (String, Float) -> Unit

class CancelledException : Exception("已取消")

/**
 * 把下载来的原始词库转成本 App 的格式。全部流式处理(逐行读、逐行写),
 * 不把整个词库放进内存;去重用 64 位哈希集合,先到先得(所以来源要按优先级排好)。
 *
 * 输出格式(UTF-8,Tab 分隔,# 开头为注释):
 *   pinyin.txt   词<Tab>pin yin<Tab>权重
 *   stroke.txt   字<Tab>hspnz 笔画串<Tab>权重
 *   english.txt  词<Tab>权重                      (权重 = ln(词频)×100,取整)
 *   english_bigram.txt  前词<Tab>后词<Tab>权重
 */
object DictConvert {

    // ------------------------------------------------------------ 小工具

    /** 开放寻址的 Long 集合,比 HashSet<Long> 省内存很多。0 当作"空槽",所以 0 会被换成 1。 */
    private class LongSet {
        private var table = LongArray(1 shl 16)
        private var size = 0

        fun add(v0: Long): Boolean {
            val v = if (v0 == 0L) 1L else v0
            if (size * 2 >= table.size) grow()
            val mask = table.size - 1
            var i = slot(v) and mask
            while (true) {
                val cur = table[i]
                if (cur == 0L) {
                    table[i] = v
                    size++
                    return true
                }
                if (cur == v) return false
                i = (i + 1) and mask
            }
        }

        private fun slot(v: Long): Int = ((v * -7046029254386353131L) ushr 32).toInt()

        private fun grow() {
            val old = table
            table = LongArray(old.size * 2)
            val mask = table.size - 1
            for (v in old) {
                if (v == 0L) continue
                var i = slot(v) and mask
                while (table[i] != 0L) i = (i + 1) and mask
                table[i] = v
            }
        }
    }

    private fun hash64(a: String, b: String): Long {
        var h = -3750763034362895579L
        for (i in 0 until a.length) h = (h xor a[i].code.toLong()) * 1099511628211L
        h = (h xor 9L) * 1099511628211L
        for (i in 0 until b.length) h = (h xor b[i].code.toLong()) * 1099511628211L
        return h
    }

    private fun clampInt(v: Long): Int = if (v > Int.MAX_VALUE) Int.MAX_VALUE else if (v < 1) 1 else v.toInt()

    private fun bucket(w: Long): Int = (ln(w.toDouble() + 1.0) * 2.0).toInt().coerceIn(0, 63)

    private fun scale(count: Long): Int = (ln(count.toDouble() + 1.0) * 100.0).toInt().coerceAtLeast(1)

    private fun isPinyin(code: String): Boolean {
        if (code.isEmpty()) return false
        var prevSpace = true // 开头不能是空格
        for (ch in code) {
            if (ch == ' ') {
                if (prevSpace) return false
                prevSpace = true
            } else if (ch in 'a'..'z') {
                prevSpace = false
            } else {
                return false
            }
        }
        return !prevSpace
    }

    private fun isStroke(code: String): Boolean {
        if (code.isEmpty()) return false
        for (ch in code) {
            if (ch != 'h' && ch != 's' && ch != 'p' && ch != 'n' && ch != 'z') return false
        }
        return true
    }

    private fun isSingleChar(text: String): Boolean = text.codePointCount(0, text.length) <= 1

    private fun parseWeight(s: String): Long {
        if (s.isEmpty()) return 1L
        val l = s.toLongOrNull()
        if (l != null) return if (l < 1) 1L else l
        if (s.endsWith("%")) {
            val d = s.substring(0, s.length - 1).toDoubleOrNull()
            if (d != null) return (d * 1000.0).toLong().coerceAtLeast(1L)
        }
        val d = s.toDoubleOrNull()
        return if (d != null) d.toLong().coerceAtLeast(1L) else 1L
    }

    /** 先写到 .tmp,写完再换名,避免中途失败留下半个词库。 */
    private fun tmpOf(out: File): File = File(out.parentFile, out.name + ".tmp")

    private fun replaceFile(tmp: File, out: File) {
        if (out.exists()) out.delete()
        if (!tmp.renameTo(out)) {
            tmp.copyTo(out, overwrite = true)
            tmp.delete()
        }
    }

    /**
     * 逐行读 Rime 的 *.dict.yaml 正文:词<Tab>编码<Tab>权重(权重可省略)。
     * 头部到单独一行 "..." 为止;没有头部就当整个文件都是正文。
     */
    private fun forEachRime(file: File, cancel: AtomicBoolean, block: (String, String, Long) -> Unit) {
        file.bufferedReader(Charsets.UTF_8).useLines { lines ->
            var started = false
            var inHeader = false
            var n = 0
            for (line in lines) {
                if ((++n and 0x3FFF) == 0 && cancel.get()) throw CancelledException()
                if (!started) {
                    val t = line.trim().trimStart('\uFEFF')
                    if (inHeader) {
                        if (t == "...") started = true
                        continue
                    }
                    if (t == "---") {
                        inHeader = true
                        continue
                    }
                    if (t.isEmpty() || t.startsWith("#")) continue
                    started = true // 没有头部,这一行就是正文
                }
                if (line.isEmpty() || line[0] == '#') continue
                val t1 = line.indexOf('\t')
                if (t1 <= 0) continue
                val t2 = line.indexOf('\t', t1 + 1)
                val text = line.substring(0, t1).trim()
                if (text.isEmpty()) continue
                val code = (if (t2 < 0) line.substring(t1 + 1) else line.substring(t1 + 1, t2)).trim().lowercase()
                var w = 1L
                if (t2 >= 0) {
                    val t3 = line.indexOf('\t', t2 + 1)
                    w = parseWeight((if (t3 < 0) line.substring(t2 + 1) else line.substring(t2 + 1, t3)).trim())
                }
                block(text, code, w)
            }
        }
    }

    // ------------------------------------------------------------ 拼音

    /**
     * main:主词库(按优先级排好),词条超出 cap 时按权重从低到高砍掉(单字永远保留)。
     * supplement:补充词库(繁体、生僻字),全部保留,权重压到最低,排在主词库后面。
     * 做两遍:第一遍按权重分桶数一数,算出能留下 cap 条的权重门槛;第二遍写出。
     */
    fun buildPinyin(
        main: List<File>,
        supplement: List<File>,
        out: File,
        cap: Int,
        cancel: AtomicBoolean,
        progress: Progress,
    ): Int {
        val hist = LongArray(64)
        var kept = 0L // 一定会保留的条数(单字 + 补充词库)
        val total = main.size + supplement.size
        var step = 0

        val seen1 = LongSet()
        for (f in main) {
            progress("统计 ${f.name}", 0.5f * step / total)
            forEachRime(f, cancel) { text, code, w ->
                if (!isPinyin(code)) return@forEachRime
                if (!seen1.add(hash64(text, code))) return@forEachRime
                if (isSingleChar(text)) kept++ else hist[bucket(w)]++
            }
            step++
        }
        for (f in supplement) {
            progress("统计 ${f.name}", 0.5f * step / total)
            forEachRime(f, cancel) { text, code, _ ->
                if (!isPinyin(code)) return@forEachRime
                if (seen1.add(hash64(text, code))) kept++
            }
            step++
        }

        // 能留下多少条多字词
        val budget = (cap - kept).coerceAtLeast(0L)
        var acc = 0L
        var threshold = 64
        while (threshold > 0 && acc + hist[threshold - 1] <= budget) {
            acc += hist[threshold - 1]
            threshold--
        }

        val tmp = tmpOf(out)
        var count = 0
        tmp.bufferedWriter(Charsets.UTF_8).use { wr ->
            wr.write("# 由词库管理生成: 词<Tab>拼音<Tab>权重\n")
            val seen2 = LongSet()
            step = 0
            for (f in main) {
                progress("写入 ${f.name}", 0.5f + 0.5f * step / total)
                forEachRime(f, cancel) { text, code, w ->
                    if (!isPinyin(code)) return@forEachRime
                    if (!seen2.add(hash64(text, code))) return@forEachRime
                    if (!isSingleChar(text) && bucket(w) < threshold) return@forEachRime
                    wr.write(text)
                    wr.write("\t")
                    wr.write(code)
                    wr.write("\t")
                    wr.write(clampInt(w).toString())
                    wr.write("\n")
                    count++
                }
                step++
            }
            for (f in supplement) {
                progress("写入 ${f.name}", 0.5f + 0.5f * step / total)
                forEachRime(f, cancel) { text, code, _ ->
                    if (!isPinyin(code)) return@forEachRime
                    if (!seen2.add(hash64(text, code))) return@forEachRime
                    wr.write(text)
                    wr.write("\t")
                    wr.write(code)
                    wr.write("\t1\n")
                    count++
                }
                step++
            }
        }
        if (count == 0) {
            tmp.delete()
            throw IllegalStateException("没有读到任何拼音词条,请确认下载的文件格式")
        }
        replaceFile(tmp, out)
        return count
    }

    // ------------------------------------------------------------ 笔画

    /** freqFile(可空):拿它的单字权重当字频,让常用字排前面;没有字频的字(繁体、生僻字)权重为 1。 */
    fun buildStroke(
        strokeFile: File,
        freqFile: File?,
        out: File,
        cancel: AtomicBoolean,
        progress: Progress,
    ): Int {
        val freq = HashMap<String, Long>()
        if (freqFile != null && freqFile.isFile) {
            progress("读取字频 ${freqFile.name}", 0f)
            forEachRime(freqFile, cancel) { text, code, w ->
                if (isSingleChar(text) && isPinyin(code)) {
                    val old = freq[text]
                    if (old == null || w > old) freq[text] = w
                }
            }
        }

        progress("转换 ${strokeFile.name}", 0.4f)
        val tmp = tmpOf(out)
        var count = 0
        val seen = LongSet()
        tmp.bufferedWriter(Charsets.UTF_8).use { wr ->
            wr.write("# 由词库管理生成: 字<Tab>笔画串(h横 s竖 p撇 n点/捺 z折)<Tab>字频\n")
            forEachRime(strokeFile, cancel) { text, code, w ->
                if (!isStroke(code)) return@forEachRime
                if (!seen.add(hash64(text, code))) return@forEachRime
                val f = freq[text]
                val weight = if (f != null) f else w
                wr.write(text)
                wr.write("\t")
                wr.write(code)
                wr.write("\t")
                wr.write(clampInt(weight).toString())
                wr.write("\n")
                count++
            }
        }
        if (count == 0) {
            tmp.delete()
            throw IllegalStateException("没有读到任何笔画条目,请确认下载的文件格式")
        }
        replaceFile(tmp, out)
        return count
    }

    // ------------------------------------------------------------ 英文

    /** 词典里没有撇号写法的常见缩写,补进去(Norvig 词表按空白切词,没有这些)。权重是已换算过的数。 */
    private val CONTRACTIONS = listOf(
        "I'm" to 2300, "it's" to 2300, "don't" to 2300, "that's" to 2250, "can't" to 2250,
        "I'll" to 2200, "you're" to 2200, "didn't" to 2200, "I've" to 2200, "what's" to 2150,
        "let's" to 2150, "there's" to 2100, "doesn't" to 2100, "isn't" to 2100, "won't" to 2100,
        "we're" to 2100, "they're" to 2100, "wasn't" to 2050, "he's" to 2050, "she's" to 2050,
        "I'd" to 2000, "you'll" to 2000, "we'll" to 2000, "they'll" to 2000, "couldn't" to 2000,
        "wouldn't" to 2000, "shouldn't" to 1950, "haven't" to 1950, "hasn't" to 1900,
        "hadn't" to 1900, "aren't" to 1950, "weren't" to 1900, "you've" to 1950, "we've" to 1950,
        "they've" to 1900, "who's" to 1900, "here's" to 1850, "how's" to 1800, "where's" to 1800,
        "he'll" to 1850, "she'll" to 1850, "it'll" to 1850, "y'all" to 1700, "ain't" to 1700,
    )

    /** 词和次数的分隔位置:优先制表符;自定义来源常见的「词 空格 次数」也认(取最后一个空格)。 */
    private fun sepOf(line: String): Int {
        val t = line.indexOf('\t')
        return if (t > 0) t else line.lastIndexOf(' ')
    }

    private fun isEnglishWord(w: String): Boolean {
        if (w.isEmpty() || w.length > 24) return false
        for (ch in w) if (ch !in 'a'..'z') return false
        return true
    }

    /**
     * count1:Norvig count_1w.txt(词<Tab>次数,按次数从高到低),取前 cap 个纯字母词。
     * count2(可空):Norvig count_2w.txt(词1 词2<Tab>次数),只留两个词都在词表里的,每个前词留最常见的 6 个后词。
     * 词表是小写的;"i" 写成 "I",再补上常见缩写。
     */
    fun buildEnglish(
        count1: File,
        count2: File?,
        outWords: File,
        outBigram: File,
        cap: Int,
        cancel: AtomicBoolean,
        progress: Progress,
    ): Int {
        val vocab = HashSet<String>()
        val tmpW = tmpOf(outWords)
        progress("转换 ${count1.name}", 0f)
        tmpW.bufferedWriter(Charsets.UTF_8).use { wr ->
            wr.write("# 由词库管理生成: 词<Tab>权重\n")
            count1.bufferedReader(Charsets.UTF_8).useLines { lines ->
                var n = 0
                for (line in lines) {
                    if (vocab.size >= cap) break
                    if ((++n and 0x3FFF) == 0 && cancel.get()) throw CancelledException()
                    val tab = sepOf(line)
                    if (tab <= 0) continue
                    val word = line.substring(0, tab)
                    if (!isEnglishWord(word)) continue
                    if (word.length == 1 && word != "a" && word != "i") continue
                    val c = line.substring(tab + 1).trim().toLongOrNull() ?: continue
                    if (!vocab.add(word)) continue
                    wr.write(if (word == "i") "I" else word)
                    wr.write("\t")
                    wr.write(scale(c).toString())
                    wr.write("\n")
                }
            }
            for ((w, f) in CONTRACTIONS) {
                if (vocab.add(w.lowercase())) {
                    wr.write(w)
                    wr.write("\t")
                    wr.write(f.toString())
                    wr.write("\n")
                }
            }
        }
        if (vocab.size < 100) {
            tmpW.delete()
            throw IllegalStateException("没有读到足够的英文词,请确认下载的文件格式")
        }

        if (count2 != null && count2.isFile) {
            progress("转换 ${count2.name}", 0.6f)
            val map = HashMap<String, ArrayList<Pair<String, Long>>>()
            count2.bufferedReader(Charsets.UTF_8).useLines { lines ->
                var n = 0
                for (line in lines) {
                    if ((++n and 0x3FFF) == 0 && cancel.get()) throw CancelledException()
                    val tab = sepOf(line)
                    if (tab <= 0) continue
                    val sp = line.indexOf(' ')
                    if (sp <= 0 || sp >= tab - 1) continue
                    val a = line.substring(0, sp)
                    val b = line.substring(sp + 1, tab)
                    if (!vocab.contains(a) || !vocab.contains(b)) continue
                    val c = line.substring(tab + 1).trim().toLongOrNull() ?: continue
                    val list = map.getOrPut(a) { ArrayList() }
                    list.add(Pair(b, c))
                    if (list.size > 16) {
                        list.sortByDescending { it.second }
                        while (list.size > 6) list.removeAt(list.size - 1)
                    }
                }
            }
            val tmpB = tmpOf(outBigram)
            tmpB.bufferedWriter(Charsets.UTF_8).use { wr ->
                wr.write("# 由词库管理生成: 前词<Tab>后词<Tab>权重\n")
                for ((a, list) in map) {
                    list.sortByDescending { it.second }
                    for (i in 0 until minOf(6, list.size)) {
                        wr.write(a)
                        wr.write("\t")
                        wr.write(list[i].first)
                        wr.write("\t")
                        wr.write(scale(list[i].second).toString())
                        wr.write("\n")
                    }
                }
            }
            replaceFile(tmpB, outBigram)
        }
        replaceFile(tmpW, outWords)
        return vocab.size
    }
}
