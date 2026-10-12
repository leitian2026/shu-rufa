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
}
