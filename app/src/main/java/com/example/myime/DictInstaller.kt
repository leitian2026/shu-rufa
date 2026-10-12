package com.example.myime

import android.content.Context
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 一个下载源。urls 是按顺序尝试的备用地址(分支名 main / master 不确定时两个都列上)。
 * optional:下载失败只跳过,不算整体失败。reuse:文件已经在 raw/ 里就不再下载(多个词库包共用)。
 */
class Source(
    val name: String,
    val urls: List<String>,
    val optional: Boolean = false,
    val reuse: Boolean = false,
)

object DictPacks {
    const val STROKE = "stroke"
    const val PINYIN = "pinyin"
    const val ENGLISH = "english"

    val ALL = listOf(STROKE, PINYIN, ENGLISH)

    private const val FREQ_FILE = "8105.dict.yaml"

    private fun gh(owner: String, repo: String, path: String): List<String> = listOf(
        "https://raw.githubusercontent.com/$owner/$repo/main/$path",
        "https://raw.githubusercontent.com/$owner/$repo/master/$path",
    )

    private fun ice(path: String) = gh("iDvel", "rime-ice", path)

    private fun norvig(name: String) = listOf(
        "https://norvig.com/ngrams/$name",
        "https://www.norvig.com/ngrams/$name",
    )

    /** 原始文件名固定,放进 raw/ 文件夹。手动部署时也用这些名字。 */
    fun sources(pack: String): List<Source> = when (pack) {
        // 笔画:rime-stroke 的笔画字表(含繁体和扩展区生僻字);8105 只用来取字频
        STROKE -> listOf(
            Source("stroke.dict.yaml", gh("rime", "rime-stroke", "stroke.dict.yaml")),
            Source(FREQ_FILE, ice("cn_dicts/8105.dict.yaml"), optional = true, reuse = true),
        )
        // 拼音:雾凇的 8105 字表 + 基础词库 + 扩展词库 + 41448 大字表(生僻字),再加朙月拼音(繁体)
        PINYIN -> listOf(
            Source(FREQ_FILE, ice("cn_dicts/8105.dict.yaml")),
            Source("base.dict.yaml", ice("cn_dicts/base.dict.yaml")),
            Source("ext.dict.yaml", ice("cn_dicts/ext.dict.yaml"), optional = true),
            Source("41448.dict.yaml", ice("cn_dicts/41448.dict.yaml"), optional = true),
            Source("luna_pinyin.dict.yaml", gh("rime", "rime-luna-pinyin", "luna_pinyin.dict.yaml"), optional = true),
        )
        // 英文:Norvig 整理的谷歌语料词频(单词 + 双词)
        ENGLISH -> listOf(
            Source("count_1w.txt", norvig("count_1w.txt")),
            Source("count_2w.txt", norvig("count_2w.txt"), optional = true),
        )
        else -> emptyList()
    }

    /** 这个词库包转换后生成的文件。 */
    fun outputs(pack: String): List<String> = when (pack) {
        STROKE -> listOf(DictStore.STROKE_FILE)
        PINYIN -> listOf(DictStore.PINYIN_FILE)
        ENGLISH -> listOf(DictStore.ENGLISH_FILE, DictStore.ENGLISH_BIGRAM_FILE)
        else -> emptyList()
    }

    fun freqFileName(): String = FREQ_FILE
}

object DictDownloader {
    private const val UA = "myime-dict/1.0"

    /** 下载到 dest(先写 .part 再换名)。自己处理重定向,允许 http→https。 */
    fun fetch(url: String, dest: File, cancel: AtomicBoolean, onBytes: (Long, Long) -> Unit) {
        var current = url
        var redirects = 0
        val part = File(dest.parentFile, dest.name + ".part")
        try {
            while (true) {
                val conn = URL(current).openConnection() as HttpURLConnection
                try {
                    conn.connectTimeout = 12_000
                    conn.readTimeout = 30_000
                    conn.instanceFollowRedirects = false
                    conn.setRequestProperty("User-Agent", UA)
                    val code = conn.responseCode
                    if (code in 300..399) {
                        val loc = conn.getHeaderField("Location") ?: throw IOException("重定向没有地址")
                        if (++redirects > 6) throw IOException("重定向太多")
                        current = URL(URL(current), loc).toString()
                        continue
                    }
                    if (code != 200) throw IOException("HTTP $code")
                    val total = conn.contentLengthLong
                    conn.inputStream.use { input ->
                        part.outputStream().use { output ->
                            val buf = ByteArray(64 * 1024)
                            var done = 0L
                            while (true) {
                                if (cancel.get()) throw CancelledException()
                                val n = input.read(buf)
                                if (n < 0) break
                                output.write(buf, 0, n)
                                done += n
                                onBytes(done, total)
                            }
                        }
                    }
                    if (part.length() == 0L) throw IOException("下载到的文件是空的")
                    if (dest.exists()) dest.delete()
                    if (!part.renameTo(dest)) {
                        part.copyTo(dest, overwrite = true)
                        part.delete()
                    }
                    return
                } finally {
                    conn.disconnect()
                }
            }
        } catch (e: Throwable) {
            part.delete()
            throw e
        }
    }
}

