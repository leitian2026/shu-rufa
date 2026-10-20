package com.example.myime

import android.content.Context
import java.io.File

/**
 * 词库的存放位置(专门文件夹)。
 *
 *   <外部私有目录>/dicts/          = /sdcard/Android/data/<包名>/files/dicts/
 *       pinyin.txt                 拼音词库(转换后,运行时读取)
 *       stroke.txt                 笔画字表
 *       english.txt                英文词表  词<Tab>词频
 *       english_bigram.txt         英文联想  前词<Tab>后词<Tab>词频
 *       gram.bin                   搭配模型(整句转换用的「哪些字经常连着出现」),二进制,见 GramPack
 *       raw/                       下载来的原始文件(Rime *.dict.yaml、Norvig 词频表),留着可离线重新转换
 *
 * 运行时优先读这个文件夹里的文件;没有就用 assets 里的内置小样本(英文没有样本,不装就没有补全)。
 * 部署也可以手动:把转换好的 .txt 直接放进这个文件夹即可,下次弹出键盘时自动重新加载。
 * 外部私有目录不可用时退回应用内部目录。
 */
object DictStore {
    const val PINYIN_FILE = "pinyin.txt"
    const val STROKE_FILE = "stroke.txt"
    const val ENGLISH_FILE = "english.txt"
    const val ENGLISH_BIGRAM_FILE = "english_bigram.txt"
    const val GRAM_FILE = "gram.bin"

    val ALL_FILES = listOf(PINYIN_FILE, STROKE_FILE, ENGLISH_FILE, ENGLISH_BIGRAM_FILE, GRAM_FILE)

    fun root(ctx: Context): File {
        val base = ctx.getExternalFilesDir(null) ?: ctx.filesDir
        val d = File(base, "dicts")
        if (!d.isDirectory) d.mkdirs()
        return d
    }

    fun raw(ctx: Context): File {
        val d = File(root(ctx), "raw")
        if (!d.isDirectory) d.mkdirs()
        return d
    }

    fun file(ctx: Context, name: String): File = File(root(ctx), name)

    /** 词库文件有没有变过(大小 + 修改时间)。输入法每次弹出键盘时比较一下,变了就重新加载。 */
    fun stamp(ctx: Context): String {
        val sb = StringBuilder()
        for (n in ALL_FILES) {
            val f = file(ctx, n)
            if (f.isFile) sb.append(f.length()).append(':').append(f.lastModified()) else sb.append('-')
            sb.append('|')
        }
        return sb.toString()
    }
}

/** 词库管理页里的几个开关和数字,存在 SharedPreferences。 */
object DictConfig {
    private const val FILE = "dictcfg"

