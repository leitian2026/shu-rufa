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

    /** 全部清掉:只在词库文件被整体换掉(还原备份)时用。平时词库更新靠头里的 大小:修改时间 自动作废,不用删。 */
    fun clear(ctx: Context) {
        val d = file(ctx, "x").parentFile ?: return
        d.listFiles()?.forEach { if (it.name.endsWith(".bin") || it.name.endsWith(".tmp")) it.delete() }
    }

    /** 只清某个词库的缓存(那个词库被删掉了,缓存用不上了);别的词库的缓存原样保留。 */
    fun clearPack(ctx: Context, pack: String) {
        val d = file(ctx, "x").parentFile ?: return
        val prefixes = when (pack) {
            DictPacks.PINYIN -> listOf("pinyin", "fuzzy_")
            DictPacks.STROKE -> listOf("stroke")
            DictPacks.ENGLISH -> listOf("english")
            else -> return
        }
        d.listFiles()?.forEach { f -> if (prefixes.any { f.name.startsWith(it) }) f.delete() }
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
            // 直接替换旧缓存:不先删再改名,中间被杀也不会把好好的缓存弄丢
            try {
                java.nio.file.Files.move(
                    tmp.toPath(), out.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (e: java.nio.file.AtomicMoveNotSupportedException) {
                java.nio.file.Files.move(tmp.toPath(), out.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            }
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

    // ------------------------------------------------------------ 排好的顺序(联想排序、模糊音索引):各自一个小文件,不动上面的主缓存

    /** 存一份排好的下标顺序(给 PinyinEngine 的联想排序、模糊音索引用)。 */
    fun saveOrder(ctx: Context, name: String, src: File, order: IntArray) {
        write(ctx, "$name.bin", src, 1) { o ->
            o.writeInt(order.size)
            for (x in order) o.writeInt(x)
        }
    }

    /** 读回来;个数对不上 n、有越界下标、文件坏了都返回 null(调用方自己重新排)。 */
    fun loadOrder(ctx: Context, name: String, src: File, n: Int): IntArray? {
        val i = openChecked(ctx, "$name.bin", src, 1) ?: return null
        return try {
            i.use {
                val m = it.readInt()
                if (m != n) return null
                val a = IntArray(n)
                for (k in 0 until n) {
                    val v = it.readInt()
                    if (v < 0 || v >= n) return null
                    a[k] = v
                }
                a
            }
        } catch (e: Exception) {
            file(ctx, "$name.bin").delete()
            null
        }
    }

    // ------------------------------------------------------------ 英文双词表

    fun saveBigram(ctx: Context, src: File, succ: HashMap<String, Array<String>>) {
        write(ctx, "english_bigram.bin", src, 1) { o ->
            o.writeInt(succ.size)
            for ((k, v) in succ) {
                o.writeUTF(k)
                o.writeInt(v.size)
                for (w in v) o.writeUTF(w)
            }
        }
    }

    fun loadBigram(ctx: Context, src: File): HashMap<String, Array<String>>? {
        val i = openChecked(ctx, "english_bigram.bin", src, 1) ?: return null
        return try {
            i.use {
                val n = it.readInt()
                val m = HashMap<String, Array<String>>(maxOf(16, n * 2))
                for (k in 0 until n) {
                    val key = it.readUTF()
                    val c = it.readInt()
                    if (c < 0) return null
                    m[key] = Array(c) { _ -> it.readUTF() }
                }
                m
            }
        } catch (e: Exception) {
            file(ctx, "english_bigram.bin").delete()
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
