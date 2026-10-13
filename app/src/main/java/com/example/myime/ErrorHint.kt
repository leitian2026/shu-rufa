package com.example.myime

import java.util.concurrent.ConcurrentHashMap

/**
 * 出错原因的集中记录(只在出错 / 状态变化时写一次,没有轮询、没有日志文件)。
 * 键盘的候选栏在"正在输入"时把当前页面相关的原因用小红字显示在预览行右边。
 *
 * key:pinyin / stroke / english(词库状态)、query(查询出错)。
 */
object ErrorHint {
    private val map = ConcurrentHashMap<String, String>()

    fun set(key: String, msg: String) {
        map[key] = msg
    }

    fun clear(key: String) {
        if (map.isNotEmpty()) map.remove(key)
    }

    fun get(key: String): String? = map[key]

    /** 所有当前的错误,一行一条(词库管理页用)。没有就返回 null。 */
    fun all(): String? = if (map.isEmpty()) null else map.values.joinToString("\n")

    /** 异常 → 一句人话。内存不足单独说明。 */
    fun of(prefix: String, t: Throwable): String {
        val why = when {
            t is OutOfMemoryError -> "内存不足"
            t.message.isNullOrBlank() -> t.javaClass.simpleName
            else -> t.message!!.lineSequence().first().take(60)
        }
        return "$prefix: $why"
    }
}
