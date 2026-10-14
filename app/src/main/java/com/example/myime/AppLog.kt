package com.example.myime

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * 应用内日志:既写系统日志(logcat),也追加到本机缓存目录里的一个小文件,给「调试日志」页看。
 *  - d():只在调用方已经判断过「调试日志开关打开」时才会被调到(见 MyImeService.diag)。
 *  - w():出错 / 词库加载失败这类很少发生的事,不管开关都记。
 * 文件在 cacheDir 里:不进备份、不上传;超过约 200 KB 就丢掉前一半旧的。写文件在单独的低优先级线程里,不挡打字。
 * 只记耗时、数量、出错原因,不记输入的内容。
 */
object AppLog {
    private const val FILE = "debuglog.txt"
    private const val MAX_BYTES = 200 * 1024L

    private val lock = Any()

    @Volatile
    private var logFile: File? = null
    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "myime-log").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
        }
    }
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)   // 只在 io 线程里用

    fun init(ctx: Context) {
        if (logFile == null) logFile = fileOf(ctx)
    }

    fun fileOf(ctx: Context): File = File(ctx.applicationContext.cacheDir, FILE)

    fun d(tag: String, msg: String) {
        Log.d(tag, msg)
        add('D', tag, msg)
    }

    fun w(tag: String, msg: String, t: Throwable? = null) {
        if (t != null) Log.w(tag, msg, t) else Log.w(tag, msg)
        val why = if (t == null) "" else ": " + t.javaClass.simpleName + " " + (t.message ?: "").lineSequence().first().take(160)
        add('W', tag, msg + why)
    }

    private fun add(level: Char, tag: String, msg: String) {
        val f = logFile ?: return
        val now = System.currentTimeMillis()
        try {
            io.execute { write(f, fmt.format(Date(now)) + " " + level + "/" + tag + " " + msg + "\n") }
        } catch (e: Exception) {
            // 记不下来不影响打字
        }
    }

    private fun write(f: File, line: String) {
        synchronized(lock) {
            try {
                if (f.length() > MAX_BYTES) trim(f)
                FileOutputStream(f, true).use { it.write(line.toByteArray(Charsets.UTF_8)) }
            } catch (e: Exception) {
                // 写不进去就算了
            }
        }
    }

    /** 丢掉前一半,从下一行开头接着留。 */
    private fun trim(f: File) {
        val all = f.readBytes()
        var from = all.size / 2
        val nl = 10.toByte()
        while (from < all.size && all[from] != nl) from++
        from++
        f.writeBytes(if (from < all.size) all.copyOfRange(from, all.size) else ByteArray(0))
    }

    fun read(ctx: Context): String = synchronized(lock) {
        try {
            val f = fileOf(ctx)
            if (f.isFile) f.readText(Charsets.UTF_8) else ""
        } catch (e: Exception) {
            ""
        }
    }

    fun clear(ctx: Context) {
        synchronized(lock) {
            try {
                fileOf(ctx).delete()
            } catch (e: Exception) {
                // 删不掉就算了
            }
        }
    }
}
