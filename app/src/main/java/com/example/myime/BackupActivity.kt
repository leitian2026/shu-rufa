package com.example.myime

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.text.InputType
import android.view.WindowManager
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 备份与还原页(应用首页的「备份与还原」进入)。
 * 备份位置有两个:本地任意文件夹、网盘文件夹;备份文件都是加密的,密码只有你知道。
 * 页面打开了防截屏(密码、密钥备注不会被截图 / 最近任务缩略图带走)。
 */
class BackupActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var result: TextView
    private lateinit var autoBox: CheckBox
    private lateinit var dictsBox: CheckBox
    private var busy = false
    private var pendingKey: BackupManager.SigningKey? = null

    private companion object {
        const val REQ_TREE_LOCAL = 1
        const val REQ_TREE_CLOUD = 2
        const val REQ_RESTORE_PICK = 3
        const val REQ_KEY_PICK = 4
        const val REQ_KEY_RESTORE_PICK = 5
        const val REQ_KEY_SAVE = 6
        const val REQ_EXPORT = 7
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

        root.addView(label("备份与还原(加密)", 22f, bold = true))
        root.addView(
            label(
                "把学习数据、固定词、各项设置打成一个加密文件,同时存到两个地方:本地文件夹和网盘文件夹。" +
                    "文件用密码加密(AES-256-GCM,密码经 60 万次迭代加固),没有密码,拿到文件的人也解不开。" +
                    "每次备份完会马上读回来解密校验,确认真的能还原。剪贴板不备份(它只在内存里)。",
                13f, alpha = 0.7f,
            ).apply { setPadding(0, pad / 2, 0, pad / 2) },
        )

        status = label("", 14f)
        root.addView(status)

        root.addView(section("1. 备份密码"))
        root.addView(label("密码忘了,备份就永远打不开,没有任何办法找回(也没有\"忘记密码\")。请写在纸上或存进密码管理器。更换密码后,以前的备份仍然要用旧密码。", 13f, alpha = 0.7f))
        root.addView(smallButton("设置 / 更换备份密码").apply { setOnClickListener { askNewPassword() } }, smallButtonParams())

        root.addView(section("2. 备份位置"))
        root.addView(
            label(
                "点「选择」会打开系统的文件夹选择器。本地:选手机上任意文件夹。网盘:在选择器左上角菜单里选 Google Drive、OneDrive、Dropbox 等" +
                    "(要先装对应的 App),或者选你的网盘 App 的本地同步文件夹。备份文件只有加密后的内容,放在哪里都不泄露数据。",
                13f, alpha = 0.7f,
            ),
        )
        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row1.addView(smallButton("选择本地文件夹").apply { setOnClickListener { pickTree(REQ_TREE_LOCAL) } }, smallButtonParams())
        row1.addView(smallButton("清除").apply { setOnClickListener { BackupConfig.setTreeUri(this@BackupActivity, BackupConfig.LOCAL, null); refresh() } }, smallButtonParams())
        root.addView(row1)
        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row2.addView(smallButton("选择网盘文件夹").apply { setOnClickListener { pickTree(REQ_TREE_CLOUD) } }, smallButtonParams())
        row2.addView(smallButton("清除").apply { setOnClickListener { BackupConfig.setTreeUri(this@BackupActivity, BackupConfig.CLOUD, null); refresh() } }, smallButtonParams())
        root.addView(row2)

        autoBox = CheckBox(this).apply {
            text = "每天自动备份一次(键盘收起时检查,数据有变化才备份;不含词库包)"
            isChecked = BackupConfig.auto(this@BackupActivity)
            setOnCheckedChangeListener { _, v -> BackupConfig.setAuto(this@BackupActivity, v) }
        }
        root.addView(autoBox)

        root.addView(section("3. 备份和还原"))
        dictsBox = CheckBox(this).apply { text = "手动备份时包含词库包(文件大很多,上传慢)" }
        root.addView(dictsBox)
        root.addView(smallButton("立即备份到上面的位置").apply { setOnClickListener { startBackup() } }, smallButtonParams())
        root.addView(smallButton("导出备份到文件…").apply { setOnClickListener { startExport() } }, smallButtonParams())
        root.addView(smallButton("导入备份文件(还原)…").apply { setOnClickListener { pickFile(REQ_RESTORE_PICK) } }, smallButtonParams())
        root.addView(
            label(
                "导出:自己挑一个位置存成单个加密文件(不用先选上面的位置)。导入:选一个备份文件、输入密码,用里面的内容替换现在的数据和设置,完成后输入法会重启一次;" +
                    "导入前现有数据会在应用内留一份。以后新增功能的数据只要放在应用数据或设置里,就会自动包含在备份里。",
                13f, alpha = 0.7f,
            ),
        )

        root.addView(section("4. 签名密钥"))
        root.addView(
            label(
                "安装包的签名密钥(.jks)丢了,以后就不能覆盖安装新版本,只能卸载重装,而卸载会清掉所有数据。" +
                    "选中你的密钥文件,加密后同样存到上面两个位置。备注里可以写别名和密码,会和密钥一起加密。",
                13f, alpha = 0.7f,
            ),
        )
        root.addView(smallButton("备份签名密钥…").apply { setOnClickListener { pickFile(REQ_KEY_PICK) } }, smallButtonParams())
        root.addView(smallButton("还原签名密钥…").apply { setOnClickListener { pickFile(REQ_KEY_RESTORE_PICK) } }, smallButtonParams())

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

    private fun treeText(which: String): String {
        val uri = BackupConfig.treeUri(this, which) ?: return "未选择"
        val id = try {
            DocumentsContract.getTreeDocumentId(uri)
        } catch (e: Exception) {
            uri.toString()
        }
        return "${uri.authority} · $id"
    }

    private fun refresh() {
        val sb = StringBuilder()
        sb.append("备份密码:").append(if (BackupVault.isSet(this)) "已设置" else "未设置(先设置才能备份)").append('\n')
        sb.append("备份内容:").append(BackupManager.scopeSummary(this)).append("(不含词库包和缓存)\n")
        for (w in listOf(BackupConfig.LOCAL, BackupConfig.CLOUD)) {
            sb.append('\n').append(BackupManager.label(w)).append(":").append(treeText(w))
            if (BackupConfig.treeUri(this, w) != null) {
                sb.append("\n    上次成功:").append(fmt(BackupConfig.lastOk(this, w)))
                val m = BackupConfig.lastMsg(this, w)
                if (m.startsWith("失败")) sb.append("\n    最近一次").append(m)
            }
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

    private fun askPassword(title: String, msg: String, onOk: (CharArray) -> Unit) {
        val f = passwordField("备份密码")
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpi(20f), dpi(8f), dpi(20f), 0)
            addView(f)
        }
        AlertDialog.Builder(this).setTitle(title).setMessage(msg).setView(box)
            .setPositiveButton("确定") { _, _ ->
                val p = f.text.toString()
                if (p.isEmpty()) toast("没有输入密码") else onOk(p.toCharArray())
            }
            .setNegativeButton("取消", null).show()
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

    // ------------------------------------------------------------ 备份密码

    private fun askNewPassword() {
        val p1 = passwordField("新密码(至少 10 个字符)")
        val p2 = passwordField("再输入一次")
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpi(20f), dpi(8f), dpi(20f), 0)
            addView(p1)
            addView(p2)
        }
        AlertDialog.Builder(this).setTitle("设置备份密码")
            .setMessage("建议用 4 个以上互不相关的词或一句话,不要用生日、手机号。密码忘了无法找回备份。")
            .setView(box)
            .setPositiveButton("确定") { _, _ ->
                val a = p1.text.toString()
                val b = p2.text.toString()
                when {
                    a != b -> toast("两次输入不一样")
                    a.codePointCount(0, a.length) < 10 -> toast("密码太短,至少 10 个字符")
                    a.toSet().size < 6 -> toast("密码太简单,请换一个")
                    else -> {
                        val pass = a.toCharArray()
                        runBg("正在生成密钥(要几秒钟)…") {
                            try {
                                BackupVault.setPassword(this, pass)
                                ui.post { say("备份密码已设置。请把它记在安全的地方。") }
                            } catch (e: Exception) {
                                ui.post { say("设置失败:" + (e.message ?: e.javaClass.simpleName) + "\n这台手机的安全存储可能不可用。") }
                            }
                        }
                    }
                }
            }
            .setNegativeButton("取消", null).show()
    }

    // ------------------------------------------------------------ 选择位置 / 文件

    private fun pickTree(req: Int) {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION,
        )
        try {
            startActivityForResult(i, req)
        } catch (e: Exception) {
            toast("这台手机没有文件夹选择器")
        }
    }

    private fun pickFile(req: Int) {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
        try {
            startActivityForResult(i, req)
        } catch (e: Exception) {
            toast("这台手机没有文件选择器")
        }
    }

    private fun displayName(uri: Uri): String {
        try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) return c.getString(0) ?: "myime.jks"
            }
        } catch (e: Exception) {
            // 取不到名字就用默认的
        }
        return "myime.jks"
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) return
        when (requestCode) {
            REQ_TREE_LOCAL, REQ_TREE_CLOUD -> {
                try {
                    contentResolver.takePersistableUriPermission(
                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                    )
                } catch (e: Exception) {
                    toast("没能保存这个文件夹的长期访问权限,自动备份可能会失败")
                }
                BackupConfig.setTreeUri(this, if (requestCode == REQ_TREE_LOCAL) BackupConfig.LOCAL else BackupConfig.CLOUD, uri)
                BackupConfig.setNextAutoAt(this, 0L)
                refresh()
            }
            REQ_RESTORE_PICK -> askPassword("还原备份", "输入做这份备份时设的密码。密码不对不会改动任何现有数据。") { pass ->
                runBg("正在解密并校验备份(要几秒钟)…") {
                    val r = BackupManager.restore(this, uri, pass)
                    ui.post {
                        say(r.msg)
                        if (r.ok) {
                            AlertDialog.Builder(this).setTitle("还原完成").setMessage(r.msg + "\n\n点「确定」重启输入法进程使其生效。")
                                .setCancelable(false)
                                .setPositiveButton("确定") { _, _ -> android.os.Process.killProcess(android.os.Process.myPid()) }
                                .show()
                        }
                    }
                }
            }
            REQ_EXPORT -> {
                val withDicts = dictsBox.isChecked
                runBg("正在导出并校验…") {
                    val r = BackupManager.exportToUri(this, uri, withDicts)
                    ui.post { showResults("导出结果:", listOf(r)) }
                }
            }
            REQ_KEY_PICK -> askKeyNote(uri)
            REQ_KEY_RESTORE_PICK -> askPassword("还原签名密钥", "输入做备份时设的密码。") { pass ->
                runBg("正在解密(要几秒钟)…") {
                    try {
                        val k = BackupManager.readSigningKey(this, uri, pass)
                        pendingKey = k
                        ui.post {
                            say("解密成功,请选择保存位置。")
                            val i = Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                                .setType("application/octet-stream").putExtra(Intent.EXTRA_TITLE, k.fileName)
                            try {
                                startActivityForResult(i, REQ_KEY_SAVE)
                            } catch (e: Exception) {
                                say("这台手机没有文件选择器,无法保存")
                            }
                        }
                    } catch (e: BackupCrypto.BadPasswordException) {
                        ui.post { say("密码不对,或者备份文件已损坏。") }
                    } catch (e: Exception) {
                        ui.post { say("还原失败:" + (e.message ?: e.javaClass.simpleName)) }
                    }
                }
            }
            REQ_KEY_SAVE -> {
                val k = pendingKey ?: return
                pendingKey = null
                try {
                    (contentResolver.openOutputStream(uri, "w") ?: throw java.io.IOException("无法写入")).use { it.write(k.data) }
                    val note = if (k.note.isBlank()) "(没有备注)" else k.note
                    say("签名密钥已保存。")
                    AlertDialog.Builder(this).setTitle("签名密钥已保存").setMessage("备份时写的备注:\n\n$note")
                        .setPositiveButton("确定", null).show()
                } catch (e: Exception) {
                    say("保存失败:" + (e.message ?: e.javaClass.simpleName))
                }
            }
        }
    }

    // ------------------------------------------------------------ 备份 / 签名密钥

    private fun showResults(title: String, rs: List<BackupManager.DestResult>) {
        val sb = StringBuilder(title)
        for (r in rs) {
            sb.append('\n')
            if (r.which.isNotEmpty()) sb.append(BackupManager.label(r.which)).append(":")
            sb.append(r.msg)
        }
        say(sb.toString())
    }

    private fun startBackup() {
        if (!BackupVault.isSet(this)) {
            toast("请先设置备份密码")
            return
        }
        if (BackupConfig.treeUri(this, BackupConfig.LOCAL) == null && BackupConfig.treeUri(this, BackupConfig.CLOUD) == null) {
            toast("请先选择至少一个备份位置")
            return
        }
        val withDicts = dictsBox.isChecked
        runBg("正在备份并校验…") {
            val rs = BackupManager.backupNow(this, withDicts)
            ui.post { showResults("备份结果:", rs) }
        }
    }

    private fun startExport() {
        if (!BackupVault.isSet(this)) {
            toast("请先设置备份密码")
            return
        }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
            .setType("application/octet-stream").putExtra(Intent.EXTRA_TITLE, BackupManager.PREFIX + stamp + BackupManager.EXT)
        try {
            startActivityForResult(i, REQ_EXPORT)
        } catch (e: Exception) {
            toast("这台手机没有文件选择器")
        }
    }

    private fun askKeyNote(uri: Uri) {
        if (!BackupVault.isSet(this)) {
            toast("请先设置备份密码")
            return
        }
        val name = displayName(uri)
        val note = EditText(this).apply {
            hint = "备注(可空):别名、密钥密码等"
            minLines = 3
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpi(20f), dpi(8f), dpi(20f), 0)
            addView(note)
        }
        AlertDialog.Builder(this).setTitle("备份签名密钥").setMessage("文件:$name\n备注会和密钥一起加密保存。").setView(box)
            .setPositiveButton("备份") { _, _ ->
                val text = note.text.toString()
                runBg("正在加密并备份签名密钥…") {
                    val rs = BackupManager.backupSigningKey(this, uri, name, text)
                    ui.post { showResults("签名密钥备份结果:", rs) }
                }
            }
            .setNegativeButton("取消", null).show()
    }
}
