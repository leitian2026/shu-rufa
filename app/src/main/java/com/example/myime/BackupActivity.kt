package com.example.myime

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.io.FileInputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 备份与还原页(应用首页的「备份与还原」进入)。
 * 备份只有两个去处:本地(存成一个加密压缩包到手机上任意位置)、坚果云(WebDAV 上传同样的加密压缩包)。
 * 还原也是两个:选本地压缩包,或从坚果云选一台设备的备份(本机预选);都会自动解压还原。
 * 几台设备(手机、平板)可以共用同一个坚果云文件夹:备份文件名里带设备名,各管各的,清理旧备份也只清自己的。
 * 加密用的备份密码在构建时从环境变量 BACKUP_PASSWORD 读入(见 BackupVault.defaultPassword()),App 里不用设置、不用记。
 * 页面打开了防截屏。
 */
class BackupActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var result: TextView
    private lateinit var autoBox: Switch
    private lateinit var dictsBox: Switch
    private lateinit var syncBox: Switch
    private var busy = false

    private companion object {
        const val REQ_EXPORT = 1
        const val REQ_RESTORE_PICK = 2
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        title = "备份与还原"
        applyPageBackground()
        val pal = Palette(this)
        val pad = dpi(16f)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, dpi(28f), pad, dpi(36f))
        }
        setContentView(pageScroll(root))

        root.addView(label("备份与还原", 28f, bold = true))
        root.addView(
            foldNote(
                "把学习数据、固定词、各项设置打成一个加密压缩包(.mybk),可以备份到手机本地,也可以备份到坚果云。" +
                    "自动加密(AES-256-GCM),不用设密码、不用记。每次备份完会马上读回来解密校验,确认真的能还原。剪贴板历史和回收箱会一起备份(备份包是加密的)。",
            ).apply { setPadding(0, dpi(4f), 0, 0) },
        )

        // ---- 当前状态
        root.addView(sectionHeader("当前状态"))
        status = label("", 14f).apply { setLineSpacing(0f, 1.25f) }
        val statusCard = settingsCard().apply { setPadding(dpi(16f), dpi(14f), dpi(16f), dpi(14f)) }
        statusCard.addView(status)
        root.addView(statusCard, cardParams())

        // ---- 1. 坚果云账号
        root.addView(sectionHeader("1. 坚果云账号"))
        val acct = settingsCard()
        acct.addView(
            foldNote(
                "要先在坚果云网页端:账户信息 → 安全选项 → 第三方应用管理 → 添加应用,生成一个「应用密码」(不是登录密码)。" +
                    "账号填坚果云登录邮箱。备份会放在你指定的文件夹里(默认 myime,不存在会自动创建;" +
                    "如果提示不能创建,请先在坚果云里建好这个文件夹)。" +
                    "几台设备(手机、平板)可以填同一个文件夹,备份文件名里会带各自的设备名,互不干扰。",
            ),
        )
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(primaryButton("设置坚果云账号").apply { setOnClickListener { askDav() } }, smallButtonParams())
        row.addView(smallButton("测试连接").apply { setOnClickListener { testDav() } }, smallButtonParams())
        row.addView(smallButton("清除").apply { setOnClickListener { BackupConfig.clearDav(this@BackupActivity); refresh() } }, smallButtonParams())
        acct.addView(row)
        acct.addView(rowDivider())
        autoBox = styledSwitch(BackupConfig.auto(this)) { v -> BackupConfig.setAuto(this, v) }
        acct.addView(switchRowFor("每天自动备份到坚果云", "仅在 Wi-Fi 下;键盘收起时检查,数据有变化才备份;不含词库包", autoBox))
        root.addView(acct, cardParams())

        // ---- 2. 备份
        root.addView(sectionHeader("2. 备份"))
        val bak = settingsCard()
        dictsBox = styledSwitch(false) { }
        bak.addView(switchRowFor("手动备份时包含词库包", "文件大很多,上传慢", dictsBox))
        bak.addView(rowDivider())
        bak.addView(settingsRow("备份到本地…", "存成一个加密压缩包,位置自己选") { startLocalBackup() })
        bak.addView(rowDivider())
        bak.addView(settingsRow("备份到坚果云", "自动上传,云端每台设备各保留最近 7 份") { startCloudBackup() })
        bak.addView(
            foldNote(
                "本地:选一个位置存成单个加密压缩包(手机上任意文件夹都行)。坚果云:自动上传,云端每台设备各保留最近 7 份。",
            ),
        )
        root.addView(bak, cardParams())

        // ---- 3. 还原
        root.addView(sectionHeader("3. 还原"))
        val res = settingsCard()
        res.addView(settingsRow("从本地压缩包还原…", "选一个 .mybk 备份文件") { pickFile(REQ_RESTORE_PICK) })
        res.addView(rowDivider())
        res.addView(settingsRow("从坚果云还原…", "在列表里选一台设备的备份") { startCloudRestore() })
        res.addView(
            foldNote(
                "会用备份里的内容替换现在的数据和设置,完成后输入法会重启一次;还原前的数据会在应用内留一份。" +
                    "换手机时先设置好坚果云账号,再点「从坚果云还原」,在列表里选旧手机的备份(列表里本机的排第一并预选;新手机没有本机备份,要自己点选)。",
            ),
        )
        root.addView(res, cardParams())

        // ---- 4. 多台手机同步学习数据
        root.addView(sectionHeader("4. 多台手机同步学习数据"))
        val sync = settingsCard()
        sync.addView(
            foldNote(
                "几台都是你自己的手机,可以共享选词记录(拼音、笔画)和联想词,打得越多越准。只同步这些;剪贴板、回收箱、设置、英文用词都不会同步。" +
                    "用上面的坚果云账号,每台手机只写自己的一个加密文件(自动加密,不用设密码)。要求几台手机装的是同一个构建,并且都设好了坚果云账号。" +
                    "键盘收起时检查:新学了 300 次以上且距上次上传满 1 天(或有新学的、超过 7 天)就上传;键盘弹出时每天拉取一次别的手机的更新。没有定时器,Wi-Fi 和流量都可以。" +
                    "在一台手机上清除学习数据只清这一台,不会清掉别的手机。",
            ),
        )
        syncBox = styledSwitch(LearnSync.enabled(this)) { v -> LearnSync.setEnabled(this, v) }
        sync.addView(switchRowFor("自动同步学习数据", "键盘收起 / 弹出时自动检查,Wi-Fi 和流量都可以", syncBox))
        sync.addView(rowDivider())
        sync.addView(settingsRow("立即同步学习数据", null) {
            runBg("正在同步学习数据(第一次要多等几秒)…") {
                val r = LearnSync.syncNow(this@BackupActivity)
                ui.post { say(r.msg) }
            }
        })
        root.addView(sync, cardParams())

        // ---- 操作结果(有内容时才显示)
        result = label("", 14f).apply {
            background = GradientDrawable().apply {
                cornerRadius = dpf(16f)
                setColor(pal.topBar)
            }
            setPadding(dpi(16f), dpi(14f), dpi(16f), dpi(14f))
            visibility = View.GONE
        }
        root.addView(
            result,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dpi(16f) },
        )
        refresh()
    }

    // ------------------------------------------------------------ 界面小工具

    private fun label(text: String, sp: Float, bold: Boolean = false, alpha: Float = 1f): TextView =
        TextView(this).apply {
            this.text = text
            textSize = sp
            this.alpha = alpha
            setTextColor(Palette(this@BackupActivity).text)
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        }

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
        sb.append("\n    本机设备名:").append(BackupNames.clean(BackupManager.deviceName(this)))
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
        result.visibility = if (msg.isBlank()) View.GONE else View.VISIBLE
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
        val devName = EditText(this).apply {
            hint = "本机设备名(留空用系统设备名称:${BackupManager.systemDeviceName(this@BackupActivity)})"
            setSingleLine(true)
            setText(BackupConfig.deviceNameCustom(this@BackupActivity))
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpi(20f), dpi(8f), dpi(20f), 0)
            addView(user)
            addView(pw)
            addView(folder)
            addView(devName)
        }
        AlertDialog.Builder(this).setTitle("设置坚果云账号")
            .setMessage("密码用「应用密码」,不是坚果云的登录密码。几台设备可以填同一个文件夹,用设备名区分各自的备份。保存后会自动测试连接。")
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
                        BackupConfig.setDeviceName(this, devName.text.toString())
                        refresh()
                        testDav()
                    }
                }
            }
            .setNegativeButton("取消", null).showPage()
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
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
            .setType("application/octet-stream").putExtra(Intent.EXTRA_TITLE, BackupManager.backupFileName(this))
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

    /** 点「从坚果云还原…」:只发一次列目录请求,弹出每台设备最新备份的列表(本机排第一并预选)。 */
    private fun startCloudRestore() {
        if (!BackupConfig.davConfigured(this)) {
            toast("请先设置坚果云账号")
            return
        }
        runBg("正在读取坚果云上的备份列表…") {
            try {
                val groups = BackupManager.listCloudBackups(this)
                ui.post { say(""); chooseCloudBackup(groups) }
            } catch (e: Exception) {
                ui.post { say("读取坚果云备份列表失败:" + (e.message ?: e.javaClass.simpleName)) }
            }
        }
    }

    private fun sizeText(bytes: Long): String =
        if (bytes >= 1024 * 1024) "%.1f MB".format(Locale.US, bytes / 1048576.0) else "${(bytes + 1023) / 1024} KB"

    private fun chooseCloudBackup(groups: List<BackupNames.Group>) {
        val items = groups.map { g ->
            g.label + (if (g.local) "(本机)" else "") + "\n" + BackupNames.stampText(g.stamp) + " · " + sizeText(g.size)
        }.toTypedArray()
        var sel = if (groups[0].local) 0 else -1   // 没有本机的备份(新手机)时不预选,免得顺手点成别的设备的
        val dlg = AlertDialog.Builder(this).setTitle("还原哪台设备的备份?")
            .setSingleChoiceItems(items, sel) { d, which ->
                sel = which
                (d as AlertDialog).getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
            }
            .setPositiveButton("还原") { _, _ ->
                val g = groups.getOrNull(sel)
                if (g != null) confirmRestoreFrom(g)
            }
            .setNegativeButton("取消", null).create()
        dlg.stylePage().show()
        dlg.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = sel >= 0
    }

    /** 本机的备份:选好点「还原」后直接还原,不再多问;别的设备 / 旧版的备份会多确认一次。 */
    private fun confirmRestoreFrom(g: BackupNames.Group) {
        if (g.local) {
            restoreFromCloud(g)
            return
        }
        val who = if (g.tag == null) "旧版备份(不知道是哪台设备的)" else "「${g.label}」的备份"
        AlertDialog.Builder(this).setTitle("还原别的设备的备份?")
            .setMessage(
                "你选的是$who(${BackupNames.stampText(g.stamp)})。\n\n" +
                    "会用它替换现在的数据和设置,键盘大小、悬浮键盘位置等设置也会变成那台设备的。还原前的数据会在应用内留一份。继续吗?",
            )
            .setPositiveButton("还原") { _, _ -> restoreFromCloud(g) }
            .setNegativeButton("取消", null).showPage()
    }

    private fun restoreFromCloud(g: BackupNames.Group) {
        runBg("正在从坚果云下载…") {
            try {
                val file = BackupManager.downloadBackup(this, g)
                ui.post { say("已下载 ${g.file},正在解密并校验…") }
                val r = BackupManager.restore(this) { FileInputStream(file) }
                BackupManager.clearTemp(this)
                ui.post { finishRestore(r) }
            } catch (e: Exception) {
                BackupManager.clearTemp(this)
                ui.post { say("从坚果云下载失败:" + (e.message ?: e.javaClass.simpleName)) }
            }
        }
    }

    private fun finishRestore(r: BackupManager.RestoreResult) {
        say(r.msg)
        if (r.ok) {
            AlertDialog.Builder(this).setTitle("还原完成").setMessage(r.msg + "\n\n点「确定」重启输入法进程使其生效。")
                .setCancelable(false)
                .setPositiveButton("确定") { _, _ -> android.os.Process.killProcess(android.os.Process.myPid()) }
                .showPage()
        }
    }
}
