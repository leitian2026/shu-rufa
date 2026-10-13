package com.example.myime

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.os.PowerManager
import android.os.Process
import android.provider.DocumentsContract
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 加密备份:把学习数据、固定词、各项设置(可选再加词库包)打成一个压缩包,用 BackupCrypto 加密成单个 .mybk 文件。
 * 只有两种去处:
 *   1) 本地:用系统的「新建文件」选择器存到手机上任意位置(exportToUri);
 *   2) 坚果云:通过 WebDAV 上传(backupToCloud,联网权限本来就有;WebDAV 客户端见 WebDavClient)。
 * 两种都是写完马上读回来解密校验,确认真的能还原;上传后还核对云端文件大小。
 * 还原也是两种:选一个本地的 .mybk,或者从坚果云下载最新的一份;都会先尝试用本机保存的备份密钥自动解开,
 * 只有解不开(换手机、重装、改过密码)才让输入密码。
 *
 * 备份范围由 BackupScope 决定:默认应用数据目录和所有设置文件全部备份,只排除登记过的"可重建 / 可下载 / 设备专属"数据。
 * 以后加新功能,数据放在这两处就自动进备份、自动能还原;不进备份的只有:备份密码、坚果云账号和剪贴板数据密钥(都在 backupcfg)、
 * 词库缓存。剪贴板历史(user/clips.txt、trash.txt)会进备份:本机是 Keystore 加密的,备份时解成明文放进已加密的备份包,见 ClipStore.plainBytes。翻译接口的密钥在 dictcfg 里,会随设置一起加密保存。
 *
 * 省电:没有定时器、没有后台常驻;自动备份(只传坚果云)只在键盘收起时做几个便宜的判断(开关、时间、省电模式、电量、文件修改时间),
 * 满足全部条件才另开低优先级线程备份一次;压缩用最快档;备份密钥已缓存,不再做耗时的密码运算。
 */
object BackupManager {
    const val PREFIX = "myime-backup-"
    const val EXT = ".mybk"
    private const val KEEP = 7                   // 坚果云上只留最近 7 份数据备份
    private const val RETRY_OK_MS = 24L * 3600 * 1000
    private const val RETRY_FAIL_MS = 6L * 3600 * 1000

    private const val RECHECK_MS = 6L * 3600 * 1000     // 没有变化时,6 小时后再看一眼
    private const val RECHECK_NET_MS = 3600L * 1000       // 当前不是 Wi-Fi,1 小时后再看
    private const val LOW_BATTERY_PERCENT = 20

    /**
     * 备份前要先把内存里还没落盘的数据写到文件。新功能如果在内存里攒数据、延迟写盘,用 addFlusher 登记一个"立刻落盘"的函数,
     * 备份时会先调用它(已登记:学习数据)。数据本身放在 filesDir 或 SharedPreferences 里就会自动进备份。
     */
    private val flushers = CopyOnWriteArrayList<() -> Unit>().apply { add { UserData.saveAll() } }

    fun addFlusher(f: () -> Unit) {
        flushers.add(f)
    }

    private fun filesDir(ctx: Context): File = ctx.applicationContext.filesDir

    private fun prefsDir(ctx: Context): File = File(ctx.applicationContext.applicationInfo.dataDir, "shared_prefs")

    /** 备份页上显示的"这次会备份什么"。 */
    fun scopeSummary(ctx: Context): String {
        val fd = filesDir(ctx)
        val files = BackupScope.listFiles(fd)
        var bytes = 0L
        for (rel in files) bytes += File(fd, rel).length()
        val prefs = BackupScope.listPrefNames(prefsDir(ctx)).size
        return "${files.size} 个数据文件、$prefs 组设置,约 ${(bytes + 1023) / 1024} KB"
    }

    private val running = AtomicBoolean(false)

    class DestResult(val which: String, val ok: Boolean, val msg: String)

    class RestoreResult(val ok: Boolean, val msg: String)

    fun label(which: String): String = if (which == BackupConfig.LOCAL) "本地" else "坚果云"