object DictInstaller {

    private fun fmtBytes(b: Long): String =
        if (b < 0) "?" else String.format(Locale.US, "%.1fMB", b / 1048576.0)

    /** 按 镜像优先 → 直连 的顺序,把一个源的每个备用地址都试一遍。 */
    private fun download(
        ctx: Context,
        s: Source,
        target: File,
        cancel: AtomicBoolean,
        onBytes: (Long, Long) -> Unit,
    ) {
        val mirror = DictConfig.mirror(ctx).trim()
        val prefix = if (mirror.isEmpty()) "" else if (mirror.endsWith("/")) mirror else "$mirror/"
        var last: Exception? = null
        for (u in s.urls) {
            val tries = if (prefix.isEmpty()) listOf(u) else listOf(prefix + u, u)
            for (t in tries) {
                if (cancel.get()) throw CancelledException()
                try {
                    DictDownloader.fetch(t, target, cancel, onBytes)
                    return
                } catch (e: CancelledException) {
                    throw e
                } catch (e: Exception) {
                    last = e
                }
            }
        }
        throw IOException(last?.message ?: "下载失败")
    }

    /** 下载 → 转换 → 换上新词库。返回给界面显示的一句话。耗时,要在后台线程调用。 */
    fun install(ctx: Context, pack: String, cancel: AtomicBoolean, progress: Progress): String {
        val raw = DictStore.raw(ctx)
        val sources = DictPacks.sources(pack)
        val notes = ArrayList<String>()
        val n = sources.size

        for (i in sources.indices) {
            val s = sources[i]
            val target = File(raw, s.name)
            if (s.reuse && target.isFile && target.length() > 0) continue
            val base = 0.7f * i / n
            val span = 0.7f / n
            try {
                download(ctx, s, target, cancel) { done, total ->
                    val frac = if (total > 0) done.toFloat() / total else 0f
                    progress("下载 ${s.name}  ${fmtBytes(done)}/${fmtBytes(total)}", base + span * frac)
                }
            } catch (e: CancelledException) {
                throw e
            } catch (e: Exception) {
                if (target.isFile && target.length() > 0) {
                    notes.add("${s.name} 下载失败,改用 raw/ 里已有的文件")
                } else if (s.optional) {
                    notes.add("${s.name} 已跳过(${e.message})")
                } else {
                    throw IOException("${s.name} 下载失败: ${e.message}")
                }
            }
        }

        val conv: Progress = { msg, f -> progress(msg, 0.7f + 0.3f * f) }
        fun rawFile(name: String): File? = File(raw, name).takeIf { it.isFile && it.length() > 0 }

        val count: Int = when (pack) {
            DictPacks.STROKE -> {
                val stroke = rawFile("stroke.dict.yaml") ?: throw IOException("缺少 raw/stroke.dict.yaml")
                DictConvert.buildStroke(
                    stroke, rawFile(DictPacks.freqFileName()),
                    DictStore.file(ctx, DictStore.STROKE_FILE), cancel, conv,
                )
            }
            DictPacks.PINYIN -> {
                val main = listOf(DictPacks.freqFileName(), "base.dict.yaml", "ext.dict.yaml", "41448.dict.yaml")
                    .mapNotNull { rawFile(it) }
                if (main.isEmpty()) throw IOException("缺少 raw/ 里的拼音词库文件")
                val supplement = listOfNotNull(rawFile("luna_pinyin.dict.yaml"))
                DictConvert.buildPinyin(
                    main, supplement, DictStore.file(ctx, DictStore.PINYIN_FILE),
                    DictConfig.pinyinCap(ctx), cancel, conv,
                )
            }
            DictPacks.ENGLISH -> {
                val c1 = rawFile("count_1w.txt") ?: throw IOException("缺少 raw/count_1w.txt")
                DictConvert.buildEnglish(
                    c1, rawFile("count_2w.txt"),
                    DictStore.file(ctx, DictStore.ENGLISH_FILE),
                    DictStore.file(ctx, DictStore.ENGLISH_BIGRAM_FILE),
                    DictConfig.englishCap(ctx), cancel, conv,
                )
            }
            else -> throw IOException("未知的词库包: $pack")
        }
        DictConfig.setMeta(ctx, pack, count)
        progress("完成", 1f)
        return "已安装 $count 条" + if (notes.isEmpty()) "" else "\n" + notes.joinToString("\n")
    }

    fun delete(ctx: Context, pack: String) {
        for (f in DictPacks.outputs(pack)) DictStore.file(ctx, f).delete()
        val raw = DictStore.raw(ctx)
        for (s in DictPacks.sources(pack)) {
            if (s.name != DictPacks.freqFileName()) File(raw, s.name).delete()
        }
        // 8105 是拼音、笔画共用的,两边都没有了才删
        if (!DictStore.file(ctx, DictStore.PINYIN_FILE).isFile &&
            !DictStore.file(ctx, DictStore.STROKE_FILE).isFile
        ) {
            File(raw, DictPacks.freqFileName()).delete()
        }
        DictConfig.clearMeta(ctx, pack)
        DictCache.clear(ctx)
    }
}