    private fun p(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** 下载镜像前缀(可空)。设置后会先试 前缀+原地址,失败再直连。 */
    fun mirror(ctx: Context): String = p(ctx).getString("mirror", "") ?: ""

    fun setMirror(ctx: Context, v: String) {
        p(ctx).edit().putString("mirror", v.trim()).apply()
    }

    /**
     * 某个词库包的自定义下载地址(一行一个,空 = 用内置默认来源)。
     * 拼音:每行一个 Rime *.dict.yaml(或「词<Tab>拼音<Tab>词频」文本),按顺序合并。
     * 笔画:第 1 行笔画字表(Rime stroke.dict.yaml 格式),第 2 行(可选)字频表(Rime 格式),第 3 行(可选)繁体字频表(Rime 格式,如朙月拼音)。
     * 英文:第 1 行单词词频(词<Tab>次数),第 2 行(可选)双词词频(词1 词2<Tab>次数)。
     */
    fun customUrls(ctx: Context, pack: String): List<String> =
        (p(ctx).getString("custom_$pack", "") ?: "").lines().map { it.trim() }
            .filter { it.startsWith("http://") || it.startsWith("https://") }

    fun customText(ctx: Context, pack: String): String = p(ctx).getString("custom_$pack", "") ?: ""

    fun setCustomText(ctx: Context, pack: String, v: String) {
        p(ctx).edit().putString("custom_$pack", v.trim()).apply()
    }

    /** 拼音词库最多保留多少条(单字不受限制)。 */
    fun pinyinCap(ctx: Context): Int = p(ctx).getInt("pinyin_cap", 200_000)

    fun setPinyinCap(ctx: Context, v: Int) {
        p(ctx).edit().putInt("pinyin_cap", v).apply()
    }

    /** 英文最多保留多少个词。 */
    fun englishCap(ctx: Context): Int = p(ctx).getInt("english_cap", 80_000)

    /** 调试日志开关(默认关)。开了以后键盘往 logcat(标签 MyIme)写启动 / 弹出 / 词库加载耗时 / 慢查询,只有耗时和数量,没有输入内容。 */
    fun debugLog(ctx: Context): Boolean = p(ctx).getBoolean("debug_log", false)

    fun setDebugLog(ctx: Context, v: Boolean) {
        p(ctx).edit().putBoolean("debug_log", v).apply()
    }

    /**
     * 压住 Edge 点收藏夹时自动弹出又收起的键盘(默认开)。关掉就完全不拦,键盘行为和以前一样。
     * 只对 Edge 生效(见 MyImeService.onShowInputRequested),别的应用不受影响。
     */
    fun edgeFilter(ctx: Context): Boolean = p(ctx).getBoolean("edge_filter", true)

    fun setEdgeFilter(ctx: Context, v: Boolean) {
        p(ctx).edit().putBoolean("edge_filter", v).apply()
    }

    /** 按键震动(默认开,和以前一样)。关掉后键盘按键、长按都不再震动,省电。 */
    fun haptic(ctx: Context): Boolean = p(ctx).getBoolean("haptic", true)

    fun setHaptic(ctx: Context, v: Boolean) {
        p(ctx).edit().putBoolean("haptic", v).apply()
        Haptics.enabled = v
    }

    /** 记录并使用我的用词(词频学习)。 */
    fun learn(ctx: Context): Boolean = p(ctx).getBoolean("learn", true)

    fun setLearn(ctx: Context, v: Boolean) {
        p(ctx).edit().putBoolean("learn", v).apply()
    }

    /**
     * 笔画页的字形偏好:false = 简体优先(默认,和以前一样),true = 繁体优先。
     * 只决定「常用的简体字」和「常用的繁体字」谁排前面(见 StrokeRank);需要笔画词库带繁体字频才有区别。
     */
    fun strokeTradFirst(ctx: Context): Boolean = p(ctx).getBoolean("stroke_trad_first", false)

    fun setStrokeTradFirst(ctx: Context, v: Boolean) {
        p(ctx).edit().putBoolean("stroke_trad_first", v).apply()
    }

    /**
     * 候选区每个候选上方显示带声调的读音(功能页「显示读音」键,默认关)。
     * 关着时候选栏和以前完全一样,也不读声调表、不多做任何查询。
     */
    fun showReading(ctx: Context): Boolean = p(ctx).getBoolean("show_reading", false)

    fun setShowReading(ctx: Context, v: Boolean) {
        p(ctx).edit().putBoolean("show_reading", v).apply()
    }

    /** 拼音每次最多给几个词候选(15 / 30 / 50 / 80 四档,默认 30)。 */
    fun candidates(ctx: Context): Int = p(ctx).getInt("cands", 30).coerceIn(10, 100)

    fun setCandidates(ctx: Context, v: Int) {
        p(ctx).edit().putInt("cands", v.coerceIn(10, 100)).apply()
    }

    /**
     * 模糊音:打开了哪些规则(Fuzzy.ZH / CH / SH / ANG / ENG / ING / LN / FH 按位或),默认 0 = 全关。
     * 开着会在第一次用时后台建一份折叠后的词库索引(几百毫秒,占一点内存),全关就一点开销都没有。
     */
    fun fuzzy(ctx: Context): Int = p(ctx).getInt("fuzzy", 0) and 0xFF

    fun setFuzzy(ctx: Context, v: Int) {
        p(ctx).edit().putInt("fuzzy", v and 0xFF).apply()
    }

    /** 打开输入法时默认的输入方式:pinyin / stroke / english;last = 沿用上次用的。 */
    fun defaultMode(ctx: Context): String = p(ctx).getString("default_mode", "last") ?: "last"

    fun setDefaultMode(ctx: Context, v: String) {
        p(ctx).edit().putString("default_mode", v).apply()
    }

    /** 英文:按空格时自动把拼错的词改成最可能的词。默认关,只在候选里给出纠正。 */
    fun autoCorrect(ctx: Context): Boolean = p(ctx).getBoolean("auto_correct", false)

    fun setAutoCorrect(ctx: Context, v: Boolean) {
        p(ctx).edit().putBoolean("auto_correct", v).apply()
    }

    fun setMeta(ctx: Context, pack: String, count: Int) {
        p(ctx).edit().putInt("meta_${pack}_count", count)
            .putLong("meta_${pack}_time", System.currentTimeMillis()).apply()
    }

    fun metaCount(ctx: Context, pack: String): Int = p(ctx).getInt("meta_${pack}_count", -1)

    fun clearMeta(ctx: Context, pack: String) {
        p(ctx).edit().remove("meta_${pack}_count").remove("meta_${pack}_time").apply()
    }

    // ------------------------------------------------------------ 输入翻译

    /** 输入翻译开关(设置页里点「译」切换)。 */
    fun translateOn(ctx: Context): Boolean = p(ctx).getBoolean("trans_on", false)

    fun setTranslateOn(ctx: Context, v: Boolean) {
        p(ctx).edit().putBoolean("trans_on", v).apply()
    }

    /** 主用翻译引擎:ms(微软)/ google(谷歌)。主用失败会自动换另一个。 */
    fun translateEngine(ctx: Context): String = p(ctx).getString("trans_engine", Translator.MS) ?: Translator.MS

    fun setTranslateEngine(ctx: Context, v: String) {
        p(ctx).edit().putString("trans_engine", v).apply()
    }

    /** 翻译方向在 Translator.pairs 里的序号。 */
    fun translatePair(ctx: Context): Int =
        p(ctx).getInt("trans_pair", 0).coerceIn(0, Translator.pairs.size - 1)

    fun setTranslatePair(ctx: Context, v: Int) {
        p(ctx).edit().putInt("trans_pair", v).apply()
    }

    // ------------------------------------------------------------ 翻译接口(主页里可改)

    /** 取 / 存一项翻译接口设置(地址、密钥、区域)。空字符串 = 用默认。 */
    fun transSetting(ctx: Context, name: String): String = p(ctx).getString("tr_$name", "") ?: ""

    fun setTransSettings(ctx: Context, values: Map<String, String>) {
        val e = p(ctx).edit()
        for ((k, v) in values) {
            if (v.isBlank()) e.remove("tr_$k") else e.putString("tr_$k", v.trim())
        }
        e.apply()
    }
}
