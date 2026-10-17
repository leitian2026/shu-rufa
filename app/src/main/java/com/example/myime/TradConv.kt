package com.example.myime

/**
 * 简繁转换:「繁体优先」开着时候选全部显示繁体,关着(简体)时候选全部显示简体。
 * 用系统自带的 ICU 转换表(Hans-Hant / Hant-Hans),不用另外下载词库;系统里没有这张表就原样返回,不影响打字。
 * 打简体笔画、打繁体笔画都能查到字,查到以后统一转成当前字形显示。
 */
object TradConv {
    private class Dir(private val id: String) {
        val tr: android.icu.text.Transliterator? by lazy {
            try {
                android.icu.text.Transliterator.getInstance(id)
            } catch (e: Throwable) {
                null
            }
        }
        // 转换结果缓存:读不加锁、不写(打字时同一批字反复出现,基本都命中);满了就整个清掉重来
        val cache = java.util.concurrent.ConcurrentHashMap<String, String>()

        fun conv(s: String): String {
            if (s.isEmpty() || !hasHan(s)) return s
            cache[s]?.let { return it }
            val t = tr ?: return s
            val r = try { synchronized(t) { t.transliterate(s) } } catch (e: Throwable) { s }
            if (cache.size > 4000) cache.clear()
            cache[s] = r
            return r
        }
    }

    private val toTradDir = Dir("Hans-Hant")
    private val toSimpDir = Dir("Hant-Hans")

    private fun hasHan(s: String): Boolean {
        for (ch in s) if (ch.code >= 0x2E80) return true
        return false
    }

    /** 把一段文字转成简体(统计「用过的不同汉字」把繁简归成一个字用)。系统没有转换表就原样返回。 */
    fun simplify(s: String): String = toSimpDir.conv(s)

    /** 提前把转换表装好(在后台线程调用):第一次装要读系统数据,不要让它落在打字的那一下。 */
    fun warm(trad: Boolean) {
        val d = if (trad) toTradDir else toSimpDir
        if (d.cache.isNotEmpty()) return   // 已经装过、用过了
        d.conv("学")
    }

    /** 候选列表整体转成繁体(trad=true)或简体(false),表情候选 kind=3 不动,转完相同的只留第一个。一个字都没变就原样返回,不新建列表。 */
    fun convert(list: List<Candidate>, trad: Boolean): List<Candidate> {
        if (list.isEmpty()) return list
        val d = if (trad) toTradDir else toSimpDir
        var changed = false
        for (c in list) if (c.kind != 3 && d.conv(c.text) != c.text) { changed = true; break }
        if (!changed) return list
        val seen = HashSet<String>()
        val out = ArrayList<Candidate>(list.size)
        for (c in list) {
            val c2 = if (c.kind == 3) c else {
                val t = d.conv(c.text)
                if (t == c.text) c else Candidate(t, c.consumed, c.kind)
            }
            if (seen.add((if (c2.kind == 3) "\u0001" else "") + c2.text)) out.add(c2)
        }
        return out
    }
}
