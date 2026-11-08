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
import java.util.TreeSet
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.function.LongSupplier

/**
 * 剪贴板多设备联动(几台都是你自己的设备,剪贴板像同一个)。数据模型和合并在 ClipSyncCore,协议(消息、快照、补发、追赶)在 ClipRelayCore,
 * 网络(OkHttp 的 WebSocket 和 HTTP)在 ClipRelayNet;这里是 Android 一侧:什么时候连、设置、状态、密钥、切到主线程读写界面上的列表。
 *
 * 同步什么:剪贴板历史(含顺序)、上锁、回收箱、删除 —— 在任何一台设备上做的这些操作,别的设备也会照做。
 * 不同步:「清空系统剪贴板」那个开关(每台设备的 app 兼容情况不同)、被密码管理器标成敏感的内容(本来就不进历史)。
 *
 * 通道:你自己的 Cloudflare Worker(地址和口令在构建时内置:环境变量 RELAY_URL / RELAY_SECRET,和 BACKUP_PASSWORD 一样不写在源码里)。
 * 变化用 WebSocket 逐台通知,快照放在 R2 的 clip/snap/ 下;命名空间 clip,和互传等别的 App 互不干扰(规则见 Worker 脚本开头)。
 * 密钥:从内置备份密码用固定盐派生(不用手动输入);几台设备要装同一个构建。
 *
 * 省电:没有定时器、没有后台常驻、不用 WorkManager、不轮询。
 *   - 键盘弹出:没连就连上一次(Worker 会把暂存的补发过来),发一个 ping;连接开着的时候别的设备的改动直接推过来;
 *   - 复制 / 删除 / 上锁等本机有变化:不到 1 秒就发(连续操作合并,两次发送至少隔 5 秒);只是挪了顺序这类不紧急的,等键盘收起时再发;
 *   - Worker 在最后一次动静之后 90~120 秒自己断开连接,这边不设定时器去关、也不自动重连;
 *   - 省电模式、电量低时也照常同步(用户要求),Wi-Fi 和移动流量都可以;没网时不去连,失败后 5 分钟内不再试。
 * 状态(序号、收到哪里了、名册)放在 backupcfg 里:设备专属、不进备份(设备 ID 不能跟着备份还原到另一台设备)。
 * 默认关闭:剪贴板里可能有账号、地址等敏感内容,要你自己打开。
 */
object ClipSync {
    private const val RETRY_FAIL_MS = 5L * 60 * 1000
    private const val MAIN_WAIT_S = 20L

    private const val K_ON = "cs_on"
    private const val K_OK = "cs_ok"
    private const val K_MSG = "cs_msg"
    private const val K_NEXT = "cs_next"
    private const val K_BKEY = "cs_bkey"       // 内置密码派生出的密钥缓存
    private const val K_BKEYTAG = "cs_bkeytag"
    private const val K_SEQ = "cr_seq"
    private const val K_LAST = "cr_last_"
    private const val K_ROSTER = "cr_roster"
    private const val K_ROSTERAT = "cr_rosterat"
    private const val K_SNAPDIRTY = "cr_snapdirty"
    private const val K_INITED = "cr_inited"
    private const val K_BAD = "cr_bad"

    /** 服务(界面所在的主线程)提供:读出 / 换掉界面上的三份列表,以及上限。下面几个方法都在主线程里被调用。 */
    interface Host : ClipSyncCore.Host {
        val maxItems: Int
        val maxChars: Int
        val maxTrash: Int

        /** 联动状态变了(连接 / 同步结果 / 开关),界面可以刷新状态行。 */
        fun onStatus()
    }

    class Outcome(val ok: Boolean, val msg: String)

    @Volatile private var host: Host? = null
    @Volatile private var appCtx: Context? = null
    private var session: ClipSyncCore.Session? = null   // 只在主线程里用
    private var savedVersion = -1L                      // 只在主线程里用
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var engine: ClipRelayCore.Engine? = null
    private var engineExec: ScheduledThreadPoolExecutor? = null

