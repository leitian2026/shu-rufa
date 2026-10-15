package com.example.myime

import android.content.Context
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * 词库的二进制缓存(存在应用内部目录 files/dictcache/,用户看不到、也不用管)。
 *
 * 文本词库每次进程启动都要:逐行切分、装箱、两次全量字符串排序,这些都是纯开销。
 * 第一次加载文本后,把"已经排好序的数组"写成二进制;之后启动直接读回来,不再切分、不再排序。
 * 缓存头里记着源文件的 大小:修改时间,源文件一换(下载更新、手动部署),缓存自动作废、重新生成。
 * 任何读取错误都当作没有缓存,退回文本加载,不影响使用。
 */
object DictCache {
    private const val MAGIC = 0x4D494331
    // 各词库的缓存格式版本分开记:拼音改了格式,只让拼音缓存作废重建,笔画和英文不受影响
    private const val PINYIN_VERSION = 2
    private const val STROKE_VERSION = 2   // 2:每条多存一个繁体字频
    private const val ENGLISH_VERSION = 1

    private fun file(ctx: Context, name: String): File {
        val d = File(ctx.applicationContext.filesDir, "dictcache")
        if (!d.isDirectory) d.mkdirs()
        return File(d, name)
    }

    private fun stampOf(src: File): String = "${src.length()}:${src.lastModified()}"

    fun clear(ctx: Context) {
        for (n in listOf("pinyin.bin", "stroke.bin", "english.bin")) file(ctx, n).delete()
    }

    private inline fun write(ctx: Context, name: String, src: File, version: Int, body: (DataOutputStream) -> Unit) {
        val out = file(ctx, name)
        val tmp = File(out.parentFile, name + ".tmp")
        try {
            DataOutputStream(BufferedOutputStream(FileOutputStream(tmp), 1 shl 16)).use { o ->
                o.writeInt(MAGIC)
                o.writeInt(version)
                o.writeUTF(stampOf(src))
                body(o)
            }
            if (out.exists()) out.delete()
            if (!tmp.renameTo(out)) tmp.delete()
        } catch (e: Exception) {
            ErrorHint.set("cache", ErrorHint.of("词库缓存写入失败(只会让启动变慢)", e))
            tmp.delete()
        }
    }

    /** 打开缓存并校验头;不合格返回 null。 */
    private fun openChecked(ctx: Context, name: String, src: File, version: Int): DataInputStream? {
        val f = file(ctx, name)
        if (!f.isFile) return null
        val i = DataInputStream(BufferedInputStream(FileInputStream(f), 1 shl 16))
        return try {
            if (i.readInt() == MAGIC && i.readInt() == version && i.readUTF() == stampOf(src)) {
                i
            } else {
                i.close()
                null
            }
        } catch (e: Exception) {
            i.close()
            null
        }
    }

    // ------------------------------------------------------------ 拼音

    /**
     * 拼音缓存格式 v2:每条 = 词、不带空格的拼音、词频、音节数 + 各音节长度(每个一个字节)。
     * 不再存带空格的写法,也不用在加载时拼 StringBuilder;首字母串由 buildInitials 复用缓冲区算出。
     */
    fun savePinyin(ctx: Context, src: File, e: PinyinEngine) {
        write(ctx, "pinyin.bin", src, PINYIN_VERSION) { o ->
            val n = e.texts.size
            o.writeInt(n)
            for (i in 0 until n) {
                o.writeUTF(e.texts[i])
                o.writeUTF(e.compactOf[i])
                o.writeInt(e.freqs[i])
                val a = e.sylStart[i]
                val b = e.sylStart[i + 1]
                o.writeByte(b - a)
                o.write(e.sylLens, a, b - a)
            }
            for (x in e.compactIds) o.writeInt(x)
            for (x in e.initialIds) o.writeInt(x)
        }
    }

