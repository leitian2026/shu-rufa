package com.example.myime

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Base64
import java.io.IOException
import java.io.StringWriter
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.LongSupplier

/**
 * 剪贴板多设备联动(几台都是你自己的设备,剪贴板像同一个)。核心(数据模型、合并、云端文件、拉取和上传)在 ClipSyncCore,
 * 纯 JDK、有自测;这里是 Android 一侧:什么时候同步、判断条件、密钥、设置、切到主线程读写界面上的列表。
 *
 * 同步什么:剪贴板历史(含顺序)、上锁、回收箱、删除 —— 在任何一台设备上做的这些操作,别的设备也会照做。
 * 不同步:「清空系统剪贴板」那个开关(每台设备自己的 app 兼容情况不同)、被密码管理器标成敏感的内容(本来就不进历史)。
 *
 * 通道:复用备份用的坚果云账号,在备份文件夹下的 clip 子文件夹里,每台设备只写自己的文件(一份快照 + 若干小的增量),从不改别人的。
 * 密钥:从内置备份密码用固定盐派生(和学习数据同步一样,不用手动输入)。前提:几台设备装的是同一个构建。
 *
 * 省电:没有定时器、没有后台常驻、不用 WorkManager。只在这几个时刻做几个便宜的判断(开关 → 退避时间 → 间隔 → 有没有在跑 →
 * 有没有网),全部满足才另开低优先级线程联网一次(省电模式、电量低时也照常同步,这是用户要求的):
 *   - 复制 / 删除 / 上锁等本机有变化:等 4 秒(期间的多次变化合并成一次)就同步,这次是"上传"(上传前顺带拉取一次);
 *   - 键盘弹出:距上次同步 ≥ 15 秒就拉取一次别的设备的更新(没有新内容只列一次目录,不下载);
 *   - 键盘收起:本机有还没传出去的改动就传。
 *   Wi-Fi 和移动流量都可以。失败不连续重试,5 分钟后再说;没网时不联网,等下一次键盘事件。
 * 状态(游标、序号、密钥)都放在 backupcfg 里:设备专属、不进备份(设备 ID 不能跟着备份还原到另一台设备)。
 * 默认关闭:剪贴板里可能有账号、地址等敏感内容,要你自己打开。
 */
object ClipSync {
    private const val PULL_GAP_MS = 15_000L
    private const val PUSH_DELAY_MS = 4_000L
    private const val RETRY_FAIL_MS = 5L * 60 * 1000
    private const val MAIN_WAIT_S = 20L

    private const val K_ON = "cs_on"
    private const val K_SEQ = "cs_seq"
    private const val K_CUR = "cs_cur_"
    private const val K_BAD = "cs_bad_"
    private const val K_OK = "cs_ok"
    private const val K_MSG = "cs_msg"
    private const val K_NEXT = "cs_next"
    private const val K_SRC = "cs_keysrc"      // 0.40.0 里短暂有过联动口令:这两项只用来清理老设置,见 key()
    private const val K_KEY = "cs_key"
    private const val K_BKEY = "cs_bkey"       // 内置密码派生出的密钥缓存
    private const val K_BKEYTAG = "cs_bkeytag"
    private const val K_RESNAP = "cs_resnap"   // 下次上传一定传完整快照(还原了备份、清了云端文件)

    /** 服务(界面所在的主线程)提供:读出 / 换掉界面上的三份列表,以及上限。下面几个方法都在主线程里被调用。 */
    interface Host : ClipSyncCore.Host {
        val maxItems: Int
        val maxChars: Int
        val maxTrash: Int

        /** 联动状态变了(同步开始 / 结束 / 开关),界面可以刷新状态行。 */
        fun onStatus()
    }

    class Outcome(val ok: Boolean, val msg: String, val busy: Boolean = false)

    @Volatile private var host: Host? = null
    @Volatile private var appCtx: Context? = null
    private var session: ClipSyncCore.Session? = null   // 只在主线程里用
    private var savedVersion = -1L                      // 只在主线程里用
    private val main = Handler(Looper.getMainLooper())
    private val syncing = AtomicBoolean(false)
    @Volatile private var lastAttemptAt = 0L
    @Volatile private var pushPending = false
    @Volatile private var unsent = true   // 本机可能有还没传出去的改动(进程刚启动时也当作有:让第一次键盘事件对一次账)

    private val pushRun = Runnable {
        pushPending = false
        appCtx?.let { request(it, push = true, manual = false, minGap = 0L) }
    }

    // ------------------------------------------------------------ 服务接入(主线程)

    fun attach(app: Context, h: Host) {
        appCtx = app.applicationContext
        host = h
        session = null
        savedVersion = -1L
    }

