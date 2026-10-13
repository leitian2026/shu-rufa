package com.example.myime

import android.content.Context
import java.io.File

/**
 * 剪贴板历史的落盘存储:filesDir/user/clips.txt,一行一条(换行等字符转义)。
 *
 * 只有两种情况会让里面的内容变少:你在剪贴板页自己点了删除/清空,或者超过 500 条时挤掉最旧的。
 * 更新版本、切换输入法、进程被系统回收、重启手机都不会动它;只有卸载输入法或在系统里清除应用数据才会没。
 * 写入用"先写临时文件、再替换"的方式,中途被杀也不会把旧文件写坏。
 */
object ClipStore {
    private const val NAME = "clips.txt"
    const val TRASH = "trash.txt"   // 回收箱(最多 10 条),存法和剪贴板一样

    private fun file(ctx: Context, name: String = NAME): File {
        val d = File(ctx.applicationContext.filesDir, "user")
        if (!d.isDirectory) d.mkdirs()
        return File(d, name)
    }

    private fun encode(s: String): String {
        if (s.indexOf('\\') < 0 && s.indexOf('\n') < 0 && s.indexOf('\r') < 0) return s
        val sb = StringBuilder(s.length + 8)
        for (c in s) {
            when (c) {
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    private fun decode(s: String): String {
        if (s.indexOf('\\') < 0) return s
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (s[i + 1]) {
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    '\\' -> sb.append('\\')
                    else -> sb.append(c).append(s[i + 1])
                }
                i += 2
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }

    /** 读出全部记录(新的在前)。文件不存在或读不了就返回空。 */
    @Synchronized
    fun load(ctx: Context, name: String = NAME): List<String> {
        val f = file(ctx, name)
        val out = ArrayList<String>()
        try {
            if (!f.isFile) return out
            f.bufferedReader(Charsets.UTF_8).useLines { lines ->
                for (line in lines) {
                    if (line.isEmpty()) continue
                    out.add(decode(line))
                }
            }
        } catch (e: Exception) {
            ErrorHint.set("clip", ErrorHint.of("剪贴板读取出错", e))
            // 读到一半出错:已读到的照样用;原文件另存一份,免得之后的写入把它盖掉
            try {
                f.copyTo(File(f.parentFile, "$name.bak"), overwrite = true)
            } catch (e2: Exception) {
            }
        }
        return out
    }

    /** 整体写入。先写临时文件,成功后再替换,旧文件在新文件写完之前一直保留。 */
    @Synchronized
    fun save(ctx: Context, list: List<String>, name: String = NAME) {
        try {
            val f = file(ctx, name)
            val tmp = File(f.parentFile, "$name.tmp")
            tmp.bufferedWriter(Charsets.UTF_8).use { w ->
                for (t in list) {
                    w.write(encode(t))
                    w.write("\n")
                }
            }
            if (!tmp.renameTo(f)) {
                // 个别系统上直接替换会失败:这时才先删旧的再改名
                f.delete()
                tmp.renameTo(f)
            }
            ErrorHint.clear("clip")
        } catch (e: Exception) {
            ErrorHint.set("clip", ErrorHint.of("剪贴板保存失败", e))
            // 存失败下次再试
        }
    }
}
