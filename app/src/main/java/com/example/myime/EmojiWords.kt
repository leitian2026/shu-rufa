package com.example.myime

import android.content.Context

/**
 * 候选词 → 表情 的对照表(assets/emoji_words.txt,每行「词<Tab>表情 表情」)。
 * 候选栏里出现表里的词时,表情会紧跟在这个词后面显示,点一下就上屏。
 * 文件很小,只在词库加载时在后台线程读一次,和词库一起释放;打字时只是 HashMap 查表,不额外耗电。
 */
object EmojiWords {
    private const val MAX_PER_WORD = 2

    @Volatile
    var map: Map<String, List<String>>? = null
        private set

    fun ensure(context: Context) {
        if (map != null) return
        val m = HashMap<String, MutableList<String>>(1024)
        try {
            context.assets.open("emoji_words.txt").bufferedReader(Charsets.UTF_8).useLines { lines ->
                for (line in lines) {
                    if (line.isEmpty() || line[0] == '#') continue
                    val t = line.indexOf('\t')
                    if (t <= 0) continue
                    val word = line.substring(0, t).trim()
                    if (word.isEmpty()) continue
                    val list = m.getOrPut(word) { ArrayList(2) }
                    for (e in line.substring(t + 1).trim().split(' ')) {
                        if (e.isNotEmpty() && e !in list && list.size < MAX_PER_WORD) list.add(e)
                    }
                }
            }
        } catch (t: Throwable) {
            // 读不到就当没有表情,不影响打字
        }
        map = m
    }

    fun release() {
        map = null
    }
}