    fun detach(h: Host) {
        main.removeCallbacks(pushRun)
        pushPending = false
        if (host === h) {
            host = null
            session = null
        }
    }

    // ------------------------------------------------------------ 设置(备份页用)

    fun enabled(ctx: Context): Boolean = BackupConfig.prefs(ctx).getBoolean(K_ON, false)

    fun setEnabled(ctx: Context, v: Boolean) {
        val app = ctx.applicationContext
        BackupConfig.prefs(app).edit().putBoolean(K_ON, v).putLong(K_NEXT, 0L).apply()
        if (v) {
            unsent = true
            request(app, push = true, manual = true, minGap = 0L)
        } else {
            main.removeCallbacks(pushRun)
            pushPending = false
        }
        notifyStatus()
    }

    fun lastOk(ctx: Context): Long = BackupConfig.prefs(ctx).getLong(K_OK, 0L)

    fun lastMsg(ctx: Context): String = BackupConfig.prefs(ctx).getString(K_MSG, "") ?: ""

    /** 解不开的云端文件个数(多半是别的设备装的不是同一个构建,内置密钥对不上)。 */
    fun badCount(ctx: Context): Int {
        var n = 0
        for (k in BackupConfig.prefs(ctx).all.keys) if (k.startsWith(K_BAD)) n++
        return n
    }

