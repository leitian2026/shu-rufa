package com.example.myime

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 调试日志页:看键盘记下的耗时 / 出错信息,可以导出成文件、清空。开关在应用首页。 */
class LogActivity : Activity() {
    private companion object {
        const val REQ_EXPORT = 1
    }

    private lateinit var info: TextView
    private lateinit var body: TextView
    private lateinit var scroll: ScrollView
    private var full = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "调试日志"
        applyPageBackground()
        val pad = dpi(16f)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        root.addView(TextView(this).apply {
            text = "调试日志"
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
        })
        info = TextView(this).apply {
            textSize = 13f
            alpha = 0.7f
            setPadding(0, pad / 2, 0, pad / 2)
        }
        root.addView(info)
        root.addView(foldNote(
            "这里只有键盘记下的耗时、数量和出错原因,不含你输入的内容。记录存在本机缓存目录的一个小文件里" +
                "(超过约 200 KB 会丢掉旧的一半),不上传、不进备份。打开开关在应用首页的「调试日志」;" +
                "词库加载失败这类出错信息不管开关都会记。系统清理缓存时这个文件也会被清掉。",
        ))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(smallButton("刷新").apply { setOnClickListener { load() } }, smallButtonParams())
        row.addView(smallButton("导出文件").apply {
            setOnClickListener {
                if (full.isEmpty()) {
                    Toast.makeText(this@LogActivity, "还没有记录", Toast.LENGTH_SHORT).show()
                } else {
                    val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                    startActivityForResult(
                        Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                            addCategory(Intent.CATEGORY_OPENABLE)
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TITLE, "myime-log-$stamp.txt")
                        },
                        REQ_EXPORT,
                    )
                }
            }
        }, smallButtonParams())
        row.addView(smallButton("清空").apply {
            setOnClickListener {
                AppLog.clear(applicationContext)
                load()
                Toast.makeText(this@LogActivity, "已清空", Toast.LENGTH_SHORT).show()
            }
        }, smallButtonParams())
        root.addView(row)
        body = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextIsSelectable(true)
            setPadding(0, pad / 2, 0, 0)
        }
        root.addView(body)
        scroll = ScrollView(this).apply { addView(root) }
        setContentView(scroll)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_EXPORT || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val app = applicationContext
        Thread {
            val msg = try {
                val bytes = AppLog.read(app).toByteArray(Charsets.UTF_8)
                val out = app.contentResolver.openOutputStream(uri, "wt") ?: throw java.io.IOException("打不开目标文件")
                out.use { it.write(bytes) }
                "已导出(" + (bytes.size / 1024) + " KB)"
            } catch (e: Exception) {
                "导出失败:" + (e.message ?: e.javaClass.simpleName).lineSequence().first().take(80)
            }
            runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }
        }.start()
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    private fun load() {
        val app = applicationContext
        Thread {
            val t = AppLog.read(app)
            runOnUiThread { show(t) }
        }.start()
    }

    private fun show(t: String) {
        full = t
        val on = DictConfig.debugLog(this)
        val lines = if (t.isEmpty()) 0 else t.count { it == '\n' }
        info.text = "调试日志开关:" + (if (on) "已开启" else "已关闭") + " · 共 $lines 行 · " + (t.length / 1024) + " KB"
        if (t.isEmpty()) {
            body.text = "还没有记录。先在应用首页打开「调试日志」开关,再回到输入框用一会儿键盘,然后回来点「刷新」。"
            return
        }
        val max = 60_000
        body.text = if (t.length > max) "…(只显示最后一部分,「导出文件」能导出完整内容)\n" + t.takeLast(max) else t
        scroll.post { scroll.scrollTo(0, scroll.getChildAt(0).height) }   // 最新的在最下面
    }
}