    // ------------------------------------------------------------ 服务接入(主线程)

    fun attach(app: Context, h: Host) {
        appCtx = app.applicationContext
        host = h
        session = null
        savedVersion = -1L
        cleanupLegacy(BackupConfig.prefs(app))
    }

    fun detach(h: Host) {
        if (host === h) {
            host = null
            session = null
            closeEngine()
        }
    }

    /** 0.40.x 走坚果云时留下的状态,现在用不到了,清掉。 */
    private fun cleanupLegacy(p: android.content.SharedPreferences) {
        val old = p.all.keys.filter {
            it.startsWith("cs_cur_") || it.startsWith("cs_bad_") || it == "cs_seq" || it == "cs_resnap" || it == "cs_keysrc" || it == "cs_key" || it == "cs_pull"
        }
        if (old.isEmpty()) return
        val ed = p.edit()
        for (k in old) ed.remove(k)
        ed.apply()
    }

    // ------------------------------------------------------------ 设置(备份页用)

    fun enabled(ctx: Context): Boolean = BackupConfig.prefs(ctx).getBoolean(K_ON, false)

    /** 这个构建有没有内置 Worker 地址和口令。 */
    fun relayError(): String? {
        ClipRelayNet.urlError(BuildConfig.RELAY_URL)?.let { return it }
        if (BuildConfig.RELAY_SECRET.isEmpty()) return "这个构建没有内置中转口令"
        return null
    }

    fun setEnabled(ctx: Context, v: Boolean) {
        val app = ctx.applicationContext
        BackupConfig.prefs(app).edit().putBoolean(K_ON, v).putLong(K_NEXT, 0L).apply()
        if (v) {
            if (relayError() == null) withEngine(app) { it.onShown() }   // 第一次加入:连上、读别的设备的快照、传自己的
        } else {
            closeEngine()
        }
        notifyStatus()
    }

    fun lastOk(ctx: Context): Long = BackupConfig.prefs(ctx).getLong(K_OK, 0L)

    fun lastMsg(ctx: Context): String = BackupConfig.prefs(ctx).getString(K_MSG, "") ?: ""

    /** 收到的、解不开的消息条数(多半是别的设备装的不是同一个构建,内置密钥对不上)。 */
    fun badCount(ctx: Context): Int = BackupConfig.prefs(ctx).getInt(K_BAD, 0)

    fun connected(): Boolean = engine?.isConnected() == true