    /** 剪贴板页那一行状态小字;联动关着返回 null(界面显示原来的提示)。 */
    fun statusLine(ctx: Context): String? {
        val app = ctx.applicationContext
        if (!enabled(app)) return null
        if (syncing.get()) return "联动:同步中…"
        val bad = badCount(app)
        if (bad > 0) return "联动:有 $bad 个文件解不开(几台设备要装同一个构建)"
        val m = lastMsg(app)
        if (m.startsWith("失败")) return "联动:同步" + m.take(40)
        if (m.startsWith("等待")) return "联动:$m"
        val ok = lastOk(app)
        if (ok <= 0L) return "联动:已开启,还没同步过"
        return "联动:" + SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ok)) + " 已同步"
    }

    private fun notifyStatus() {
        main.post { host?.onStatus() }
    }

    private fun setMsg(app: Context, msg: String) {
        val p = BackupConfig.prefs(app)
        if (p.getString(K_MSG, "") == msg) return
        p.edit().putString(K_MSG, msg).apply()
        notifyStatus()
    }

    /** 清掉"已经收到别的设备哪里了"和"解不开的文件"的记录:下次同步把别的设备的文件重新读一遍(合并是幂等的,重复读无害)。 */
    private fun resetRemoteState(p: android.content.SharedPreferences) {
        val ed = p.edit()
        for (k in p.all.keys) if (k.startsWith(K_CUR) || k.startsWith(K_BAD)) ed.remove(k)
        ed.commit()
    }

    /** 从备份还原成功后调用(BackupManager.restore):剪贴板换成了备份里的内容,要重新读别的设备的文件、把本机内容整份重传。 */
    fun afterRestore(ctx: Context) {
        try {
            val p = BackupConfig.prefs(ctx.applicationContext)
            resetRemoteState(p)
            p.edit().putBoolean(K_RESNAP, true).putLong(K_NEXT, 0L).commit()   // 还原完马上会重启进程,用 commit 保证写进去了
        } catch (e: Exception) {
            // 清不掉也不影响还原本身
        }
    }

    /** 删掉这台设备在云端的联动文件(不影响别的设备、不影响本机的剪贴板)。后台线程调用。 */
    fun clearCloud(ctx: Context): Outcome {
        val app = ctx.applicationContext
        return try {
            val client = BackupManager.dav(app)
            val folder = BackupManager.davFolder(app) + ClipSyncCore.SUBFOLDER
            val dev = LearnSync.deviceId(app)
            var n = 0
            for (e in client.list(folder)) {
                if (e.dir) continue
                if (e.name.startsWith("s-$dev-") || e.name.startsWith("d-$dev-")) {
                    client.delete(folder, e.name)
                    n++
                }
            }
            BackupConfig.prefs(app).edit().putBoolean(K_RESNAP, true).apply()
            Outcome(true, "已删除云端本机的 $n 个联动文件")
        } catch (e: Exception) {
            Outcome(false, "失败:" + why(e))
        }
    }

    // ------------------------------------------------------------ 触发

    /** 剪贴板有变化(服务的 markClips 里调,主线程)。urgent 的(新复制 / 删除等)4 秒后同步;不紧急的(只是挪了顺序)等键盘收起时。 */
    fun onLocalChange(app: Context, urgent: Boolean) {
        if (!enabled(app)) return
        unsent = true
        if (urgent && !pushPending) {
            pushPending = true
            main.postDelayed(pushRun, PUSH_DELAY_MS)
        }
    }

    /** 键盘弹出时(后台线程调):距上次够久就同步一次(先拉取,本机有改动再上传)。 */
    fun onShown(app: Context) {
        request(app, push = true, manual = false, minGap = PULL_GAP_MS)
    }

    /** 键盘收起时(后台线程调):本机有还没传出去的改动就同步。 */
    fun onHidden(app: Context) {
        if (!unsent) return
        request(app, push = true, manual = false, minGap = 0L)
    }

    /** 备份页的「立即同步」:不看间隔,拉取 + 上传都做。后台线程调用。 */
    fun syncNow(ctx: Context): Outcome {
        val app = ctx.applicationContext
        if (!online(app)) return Outcome(false, "失败:现在没有网络")
        return syncOnce(app, true)
    }

    /**
     * 按"从便宜到贵"的顺序判断,哪一步不满足就立刻返回 false;全满足才开低优先级线程同步一次。manual = 用户主动要求(跳过间隔和退避)。
     * 省电模式、电量低时也照常同步(用户要求),所以这里不看电量。
     */
    private fun request(app: Context, push: Boolean, manual: Boolean, minGap: Long): Boolean {
        try {
            val p = BackupConfig.prefs(app)
            if (!p.getBoolean(K_ON, false)) return false
            val now = System.currentTimeMillis()
            if (!manual) {
                if (now < p.getLong(K_NEXT, 0L)) return false
                if (minGap > 0L && lastAttemptAt <= now && now - lastAttemptAt < minGap) return false
            }
            if (syncing.get() || BackupManager.busy()) return false
            if (!BackupConfig.hasDav(app)) {
                setMsg(app, "等待设置坚果云账号")
                return false
            }
            if (host == null) return false
            if (!manual) {
                if (!online(app)) {
                    setMsg(app, "等待网络")
                    return false
                }
            }
            Thread {
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                val r = syncOnce(app, push)
                if (!r.busy && !r.ok) {
                    p.edit().putLong(K_NEXT, System.currentTimeMillis() + RETRY_FAIL_MS).apply()
                }
            }.start()
            return true
        } catch (e: Exception) {
            return false   // 同步出任何问题都不能影响打字
        }
    }

    /** 有网就行(Wi-Fi 和移动流量都可以)。 */
    private fun online(app: Context): Boolean {
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    // ------------------------------------------------------------ 一次同步

    private fun syncOnce(app: Context, push: Boolean): Outcome {
        if (!BackupConfig.davConfigured(app)) return fail(app, "还没有设置坚果云账号")
        if (host == null) return fail(app, "输入法没有在运行(先切换到本输入法、打开一次键盘再试)")
        if (BackupManager.busy()) return Outcome(false, "另一项备份 / 还原正在进行,请稍后再试", busy = true)
        if (!syncing.compareAndSet(false, true)) return Outcome(false, "正在同步中", busy = true)
        notifyStatus()
        val p = BackupConfig.prefs(app)
        unsent = false   // 先清:同步期间又有新改动会重新置位,不会漏
        lastAttemptAt = System.currentTimeMillis()
        return try {
            val k = key(app)
            val client = BackupManager.dav(app)
            val folder = BackupManager.davFolder(app) + ClipSyncCore.SUBFOLDER
            val force = p.getBoolean(K_RESNAP, false)
            val now = System.currentTimeMillis()
            val r = ClipSyncCore.run(
                client, folder, LearnSync.deviceId(app), k.first, k.second,
                store, app.cacheDir, state(app), push, force, now,
            )
            val e = p.edit().putLong(K_OK, now).putString(K_MSG, "成功:" + r.summary()).putLong(K_NEXT, 0L)
            if (force && r.pushed.isNotEmpty()) e.remove(K_RESNAP)
            e.apply()
            Outcome(true, "成功:" + r.summary())
        } catch (e: Exception) {
            unsent = true
            fail(app, why(e))
        } finally {
            syncing.set(false)
            notifyStatus()
        }
    }

    private fun fail(app: Context, why: String): Outcome {
        BackupConfig.prefs(app).edit().putString(K_MSG, "失败:$why").apply()
        return Outcome(false, "失败:$why")
    }

    private fun why(e: Exception): String {
        val m = e.message
        return (if (m.isNullOrBlank()) e.javaClass.simpleName else m.lineSequence().first()).take(120)
    }

    private fun state(app: Context): ClipSyncCore.State {
        val p = BackupConfig.prefs(app)
        return object : ClipSyncCore.State {
            override fun cursor(dev: String): Long = p.getLong(K_CUR + dev, -1L)

            override fun setCursor(dev: String, seq: Long) {
                p.edit().putLong(K_CUR + dev, seq).apply()
            }

            override fun bad(fileName: String): Boolean = p.getBoolean(K_BAD + fileName, false)

            override fun markBad(fileName: String) {
                p.edit().putBoolean(K_BAD + fileName, true).apply()
            }

            override fun mySeq(): Long = p.getLong(K_SEQ, 0L)

            override fun setMySeq(seq: Long) {
                p.edit().putLong(K_SEQ, seq).apply()
            }
        }
    }

    // ------------------------------------------------------------ 读写模型(切到主线程)

    private fun session(h: Host, app: Context): ClipSyncCore.Session {
        session?.let { return it }
        val l = h.lists()
        val texts = ArrayList<String>(l.clips.size + l.trash.size)
        texts.addAll(l.clips)
        texts.addAll(l.trash)
        val model = ClipSyncCore.Model.readMeta(ClipStore.loadMeta(app), texts)
        val s = ClipSyncCore.Session(model, h, h.maxItems, h.maxChars, h.maxTrash, LongSupplier { System.currentTimeMillis() })
        session = s
        savedVersion = model.version()
        return s
    }

    private fun <T> onMain(block: () -> T): T {
        if (Looper.myLooper() === Looper.getMainLooper()) return block()
        val task = FutureTask<T>(Callable { block() })
        main.post(task)
        try {
            return task.get(MAIN_WAIT_S, TimeUnit.SECONDS)
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        } catch (e: TimeoutException) {
            task.cancel(false)
            throw IOException("输入法界面线程没有响应")
        }
    }

    /**
     * 同步流程读写模型的入口:整段切到主线程执行(界面上的列表只在主线程里动),对账 → 执行 → 列表变了就换掉界面上的列表。
     * 模型有变化就把记录存盘(存盘在调用方的后台线程里做,不占主线程)。
     */
    private val store = object : ClipSyncCore.Store {
        override fun <T> atomically(op: ClipSyncCore.Op<T>): T {
            val app = appCtx ?: throw IOException("输入法服务没有运行")
            val r = onMain {
                val h = host ?: throw IOException("输入法服务没有运行")
                val s = session(h, app)
                val res = s.atomically(op)
                val m = s.model()
                var lines: List<String>? = null
                if (m.version() != savedVersion) {
                    savedVersion = m.version()
                    val sw = StringWriter()
                    m.writeMeta(sw)
                    lines = sw.toString().split('\n').filter { it.isNotEmpty() }
                }
                Pair(res, lines)
            }
            r.second?.let { ClipStore.saveMeta(app, it) }
            return r.first
        }
    }

    // ------------------------------------------------------------ 密钥

    private fun salt(): ByteArray =
        MessageDigest.getInstance("SHA-256").digest("myime-clip-sync-v1".toByteArray(Charsets.UTF_8)).copyOf(16)

    /**
     * 联动密钥(master, 固定盐):内置备份密码 → PBKDF2(固定盐, 最低迭代次数) → 32 字节。算一次要几秒,用 Keystore 里取不出来的密钥包起来缓存,
     * 内置密码变了(标记对不上)就重算。固定盐是必须的:几台设备要算出同一把。后台线程调用。
     * 0.40.0 里短暂有过「联动口令」:这台设备如果设过,这里清掉旧设置、清掉"已收到哪里了"的记录,并让下次整份重传(云端本机的旧文件是用口令加密的)。
     */
    private fun key(app: Context): Pair<ByteArray, ByteArray> {
        val salt = salt()
        val p = BackupConfig.prefs(app)
        if (p.contains(K_SRC) || p.contains(K_KEY)) {
            resetRemoteState(p)
            p.edit().remove(K_SRC).remove(K_KEY).putBoolean(K_RESNAP, true).commit()
        }
        val pw = BackupVault.defaultPassword()
        if (pw.isEmpty()) throw IOException("这个构建没有内置备份密码,无法加密同步")
        val tag = Base64.encodeToString(
            MessageDigest.getInstance("SHA-256").digest(("myime-cs-tag:" + String(pw)).toByteArray(Charsets.UTF_8)).copyOf(8),
            Base64.NO_WRAP,
        )
        if (p.getString(K_BKEYTAG, null) == tag) {
            val cached = p.getString(K_BKEY, null)?.let { BackupVault.unseal(it) }
            if (cached != null && cached.size == 32) return Pair(cached, salt)
        }
        val master = BackupCrypto.deriveMaster(pw, salt, BackupCrypto.MIN_ITERATIONS)
        try {
            p.edit().putString(K_BKEY, BackupVault.seal(master)).putString(K_BKEYTAG, tag).commit()
        } catch (e: Exception) {
            // 安全存储不可用:这次照常用,下次再算
        }
        return Pair(master, salt)
    }
}
