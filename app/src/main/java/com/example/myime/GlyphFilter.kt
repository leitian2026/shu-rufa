package com.example.myime

import android.graphics.Paint
import java.util.concurrent.ConcurrentHashMap

/**
 * 候选里去掉「系统字体里没有字形」的字(显示出来是一个写着 NO GLYPH 的方框,看不出是什么字,也打不出有用的东西)。
 * 笔画、拼音词库里带了很多扩展区的生僻字(扩展 B 到 G、西夏文等),系统字体不一定都带。
 * 按单个字符判断(Paint.hasGlyph 对多个字符只在能合成一个字形时才返回 true,不能整词问);结果按码点缓存,每个字只问系统一次。
 */
object GlyphFilter {
    private val paint = Paint()
    private val cache = ConcurrentHashMap<Int, Boolean>()

    private fun has(cp: Int): Boolean {
        cache[cp]?.let { return it }
        val ok = try {
            synchronized(paint) { paint.hasGlyph(String(Character.toChars(cp))) }
        } catch (e: Throwable) {
            true   // 问不了就当有,不能因此把候选吃掉
        }
        if (cache.size > 20000) cache.clear()
        cache[cp] = ok
        return ok
    }

    /** 引擎挑候选时用:这个字(词)在系统字体里是不是每个字都有字形。 */
    fun accepts(text: String): Boolean = ok(text)

    private fun ok(text: String): Boolean {
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            i += Character.charCount(cp)
            if (cp < 0x2E80) continue   // 字母、数字、拉丁标点不用问
            if (!has(cp)) return false
        }
        return true
    }

    /** 表情候选(kind 3)不动;一个都没去掉就原样返回,不新建列表。 */
    fun filter(list: List<Candidate>): List<Candidate> {
        if (list.isEmpty()) return list
        var drop = false
        for (c in list) if (c.kind != 3 && !ok(c.text)) { drop = true; break }
        if (!drop) return list
        val out = ArrayList<Candidate>(list.size)
        for (c in list) if (c.kind == 3 || ok(c.text)) out.add(c)
        return out
    }
}
