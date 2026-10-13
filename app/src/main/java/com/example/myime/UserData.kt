package com.example.myime

import android.content.Context
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 用户词频学习的数据,只存在应用私有目录(filesDir/user/),不联网、不上传。
 * 输入法服务和词库管理页在同一个进程里,共用这里的单例。
 * 所有方法都加了同步:候选查询在后台线程,学习在主线程。
 */

/** 拼音 / 笔画用:记"选过哪个词"。同一个词按过的次数越多,排得越靠前。 */
class UserFreq(private val file: File) {
    // 候选排序在后台线程里对每个匹配项都要查分数,用 ConcurrentHashMap 让读不加锁;写(学习、保存)仍然串行
    private val byText = ConcurrentHashMap<String, Int>()
    private val byPair = ConcurrentHashMap<String, Int>() // "输入的编码\t词" -> 次数
    private var dirty = false
    private var events = 0
    @Volatile private var loaded = false

    @Synchronized
    private fun ensureLoaded() {
        if (loaded) return
        try {
            val r = SafeFile.read(file)
            if (r != null) {
                for (line in r.lines) {
                    val p = line.split('\t')
                    if (p.size == 3 && p[0] == "t") {
                        val c = p[2].toIntOrNull() ?: continue
                        byText[p[1]] = c
                    } else if (p.size == 4 && p[0] == "p") {
                        val c = p[3].toIntOrNull() ?: continue
                        byPair[p[1] + "\t" + p[2]] = c
                    }
                }
                if (r.fromBackup) dirty = true   // 主文件坏了、这是从备份恢复的:下次保存时把主文件重写好
            } else if (file.isFile) {
                ErrorHint.set("learn", "用词习惯文件已损坏且没有可用备份,原文件另存为 ${file.name}.corrupt")
            }
        } catch (e: Exception) {
            ErrorHint.set("learn", ErrorHint.of("用词习惯读取失败", e))
        } finally {
            loaded = true   // 读完才标记,别的线程在这之前会等在 ensureLoaded 的锁上
        }
    }

    /** 有没有学过任何词。没有的话排序时可以完全不查分数。 */
    fun hasLearned(): Boolean {
        if (!loaded) ensureLoaded()
        return !byText.isEmpty()
    }

    /** 排序用的加分;0 表示没学过。不加锁。 */
    fun score(text: String, code: String): Int {
        if (!loaded) ensureLoaded()
        val t = byText[text] ?: return 0
        val p = byPair["$code\t$text"] ?: 0
        return minOf(t, 20) + minOf(p, 20) * 3
    }

    @Synchronized
    fun learn(code: String, text: String) {
        if (text.isEmpty() || code.isEmpty()) return
        ensureLoaded()
        byText[text] = (byText[text] ?: 0) + 1
        val k = "$code\t$text"
        byPair[k] = (byPair[k] ?: 0) + 1
        dirty = true
        if (++events >= DECAY_EVERY) age()
        if (byText.size > MAX_ENTRIES) prune()
    }

    /** 每学 DECAY_EVERY 次,所有次数乘 3/4:近期的选择比很久以前的更重要,只选过一次的偏好会慢慢淡掉。 */
    private fun age() {
        events = 0
        byText.replaceAll { _, v -> v * 3 / 4 }
        byPair.replaceAll { _, v -> v * 3 / 4 }
        byText.values.removeIf { it <= 0 }
        byPair.values.removeIf { it <= 0 }
    }

    private fun prune() {
        val keep = byText.entries.sortedByDescending { it.value }
            .take(MAX_ENTRIES * 3 / 4).map { it.key }.toHashSet()
        byText.keys.retainAll(keep)
        byPair.keys.removeAll { k -> !keep.contains(k.substring(k.indexOf('\t') + 1)) }
    }

    @Synchronized
    fun count(): Int {
        ensureLoaded()
        return byText.size
    }