    fun loadPinyin(ctx: Context, src: File, user: UserFreq?): PinyinEngine? {
        val i = openChecked(ctx, "pinyin.bin", src, PINYIN_VERSION) ?: return null
        return try {
            i.use {
                val n = it.readInt()
                val texts = Array(n) { "" }
                val compact = Array(n) { "" }
                val freqs = IntArray(n)
                val sylStart = IntArray(n + 1)
                var lens = ByteArray(maxOf(n * 3, 16))
                var total = 0
                for (k in 0 until n) {
                    texts[k] = it.readUTF()
                    compact[k] = it.readUTF()
                    freqs[k] = it.readInt()
                    val c = it.readUnsignedByte()
                    if (total + c > lens.size) lens = lens.copyOf(maxOf(lens.size * 2, total + c))
                    it.readFully(lens, total, c)
                    sylStart[k] = total
                    total += c
                }
                sylStart[n] = total
                val sylLens = lens.copyOf(total)
                val co = IntArray(n) { _ -> it.readInt() }
                val io = IntArray(n) { _ -> it.readInt() }
                PinyinEngine(texts, compact, buildInitials(compact, sylLens, sylStart), sylLens, sylStart, freqs, user, co, io)
            }
        } catch (e: Exception) {
            ErrorHint.set("cache", ErrorHint.of("词库缓存读取失败,已重建", e))
            file(ctx, "pinyin.bin").delete()
            null
        }
    }

    // ------------------------------------------------------------ 笔画

    fun saveStroke(ctx: Context, src: File, e: StrokeEngine) {
        write(ctx, "stroke.bin", src, STROKE_VERSION) { o ->
            val n = e.texts.size
            o.writeInt(n)
            for (i in 0 until n) {
                o.writeUTF(e.texts[i])
                o.writeUTF(e.codeOf[i])
                o.writeInt(e.freqs[i])
                o.writeInt(e.tfreqs?.get(i) ?: 0)
            }
            for (x in e.ids) o.writeInt(x)
        }
    }

    fun loadStroke(ctx: Context, src: File, user: UserFreq?): StrokeEngine? {
        val i = openChecked(ctx, "stroke.bin", src, STROKE_VERSION) ?: return null
        return try {
            i.use {
                val n = it.readInt()
                val texts = Array(n) { "" }
                val codes = Array(n) { "" }
                val freqs = IntArray(n)
                val tfreqs = IntArray(n)
                for (k in 0 until n) {
                    texts[k] = it.readUTF()
                    codes[k] = it.readUTF()
                    freqs[k] = it.readInt()
                    tfreqs[k] = it.readInt()
                }
                val order = IntArray(n) { _ -> it.readInt() }
                StrokeEngine(texts, codes, freqs, user, order, tfreqs)
            }
        } catch (e: Exception) {
            ErrorHint.set("cache", ErrorHint.of("词库缓存读取失败,已重建", e))
            file(ctx, "stroke.bin").delete()
            null
        }
    }

    // ------------------------------------------------------------ 英文(只缓存词表;双词表很小,每次读文本)

    fun saveEnglish(ctx: Context, src: File, e: EnglishEngine) {
        write(ctx, "english.bin", src, ENGLISH_VERSION) { o ->
            val n = e.words.size
            o.writeInt(n)
            for (i in 0 until n) {
                o.writeUTF(e.words[i])
                o.writeInt(e.freqs[i])
            }
            for (x in e.ids) o.writeInt(x)
        }
    }

    /** 读出 词、词频、排好的顺序;succ/user 由调用者补上。 */
    fun loadEnglish(
        ctx: Context, src: File, succ: HashMap<String, Array<String>>, user: UserEnglish?,
    ): EnglishEngine? {
        val i = openChecked(ctx, "english.bin", src, ENGLISH_VERSION) ?: return null
        return try {
            i.use {
                val n = it.readInt()
                val words = Array(n) { "" }
                val freqs = IntArray(n)
                for (k in 0 until n) {
                    words[k] = it.readUTF()
                    freqs[k] = it.readInt()
                }
                val order = IntArray(n) { _ -> it.readInt() }
                EnglishEngine(words, freqs, succ, user, order)
            }
        } catch (e: Exception) {
            ErrorHint.set("cache", ErrorHint.of("词库缓存读取失败,已重建", e))
            file(ctx, "english.bin").delete()
            null
        }
    }
}
