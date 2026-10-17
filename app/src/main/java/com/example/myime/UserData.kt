package com.example.myime

import android.content.Context
import java.io.File

/**
 * 用户词频学习的数据,只存在应用私有目录(filesDir/user/),不联网、不上传。
 * 输入法服务和词库管理页在同一个进程里,共用这里的单例。
 * 所有方法都加了同步:候选查询在后台线程,学习在主线程。
 */

// 拼音 / 笔画的 UserFreq、中文联想的 UserChain 在各自的 Java 文件里(纯 JDK,电脑上能单独测试)。

/** 英文用:记打过的词、选过的词、词和词的先后关系(用于联想)。词库里没有的新词出现 2 次才收。 */
class UserEnglish(private val file: File) {
    private val words = java.util.TreeMap<String, Int>()  // 小写词 -> 次数(有序,按前缀查找不用扫全表)
    private val display = HashMap<String, String>()    // 小写词 -> 写法(只记词库里没有的词)
    private val pending = HashMap<String, Int>()       // 词库里没有的词,还没到 2 次
    private val bigrams = HashMap<String, HashMap<String, Int>>() // 前词 -> (后词 -> 次数)
    private var dirty = false
    private var events = 0
    private var loaded = false

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        try {
            val r = SafeFile.read(file)
            if (r != null) {
                for (line in r.lines) {
                    val p = line.split('\t')
                    when {
                        p.size == 3 && p[0] == "w" -> words[p[1]] = p[2].toIntOrNull() ?: continue
                        p.size == 3 && p[0] == "d" -> display[p[1]] = p[2]
                        p.size == 4 && p[0] == "b" -> {
                            val c = p[3].toIntOrNull() ?: continue
                            bigrams.getOrPut(p[1]) { HashMap() }[p[2]] = c
                        }
                    }
                }
                if (r.fromBackup) dirty = true
            } else if (file.isFile) {
                ErrorHint.set("learn", "英文用词文件已损坏且没有可用备份,原文件另存为 ${file.name}.corrupt")
            }
        } catch (e: Exception) {
            ErrorHint.set("learn", ErrorHint.of("用词习惯读取失败", e))
        }
    }

    @Synchronized
    fun wordScore(lower: String): Int {
        ensureLoaded()
        return words[lower] ?: 0
    }

    @Synchronized
    fun hasWord(lower: String): Boolean {
        ensureLoaded()
        return words.containsKey(lower)
    }

    @Synchronized
    fun bigramScore(prev: String, next: String): Int {
        ensureLoaded()
        return bigrams[prev]?.get(next) ?: 0
    }

    @Synchronized
    fun displayOf(lower: String): String? {
        ensureLoaded()
        return display[lower]
    }

    /** 以 prefix(小写)开头的、学过的词,带次数。 */
    @Synchronized
    fun prefixWords(prefix: String): List<Pair<String, Int>> {
        ensureLoaded()
        val out = ArrayList<Pair<String, Int>>()
        for ((w, c) in words.subMap(prefix, prefix + '\uffff')) out.add(Pair(w, c))
        return out
    }

    /** prev 之后我最常打的词。 */
    @Synchronized
    fun nextWords(prev: String): List<Pair<String, Int>> {
        ensureLoaded()
        val m = bigrams[prev] ?: return emptyList()
        return m.entries.sortedByDescending { it.value }.take(8).map { Pair(it.key, it.value) }
    }

    /** known:这个词在词库里。word 是用户实际打出来的写法。 */
    @Synchronized
    fun learn(prev: String?, word: String, known: Boolean) {
        if (word.isEmpty()) return
        ensureLoaded()
        val lower = word.lowercase()
        if (known || words.containsKey(lower)) {
            words[lower] = (words[lower] ?: 0) + 1
        } else {
            val c = (pending[lower] ?: 0) + 1
            if (c >= 2) {
                words[lower] = c
                pending.remove(lower)
            } else {
                if (pending.size > 2000) pending.clear()
                pending[lower] = c
            }
        }
        if (!known && words.containsKey(lower)) display[lower] = word
        if (prev != null && words.containsKey(lower)) {
            val m = bigrams.getOrPut(prev) { HashMap() }
            m[lower] = (m[lower] ?: 0) + 1
        }
        dirty = true
        if (++events >= DECAY_EVERY) age()
        if (words.size > MAX_WORDS) prune()
    }

    /** 每学 DECAY_EVERY 次,所有次数乘 3/4,近期习惯权重更高。 */
    private fun age() {
        events = 0
        val wi = words.entries.iterator()
        while (wi.hasNext()) {
            val e = wi.next()
            val v = e.value * 3 / 4
            if (v <= 0) wi.remove() else e.setValue(v)
        }
        display.keys.retainAll(words.keys)
        val rows = bigrams.entries.iterator()
        while (rows.hasNext()) {
            val row = rows.next()
            if (!words.containsKey(row.key)) { rows.remove(); continue }
            val cols = row.value.entries.iterator()
            while (cols.hasNext()) {
                val e = cols.next()
                val v = e.value * 3 / 4
                if (v <= 0 || !words.containsKey(e.key)) cols.remove() else e.setValue(v)
            }
            if (row.value.isEmpty()) rows.remove()
        }
    }

    private fun prune() {
        val keep = words.entries.sortedByDescending { it.value }
            .take(MAX_WORDS * 3 / 4).map { it.key }.toHashSet()
        words.keys.retainAll(keep)
        display.keys.retainAll(keep)
        bigrams.keys.retainAll(keep)
        for (m in bigrams.values) m.keys.retainAll(keep)
    }

    @Synchronized
    fun count(): Int {
        ensureLoaded()
        return words.size
    }

    @Synchronized
    fun saveIfDirty() {
        if (!dirty) return
        try {
            SafeFile.write(file) { w ->
                for ((k, v) in words) w.write("w\t$k\t$v\n")
                for ((k, v) in display) w.write("d\t$k\t$v\n")
                for ((a, m) in bigrams) for ((b, c) in m) w.write("b\t$a\t$b\t$c\n")
            }
            dirty = false
            ErrorHint.clear("learn")
        } catch (e: Exception) {
            ErrorHint.set("learn", ErrorHint.of("用词习惯保存失败", e))
        }
    }

    @Synchronized
    fun clear() {
        loaded = true
        words.clear()
        display.clear()
        pending.clear()
        bigrams.clear()
        dirty = false
        SafeFile.deleteAll(file)
    }

    private companion object {
        const val MAX_WORDS = 20_000
        const val DECAY_EVERY = 300
    }
}

