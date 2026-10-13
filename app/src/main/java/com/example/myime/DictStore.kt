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

    val ALL_FILES = listOf(PINYIN_FILE, STROKE_FILE, ENGLISH_FILE, ENGLISH_BIGRAM_FILE)

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
     * 笔画:第 1 行笔画字表(Rime stroke.dict.yaml 格式),第 2 行(可选)字频表(Rime 格式)。
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

    /** 记录并使用我的用词(词频学习)。 */
    fun learn(ctx: Context): Boolean = p(ctx).getBoolean("learn", true)

    fun setLearn(ctx: Context, v: Boolean) {
        p(ctx).edit().putBoolean("learn", v).apply()
    }

    /** 学习系数:越大,选过的词越容易排到前面(0 = 只按词频)。默认 6,范围 0~20。 */
    fun learnCoef(ctx: Context): Double =
        p(ctx).getFloat("learn_coef", LEARN_COEF_DEFAULT.toFloat()).toDouble().coerceIn(0.0, LEARN_COEF_MAX)

    fun setLearnCoef(ctx: Context, v: Double) {
        p(ctx).edit().putFloat("learn_coef", v.coerceIn(0.0, LEARN_COEF_MAX).toFloat()).apply()
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
