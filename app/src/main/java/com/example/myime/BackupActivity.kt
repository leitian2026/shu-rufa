package com.example.myime

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.WindowManager
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.FileInputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 备份与还原页(应用首页的「备份与还原」进入)。
 * 备份只有两个去处:本地(存成一个加密压缩包到手机上任意位置)、坚果云(WebDAV 上传同样的加密压缩包)。
 * 还原也是两个:选本地压缩包,或点一下从坚果云下载最新的一份;都会自动解压还原。
 * 加密用的备份密码在构建时从环境变量 BACKUP_PASSWORD 读入(见 BackupVault.defaultPassword()),App 里不用设置、不用记。
 * 页面打开了防截屏。
 */
class BackupActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var result: TextView
    private lateinit var autoBox: CheckBox
    private lateinit var dictsBox: CheckBox
    private lateinit var syncBox: CheckBox
    private var busy = false

    private companion object {
        const val REQ_EXPORT = 1
        const val REQ_RESTORE_PICK = 2
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        title = "备份与还原"
        val pad = dpi(16f)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        setContentView(ScrollView(this).apply { addView(root) })

        root.addView(label("备份与还原", 22f, bold = true))
        root.addView(
            label(
                "把学习数据、固定词、各项设置打成一个加密压缩包(.mybk),可以备份到手机本地,也可以备份到坚果云。" +
                    "自动加密(AES-256-GCM),不用设密码、不用记。每次备份完会马上读回来解密校验,确认真的能还原。剪贴板历史和回收箱会一起备份(备份包是加密的)。",
                13f, alpha = 0.7f,
            ).apply { setPadding(0, pad / 2, 0, pad / 2) },
        )

        status = label("", 14f)
        root.addView(status)

        root.addView(section("1. 坚果云账号"))
        root.addView(
            label(
                "要先在坚果云网页端:账户信息 → 安全选项 → 第三方应用管理 → 添加应用,生成一个「应用密码」(不是登录密码)。" +
                    "账号填坚果云登录邮箱。备份会放在你指定的文件夹里(默认 myime,不存在会自动创建;" +
                    "如果提示不能创建,请先在坚果云里建好这个文件夹)。",
                13f, alpha = 0.7f,
            ),
        )
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(smallButton("设置坚果云账号").apply { setOnClickListener { askDav() } }, smallButtonParams())
        row.addView(smallButton("测试连接").apply { setOnClickListener { testDav() } }, smallButtonParams())
        row.addView(smallButton("清除").apply { setOnClickListener { BackupConfig.clearDav(this@BackupActivity); refresh() } }, smallButtonParams())
        root.addView(row)
        autoBox = CheckBox(this).apply {
            text = "每天自动备份到坚果云一次(仅在 Wi-Fi 下;键盘收起时检查,数据有变化才备份;不含词库包)"
            isChecked = BackupConfig.auto(this@BackupActivity)
            setOnCheckedChangeListener { _, v -> BackupConfig.setAuto(this@BackupActivity, v) }
        }
        root.addView(autoBox)

        root.addView(section("2. 备份"))
        dictsBox = CheckBox(this).apply { text = "手动备份时包含词库包(文件大很多,上传慢)" }
        root.addView(dictsBox)
        root.addView(smallButton("备份到本地…").apply { setOnClickListener { startLocalBackup() } }, smallButtonParams())
        root.addView(smallButton("备份到坚果云").apply { setOnClickListener { startCloudBackup() } }, smallButtonParams())
        root.addView(
            label(
                "本地:选一个位置存成单个加密压缩包(手机上任意文件夹都行)。坚果云:自动上传,云端只保留最近 7 份。",
                13f, alpha = 0.7f,
            ),
        )

        root.addView(section("3. 还原"))
        root.addView(smallButton("从本地压缩包还原…").apply { setOnClickListener { pickFile(REQ_RESTORE_PICK) } }, smallButtonParams())
        root.addView(smallButton("从坚果云还原(最新一份)").apply { setOnClickListener { confirmCloudRestore() } }, smallButtonParams())
        root.addView(
            label(
                "会用备份里的内容替换现在的数据和设置,完成后输入法会重启一次;还原前的数据会在应用内留一份。" +
                    "换手机时先设置好坚果云账号,再点「从坚果云还原」。",
                13f, alpha = 0.7f,
            ),
        )

        root.addView(section("4. 多台手机同步学习数据"))
        root.addView(
            label(
                "几台都是你自己的手机,可以共享选词记录(拼音、笔画)和联想词,打得越多越准。只同步这些;剪贴板、回收箱、设置、英文用词都不会同步。" +
                    "用上面的坚果云账号,每台手机只写自己的一个加密文件(自动加密,不用设密码)。要求几台手机装的是同一个构建,并且都设好了坚果云账号。" +
                    "键盘收起时检查:新学了 300 次以上且距上次上传满 1 天(或有新学的、超过 7 天)就上传;键盘弹出时每天拉取一次别的手机的更新。没有定时器,Wi-Fi 和流量都可以。" +
                    "在一台手机上清除学习数据只清这一台,不会清掉别的手机。",
                13f, alpha = 0.7f,
            ),
        )
        syncBox = CheckBox(this).apply {
            text = "自动同步学习数据"
            isChecked = LearnSync.enabled(this@BackupActivity)
            setOnCheckedChangeListener { _, v -> LearnSync.setEnabled(this@BackupActivity, v) }
        }
        root.addView(syncBox)
        root.addView(
            smallButton("立即同步学习数据").apply {
                setOnClickListener {
                    runBg("正在同步学习数据(第一次要多等几秒)…") {
                        val r = LearnSync.syncNow(this@BackupActivity)
                        ui.post { say(r.msg) }
                    }
                }
            },
            smallButtonParams(),
        )

        result = label("", 14f).apply { setPadding(0, pad, 0, 0) }
        root.addView(result)
        refresh()
    }

    // ------------------------------------------------------------ 界面小工具

    private fun label(text: String, sp: Float, bold: Boolean = false, alpha: Float = 1f): TextView =
        TextView(this).apply {
            this.text = text
            textSize = sp
            this.alpha = alpha
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        }

    private fun section(text: String): TextView = label(text, 17f, bold = true).apply { setPadding(0, dpi(18f), 0, dpi(4f)) }

    private fun fmt(t: Long): String =
        if (t <= 0L) "还没有成功过" else SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(t))

    private fun refresh() {
        val sb = StringBuilder()
        sb.append("备份内容:").append(BackupManager.scopeSummary(this)).append("(不含词库包和缓存)\n")
        sb.append("\n坚果云:")
        if (BackupConfig.davConfigured(this)) {
            sb.append(BackupConfig.davUser(this)).append(" · 文件夹 ").append(BackupConfig.davFolder(this))
        } else {
            sb.append("未设置账号")
        }
        sb.append("\n    上次成功:").append(fmt(BackupConfig.lastOk(this, BackupConfig.CLOUD)))
        val m = BackupConfig.lastMsg(this, BackupConfig.CLOUD)
        if (m.startsWith("失败")) sb.append("\n    最近一次").append(m)
        sb.append("\n本地:上次成功:").append(fmt(BackupConfig.lastOk(this, BackupConfig.LOCAL)))
        sb.append("\n学习数据同步:")
        if (!LearnSync.available()) {
            sb.append("不可用(这个构建没有内置备份密码)")
        } else {
            sb.append(if (LearnSync.enabled(this)) "已开启" else "已关闭")
            sb.append("\n    上次成功:").append(fmt(LearnSync.lastOk(this)))
            sb.append("\n    自上次上传新学:").append(LearnSync.pendingNew(this)).append(" 次")
            val lm = LearnSync.lastMsg(this)
            if (lm.startsWith("失败")) sb.append("\n    最近一次").append(lm)
        }
        status.text = sb.toString()
    }

    private fun say(msg: String) {
        result.text = msg
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun passwordField(hint: String): EditText = EditText(this).apply {
        this.hint = hint
        setSingleLine(true)
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
    }

    private fun runBg(startMsg: String, work: () -> Unit) {
        if (busy) {
            toast("正在处理中,请稍等")
            return
        }
        busy = true
        say(startMsg)
        Thread {
            try {
                work()
            } finally {
                ui.post { busy = false; refresh() }
            }
        }.start()
    }

    private fun pickFile(req: Int) {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
        try {
            startActivityForResult(i, req)
        } catch (e: Exception) {
            toast("这台手机没有文件选择器")
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) return
        when (requestCode) {
            REQ_EXPORT -> {
                val withDicts = dictsBox.isChecked
                runBg("正在备份并校验(第一次要多等几秒)…") {
                    val r = BackupManager.exportToUri(this, uri, withDicts)
                    ui.post { showResults("备份结果:", listOf(r)) }
                }
            }
            REQ_RESTORE_PICK -> runBg("正在解密并校验备份(要几秒钟)…") {
                val r = BackupManager.restore(this) { contentResolver.openInputStream(uri) }
                ui.post { finishRestore(r) }
            }
        }
    }

    // ------------------------------------------------------------ 坚果云账号

    private fun askDav() {
        val user = EditText(this).apply {
            hint = "坚果云账号(登录邮箱)"
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            setText(BackupConfig.davUser(this@BackupActivity))
        }
        val hasPw = BackupConfig.davPassword(this) != null
        val pw = passwordField(if (hasPw) "应用密码(留空表示不修改)" else "应用密码(第三方应用密码)")
        val folder = EditText(this).apply {
            hint = "备份文件夹(默认 ${BackupConfig.DEFAULT_FOLDER})"
            setSingleLine(true)
            setText(BackupConfig.davFolder(this@BackupActivity))
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpi(20f), dpi(8f), dpi(20f), 0)
            addView(user)
            addView(pw)
            addView(folder)
        }
        AlertDialog.Builder(this).setTitle("设置坚果云账号")
            .setMessage("密码用「应用密码」,不是坚果云的登录密码。保存后会自动测试连接。")
            .setView(box)
            .setPositiveButton("保存") { _, _ ->
                val u = user.text.toString().trim()
                val p = pw.text.toString()
                val f = folder.text.toString().trim().ifEmpty { BackupConfig.DEFAULT_FOLDER }
                when {
                    u.isEmpty() -> toast("没有填账号")
                    p.isEmpty() && !hasPw -> toast("没有填应用密码")
                    WebDavClient.splitFolder(f).isEmpty() -> toast("文件夹名不对")
                    else -> {
                        BackupConfig.setDav(this, BackupConfig.DEFAULT_DAV_URL, u, f, p.ifEmpty { null })
                        refresh()
                        testDav()
                    }
                }
            }
            .setNegativeButton("取消", null).show()
    }

    private fun testDav() {
        if (!BackupConfig.davConfigured(this)) {
            toast("请先设置坚果云账号")
            return
        }
        runBg("正在连接坚果云…") {
            val r = BackupManager.testCloud(this)
            ui.post { say((if (r.ok) "" else "坚果云") + r.msg) }
        }
    }

    // ------------------------------------------------------------ 备份

    private fun showResults(title: String, rs: List<BackupManager.DestResult>) {
        val sb = StringBuilder(title)
        for (r in rs) {
            sb.append('\n')
            if (r.which.isNotEmpty()) sb.append(BackupManager.label(r.which)).append(":")
            sb.append(r.msg)
        }
        say(sb.toString())
    }

    private fun startLocalBackup() {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
            .setType("application/octet-stream").putExtra(Intent.EXTRA_TITLE, BackupManager.PREFIX + stamp + BackupManager.EXT)
        try {
            startActivityForResult(i, REQ_EXPORT)
        } catch (e: Exception) {
            toast("这台手机没有文件选择器")
        }
    }

    private fun startCloudBackup() {
        if (!BackupConfig.davConfigured(this)) {
            toast("请先设置坚果云账号")
            return
        }
        val withDicts = dictsBox.isChecked
        runBg("正在备份并上传到坚果云(第一次要多等几秒)…") {
            val r = BackupManager.backupToCloud(this, withDicts)
            ui.post { showResults("备份结果:", listOf(r)) }
        }
    }

    // ------------------------------------------------------------ 还原

    private fun confirmCloudRestore() {
        if (!BackupConfig.davConfigured(this)) {
            toast("请先设置坚果云账号")
            return
        }
        AlertDialog.Builder(this).setTitle("从坚果云还原")
            .setMessage("会下载坚果云上最新的一份备份,并用它替换现在的数据和设置。还原前的数据会在应用内留一份。继续吗?")
            .setPositiveButton("还原") { _, _ ->
                runBg("正在从坚果云下载…") {
                    try {
                        val (name, file) = BackupManager.downloadLatest(this, BackupManager.PREFIX, BackupManager.EXT)
                        ui.post { say("已下载 $name,正在解密并校验…") }
                        val r = BackupManager.restore(this) { FileInputStream(file) }
                        BackupManager.clearTemp(this)
                        ui.post { finishRestore(r) }
                    } catch (e: Exception) {
                        BackupManager.clearTemp(this)
                        ui.post { say("从坚果云下载失败:" + (e.message ?: e.javaClass.simpleName)) }
                    }
                }
            }
            .setNegativeButton("取消", null).show()
    }

    private fun finishRestore(r: BackupManager.RestoreResult) {
        say(r.msg)
        if (r.ok) {
            AlertDialog.Builder(this).setTitle("还原完成").setMessage(r.msg + "\n\n点「确定」重启输入法进程使其生效。")
                .setCancelable(false)
                .setPositiveButton("确定") { _, _ -> android.os.Process.killProcess(android.os.Process.myPid()) }
                .show()
        }
    }
}