    @Synchronized
    fun saveIfDirty() {
        if (!dirty) return
        try {
            SafeFile.write(file) { w ->
                for ((k, v) in byText) w.write("t\t$k\t$v\n")
                for ((k, v) in byPair) w.write("p\t$k\t$v\n")
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
        byText.clear()
        byPair.clear()
        dirty = false
        SafeFile.deleteAll(file)
    }

    private companion object {
        const val MAX_ENTRIES = 20_000
        const val DECAY_EVERY = 300
    }
}

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

/**
 * 中文联想用:记"上一个上屏的词 → 下一个上屏的词"出现过几次(比如我打过"吃"后面接"苹果")。
 * 只存在本机应用私有目录。读不加锁,写串行。
 */
class UserChain(private val file: File) {
    private val map = ConcurrentHashMap<String, ConcurrentHashMap<String, Int>>()
    private var dirty = false
    private var events = 0
    private var sincePrune = 0
    @Volatile private var loaded = false

    @Synchronized
    private fun ensureLoaded() {
        if (loaded) return
        try {
            val r = SafeFile.read(file)
            if (r != null) {
                for (line in r.lines) {
                    val p = line.split('\t')
                    if (p.size == 4 && p[0] == "c") {
                        val c = p[3].toIntOrNull() ?: continue
                        map.getOrPut(p[1]) { ConcurrentHashMap() }[p[2]] = c
                    }
                }
                if (r.fromBackup) dirty = true
            } else if (file.isFile) {
                ErrorHint.set("learn", "联想数据文件已损坏且没有可用备份,原文件另存为 ${file.name}.corrupt")
            }
        } catch (e: Exception) {
            ErrorHint.set("learn", ErrorHint.of("用词习惯读取失败", e))
        } finally {
            loaded = true
        }
    }

    /**
     * 上下文行:上上个词+上一个词(两词上下文,权重 3 倍)和上一个词(一词上下文)后面我接过的词,合并成 词 -> 分。
     * 没有记录返回 null。两词上下文数据少但更准,一词上下文覆盖广,两个一起用。
     */
    fun ctxRow(prev2: String?, prev: String?): Map<String, Int>? {
        if (prev == null) return null
        if (!loaded) ensureLoaded()
        val one = map[prev]
        val two = if (prev2 != null) map[prev2 + SEP + prev] else null
        if (one == null && two == null) return null
        val out = HashMap<String, Int>()
        if (one != null) for ((w, c) in one) out[w] = c
        if (two != null) for ((w, c) in two) out[w] = (out[w] ?: 0) + c * 3
        return out
    }

    /** 联想:接在这个(或这两个)词后面,我最常打的词,最多 8 个。 */
    fun next(prev2: String?, prev: String): List<String> {
        val row = ctxRow(prev2, prev) ?: return emptyList()
        return row.entries.sortedByDescending { it.value }.take(8).map { it.key }
    }

    @Synchronized
    fun learn(prev: String, next: String) {
        if (prev.isEmpty() || next.isEmpty()) return
        if (prev.indexOf('\t') >= 0 || prev.indexOf('\n') >= 0) return
        if (next.indexOf('\t') >= 0 || next.indexOf('\n') >= 0) return
        ensureLoaded()
        val m = map.getOrPut(prev) { ConcurrentHashMap() }
        m[next] = (m[next] ?: 0) + 1
        dirty = true
        if (++events >= DECAY_EVERY) age()
        if (++sincePrune >= 500) {
            sincePrune = 0
            if (count() > MAX_PAIRS) prune()
        }
    }

    /** 每学 DECAY_EVERY 次,所有次数乘 3/4,近期习惯权重更高。 */
    private fun age() {
        events = 0
        val rows = map.entries.iterator()
        while (rows.hasNext()) {
            val row = rows.next()
            row.value.replaceAll { _, v -> v * 3 / 4 }
            row.value.values.removeIf { it <= 0 }
            if (row.value.isEmpty()) rows.remove()
        }
    }

    private fun prune() {
        val all = ArrayList<Triple<String, String, Int>>()
        for ((a, m) in map) for ((b, c) in m) all.add(Triple(a, b, c))
        all.sortByDescending { it.third }
        map.clear()
        for (i in 0 until minOf(all.size, MAX_PAIRS * 3 / 4)) {
            val t = all[i]
            map.getOrPut(t.first) { ConcurrentHashMap() }[t.second] = t.third
        }
    }

    @Synchronized
    fun count(): Int {
        ensureLoaded()
        var n = 0
        for (m in map.values) n += m.size
        return n
    }

    @Synchronized
    fun saveIfDirty() {
        if (!dirty) return
        try {
            SafeFile.write(file) { w ->
                for ((a, m) in map) for ((b, c) in m) w.write("c\t$a\t$b\t$c\n")
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
        map.clear()
        dirty = false
        SafeFile.deleteAll(file)
    }

    companion object {
        const val SEP = '\u0001'   // 两词上下文的分隔符(不会出现在词里,也不是制表/换行)
        private const val MAX_PAIRS = 20_000
        private const val DECAY_EVERY = 300
    }
}

object UserData {
    private var pinyinFreq: UserFreq? = null
    private var strokeFreq: UserFreq? = null
    private var englishData: UserEnglish? = null
    private var chainData: UserChain? = null
    private var pinsData: EnglishPins? = null

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
    fun pins(ctx: Context): EnglishPins =
        pinsData ?: EnglishPins(File(dir(ctx), "english_pins.txt")).also { pinsData = it }

    @Synchronized
    fun saveAll() {
        pinsData?.saveIfDirty()
        pinyinFreq?.saveIfDirty()
        strokeFreq?.saveIfDirty()
        englishData?.saveIfDirty()
        chainData?.saveIfDirty()
    }

    fun clearAll(ctx: Context) {
        pinyin(ctx).clear()
        stroke(ctx).clear()
        english(ctx).clear()
        chain(ctx).clear()
    }
}
