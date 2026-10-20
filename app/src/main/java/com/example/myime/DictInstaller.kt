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
    const val GRAM = "gram"

    val ALL = listOf(STROKE, PINYIN, ENGLISH, GRAM)

    private const val FREQ_FILE = "8105.dict.yaml"
    private const val TRAD_FILE = "luna_pinyin.dict.yaml"

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
            // 朙月拼音只用来取繁体字频(拼音包也下它,已经有了就直接用)
            Source(TRAD_FILE, gh("rime", "rime-luna-pinyin", "luna_pinyin.dict.yaml"), optional = true, reuse = true),
        )
        // 拼音:雾凇的 8105 字表 + 基础词库 + 扩展词库 + 41448 大字表(生僻字),再加朙月拼音(繁体)
        PINYIN -> listOf(
            Source(FREQ_FILE, ice("cn_dicts/8105.dict.yaml")),
            Source("base.dict.yaml", ice("cn_dicts/base.dict.yaml")),
            Source("ext.dict.yaml", ice("cn_dicts/ext.dict.yaml"), optional = true),
            Source("41448.dict.yaml", ice("cn_dicts/41448.dict.yaml"), optional = true),
            Source(TRAD_FILE, gh("rime", "rime-luna-pinyin", "luna_pinyin.dict.yaml"), optional = true),
        )
        // 英文:Norvig 整理的谷歌语料词频(单词 + 双词)
        ENGLISH -> listOf(
            Source("count_1w.txt", norvig("count_1w.txt")),
            Source("count_2w.txt", norvig("count_2w.txt"), optional = true),
        )
        // 搭配模型:一个现成的二进制包(gram.bin),没有内置下载地址,要在「自定义来源」里填(见 README)
        GRAM -> emptyList()
        else -> emptyList()
    }

    /** 自定义来源在 raw/ 里的文件名。 */
    fun customName(pack: String, i: Int) = "custom_${pack}_$i.txt"

    /** 有自定义地址就用自定义的,否则用内置默认来源。 */
    fun sources(ctx: Context, pack: String): List<Source> {
        val urls = DictConfig.customUrls(ctx, pack)
        if (urls.isEmpty()) return sources(pack)
        return urls.mapIndexed { i, u ->
            val optional = pack != PINYIN && i >= 1   // 笔画的字频表和繁体字频表、英文的双词表可以缺
            Source(customName(pack, i), listOf(u), optional = optional)
        }
    }

    /** 这个词库包转换后生成的文件。 */
    fun outputs(pack: String): List<String> = when (pack) {
        STROKE -> listOf(DictStore.STROKE_FILE)
        PINYIN -> listOf(DictStore.PINYIN_FILE)
        ENGLISH -> listOf(DictStore.ENGLISH_FILE, DictStore.ENGLISH_BIGRAM_FILE)
        GRAM -> listOf(DictStore.GRAM_FILE)
        else -> emptyList()
    }

    fun freqFileName(): String = FREQ_FILE

    /** 朙月拼音:拼音包拿它当繁体补充,笔画包拿它取繁体字频,两边共用一份。 */
    fun tradFileName(): String = TRAD_FILE
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
                    // 开头是网页 = 镜像/代理/网络认证页返回的错误页(状态码仍是 200)
                    val head = part.inputStream().use { ins ->
                        val b = ByteArray(512)
                        String(b, 0, maxOf(0, ins.read(b)), Charsets.UTF_8)
                    }.trimStart('\uFEFF', ' ', '\n', '\r', '\t').lowercase()
                    if (head.startsWith("<!doctype") || head.startsWith("<html") || head.startsWith("<?xml")) {
                        throw IOException("返回的是网页而不是词库,请检查镜像前缀或网络")
                    }
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
        val custom = DictConfig.customUrls(ctx, pack).isNotEmpty()
        val sources = DictPacks.sources(ctx, pack)
        if (pack == DictPacks.GRAM && sources.isEmpty()) {
            throw IOException("搭配模型没有默认下载地址:点「从本地导入」选手机上的 gram.bin,或在「自定义来源」填它的下载地址")
        }
        val notes = ArrayList<String>()
        if (custom) {
            // 自定义来源:清掉以前多出来的自定义原始文件,避免地址变少后旧文件混进来
            val keep = sources.map { it.name }.toSet()
            raw.listFiles()?.forEach {
                if (it.name.startsWith("custom_${pack}_") && it.name !in keep) it.delete()
            }
        }
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
                val strokeName = if (custom) DictPacks.customName(pack, 0) else "stroke.dict.yaml"
                val stroke = rawFile(strokeName) ?: throw IOException("缺少 raw/$strokeName")
                val freq = if (custom) rawFile(DictPacks.customName(pack, 1)) else rawFile(DictPacks.freqFileName())
                val trad = if (custom) rawFile(DictPacks.customName(pack, 2)) else rawFile(DictPacks.tradFileName())
                DictConvert.buildStroke(
                    stroke, freq,
                    DictStore.file(ctx, DictStore.STROKE_FILE), cancel, conv,
                    tradFile = trad,
                )
            }
            DictPacks.PINYIN -> {
                val main = if (custom) sources.mapNotNull { rawFile(it.name) }
                else listOf(DictPacks.freqFileName(), "base.dict.yaml", "ext.dict.yaml", "41448.dict.yaml")
                    .mapNotNull { rawFile(it) }
                if (main.isEmpty()) throw IOException("缺少 raw/ 里的拼音词库文件")
                val supplement = if (custom) emptyList() else listOfNotNull(rawFile("luna_pinyin.dict.yaml"))
                DictConvert.buildPinyin(
                    main, supplement, DictStore.file(ctx, DictStore.PINYIN_FILE),
                    DictConfig.pinyinCap(ctx), cancel, conv,
                    minMainSingles = if (custom) 0 else 3000,
                )
            }
            DictPacks.ENGLISH -> {
                val n1 = if (custom) DictPacks.customName(pack, 0) else "count_1w.txt"
                val n2 = if (custom) DictPacks.customName(pack, 1) else "count_2w.txt"
                val c1 = rawFile(n1) ?: throw IOException("缺少 raw/$n1")
                DictConvert.buildEnglish(
                    c1, rawFile(n2),
                    DictStore.file(ctx, DictStore.ENGLISH_FILE),
                    DictStore.file(ctx, DictStore.ENGLISH_BIGRAM_FILE),
                    DictConfig.englishCap(ctx), cancel, conv,
                )
            }
            DictPacks.GRAM -> {
                // 下载到的就是成品:检查文件头,通过了再换上
                val src = rawFile(DictPacks.customName(pack, 0)) ?: throw IOException("缺少 raw/${DictPacks.customName(pack, 0)}")
                conv("检查搭配模型", 0.2f)
                val entries = try {
                    installGramFile(ctx, src)
                } catch (e: IOException) {
                    src.delete()
                    throw IOException("下载到的不是可用的搭配模型包(${e.message})")
                }
                src.delete()   // 成品已经在词库文件夹里,不用在 raw/ 里再留一份
                entries
            }
            else -> throw IOException("未知的词库包: $pack")
        }
        DictConfig.setMeta(ctx, pack, count)
        progress("完成", 1f)
        return "已安装 $count 条" + if (notes.isEmpty()) "" else "\n" + notes.joinToString("\n")
    }

    /** 把一个搭配模型成品装进词库文件夹:先检查文件头(不对抛 IOException),再写临时文件、改名,装到一半不会留下坏文件。返回条目数。 */
    private fun installGramFile(ctx: Context, src: File): Int {
        val entries = GramPack.verify(src)
        val dest = DictStore.file(ctx, DictStore.GRAM_FILE)
        val part = File(dest.parentFile, dest.name + ".part")
        src.copyTo(part, overwrite = true)
        if (dest.exists()) dest.delete()
        if (!part.renameTo(dest)) {
            part.copyTo(dest, overwrite = true)
            part.delete()
        }
        return entries
    }

    /**
     * 从手机上的本地文件导入词库并装好(词库管理页的「从本地导入」)。picked 是用户选的文件,已经复制到缓存文件夹。
     * zip 会先展开,所以把 gram.bin 或一批 *.dict.yaml 压成 zip 传到手机也行。
     * 各词库包要的文件(和「自定义来源」一样):
     *   拼音:一个或多个 Rime *.dict.yaml,或「词<Tab>拼音(音节空格分开)<Tab>词频」文本,按顺序合并
     *   笔画:笔画字表(文件名带 stroke 的排最前),可选字频表、繁体字频表(Rime 格式)
     *   英文:单词词频(词<Tab>次数),可选双词词频(文件名带 2w 或 bigram 的当双词表)
     *   搭配:gram.bin(检查文件头,不对不会覆盖现有的)
     * 装好后键盘下次弹出时自动重新加载。返回给界面显示的一句话。耗时,要在后台线程调用。
     */
    fun importLocal(ctx: Context, pack: String, picked: List<File>, cancel: AtomicBoolean, progress: Progress): String {
        val work = File(ctx.cacheDir, "import_unzip")
        work.deleteRecursively()
        work.mkdirs()
        try {
            val files = ArrayList<File>()
            for (f in picked) {
                if (DictZip.isZip(f)) {
                    progress("展开 ${f.name}", 0.05f)
                    files.addAll(DictZip.expand(f, work, 2L shl 30))
                } else {
                    files.add(f)
                }
            }
            if (files.isEmpty()) throw IOException("选的文件里没有可用的内容")
            val conv: Progress = { msg, f -> progress(msg, 0.1f + 0.9f * f) }
            val count: Int = when (pack) {
                DictPacks.GRAM -> {
                    conv("检查搭配模型", 0.2f)
                    val good = files.firstOrNull {
                        try {
                            GramPack.verify(it)
                            true
                        } catch (e: IOException) {
                            false
                        }
                    } ?: throw IOException("选的文件里没有可用的搭配模型包(gram.bin)")
                    installGramFile(ctx, good)
                }
                DictPacks.PINYIN -> DictConvert.buildPinyin(
                    files, emptyList(), DictStore.file(ctx, DictStore.PINYIN_FILE),
                    DictConfig.pinyinCap(ctx), cancel, conv, minMainSingles = 0,
                )
                DictPacks.STROKE -> {
                    val s = files.sortedBy { if (it.name.contains("stroke", ignoreCase = true)) 0 else 1 }
                    DictConvert.buildStroke(
                        s[0], s.getOrNull(1), DictStore.file(ctx, DictStore.STROKE_FILE), cancel, conv,
                        tradFile = s.getOrNull(2),
                    )
                }
                DictPacks.ENGLISH -> {
                    val s = files.sortedBy {
                        if (it.name.contains("2w", ignoreCase = true) || it.name.contains("bigram", ignoreCase = true)) 1 else 0
                    }
                    DictConvert.buildEnglish(
                        s[0], s.getOrNull(1),
                        DictStore.file(ctx, DictStore.ENGLISH_FILE),
                        DictStore.file(ctx, DictStore.ENGLISH_BIGRAM_FILE),
                        DictConfig.englishCap(ctx), cancel, conv,
                    )
                }
                else -> throw IOException("未知的词库包: $pack")
            }
            DictConfig.setMeta(ctx, pack, count)
            progress("完成", 1f)
            return "已导入 $count 条"
        } finally {
            work.deleteRecursively()
        }
    }

    fun delete(ctx: Context, pack: String) {
        for (f in DictPacks.outputs(pack)) DictStore.file(ctx, f).delete()
        val raw = DictStore.raw(ctx)
        for (s in DictPacks.sources(pack)) {
            // 8105 和朙月拼音是拼音、笔画共用的,放到下面两边都没有了再删
            if (s.name != DictPacks.freqFileName() && s.name != DictPacks.tradFileName()) File(raw, s.name).delete()
        }
        raw.listFiles()?.forEach { if (it.name.startsWith("custom_${pack}_")) it.delete() }
        // 8105 是拼音、笔画共用的,两边都没有了才删
        if (!DictStore.file(ctx, DictStore.PINYIN_FILE).isFile &&
            !DictStore.file(ctx, DictStore.STROKE_FILE).isFile
        ) {
            File(raw, DictPacks.freqFileName()).delete()
            File(raw, DictPacks.tradFileName()).delete()
        }
        DictConfig.clearMeta(ctx, pack)
        DictCache.clear(ctx)
    }
}
