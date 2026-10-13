package com.example.myime

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.text.InputType
import android.view.WindowManager
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.FileInputStream
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 备份与还原页(应用首页的「备份与还原」进入)。
 * 备份只有两个去处:本地(存成一个加密压缩包到手机上任意位置)、坚果云(WebDAV 上传同样的加密压缩包)。
 * 还原也是两个:选本地压缩包,或点一下从坚果云下载最新的一份;都会自动解压还原,
 * 本机存着备份密钥就不用再输密码,只有换手机 / 重装 / 改过密码才需要输入当初的密码。
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
    private var pendingKeyBackup: KeyBackup? = null

    private class KeyBackup(val data: ByteArray, val name: String, val note: String)

    private companion object {
        const val REQ_EXPORT = 1
        const val REQ_RESTORE_PICK = 2
        const val REQ_KEY_PICK = 3
        const val REQ_KEY_RESTORE_PICK = 4
        const val REQ_KEY_SAVE = 5
        const val REQ_KEY_EXPORT = 6
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
                "把学习数据、固定词、各项设置打成一个加密压缩包(.mybk),可以备份到手机本地,也可以备份到坚果云。" +
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

        root.addView(section("2. 坚果云账号"))
        root.addView(
            label(
                "要先在坚果云网页端:账户信息 → 安全选项 → 第三方应用管理 → 添加应用,生成一个「应用密码」(不是登录密码)。" +
                    "账号填坚果云登录邮箱。备份会放在你指定的文件夹里(默认 myime,不存在会自动创建;" +
                    "如果提示不能创建,请先在坚果云里建好这个文件夹)。每个备份文件加密后才上传,坚果云看不到内容。",
                13f, alpha = 0.7f,
            ),
        )
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(smallButton("设置坚果云账号").apply { setOnClickListener { askDav() } }, smallButtonParams())
        row.addView(smallButton("测试连接").apply { setOnClickListener { testDav() } }, smallButtonParams())
        row.addView(smallButton("清除").apply { setOnClickListener { BackupConfig.clearDav(this@BackupActivity); refresh() } }, smallButtonParams())
        root.addView(row)
        autoBox = CheckBox(this).apply {
            text = "每天自动备份到坚果云一次(键盘收起时检查,数据有变化才备份;不含词库包)"
            isChecked = BackupConfig.auto(this@BackupActivity)
            setOnCheckedChangeListener { _, v -> BackupConfig.setAuto(this@BackupActivity, v) }
        }
        root.addView(autoBox)

        root.addView(section("3. 备份"))
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

        root.addView(section("4. 还原"))
        root.addView(smallButton("从本地压缩包还原…").apply { setOnClickListener { pickFile(REQ_RESTORE_PICK) } }, smallButtonParams())
        root.addView(smallButton("从坚果云还原(最新一份)").apply { setOnClickListener { confirmCloudRestore() } }, smallButtonParams())
        root.addView(
            label(
                "会用备份里的内容替换现在的数据和设置,完成后输入法会重启一次;还原前的数据会在应用内留一份。" +
                    "本机存着备份密码时自动解开,不用再输;换手机或重装后会提示输入当初的备份密码(换手机时先设置好坚果云账号再还原)。" +
                    "以后新增功能的数据只要放在应用数据或设置里,就会自动包含在备份里。",
                13f, alpha = 0.7f,
            ),
        )

        root.addView(section("5. 签名密钥"))
        root.addView(
            label(
                "安装包的签名密钥(.jks)丢了,以后就不能覆盖安装新版本,只能卸载重装,而卸载会清掉所有数据。" +
                    "选中你的密钥文件,加密后存到本地或坚果云。备注里可以写别名和密码,会和密钥一起加密。",
                13f, alpha = 0.7f,
            ),
        )
        root.addView(smallButton("备份签名密钥…").apply { setOnClickListener { pickFile(REQ_KEY_PICK) } }, smallButtonParams())
        root.addView(smallButton("从本地文件还原签名密钥…").apply { setOnClickListener { pickFile(REQ_KEY_RESTORE_PICK) } }, smallButtonParams())
        root.addView(smallButton("从坚果云还原签名密钥").apply { setOnClickListener { startCloudKeyRestore() } }, smallButtonParams())

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
        sb.append("备份密码:").append(if (BackupVault.isSet(this)) "已设置" else "未设置(先设置才能备份)").append('\n')
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

    private fun askPassword(title: String, msg: String, onCancel: (() -> Unit)? = null, onOk: (CharArray) -> Unit) {
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
            .setNegativeButton("取消") { _, _ -> onCancel?.invoke() }
            .setOnCancelListener { onCancel?.invoke() }
            .show()
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
        if (resultCode != RESULT_OK || uri == null) {
            if (requestCode == REQ_KEY_EXPORT) pendingKeyBackup = null
            return
        }
        when (requestCode) {
            REQ_EXPORT -> {
                val withDicts = dictsBox.isChecked
                runBg("正在备份并校验…") {
                    val r = BackupManager.exportToUri(this, uri, withDicts)
                    ui.post { showResults("备份结果:", listOf(r)) }
                }
            }
            REQ_RESTORE_PICK -> runBg("正在解密并校验备份(要几秒钟)…") {
                restoreInBg({ contentResolver.openInputStream(uri) }, {})
            }
            REQ_KEY_PICK -> {
                if (!BackupVault.isSet(this)) {
                    toast("请先设置备份密码")
                    return
                }
                val name = displayName(uri)
                runBg("正在读取密钥文件…") {
                    try {
                        val bytes = BackupManager.readKeyFile(this, uri)
                        ui.post { say(""); askKeyNote(bytes, name) }
                    } catch (e: Exception) {
                        ui.post { say("读取密钥文件失败:" + (e.message ?: e.javaClass.simpleName)) }
                    }
                }
            }
            REQ_KEY_EXPORT -> {
                val kb = pendingKeyBackup ?: return
                pendingKeyBackup = null
                runBg("正在加密并备份签名密钥…") {
                    val r = BackupManager.backupSigningKeyToUri(this, kb.data, kb.name, kb.note, uri)
                    ui.post { showResults("签名密钥备份结果:", listOf(r)) }
                }
            }
            REQ_KEY_RESTORE_PICK -> runBg("正在解密(要几秒钟)…") {
                keyRestoreInBg({ contentResolver.openInputStream(uri) }, {})
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

    private fun startCloudBackup() {
        if (!BackupVault.isSet(this)) {
            toast("请先设置备份密码")
            return
        }
        if (!BackupConfig.davConfigured(this)) {
            toast("请先设置坚果云账号")
            return
        }
        val withDicts = dictsBox.isChecked
        runBg("正在备份并上传到坚果云…") {
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
                        restoreInBg({ FileInputStream(file) }, { BackupManager.clearTemp(this) })
                    } catch (e: Exception) {
                        BackupManager.clearTemp(this)
                        ui.post { say("从坚果云下载失败:" + (e.message ?: e.javaClass.simpleName)) }
                    }
                }
            }
            .setNegativeButton("取消", null).show()
    }

    /**
     * 在后台线程里调用:先用本机保存的备份密钥自动解开还原;解不开再让用户输入密码。
     * cleanup 在不再需要源文件时调用(删临时文件)。
     */
    private fun restoreInBg(open: () -> InputStream?, cleanup: () -> Unit) {
        val r = BackupManager.restore(this, open, null)
        if (!r.needPassword) {
            cleanup()
            ui.post { finishRestore(r) }
            return
        }
        ui.post {
            say("需要输入备份密码。")
            askPassword(
                "还原备份", "这份备份不是用本机保存的密码做的(换手机、重装或改过密码会这样)。输入做这份备份时设的密码。密码不对不会改动任何现有数据。",
                onCancel = { cleanup(); say("已取消还原。") },
            ) { pass ->
                runBg("正在解密并校验备份(要几秒钟)…") {
                    val r2 = BackupManager.restore(this, open, pass)
                    if (r2.ok && !BackupVault.isSet(this)) {
                        try {
                            BackupVault.setPassword(this, pass)   // 全新安装时顺手把它设成备份密码,之后可以直接继续备份
                        } catch (e: Exception) {
                            // 设不上就算了,之后手动设置
                        }
                    }
                    cleanup()
                    ui.post { finishRestore(r2) }
                }
            }
        }
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

    // ------------------------------------------------------------ 签名密钥

    private fun askKeyNote(data: ByteArray, name: String) {
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
        AlertDialog.Builder(this).setTitle("备份签名密钥").setMessage("文件:$name\n备注会和密钥一起加密保存。存到哪里?").setView(box)
            .setPositiveButton("存到本地…") { _, _ ->
                pendingKeyBackup = KeyBackup(data, name, note.text.toString())
                val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                val i = Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("application/octet-stream").putExtra(Intent.EXTRA_TITLE, BackupManager.KEY_PREFIX + stamp + BackupManager.KEY_EXT)
                try {
                    startActivityForResult(i, REQ_KEY_EXPORT)
                } catch (e: Exception) {
                    pendingKeyBackup = null
                    toast("这台手机没有文件选择器")
                }
            }
            .setNeutralButton("存到坚果云") { _, _ ->
                if (!BackupConfig.davConfigured(this)) {
                    toast("请先设置坚果云账号")
                    return@setNeutralButton
                }
                val text = note.text.toString()
                runBg("正在加密并上传签名密钥…") {
                    val r = BackupManager.backupSigningKeyToCloud(this, data, name, text)
                    ui.post { showResults("签名密钥备份结果:", listOf(r)) }
                }
            }
            .setNegativeButton("取消", null).show()
    }

    private fun startCloudKeyRestore() {
        if (!BackupConfig.davConfigured(this)) {
            toast("请先设置坚果云账号")
            return
        }
        runBg("正在从坚果云下载签名密钥备份…") {
            try {
                val (_, file) = BackupManager.downloadLatest(this, BackupManager.KEY_PREFIX, BackupManager.KEY_EXT)
                keyRestoreInBg({ FileInputStream(file) }, { BackupManager.clearTemp(this) })
            } catch (e: Exception) {
                BackupManager.clearTemp(this)
                ui.post { say("从坚果云下载失败:" + (e.message ?: e.javaClass.simpleName)) }
            }
        }
    }

    /** 在后台线程里调用:先用本机保存的备份密钥自动解开;解不开再让用户输入密码。 */
    private fun keyRestoreInBg(open: () -> InputStream?, cleanup: () -> Unit) {
        try {
            val k = BackupManager.readSigningKey(this, open, null)
            cleanup()
            ui.post { offerSaveKey(k) }
        } catch (e: BackupCrypto.BadPasswordException) {
            ui.post {
                say("需要输入备份密码。")
                askPassword(
                    "还原签名密钥", "输入做这份备份时设的密码。",
                    onCancel = { cleanup(); say("已取消。") },
                ) { pass ->
                    runBg("正在解密(要几秒钟)…") {
                        try {
                            val k = BackupManager.readSigningKey(this, open, pass)
                            ui.post { offerSaveKey(k) }
                        } catch (e2: BackupCrypto.BadPasswordException) {
                            ui.post { say("密码不对,或者备份文件已损坏。") }
                        } catch (e2: Exception) {
                            ui.post { say("还原失败:" + (e2.message ?: e2.javaClass.simpleName)) }
                        } finally {
                            cleanup()
                        }
                    }
                }
            }
        } catch (e: Exception) {
            cleanup()
            ui.post { say("还原失败:" + (e.message ?: e.javaClass.simpleName)) }
        }
    }

    private fun offerSaveKey(k: BackupManager.SigningKey) {
        pendingKey = k
        say("解密成功,请选择保存位置。")
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
            .setType("application/octet-stream").putExtra(Intent.EXTRA_TITLE, k.fileName)
        try {
            startActivityForResult(i, REQ_KEY_SAVE)
        } catch (e: Exception) {
            say("这台手机没有文件选择器,无法保存")
        }
    }
}