    /** 剪贴板页那一行状态小字;联动关着返回 null(界面显示原来的提示)。 */
    fun statusLine(ctx: Context): String? {
        val app = ctx.applicationContext
        if (!enabled(app)) return null
        relayError()?.let { return "联动:$it" }
        val bad = badCount(app)
        if (bad > 0) return "联动:有 $bad 条别的设备的消息解不开(几台设备要装同一个构建)"
        val m = lastMsg(app)
        if (m.startsWith("失败")) return "联动:同步" + m.take(40)
        if (m.startsWith("等待")) return "联动:$m"
        if (connected()) return "联动:已连接,实时同步"
        val ok = lastOk(app)
        if (ok <= 0L) return "联动:已开启,还没同步过"
        return "联动:" + SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ok)) + " 已同步(键盘弹出时重连)"
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

    /** 从备份还原成功后调用(BackupManager.restore):剪贴板换成了备份里的内容,要当作第一次加入重来(重新读别的设备的快照、传自己的)。 */
    fun afterRestore(ctx: Context) {
        try {
            val p = BackupConfig.prefs(ctx.applicationContext)
            val ed = p.edit().remove(K_INITED).putBoolean(K_SNAPDIRTY, true).putLong(K_NEXT, 0L)
            for (k in p.all.keys) if (k.startsWith(K_LAST)) ed.remove(k)
            ed.commit()   // 还原完马上会重启进程,用 commit 保证写进去了
        } catch (e: Exception) {
            // 清不掉也不影响还原本身
        }
    }

    /** 删掉这台设备在 R2 里的快照(不影响别的设备、不影响本机的剪贴板)。后台线程调用。 */
    fun clearCloud(ctx: Context): Outcome {
        val app = ctx.applicationContext
        relayError()?.let { return Outcome(false, "失败:$it") }
        return try {
            val e = engineOrCreate(app) ?: return Outcome(false, "失败:联动没法启动(没有打开联动开关?)")
            val ok = e.deleteOwnCloud().get(30, TimeUnit.SECONDS)
            Outcome(ok, if (ok) "已删除云端本机的快照" else lastMsg(app))
        } catch (e: Exception) {
            Outcome(false, "失败:" + why(e))
        }
    }

    /** 备份页的「立即同步」:重新读一遍别的设备的快照、发出没发的改动、更新自己的快照。后台线程调用。 */
    fun syncNow(ctx: Context): Outcome {
        val app = ctx.applicationContext
        relayError()?.let { return Outcome(false, "失败:$it") }
        if (!online(app)) return Outcome(false, "失败:现在没有网络")
        return try {
            val e = engineOrCreate(app) ?: return Outcome(false, "失败:联动没法启动(没有打开联动开关?)")
            val ok = e.syncNow().get(60, TimeUnit.SECONDS)
            Outcome(ok, if (ok) "成功:已同步" else lastMsg(app))
        } catch (e: Exception) {
            Outcome(false, "失败:" + why(e))
        }
    }

    // ------------------------------------------------------------ 触发(都很便宜:只丢给引擎的执行器,不在调用线程上联网)

    /** 剪贴板有变化(服务的 markClips 里调,主线程)。urgent 的(新复制 / 删除 / 上锁等)尽快发;不紧急的(只是挪了顺序)等键盘收起时。 */
    fun onLocalChange(app: Context, urgent: Boolean) {
        if (!usable(app)) return
        withEngine(app) { if (urgent) it.onLocalChange() else it.markDirty() }
    }

    /** 键盘弹出时(后台线程调):没连就连上;连着就发一个 ping 让连接多留一会。 */
    fun onShown(app: Context) {
        if (!usable(app)) return
        if (!online(app)) {
            setMsg(app, "等待网络")
            return
        }
        withEngine(app) { it.onShown() }
    }

    /** 键盘收起时(后台线程调):本机有还没发出去的改动就发。 */
    fun onHidden(app: Context) {
        if (!usable(app)) return
        if (engine?.isConnected() != true && !online(app)) return   // 没连着又没网:不去尝试
        withEngine(app) { it.onHidden() }
    }

    /** 开着、构建里有地址和口令、不在失败退避期。 */
    private fun usable(app: Context): Boolean {
        return try {
            val p = BackupConfig.prefs(app)
            p.getBoolean(K_ON, false) && relayError() == null && System.currentTimeMillis() >= p.getLong(K_NEXT, 0L)
        } catch (e: Exception) {
            false   // 联动出任何问题都不能影响打字
        }
    }

    /** 有网就行(Wi-Fi 和移动流量都可以)。 */
    private fun online(app: Context): Boolean {
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun why(e: Exception): String {
        val m = e.message
        return (if (m.isNullOrBlank()) e.javaClass.simpleName else m.lineSequence().first()).take(120)
    }

    // ------------------------------------------------------------ 引擎

    /** 引擎已经有了就直接用(它的入口都只是排队,可以在任何线程调);还没有就另开线程创建(第一次要算密钥,要几秒)再用。 */
    private fun withEngine(app: Context, block: (ClipRelayCore.Engine) -> Unit) {
        val e = engine
        if (e != null) {
            block(e)
            return
        }
        Thread {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            try {
                engineOrCreate(app)?.let(block)
            } catch (ex: Exception) {
                setMsg(app, "失败:" + why(ex))
            }
        }.start()
    }

    @Synchronized
    private fun engineOrCreate(app: Context): ClipRelayCore.Engine? {
        engine?.let { return it }
        if (relayError() != null || !enabled(app)) return null
        val k = key(app)
        val exec = ScheduledThreadPoolExecutor(1) { r ->
            Thread {
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                r.run()
            }.apply {
                name = "clip-relay"
                isDaemon = true
            }
        }
        exec.setKeepAliveTime(10, TimeUnit.SECONDS)   // 闲着的时候线程自己退出,不常驻
        exec.allowCoreThreadTimeOut(true)
        val dev = LearnSync.deviceId(app)
        val net = ClipRelayNet(BuildConfig.RELAY_URL, BuildConfig.RELAY_SECRET, dev)
        val e = ClipRelayCore.Engine(
            net, store, state(app), dev, k.first, k.second,
            LongSupplier { System.currentTimeMillis() }, exec, sink(app), ClipRelayCore.Config(),
        )
        engineExec = exec
        engine = e
        return e
    }

    @Synchronized
    private fun closeEngine() {
        val e = engine
        val x = engineExec
        engine = null
        engineExec = null
        try {
            e?.close()
        } catch (ex: Exception) {
            // 已经断了
        }
        x?.shutdownNow()
    }

    private fun sink(app: Context): ClipRelayCore.Sink = object : ClipRelayCore.Sink {
        override fun ok(msg: String) {
            val now = System.currentTimeMillis()
            BackupConfig.prefs(app).edit().putLong(K_OK, now).putString(K_MSG, "成功:$msg").putLong(K_NEXT, 0L).apply()
            notifyStatus()
        }

        override fun fail(why: String) {
            val now = System.currentTimeMillis()
            BackupConfig.prefs(app).edit().putString(K_MSG, "失败:$why").putLong(K_NEXT, now + RETRY_FAIL_MS).apply()
            notifyStatus()
        }
    }

    private fun state(app: Context): ClipRelayCore.State {
        val p = BackupConfig.prefs(app)
        return object : ClipRelayCore.State {
            override fun mySeq(): Long = p.getLong(K_SEQ, 0L)

            override fun setMySeq(seq: Long) {
                p.edit().putLong(K_SEQ, seq).apply()
            }

            override fun last(dev: String): Long = p.getLong(K_LAST + dev, -1L)

            override fun setLast(dev: String, seq: Long) {
                p.edit().putLong(K_LAST + dev, seq).apply()
            }

            override fun roster(): MutableSet<String> {
                val r = TreeSet<String>()
                for (x in (p.getString(K_ROSTER, "") ?: "").split(',')) if (x.isNotEmpty()) r.add(x)
                return r
            }

            override fun rosterAt(): Long = p.getLong(K_ROSTERAT, 0L)

            override fun setRoster(roster: Set<String>, at: Long) {
                p.edit().putString(K_ROSTER, roster.joinToString(",")).putLong(K_ROSTERAT, at).apply()
            }

            override fun snapDirty(): Boolean = p.getBoolean(K_SNAPDIRTY, false)

            override fun setSnapDirty(v: Boolean) {
                p.edit().putBoolean(K_SNAPDIRTY, v).apply()
            }

            override fun inited(): Boolean = p.getBoolean(K_INITED, false)

            override fun setInited(v: Boolean) {
                p.edit().putBoolean(K_INITED, v).apply()
            }

            override fun badCount(): Int = p.getInt(K_BAD, 0)

            override fun addBad() {
                p.edit().putInt(K_BAD, p.getInt(K_BAD, 0) + 1).apply()
                notifyStatus()
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
     */
    private fun key(app: Context): Pair<ByteArray, ByteArray> {
        val salt = salt()
        val p = BackupConfig.prefs(app)
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
