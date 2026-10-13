package com.example.myime

import java.io.File

/**
 * 英文候选的"固定":我长按候选词选了"固定"的词,打到它的前缀时永远排在最前面(原样输入那一项之后),
 * 上屏一个词后的联想里也排最前。和学习数据分开存(清除学习数据不会清掉固定),也不受容量上限裁剪。
 *
 * 文件 user/english_pins.txt,每行:小写词<Tab>写法。数据很少(几十条以内),固定/取消时立刻落盘,
 * 键盘隐藏时也会补存一次。读取不加锁(快照),写串行。
 */
class EnglishPins(private val file: File) {
    private val map = LinkedHashMap<String, String>()   // 小写 -> 显示写法,按固定的先后顺序
    @Volatile private var snapshot: List<Pair<String, String>> = emptyList()
    @Volatile private var loaded = false
    private var dirty = false

    @Synchronized
    private fun ensureLoaded() {
        if (loaded) return
        try {
            if (file.isFile) {
                file.bufferedReader(Charsets.UTF_8).useLines { lines ->
                    for (line in lines) {
                        val t = line.indexOf('\t')
                        if (t <= 0) continue
                        val lower = line.substring(0, t)
                        val spelling = line.substring(t + 1).ifEmpty { lower }
                        map[lower] = spelling
                    }
                }
                publish()
            }
        } catch (e: Exception) {
            ErrorHint.set("pins", ErrorHint.of("固定词读取失败", e))
        } finally {
            loaded = true
        }
    }

    private fun publish() {
        snapshot = map.entries.map { Pair(it.key, it.value) }
    }

    /** 当前所有固定的词(小写, 写法),按固定的先后。没有就是空列表。 */
    fun list(): List<Pair<String, String>> {
        if (!loaded) ensureLoaded()
        return snapshot
    }

    fun isPinned(lower: String): Boolean {
        if (!loaded) ensureLoaded()
        return snapshot.any { it.first == lower }
    }

    @Synchronized
    fun pin(lower: String, spelling: String) {
        ensureLoaded()
        if (lower.isEmpty() || map.containsKey(lower)) return
        map[lower] = spelling
        dirty = true
        publish()
    }

    @Synchronized
    fun unpin(lower: String) {
        ensureLoaded()
        if (map.remove(lower) == null) return
        dirty = true
        publish()
    }

    @Synchronized
    fun saveIfDirty() {
        if (!dirty) return
        try {
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.bufferedWriter(Charsets.UTF_8).use { w ->
                for ((k, v) in map) w.write("$k\t$v\n")
            }
            if (file.exists()) file.delete()
            tmp.renameTo(file)
            dirty = false
            ErrorHint.clear("pins")
        } catch (e: Exception) {
            ErrorHint.set("pins", ErrorHint.of("固定词保存失败", e))
        }
    }
}
