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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.LongSupplier

/**
 * 剪贴板多设备联动(几台都是你自己的设备,剪贴板像同一个)。数据模型和合并在 ClipSyncCore,协议(消息、快照、补发、追赶)在 ClipRelayCore,
 * 网络(OkHttp 的 WebSocket 和 HTTP)在 ClipRelayNet;这里是 Android 一侧:什么时候连、设置、状态、密钥、切到主线程读写界面上的列表。
 *
 * 同步什么:剪贴板历史(含顺序)、上锁、回收箱、删除 —— 在任何一台设备上做的这些操作,别的设备也会照做。
 * 不同步:「清空系统剪贴板」那个开关(每台设备的 app 兼容情况不同)、被密码管理器标成敏感的内容(本来就不进历史)。
 *
 * 通道:你自己的 Cloudflare Worker(地址、口令和域名解析在备份页「5. 剪贴板联动」里填,存在本机,见 RelayCfg;不再编进构建)。
 * 变化用 WebSocket 逐台通知,快照放在 R2 的 clip/snap/ 下;命名空间 clip,和互传等别的 App 互不干扰(规则见 Worker 脚本开头)。
 * 备用通道:坚果云(WebDAV,ClipDavCore),用备份页「1. 坚果云账号」里的账号;和 Worker 可以同时开着,各记各的"待发送",
 *   合并幂等所以不会重复。Worker 连不上(被屏蔽)的设备靠它照样同步;几台设备都要打开。
 * 两条通道各有自己的开关(Worker 连不上时可以关掉,免得白白耗电、白等超时);两个都关就是联动关着。
 * 密钥:两条通道都从内置备份密码用固定盐派生(不用手动输入);几台设备要装同一个构建。
 *
 * 省电:没有定时器、没有后台常驻、不用 WorkManager、不轮询。
 *   - 键盘弹出:没连就连上一次(Worker 会把暂存的补发过来),发一个 ping;连接开着的时候别的设备的改动直接推过来;
 *   - 复制 / 删除 / 上锁等本机有变化:不到 1 秒就发(连续操作合并,两次发送至少隔 5 秒);只是挪了顺序这类不紧急的,等键盘收起时再发;
 *   - Worker 在最后一次动静之后 90~120 秒自己断开连接,这边不设定时器去关、也不自动重连;
 *   - 省电模式、电量低时也照常同步(用户要求),Wi-Fi 和移动流量都可以;没网时不去连,失败后 5 分钟内不再试。
 *   - 坚果云备用通道(开着才有):本机有变化 5 秒后传一个很小的增量文件(连续操作合并);键盘弹出时去看一次(间隔至少 1 分钟,没有新内容只列一次目录);
 *     键盘收起时补传没传出去的。
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
    // 备用通道(坚果云)
    private const val K_DAV_ON = "cs_dav_on"
    private const val K_DSEQ = "cd_seq"
    private const val K_DCUR = "cd_cur_"
    private const val K_DBAD = "cd_bad_"
    private const val K_DRESNAP = "cd_resnap"
    private const val K_DOK = "cd_ok"
    private const val K_DMSG = "cd_msg"
    private const val K_DNEXT = "cd_next"
    private const val DAV_PUSH_DELAY_MS = 5_000L
    private const val DAV_PULL_GAP_MS = 60_000L
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
    private var engineNet: ClipRelayNet? = null
    private val davSyncing = AtomicBoolean(false)
    @Volatile private var davLastAttemptAt = 0L
    @Volatile private var davPushPending = false
    @Volatile private var unsentDav = true   // 本机可能有还没传到坚果云的改动(进程刚启动时也当作有)
    private val davPushRun = Runnable {
        davPushPending = false
        appCtx?.let { davRequest(it, manual = false, minGap = 0L) }
    }

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
            main.removeCallbacks(davPushRun)
            davPushPending = false
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

    /** Worker 通道的开关(0.45 以前叫"联动"开关,键没变,老用户的设置原样保留)。 */
    fun relayEnabled(ctx: Context): Boolean = BackupConfig.prefs(ctx).getBoolean(K_ON, false)

    /** 联动是否在运行:Worker 通道和坚果云通道,有一个开着就算开着。 */
    fun enabled(ctx: Context): Boolean = relayEnabled(ctx) || davEnabled(ctx)

    /** 没设置 Worker 地址和口令时的说明;设置好了返回 null。只看有没有,不碰 Keystore。 */
    fun relayError(ctx: Context): String? = if (RelayCfg.has(ctx)) null else "还没有设置 Worker 地址和口令"

    /** Worker 通道的开关。关掉就断开连接、不再去连(Cloudflare 连不上时关掉,省得白白耗电、白等超时);坚果云通道不受影响。 */
    fun setRelayEnabled(ctx: Context, v: Boolean) {
        val app = ctx.applicationContext
        BackupConfig.prefs(app).edit().putBoolean(K_ON, v).putLong(K_NEXT, 0L).apply()
        if (v) {
            if (relayError(app) == null) withEngine(app) { it.onShown() }   // 第一次加入:连上、读别的设备的快照、传自己的
        } else {
            closeEngine()
        }
        notifyStatus()
    }

    // ---- 备用通道(坚果云)的开关和状态

    fun davEnabled(ctx: Context): Boolean = BackupConfig.prefs(ctx).getBoolean(K_DAV_ON, false)

    /** 没设置坚果云账号时的说明;设置好了返回 null。只看有没有,不碰 Keystore。 */
    fun davError(ctx: Context): String? = if (BackupConfig.hasDav(ctx)) null else "还没有设置坚果云账号(备份页「1. 坚果云账号」)"

    /** 坚果云通道现在能用:它的开关开着、有坚果云账号。 */
    fun davReady(ctx: Context): Boolean = davEnabled(ctx) && davError(ctx) == null

    fun setDavEnabled(ctx: Context, v: Boolean) {
        val app = ctx.applicationContext
        BackupConfig.prefs(app).edit().putBoolean(K_DAV_ON, v).putLong(K_DNEXT, 0L).apply()
        if (v) {
            unsentDav = true
            if (davReady(app)) davRequest(app, manual = true, minGap = 0L)
        } else {
            main.removeCallbacks(davPushRun)
            davPushPending = false
        }
        notifyStatus()
    }

    fun davLastOk(ctx: Context): Long = BackupConfig.prefs(ctx).getLong(K_DOK, 0L)

    fun davLastMsg(ctx: Context): String = BackupConfig.prefs(ctx).getString(K_DMSG, "") ?: ""

    fun lastOk(ctx: Context): Long = BackupConfig.prefs(ctx).getLong(K_OK, 0L)

    fun lastMsg(ctx: Context): String = BackupConfig.prefs(ctx).getString(K_MSG, "") ?: ""

    /** 收到的、解不开的消息条数(多半是别的设备装的不是同一个构建,内置密钥对不上)。 */
    fun badCount(ctx: Context): Int = BackupConfig.prefs(ctx).getInt(K_BAD, 0)

    fun connected(): Boolean = engine?.isConnected() == true

    /** 剪贴板页那一行状态小字;联动关着(两条通道都没开)返回 null(界面显示原来的提示)。 */
    fun statusLine(ctx: Context): String? {
        val app = ctx.applicationContext
        if (!enabled(app)) return null
        val relayOn = relayEnabled(app)
        val dav = davReady(app)
        val davText = if (davLastMsg(app).startsWith("失败")) "坚果云同步失败" else if (davLastOk(app) > 0L) "坚果云 " + hhmm(davLastOk(app)) + " 已同步" else "坚果云还没同步过"
        if (!relayOn) return if (dav) "联动:$davText(Worker 通道已关)" else "联动:" + (davError(app) ?: "")
        val relayErr = relayError(app)
        if (relayErr != null) return if (dav) "联动:未设置 Worker;$davText" else "联动:$relayErr"
        val bad = badCount(app)
        if (bad > 0) return "联动:有 $bad 条别的设备的消息解不开(几台设备要装同一个构建)"
        val m = lastMsg(app)
        if (m.startsWith("失败")) return if (dav) "联动:Worker 不可用;$davText" else "联动:同步" + m.take(40)
        if (m.startsWith("等待")) return "联动:$m"
        if (connected()) return "联动:已连接,实时同步"
        val ok = lastOk(app)
        if (ok <= 0L) return "联动:已开启,还没同步过"
        return "联动:" + hhmm(ok) + " 已同步(键盘弹出时重连)"
    }

    private fun hhmm(t: Long): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(t))

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
            val ed = p.edit().remove(K_INITED).putBoolean(K_SNAPDIRTY, true).putLong(K_NEXT, 0L).putBoolean(K_DRESNAP, true).putLong(K_DNEXT, 0L)
            for (k in p.all.keys) if (k.startsWith(K_LAST) || k.startsWith(K_DCUR) || k.startsWith(K_DBAD)) ed.remove(k)
            ed.commit()   // 还原完马上会重启进程,用 commit 保证写进去了
        } catch (e: Exception) {
            // 清不掉也不影响还原本身
        }
    }

    /** 删掉这台设备在云端的东西(不影响别的设备、不影响本机的剪贴板):Worker 的 R2 快照,和坚果云 clip 文件夹里这台设备的文件。后台线程调用。 */
    fun clearCloud(ctx: Context): Outcome {
        val app = ctx.applicationContext
        val parts = ArrayList<String>()
        var ok = true
        if (relayError(app) == null) {
            try {
                val e = engineOrCreate(app, needSwitch = false)
                if (e == null) {
                    parts.add("Worker:没法启动,没删")
                } else {
                    val r = e.deleteOwnCloud().get(30, TimeUnit.SECONDS)
                    ok = ok && r
                    parts.add("Worker:" + (if (r) "已删除快照" else lastMsg(app)))
                    if (!relayEnabled(app)) closeEngine()   // 这个通道的开关关着:删完就断开,不留着连接
                }
            } catch (e: Exception) {
                ok = false
                parts.add("Worker:失败:" + why(e))
            }
        }
        if (davError(app) == null) {
            val r = davClearCloud(app)
            ok = ok && r.ok
            parts.add("坚果云:" + r.msg)
        }
        if (parts.isEmpty()) return Outcome(false, "失败:Worker 和坚果云都没设置")
        return Outcome(ok, parts.joinToString(";"))
    }

    /** 备份页的「立即同步」:每条开着的通道都重新读一遍别的设备的内容、发出没发的改动、更新自己的快照。后台线程调用。 */
    fun syncNow(ctx: Context): Outcome {
        val app = ctx.applicationContext
        val relayOn = relayEnabled(app) && relayError(app) == null
        val davOn = davReady(app)
        if (!relayOn && !davOn) return Outcome(false, "失败:Worker 通道和坚果云通道都没有打开(或没设置好)")
        if (!online(app)) return Outcome(false, "失败:现在没有网络")
        val parts = ArrayList<String>()
        var ok = true
        if (relayOn) {
            try {
                val e = engineOrCreate(app)
                if (e == null) {
                    ok = false
                    parts.add("Worker:没法启动")
                } else {
                    val r = e.syncNow().get(60, TimeUnit.SECONDS)
                    ok = ok && r
                    parts.add("Worker:" + (if (r) "已同步" else lastMsg(app)))
                }
            } catch (e: Exception) {
                ok = false
                parts.add("Worker:失败:" + why(e))
            }
        }
        if (davOn) {
            val r = davSyncOnce(app, push = true, force = true)
            ok = ok && r.ok
            parts.add("坚果云:" + r.msg)
        }
        return Outcome(ok, (if (ok) "成功:" else "") + parts.joinToString(";"))
    }

    // ------------------------------------------------------------ Worker 设置变了

    private var dns: RelayDns? = null

    @Synchronized
    private fun relayDns(app: Context): RelayDns = dns ?: RelayDns(app.applicationContext).also { dns = it }

    /** 翻译(经 Worker)和剪贴板联动连的是同一个 Worker,共用这一个解析器:谁先解析,另一个直接用缓存,不再各问一遍。 */
    fun sharedDns(app: Context): RelayDns = relayDns(app)

    /**
     * 保存了新的 Worker 地址 / 口令之后调用:断开旧连接,清掉这台设备的联动状态(不同的 Worker 里序号和快照互不相关),
     * 当作第一次加入重来。联动开着就马上连一次。
     */
    fun onRelayChanged(ctx: Context) {
        val app = ctx.applicationContext
        closeEngine()
        relayDns(app).reset()
        val p = BackupConfig.prefs(app)
        val ed = p.edit().remove(K_INITED).remove(K_ROSTER).remove(K_ROSTERAT).remove(K_BAD).remove(K_MSG).putLong(K_NEXT, 0L).putBoolean(K_SNAPDIRTY, true)
        for (k in p.all.keys) if (k.startsWith(K_LAST)) ed.remove(k)
        ed.commit()
        if (relayEnabled(app) && relayError(app) == null) withEngine(app) { it.onShown() }
        notifyStatus()
    }

    /** 保存了新的域名解析设置之后调用:清掉解析缓存,断开旧连接,下次连接按新设置解析(联动的状态不变)。 */
    fun onDnsChanged(ctx: Context) {
        val app = ctx.applicationContext
        closeEngine()
        relayDns(app).reset()
        Translator.dnsChanged()   // 翻译和这里共用解析器,但各有各的连接池;设置改了它的连接池也要清
        BackupConfig.prefs(app).edit().putLong(K_NEXT, 0L).apply()
        notifyStatus()
    }

    /**
     * 设置页的「测试连接」:用给定的地址和口令(口令留空就用已保存的)连一下 Worker,检查口令、R2 绑定、是不是 v2 脚本;
     * 用的是已经保存的域名解析设置。后台线程调用。
     */
    fun testRelay(ctx: Context, normalizedUrl: String, secretOrNull: String?): Outcome {
        val app = ctx.applicationContext
        val secret = secretOrNull?.takeIf { it.isNotEmpty() } ?: RelayCfg.secret(app) ?: return Outcome(false, "失败:没有口令")
        val dns = relayDns(app)
        val err = ClipRelayNet.test(normalizedUrl, secret, dns)
        // 写明这个域名是怎么解析的(Host 映射 / 自建 DoH / 系统 DNS,是否回退),不用再猜是哪一路没通
        val via = try { java.net.URI(normalizedUrl).host?.let { dns.sourceOf(it) } } catch (e: Exception) { null }
        val viaText = if (via == null) "" else "\n域名解析:$via"
        return if (err == null) Outcome(true, "Worker 连接正常(口令对、R2 已绑定、是 v2 脚本)$viaText") else Outcome(false, "失败:$err$viaText")
    }

    // ------------------------------------------------------------ 触发(都很便宜:只丢给引擎的执行器,不在调用线程上联网)

    /** 剪贴板有变化(服务的 markClips 里调,主线程)。urgent 的(新复制 / 删除 / 上锁等)尽快发;不紧急的(只是挪了顺序)等键盘收起时。 */
    fun onLocalChange(app: Context, urgent: Boolean) {
        if (relayUsable(app)) withEngine(app) { if (urgent) it.onLocalChange() else it.markDirty() }
        if (davUsable(app)) {
            unsentDav = true
            if (urgent && !davPushPending) {
                davPushPending = true
                main.postDelayed(davPushRun, DAV_PUSH_DELAY_MS)   // 5 秒后传一个增量文件(这几秒里的多次变化合成一个)
            }
        }
    }

    /** 键盘弹出时(后台线程调):Worker 没连就连上、连着就发一个 ping;备用通道开着就看一次坚果云(间隔至少 1 分钟)。 */
    fun onShown(app: Context) {
        if (relayUsable(app)) {
            if (!online(app)) setMsg(app, "等待网络") else withEngine(app) { it.onShown() }
        }
        if (davUsable(app)) davRequest(app, manual = false, minGap = DAV_PULL_GAP_MS)
    }

    /** 打开剪贴板面板时(后台线程调):联动开着、没连着、有网才连(连着就什么都不发,不额外联网)。 */
    fun onPanelOpened(app: Context) {
        try {
            if (!relayUsable(app) || !online(app)) return
            if (engine?.isConnected() == true) return
            withEngine(app) { it.onShown() }
        } catch (e: Exception) {
            // 联动出任何问题都不能影响打字
        }
    }

    /** 键盘收起时(后台线程调):本机有还没发出去的改动就发。 */
    fun onHidden(app: Context) {
        if (relayUsable(app)) {
            if (engine?.isConnected() == true || online(app)) withEngine(app) { it.onHidden() }   // 没连着又没网:不去尝试
        }
        if (davUsable(app) && unsentDav) davRequest(app, manual = false, minGap = 0L)
    }

    /** Worker 主通道:联动开着、设置了地址和口令、不在失败退避期。 */
    private fun relayUsable(app: Context): Boolean {
        return try {
            val p = BackupConfig.prefs(app)
            p.getBoolean(K_ON, false) && relayError(app) == null && System.currentTimeMillis() >= p.getLong(K_NEXT, 0L)
        } catch (e: Exception) {
            false   // 联动出任何问题都不能影响打字
        }
    }

    /** 坚果云备用通道:联动开着、备用开关开着、有坚果云账号、不在失败退避期。 */
    private fun davUsable(app: Context): Boolean {
        return try {
            davReady(app) && System.currentTimeMillis() >= BackupConfig.prefs(app).getLong(K_DNEXT, 0L)
        } catch (e: Exception) {
            false
        }
    }

    // ------------------------------------------------------------ 备用通道(坚果云)

    /**
     * 判断(从便宜到贵)满足就另开低优先级线程同步一次:间隔 → 有没有在跑 / 备份在不在做 → 有没有网(manual 跳过间隔和有没有网的判断)。
     * 省电模式、电量低时也照常。失败后 5 分钟内不再试。
     */
    private fun davRequest(app: Context, manual: Boolean, minGap: Long): Boolean {
        try {
            val now = System.currentTimeMillis()
            if (!manual && minGap > 0L && davLastAttemptAt <= now && now - davLastAttemptAt < minGap) return false
            if (davSyncing.get() || BackupManager.busy() || host == null) return false
            if (!manual && !online(app)) return false
            Thread {
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                val r = davSyncOnce(app, push = true, force = false)
                if (!r.ok && !r.msg.startsWith("忙")) {
                    BackupConfig.prefs(app).edit().putLong(K_DNEXT, System.currentTimeMillis() + RETRY_FAIL_MS).apply()
                }
            }.start()
            return true
        } catch (e: Exception) {
            return false
        }
    }

    /** 做一次坚果云同步:先拉取合并,再传本机的新变化(第一次传整份快照)。后台线程调用。 */
    private fun davSyncOnce(app: Context, push: Boolean, force: Boolean): Outcome {
        if (!BackupConfig.davConfigured(app)) return davFail(app, "还没有设置坚果云账号")
        if (host == null) return davFail(app, "输入法没有在运行(先切换到本输入法、打开一次键盘再试)")
        if (BackupManager.busy()) return Outcome(false, "忙:另一项备份 / 还原正在进行,请稍后再试")
        if (!davSyncing.compareAndSet(false, true)) return Outcome(false, "忙:正在同步中")
        notifyStatus()
        val p = BackupConfig.prefs(app)
        unsentDav = false   // 先清:同步期间又有新改动会重新置位,不会漏
        davLastAttemptAt = System.currentTimeMillis()
        return try {
            val k = key(app)
            val client = BackupManager.dav(app)
            val folder = BackupManager.davFolder(app) + ClipDavCore.SUBFOLDER
            val forceSnap = force || p.getBoolean(K_DRESNAP, false)
            val now = System.currentTimeMillis()
            val r = ClipDavCore.run(client, folder, LearnSync.deviceId(app), k.first, k.second, store, app.cacheDir, davState(app), push, forceSnap, now)
            val e = p.edit().putLong(K_DOK, now).putString(K_DMSG, "成功:" + r.summary()).putLong(K_DNEXT, 0L)
            if (forceSnap && r.pushed.isNotEmpty()) e.remove(K_DRESNAP)
            e.apply()
            Outcome(true, r.summary())
        } catch (e: Exception) {
            unsentDav = true
            davFail(app, why(e))
        } finally {
            davSyncing.set(false)
            notifyStatus()
        }
    }

    private fun davFail(app: Context, why: String): Outcome {
        BackupConfig.prefs(app).edit().putString(K_DMSG, "失败:$why").apply()
        return Outcome(false, "失败:$why")
    }

    /** 删掉这台设备在坚果云 clip 文件夹里的文件(别的设备的不动)。 */
    private fun davClearCloud(app: Context): Outcome {
        return try {
            val client = BackupManager.dav(app)
            val folder = BackupManager.davFolder(app) + ClipDavCore.SUBFOLDER
            val dev = LearnSync.deviceId(app)
            var n = 0
            for (e in client.list(folder)) {
                if (e.dir) continue
                if (e.name.startsWith("s-$dev-") || e.name.startsWith("d-$dev-")) {
                    client.delete(folder, e.name)
                    n++
                }
            }
            BackupConfig.prefs(app).edit().putBoolean(K_DRESNAP, true).apply()
            Outcome(true, "已删除这台设备的 $n 个文件")
        } catch (e: Exception) {
            Outcome(false, "失败:" + why(e))
        }
    }

    private fun davState(app: Context): ClipDavCore.State {
        val p = BackupConfig.prefs(app)
        return object : ClipDavCore.State {
            override fun cursor(dev: String): Long = p.getLong(K_DCUR + dev, -1L)

            override fun setCursor(dev: String, seq: Long) {
                p.edit().putLong(K_DCUR + dev, seq).apply()
            }

            override fun bad(fileName: String): Boolean = p.getBoolean(K_DBAD + fileName, false)

            override fun markBad(fileName: String) {
                p.edit().putBoolean(K_DBAD + fileName, true).apply()
            }

            override fun mySeq(): Long = p.getLong(K_DSEQ, 0L)

            override fun setMySeq(seq: Long) {
                p.edit().putLong(K_DSEQ, seq).apply()
            }
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
    private fun engineOrCreate(app: Context, needSwitch: Boolean = true): ClipRelayCore.Engine? {
        engine?.let { return it }
        if (relayError(app) != null || (needSwitch && !relayEnabled(app))) return null
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
        val secret = RelayCfg.secret(app) ?: throw IOException("Worker 口令在这台手机上取不出来了,请在设置里重新填一遍")
        val net = ClipRelayNet(RelayCfg.url(app), secret, dev, relayDns(app))
        val e = ClipRelayCore.Engine(
            net, store, state(app), dev, k.first, k.second,
            LongSupplier { System.currentTimeMillis() }, exec, sink(app), ClipRelayCore.Config(),
        )
        engineExec = exec
        engineNet = net
        engine = e
        return e
    }

    @Synchronized
    private fun closeEngine() {
        val e = engine
        val x = engineExec
        val n = engineNet
        engine = null
        engineExec = null
        engineNet = null
        try {
            e?.close()
        } catch (ex: Exception) {
            // 已经断了
        }
        x?.shutdownNow()
        n?.dispose()
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