    private fun stamp(): String = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())

    // ------------------------------------------------------------ 打包

    /** 主文件不完好、但有完好的 .bak 时用 .bak(备份里不放坏文件);其余情况(含二进制文件)用原文件。 */
    private fun pick(f: File): File {
        val bak = SafeFile.backupOf(f)
        return if (bak.isFile && !SafeFile.isValid(f) && SafeFile.isValid(bak)) bak else f
    }

    private fun esc(s: String): String =
        s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n").replace("\r", "\\r")

    private fun unesc(s: String): String {
        if (s.indexOf('\\') < 0) return s
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (s[i + 1]) {
                    't' -> sb.append('\t')
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    else -> sb.append(s[i + 1])
                }
                i += 2
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }

    private fun dumpPrefs(ctx: Context, name: String, skipMeta: Boolean): String {
        val sp = ctx.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE)
        val sb = StringBuilder()
        for ((k, v) in sp.all) {
            // 词库安装信息:没带词库包就不还原,免得页面显示成已安装(只对词库设置文件生效,别的设置里叫 meta_ 的照常备份)
            if (skipMeta && k.startsWith("meta_")) continue
            if (v is Set<*>) {
                val items = v.filterIsInstance<String>()
                if (items.isEmpty()) {
                    sb.append("se\t").append(esc(k)).append("\t\n")
                } else {
                    sb.append("ss\t").append(esc(k)).append('\t').append(items.joinToString("\t") { esc(it) }).append('\n')
                }
                continue
            }
            val t = when (v) {
                is String -> "s"
                is Int -> "i"
                is Long -> "l"
                is Float -> "f"
                is Boolean -> "b"
                else -> continue
            }
            sb.append(t).append('\t').append(esc(k)).append('\t').append(esc(v.toString())).append('\n')
        }
        return sb.toString()
    }

    private fun putText(z: ZipOutputStream, name: String, text: String) = putBytes(z, name, text.toByteArray(Charsets.UTF_8))

    private fun putBytes(z: ZipOutputStream, name: String, data: ByteArray) {
        z.putNextEntry(ZipEntry(name))
        z.write(data)
        z.closeEntry()
    }

    private fun putFile(z: ZipOutputStream, name: String, f: File) {
        z.putNextEntry(ZipEntry(name))
        f.inputStream().use { it.copyTo(z) }
        z.closeEntry()
    }

    private fun versionName(ctx: Context): String = try {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: ""
    } catch (e: Exception) {
        ""
    }

    private fun writeBackup(ctx: Context, master: ByteArray, salt: ByteArray, iter: Int, includeDicts: Boolean, out: OutputStream) {
        for (fl in flushers) {
            try {
                fl()
            } catch (e: Exception) {
                // 某个功能落盘失败不挡着备份其余的数据
            }
        }
        val fd = filesDir(ctx)
        val enc = BackupCrypto.encrypt(out, master, salt, iter)
        val zip = ZipOutputStream(enc)
        zip.setLevel(Deflater.BEST_SPEED)   // 最快档:少耗 CPU,数据本来也不大
        zip.use { z ->
            putText(z, "meta.txt", "myime-backup 1\ntime=${System.currentTimeMillis()}\napp=${versionName(ctx)}\ndicts=${if (includeDicts) 1 else 0}\n")
            for (rel in BackupScope.listFiles(fd)) {
                val f = pick(File(fd, rel))
                if (!f.isFile) continue
                if (ClipStore.isClipFile(rel)) {
                    // 剪贴板文件在本机是用 Keystore 密钥加密的,换手机就解不开:解成明文再放进(已加密的)备份包
                    ClipStore.plainBytes(ctx, f, rel)?.let { putBytes(z, "files/$rel", it) }
                } else {
                    putFile(z, "files/$rel", f)
                }
            }
            for (name in BackupScope.listPrefNames(prefsDir(ctx))) {
                putText(z, "prefs/$name.txt", dumpPrefs(ctx, name, name == "dictcfg" && !includeDicts))
            }
            if (includeDicts) {
                for (name in DictStore.ALL_FILES) {
                    val f = DictStore.file(ctx, name)
                    if (f.isFile) putFile(z, "dicts/$name", f)
                }
            }
        }
    }

    /** 读回来完整解密一遍(每一块都通过认证),返回压缩包里每个条目的 SHA-256。 */
    private fun verify(open: () -> InputStream, master: ByteArray): Map<String, ByteArray> {
        val digests = HashMap<String, ByteArray>()
        open().use { raw ->
            val dec = BackupCrypto.decryptWithMaster(BufferedInputStream(raw, 1 shl 16), master)
            val z = ZipInputStream(dec)
            val buf = ByteArray(16 * 1024)
            while (true) {
                val e = z.nextEntry ?: break
                val md = MessageDigest.getInstance("SHA-256")
                while (true) {
                    val n = z.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                }
                digests[e.name] = md.digest()
            }
            while (dec.read(buf) >= 0) {
                // 读到加密流末尾,最后一块才会被校验
            }
        }
        return digests
    }

    private fun why(e: Exception): String {
        val m = e.message
        return (if (m.isNullOrBlank()) e.javaClass.simpleName else m.lineSequence().first()).take(120)
    }

    // ------------------------------------------------------------ 坚果云(WebDAV)

    private fun dav(ctx: Context): WebDavClient {
        val user = BackupConfig.davUser(ctx)
        val pw = BackupConfig.davPassword(ctx)
        if (user.isBlank() || pw == null) throw IOException("还没有设置坚果云账号(点「设置坚果云账号」)")
        try {
            return WebDavClient(BackupConfig.davUrl(ctx), user, pw)
        } catch (e: IllegalArgumentException) {
            throw IOException(e.message ?: "坚果云服务器地址不对")
        }
    }

    private fun davFolder(ctx: Context): List<String> {
        val f = WebDavClient.splitFolder(BackupConfig.davFolder(ctx))
        return if (f.isEmpty()) WebDavClient.splitFolder(BackupConfig.DEFAULT_FOLDER) else f
    }

    private fun tmpFile(ctx: Context, name: String): File = File(ctx.applicationContext.cacheDir, name)

    /** 删掉下载 / 上传时留在缓存里的临时文件。 */
    fun clearTemp(ctx: Context) {
        tmpFile(ctx, "cloud_upload.tmp").delete()
        tmpFile(ctx, "cloud_download.tmp").delete()
    }

    /** 测试坚果云账号和文件夹:账号密码对不对、文件夹在不在(不在就创建)。要后台线程调用。 */
    fun testCloud(ctx: Context): DestResult {
        val app = ctx.applicationContext
        return try {
            dav(app).ensureFolder(davFolder(app))
            DestResult(BackupConfig.CLOUD, true, "连接成功,备份会放在坚果云的「${BackupConfig.davFolder(app)}」文件夹里")
        } catch (e: Exception) {
            DestResult(BackupConfig.CLOUD, false, "失败:" + why(e))
        }
    }

    /**
     * 先写到本机临时文件并读回校验,再上传,上传后核对云端文件大小。任何一步失败都不留残缺文件。
     * 临时文件(加密后的,和云端一样)用完就删。
     */
    private fun cloudStore(
        ctx: Context, fileName: String, master: ByteArray, prefix: String, ext: String, prune: Boolean,
        write: (OutputStream) -> Unit, check: (Map<String, ByteArray>) -> Unit,
    ): DestResult {
        val tmp = tmpFile(ctx, "cloud_upload.tmp")
        return try {
            val client = dav(ctx)
            val folder = davFolder(ctx)
            tmp.delete()
            BufferedOutputStream(FileOutputStream(tmp), 1 shl 16).use { o -> write(o) }
            check(verify({ FileInputStream(tmp) }, master))
            client.ensureFolder(folder)
            client.put(folder, fileName, tmp)
            val entries = client.list(folder)   // 一次列目录同时用来核对大小和清理旧备份
            val remote = entries.firstOrNull { it.name == fileName }
            if (remote == null || remote.size != tmp.length()) {
                try {
                    client.delete(folder, fileName)   // 云端文件大小对不上,不留残缺文件
                } catch (e: Exception) {
                    // 删不掉就留着
                }
                throw IOException("上传后核对失败:云端文件和本地不一致")
            }
            if (prune) {
                try {
                    val all = entries.filter { it.name.startsWith(prefix) && it.name.endsWith(ext) }.sortedByDescending { it.name }
                    for (i in KEEP until all.size) client.delete(folder, all[i].name)
                } catch (e: Exception) {
                    // 清理旧备份失败不算备份失败
                }
            }
            DestResult(BackupConfig.CLOUD, true, "已上传并校验通过:$fileName")
        } catch (e: Exception) {
            DestResult(BackupConfig.CLOUD, false, "失败:" + why(e))
        } finally {
            tmp.delete()
        }
    }

    // ------------------------------------------------------------ 备份

    /** 备份到坚果云。要后台线程调用。 */
    fun backupToCloud(ctx: Context, includeDicts: Boolean): DestResult {
        val app = ctx.applicationContext
        if (!BackupConfig.davConfigured(app)) return DestResult(BackupConfig.CLOUD, false, "还没有设置坚果云账号")
        val master = BackupVault.ensure(app)?.let { BackupVault.master(app) }
        val salt = BackupVault.salt(app)
        if (master == null || salt == null) return DestResult("", false, "这台手机的安全存储不可用,无法加密备份")
        if (!running.compareAndSet(false, true)) return DestResult("", false, "另一项备份 / 还原正在进行,请稍后再试")
        try {
            val iter = BackupVault.iterations(app)
            val r = cloudStore(
                app, PREFIX + stamp() + EXT, master, PREFIX, EXT, true,
                { o -> writeBackup(app, master, salt, iter, includeDicts, o) },
                { m -> if (!m.containsKey("meta.txt")) throw IOException("读回校验失败:缺少内容") },
            )
            BackupConfig.setResult(app, BackupConfig.CLOUD, r.ok, r.msg)
            return r
        } finally {
            running.set(false)
        }
    }

    /** 当前有网络、且是不计流量的(Wi-Fi 等)。 */
    private fun onUnmeteredNetwork(ctx: Context): Boolean {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    /** 电池低(且没在充电)或系统开着省电模式:自动备份先不做,等条件好了再说。 */
    private fun lowPower(ctx: Context): Boolean {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (pm != null && pm.isPowerSaveMode) return true
        val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return false   // 粘性广播,只读一次,不注册接收器
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        return !charging && level >= 0 && scale > 0 && level * 100 / scale < LOW_BATTERY_PERCENT
    }

    /**
     * 键盘收起时调用。按"从便宜到贵"的顺序判断,任何一步不满足就立刻返回:
     * 开着自动备份、设过密码 → 到了下次时间 → 设了坚果云账号 → 没有别的备份在跑 → 不在省电模式 / 电量够 →
     * 数据有变化(只看文件修改时间,不读内容)。全部满足才另开低优先级线程备份一次(不含词库包)。
     * 成功后 24 小时内不再备份;失败 6 小时后再试;没有变化 6 小时后再看一眼。没有定时器,只在键盘收起时检查。
     */
    fun maybeAuto(ctx: Context) {
        val app = ctx.applicationContext
        try {
            if (!BackupConfig.auto(app)) return
            val now = System.currentTimeMillis()
            if (now < BackupConfig.nextAutoAt(app)) return
            if (!BackupConfig.hasDav(app)) return
            if (running.get()) return
            if (lowPower(app)) return
            val sig = BackupScope.signature(filesDir(app), prefsDir(app))
            if (sig == 0L || BackupConfig.lastOk(app, BackupConfig.CLOUD) >= sig) {
                BackupConfig.setNextAutoAt(app, now + RECHECK_MS)
                return
            }
            if (!onUnmeteredNetwork(app)) {   // 只在 Wi-Fi 等不计流量的网络上自动传,不为备份唤醒移动数据
                BackupConfig.setNextAutoAt(app, now + RECHECK_NET_MS)
                return
            }
            Thread {
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                val ok = backupToCloud(app, false).ok
                BackupConfig.setNextAutoAt(app, System.currentTimeMillis() + if (ok) RETRY_OK_MS else RETRY_FAIL_MS)
            }.start()
        } catch (e: Exception) {
            // 自动备份出任何问题都不能影响打字
        }
    }

    /** 备份到本地:存成用户用系统选择器新建的单个加密文件(手机上任意位置)。要后台线程调用。 */
    fun exportToUri(ctx: Context, out: Uri, includeDicts: Boolean): DestResult {
        val app = ctx.applicationContext
        val master = BackupVault.ensure(app)?.let { BackupVault.master(app) }
        val salt = BackupVault.salt(app)
        if (master == null || salt == null) return DestResult("", false, "这台手机的安全存储不可用,无法加密备份")
        if (!running.compareAndSet(false, true)) return DestResult("", false, "另一项备份 / 还原正在进行,请稍后再试")
        val resolver = app.contentResolver
        try {
            try {
                val os = resolver.openOutputStream(out, "w") ?: throw IOException("无法写入这个文件")
                BufferedOutputStream(os, 1 shl 16).use { o -> writeBackup(app, master, salt, BackupVault.iterations(app), includeDicts, o) }
                val m = verify({ resolver.openInputStream(out) ?: throw IOException("无法读回这个文件") }, master)
                if (!m.containsKey("meta.txt")) throw IOException("读回校验失败:缺少内容")
            } catch (e: Exception) {
                try {
                    DocumentsContract.deleteDocument(resolver, out)   // 残缺的导出文件不留
                } catch (e2: Exception) {
                    // 删不掉就留着
                }
                throw e
            }
            BackupConfig.setResult(app, BackupConfig.LOCAL, true, "已备份到本地")
            return DestResult(BackupConfig.LOCAL, true, "已备份并校验通过")
        } catch (e: Exception) {
            return DestResult(BackupConfig.LOCAL, false, "失败:" + why(e))
        } finally {
            running.set(false)
        }
    }

    // ------------------------------------------------------------ 还原

    private fun allowedEntry(ctx: Context, name: String): Boolean = when {
        name == "meta.txt" -> true
        name.startsWith("files/") -> BackupScope.resolve(filesDir(ctx), name.substring(6)) != null
        name.startsWith("prefs/") && name.endsWith(".txt") -> BackupScope.safePrefName(name.substring(6, name.length - 4))
        name.startsWith("dicts/") -> name.substring(6) in DictStore.ALL_FILES
        else -> false
    }

    /** 还原一条设置:s/i/l/f/b 是单个值,ss 是字符串集合(条目用 Tab 隔开),se 是空集合。 */
    private fun putPref(e: android.content.SharedPreferences.Editor, line: String) {
        val p = line.split('\t', limit = 3)
        if (p.size != 3) return
        val k = unesc(p[1])
        when (p[0]) {
            "s" -> e.putString(k, unesc(p[2]))
            "i" -> e.putInt(k, unesc(p[2]).toIntOrNull() ?: return)
            "l" -> e.putLong(k, unesc(p[2]).toLongOrNull() ?: return)
            "f" -> e.putFloat(k, unesc(p[2]).toFloatOrNull() ?: return)
            "b" -> e.putBoolean(k, unesc(p[2]) == "true")
            "ss" -> e.putStringSet(k, p[2].split('\t').map { unesc(it) }.toSet())
            "se" -> e.putStringSet(k, emptySet())
        }
    }

    /**
     * 从备份文件还原。先完整解密并通过校验、放进临时目录,确认整个文件没问题才动现有数据
     * (密码错、文件被改、下载不完整,都会在这一步失败,现有数据原封不动)。
     * 条目路径要通过 BackupScope.resolve 的安全检查(不能跑出应用数据目录)。
     * 动之前把会被替换的文件和设置复制一份到 files/user_before_restore/。成功后要重启输入法进程。
     * 不需要输密码:备份用的是内置密码(BackupVault.defaultPassword),任何手机上都能直接解开。
     */
    fun restore(ctx: Context, open: () -> InputStream?): RestoreResult {
        val app = ctx.applicationContext
        if (!running.compareAndSet(false, true)) return RestoreResult(false, "另一项备份 / 还原正在进行,请稍后再试")
        val fd = filesDir(app)
        val staging = File(fd, "restore_staging")
        var frozen = false
        var success = false
        try {
            staging.deleteRecursively()
            staging.mkdirs()
            val got = HashMap<String, File>()
            val raw = open() ?: return RestoreResult(false, "无法打开这个文件")
            raw.use {
                val buffered = BufferedInputStream(raw, 1 shl 16)
                val dec = BackupCrypto.decrypt(buffered, BackupVault.defaultPassword())
                val z = ZipInputStream(dec)
                var i = 0
                while (true) {
                    val e = z.nextEntry ?: break
                    var name = e.name
                    if (name.startsWith("user/")) name = "files/$name"   // 0.10.0 早期备份的旧条目名
                    if (!allowedEntry(app, name)) continue
                    val f = File(staging, "e${i++}")
                    FileOutputStream(f).use { o -> z.copyTo(o) }
                    got[name] = f
                }
                val buf = ByteArray(16 * 1024)
                while (dec.read(buf) >= 0) {
                    // 读到末尾,让最后一块也通过认证
                }
            }
            val meta = got["meta.txt"]?.readText(Charsets.UTF_8) ?: return RestoreResult(false, "这个文件不是本输入法的数据备份")
            if (!meta.startsWith("myime-backup 1")) return RestoreResult(false, "这个文件不是本输入法的数据备份")

            SafeFile.setFrozen(true)
            frozen = true

            // 先留一份现在的:会被替换的文件、会被改写的设置
            val prev = File(fd, "user_before_restore")
            prev.deleteRecursively()
            prev.mkdirs()
            for (name in got.keys) {
                if (name.startsWith("files/")) {
                    val t = BackupScope.resolve(fd, name.substring(6)) ?: continue
                    if (t.isFile) {
                        val c = File(prev, name)
                        c.parentFile?.mkdirs()
                        t.copyTo(c, true)
                    }
                } else if (name.startsWith("prefs/")) {
                    val pn = name.substring(6, name.length - 4)
                    val c = File(prev, name)
                    c.parentFile?.mkdirs()
                    c.writeText(dumpPrefs(app, pn, false), Charsets.UTF_8)
                }
            }

            var fileCount = 0
            var prefCount = 0
            for ((name, s) in got) {
                if (!name.startsWith("files/")) continue
                val t = BackupScope.resolve(fd, name.substring(6)) ?: continue
                t.parentFile?.mkdirs()
                SafeFile.deleteAll(t)   // 旧的 .bak / 临时文件一起清掉,免得混进还原后的数据
                Files.move(s.toPath(), t.toPath(), StandardCopyOption.REPLACE_EXISTING)
                fileCount++
            }
            val hasDicts = got.keys.any { it.startsWith("dicts/") }
            for ((name, s) in got) {
                if (!name.startsWith("prefs/")) continue
                val pn = name.substring(6, name.length - 4)
                val sp = app.getSharedPreferences(pn, Context.MODE_PRIVATE)
                val e = sp.edit()
                for (k in sp.all.keys) {
                    if (pn == "dictcfg" && !hasDicts && k.startsWith("meta_")) continue
                    e.remove(k)
                }
                for (line in s.readLines(Charsets.UTF_8)) putPref(e, line)
                e.commit()
                prefCount++
            }
            if (hasDicts) {
                for (name in DictStore.ALL_FILES) {
                    val s = got["dicts/$name"] ?: continue
                    Files.move(s.toPath(), DictStore.file(app, name).toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
                DictCache.clear(app)
            }
            val dictNote = if (hasDicts) "、词库包" else ""
            success = true
            return RestoreResult(true, "已还原:$fileCount 个数据文件、$prefCount 组设置$dictNote。\n还原前的数据留了一份在应用内的 user_before_restore 文件夹。")
        } catch (e: BackupCrypto.BadPasswordException) {
            return RestoreResult(false, "这个文件解不开:可能是旧版用自己的密码做的备份,或者文件已损坏。现有数据没有改动。")
        } catch (e: Exception) {
            return RestoreResult(false, "还原失败:" + why(e))
        } finally {
            staging.deleteRecursively()
            // 中途出错就解除冻结;成功的话保持冻结直到输入法进程重启
            if (frozen && !success) SafeFile.setFrozen(false)
            running.set(false)
        }
    }

    // ------------------------------------------------------------ 从坚果云下载

    /**
     * 把坚果云上最新的一份(名字带时间,按名字排最大的)下载到本机临时文件,返回文件和云端文件名。
     * 临时文件由调用方用完后 clearTemp 删掉。要后台线程调用。云端没有备份时抛异常。
     */
    fun downloadLatest(ctx: Context, prefix: String, ext: String): Pair<String, File> {
        val app = ctx.applicationContext
        if (!running.compareAndSet(false, true)) throw IOException("另一项备份 / 还原正在进行,请稍后再试")
        try {
            val client = dav(app)
            val folder = davFolder(app)
            val latest = client.list(folder).filter { !it.dir && it.name.startsWith(prefix) && it.name.endsWith(ext) }.maxByOrNull { it.name }
                ?: throw IOException("坚果云的「${BackupConfig.davFolder(app)}」文件夹里还没有找到备份")
            val tmp = tmpFile(app, "cloud_download.tmp")
            tmp.delete()
            try {
                client.get(folder, latest.name, tmp)
                if (tmp.length() != latest.size) throw IOException("下载不完整,请重试")
            } catch (e: Exception) {
                tmp.delete()
                throw e
            }
            return Pair(latest.name, tmp)
        } finally {
            running.set(false)
        }
    }
}
