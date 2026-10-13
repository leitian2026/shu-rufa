package com.example.myime

import android.content.Context
import java.io.File

/**
 * 剪贴板历史和回收箱:放在内存里的同时落盘(filesDir/user/clips.txt、trash.txt),
 * 这样切换输入法、进程被回收、重启手机之后都还在。
 *
 * 省电:没有定时保存;内容变了才写,而且只写变了的那个文件,短时间内的多次改动合并成一次;键盘隐藏、服务销毁时补存。
 * 每行 "c<Tab>转义后的文本"(回收箱是 "t<Tab>…"),换行、回车、反斜杠转义,所以多行内容也是一条。
 * 读写走 SafeFile:原子替换、有 .bak 备份,写到一半被杀也不会丢旧数据。
 */
object ClipStore {
    private const val CLIP_FILE = "clips.txt"
    private const val TRASH_FILE = "trash.txt"

    class Data(val clips: List<String>, val trash: List<String>)

    private fun dir(ctx: Context): File {
        val d = File(ctx.applicationContext.filesDir, "user")
        if (!d.isDirectory) d.mkdirs()
        return d
    }

    private fun esc(s: String): String {
        val b = StringBuilder(s.length + 8)
        for (ch in s) {
            when (ch) {
                '\\' -> b.append("\\\\")
                '\n' -> b.append("\\n")
                '\r' -> b.append("\\r")
                else -> b.append(ch)
            }
        }
        return b.toString()
    }

    private fun unesc(s: String): String {
        if (s.indexOf('\\') < 0) return s
        val b = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val ch = s[i]
            if (ch == '\\' && i + 1 < s.length) {
                when (val n = s[i + 1]) {
                    'n' -> b.append('\n')
                    'r' -> b.append('\r')
                    else -> b.append(n)
                }
                i += 2
            } else {
                b.append(ch)
                i++
            }
        }
        return b.toString()
    }

    private fun readList(file: File, tag: String, max: Int): List<String> {
        val out = ArrayList<String>()
        try {
            val r = SafeFile.read(file) ?: return out
            for (line in r.lines) {
                if (line.length < 2 || line[0] != tag[0] || line[1] != '\t') continue
                val t = unesc(line.substring(2))
                if (t.isNotBlank() && !out.contains(t)) out.add(t)
                if (out.size >= max) break
            }
        } catch (e: Exception) {
            ErrorHint.set("clip", ErrorHint.of("剪贴板读取失败", e))
        }
        return out
    }

    /** 启动时读一次。旧版本写的文件格式相同,直接读;没有就是空的。 */
    fun load(ctx: Context, maxClips: Int, maxTrash: Int): Data {
        val d = dir(ctx)
        return Data(readList(File(d, CLIP_FILE), "c", maxClips), readList(File(d, TRASH_FILE), "t", maxTrash))
    }

    /** 保存快照(调用方在主线程拿快照,在后台线程调用这里)。传 null 的那个文件没变,不写。 */
    @Synchronized
    fun save(ctx: Context, clips: List<String>?, trash: List<String>?) {
        val d = dir(ctx)
        try {
            if (clips != null) SafeFile.write(File(d, CLIP_FILE)) { w ->
                for (t in clips) w.write("c\t" + esc(t) + "\n")
            }
            if (trash != null) SafeFile.write(File(d, TRASH_FILE)) { w ->
                for (t in trash) w.write("t\t" + esc(t) + "\n")
            }
            ErrorHint.clear("clip")
        } catch (e: Exception) {
            ErrorHint.set("clip", ErrorHint.of("剪贴板保存失败", e))
        }
    }
}