object UserData {
    private var pinyinFreq: UserFreq? = null
    private var strokeFreq: UserFreq? = null
    private var englishData: UserEnglish? = null
    private var chainData: UserChain? = null
    private var wordsData: UserWords? = null
    private var pickData: UserFreq? = null
    private var charsData: UserChars? = null

    private fun dir(ctx: Context): File {
        val d = File(ctx.applicationContext.filesDir, "user")
        if (!d.isDirectory) d.mkdirs()
        return d
    }

    @Synchronized
    fun pinyin(ctx: Context): UserFreq =
        pinyinFreq ?: UserFreq(File(dir(ctx), "pinyin.txt")).also { pinyinFreq = it }

    @Synchronized
    fun stroke(ctx: Context): UserFreq =
        strokeFreq ?: UserFreq(File(dir(ctx), "stroke.txt")).also { strokeFreq = it }

    @Synchronized
    fun english(ctx: Context): UserEnglish =
        englishData ?: UserEnglish(File(dir(ctx), "english.txt")).also { englishData = it }

    @Synchronized
    fun chain(ctx: Context): UserChain =
        chainData ?: UserChain(File(dir(ctx), "chain.txt")).also { chainData = it }

    @Synchronized
    fun words(ctx: Context): UserWords =
        wordsData ?: UserWords(File(dir(ctx), "words.txt")).also { wordsData = it }

    /** 用过的不同汉字(只增不减,清除学习数据才清空)。多机同步会合并。 */
    @Synchronized
    fun chars(ctx: Context): UserChars =
        charsData ?: UserChars(File(dir(ctx), "chars.txt")) { TradConv.simplify(it) }.also { charsData = it }

    /**
     * 把拼音 / 笔画 / 联想 / 造词几张表里出现过的汉字补进字集(时间用各条记录自己的),返回字集里不同汉字的总数。
     * 字集里已有的不重复记。这样第一次启用时就能数出「之前用过多少个」,别的手机合并进表里的词也会被补进来。
     * 要读几张表(一两万条),几毫秒到几十毫秒;只在打开词库管理页和同步前调用,别放在打字的路径上。
     */
    fun absorbChars(ctx: Context): Int {
        val c = chars(ctx)
        val now = System.currentTimeMillis()
        pinyin(ctx).forEachText { t, ts -> c.absorb(t, ts, now) }
        stroke(ctx).forEachText { t, ts -> c.absorb(t, ts, now) }
        chain(ctx).forEachWord { t, ts -> c.absorb(t, ts, now) }
        words(ctx).forEachWord { t, ts -> c.absorb(t, ts, now) }
        return c.count()
    }

    /** 英文:点选过哪个候选(和拼音 / 笔画同一套「选过的排前面」规则)。只在本机,不参与多机同步。 */
    @Synchronized
    fun englishPick(ctx: Context): UserFreq =
        pickData ?: UserFreq(File(dir(ctx), "english_pick.txt")).also { pickData = it }

    @Synchronized
    fun saveAll() {
        pickData?.saveIfDirty()
        pinyinFreq?.saveIfDirty()
        strokeFreq?.saveIfDirty()
        englishData?.saveIfDirty()
        chainData?.saveIfDirty()
        wordsData?.saveIfDirty()
        charsData?.saveIfDirty()
    }

    fun clearAll(ctx: Context) {
        pinyin(ctx).clear()
        stroke(ctx).clear()
        english(ctx).clear()
        englishPick(ctx).clear()
        chain(ctx).clear()
        words(ctx).clear()
        chars(ctx).clear()
        LearnSync.noteCleared(ctx)   // 多机同步:清除只清本机,清除之前的记录也不会被别的手机再同步回来
    }
}
