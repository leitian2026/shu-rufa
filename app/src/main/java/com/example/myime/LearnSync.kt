package com.example.myime

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Process
import android.util.Base64
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 几台自己的手机共享词频学习数据(拼音 / 笔画的选词记录 + 中文联想)。只共享这三样,别的数据一概不碰(白名单,见 LearnShare)。
 * 核心流程在 LearnSyncCore(纯 JDK,有自测);这里是 Android 一侧:什么时候同步、省电判断、密钥、设置。
 *
 * 通道:复用备份用的坚果云账号和文件夹,每台手机只写自己的 myime-learn-<设备ID>.lrn,从不改别人的。
 * 密钥:从内置的备份密码(BuildConfig.BACKUP_PASSWORD)用固定盐派生,不用手动输入;和备份密钥是两把不同的密钥。
 *       前提:几台手机装的是同一个构建(同一个 BACKUP_PASSWORD),每台都设过坚果云账号。密码一旦用过就不能改。
 *
 * 省电:没有定时器、没有后台常驻、不用 WorkManager,只在键盘收起 / 弹出时做几个便宜的判断(开关、时间、攒了多少、省电模式、电量、有没有网),
 * 全部满足才另开低优先级线程同步一次。Wi-Fi 和流量都可以(用户要求);失败不连续重试,隔几小时再说。
 *   - 上传:键盘收起时,自上次上传以来新学 ≥300 次且距上次上传 ≥1 天;或者有新学的且超过 7 天;第一次(从没上传过)有数据就传。
 *   - 拉取:键盘弹出时,距上次拉取 ≥1 天(没变化的远端文件只列一次目录,不下载)。上传前一定先拉取一次再合并。
 *   - 清除学习数据只清本机,不传播;清除之前的记录也不会被别的手机再同步回来(见 noteCleared)。
 *
 * 状态全放在 backupcfg 里(设备专属、不进备份):设备 ID 不能跟着备份还原到另一台手机,不然两台手机会写同一个文件。
 */
object LearnSync {
    private const val MIN_NEW = 300
    private const val PUSH_MIN_GAP_MS = 24L * 3600 * 1000
    private const val PUSH_MAX_GAP_MS = 7L * 24 * 3600 * 1000
    private const val PULL_GAP_MS = 24L * 3600 * 1000
    private const val RETRY_FAIL_MS = 6L * 3600 * 1000
    private const val RETRY_NET_MS = 3600L * 1000

    private const val K_ON = "ls_on"
    private const val K_DEV = "ls_dev"
    private const val K_NEW = "ls_new"
    private const val K_PUSH = "ls_push"
    private const val K_PULL = "ls_pull"
    private const val K_NEXT = "ls_next"
    private const val K_MSG = "ls_msg"
    private const val K_OK = "ls_ok"
    private const val K_FLOOR = "ls_floor"
    private const val K_KEY = "ls_key"
    private const val K_KEYTAG = "ls_keytag"
    private const val SIG_PREFIX = "ls_sig_"

    private val syncing = AtomicBoolean(false)
    @Volatile
    private var seenLearned = 0L   // 本进程里已经折算进 ls_new 的学习次数(只在键盘收起的那个线程里用)

    class Outcome(val ok: Boolean, val msg: String, val busy: Boolean = false)

    // ------------------------------------------------------------ 设置(备份页用)

    fun enabled(ctx: Context): Boolean = BackupConfig.prefs(ctx).getBoolean(K_ON, true)

    fun setEnabled(ctx: Context, v: Boolean) {
        BackupConfig.prefs(ctx).edit().putBoolean(K_ON, v).apply()
    }

    fun lastOk(ctx: Context): Long = BackupConfig.prefs(ctx).getLong(K_OK, 0L)

    fun lastPush(ctx: Context): Long = BackupConfig.prefs(ctx).getLong(K_PUSH, 0L)

    fun lastPull(ctx: Context): Long = BackupConfig.prefs(ctx).getLong(K_PULL, 0L)

    fun lastMsg(ctx: Context): String = BackupConfig.prefs(ctx).getString(K_MSG, "") ?: ""

    fun pendingNew(ctx: Context): Long = BackupConfig.prefs(ctx).getLong(K_NEW, 0L)

    /** 这个构建有没有内置备份密码(没有就没法加密,同步和云备份都用不了)。 */
    fun available(): Boolean = BuildConfig.BACKUP_PASSWORD.isNotEmpty()

    /**
     * 这台手机清除了学习数据(UserData.clearAll 里调):记下时间,之后合并别的手机的数据时,
     * 时间戳早于这个时间的一概不收 —— 不然别的手机文件里的旧数据又会把清掉的同步回来。
     * 之后新学的照常同步。清除只清本机,不会去动别的手机。
     */
    fun noteCleared(ctx: Context) {
        try {
            BackupConfig.prefs(ctx).edit().putLong(K_FLOOR, System.currentTimeMillis()).putLong(K_NEW, 0L).apply()
            seenLearnedReset(ctx.applicationContext)
        } catch (e: Exception) {
            // 记不下来不影响清除本身
        }
    }

    // ------------------------------------------------------------ 触发

    /**
     * 键盘收起(hidden = true)/ 弹出(false)时调用,在后台线程里调。按"从便宜到贵"的顺序判断,哪一步不满足就立刻返回。
     * 收起时才可能上传;弹出时只拉取。
     */
    fun maybe(ctx: Context, hidden: Boolean) {
        val app = ctx.applicationContext
        try {
            val p = BackupConfig.prefs(app)
            if (hidden) countNew(app)
            if (!p.getBoolean(K_ON, true)) return
            val now = System.currentTimeMillis()
            if (now < p.getLong(K_NEXT, 0L)) return
            if (!BackupConfig.hasDav(app) || !available()) return
            if (syncing.get() || BackupManager.busy()) return

            val lastPull = p.getLong(K_PULL, 0L)
            val lastPush = p.getLong(K_PUSH, 0L)
            val gapPull = if (lastPull > now) Long.MAX_VALUE else now - lastPull   // 时钟被往回调:当作已经很久
            val gapPush = if (lastPush > now) Long.MAX_VALUE else now - lastPush
            val pending = p.getLong(K_NEW, 0L)
            val pushDue = hidden && (
                (lastPush == 0L && hasData(app)) ||
                    (pending >= MIN_NEW && gapPush >= PUSH_MIN_GAP_MS) ||
                    (pending > 0 && gapPush >= PUSH_MAX_GAP_MS)
                )
            val pullDue = !hidden && (lastPull == 0L || gapPull >= PULL_GAP_MS)
            if (!pushDue && !pullDue) return

            if (BackupManager.lowPower(app)) return
            if (!online(app)) {
                p.edit().putLong(K_NEXT, now + RETRY_NET_MS).apply()
                return
            }
            Thread {
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                val r = syncOnce(app, pushDue)
                if (!r.busy && !r.ok) {
                    BackupConfig.prefs(app).edit().putLong(K_NEXT, System.currentTimeMillis() + RETRY_FAIL_MS).apply()
                }
            }.start()
        } catch (e: Exception) {
            // 同步出任何问题都不能影响打字
        }
    }

    /** 把本进程新学的次数折算进 ls_new(持久保存,进程重启不丢)。 */
    private fun countNew(app: Context) {
        val total = UserData.pinyin(app).learnedCount() + UserData.stroke(app).learnedCount() + UserData.chain(app).learnedCount()
        val delta = total - seenLearned
        if (delta <= 0) return
        seenLearned = total
        val p = BackupConfig.prefs(app)
        p.edit().putLong(K_NEW, p.getLong(K_NEW, 0L) + delta).apply()
    }

    private fun hasData(app: Context): Boolean =
        UserData.pinyin(app).hasLearned() || UserData.stroke(app).hasLearned() || UserData.chain(app).count() > 0

    /** 有网就行(Wi-Fi 和移动流量都可以)。 */
    private fun online(app: Context): Boolean {
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    // ------------------------------------------------------------ 一次同步

    /** 备份页的「立即同步」:不看时间和攒了多少,拉取 + 上传都做。后台线程调用。 */
    fun syncNow(ctx: Context): Outcome {
        val app = ctx.applicationContext
        if (!online(app)) return Outcome(false, "失败:现在没有网络")
        return syncOnce(app, true)
    }

    private fun syncOnce(app: Context, push: Boolean): Outcome {
        if (!BackupConfig.davConfigured(app)) return Outcome(false, "还没有设置坚果云账号")
        if (!available()) return Outcome(false, "这个构建没有内置备份密码(构建时没提供 BACKUP_PASSWORD),无法加密同步")
        if (BackupManager.busy()) return Outcome(false, "另一项备份 / 还原正在进行,请稍后再试", busy = true)
        if (!syncing.compareAndSet(false, true)) return Outcome(false, "正在同步中", busy = true)
        val p = BackupConfig.prefs(app)
        return try {
            val k = key(app)
            val client = BackupManager.dav(app)
            val folder = BackupManager.davFolder(app)
            val state = object : LearnSyncCore.State {
                override fun sig(fileName: String): String? = p.getString(SIG_PREFIX + fileName, null)

                override fun putSig(fileName: String, sig: String) {
                    p.edit().putString(SIG_PREFIX + fileName, sig).apply()
                }

                override fun floor(): Long = p.getLong(K_FLOOR, 0L)
            }
            val now = System.currentTimeMillis()
            val r = LearnSyncCore.run(
                client, folder, deviceId(app), k.first, k.second,
                UserData.pinyin(app), UserData.stroke(app), UserData.chain(app),
                app.cacheDir, state, push, now,
            ) { UserData.saveAll() }
            val e = p.edit().putLong(K_PULL, now).putLong(K_OK, now)
            if (r.pushed) {
                e.putLong(K_PUSH, now)
                e.putLong(K_NEW, 0L)
                seenLearnedReset(app)
            }
            val msg = "成功:" + r.summary()
            e.putString(K_MSG, msg).apply()
            Outcome(true, msg)
        } catch (e: Exception) {
            val m = e.message
            val why = (if (m.isNullOrBlank()) e.javaClass.simpleName else m.lineSequence().first()).take(120)
            p.edit().putString(K_MSG, "失败:$why").apply()
            Outcome(false, "失败:$why")
        } finally {
            syncing.set(false)
        }
    }

    /** 上传成功后,把"已折算"的计数对齐到现在的学习次数,免得马上又重复计一遍。 */
    private fun seenLearnedReset(app: Context) {
        seenLearned = UserData.pinyin(app).learnedCount() + UserData.stroke(app).learnedCount() + UserData.chain(app).learnedCount()
    }

    // ------------------------------------------------------------ 设备 ID 和密钥

    /** 这台手机的设备 ID(随机 10 位十六进制,第一次用时生成,存在不进备份的设置里)。 */
    private fun deviceId(app: Context): String {
        val p = BackupConfig.prefs(app)
        p.getString(K_DEV, null)?.takeIf { it.length == 10 }?.let { return it }
        val id = BackupCrypto.randomBytes(5).joinToString("") { "%02x".format(it) }
        p.edit().putString(K_DEV, id).commit()
        return id
    }

    /**
     * 同步密钥(master, 固定盐):内置备份密码 → PBKDF2(固定盐, 最低迭代次数) → 32 字节。
     * 算一次要几秒,用 Keystore 里取不出来的密钥包起来缓存;内置密码变了(标记对不上)就重算。后台线程调用。
     * 固定盐是必须的:备份的 master 每台手机用随机盐,各不相同;同步要几台手机算出同一把。
     */
    private fun key(app: Context): Pair<ByteArray, ByteArray> {
        val pw = BackupVault.defaultPassword()
        if (pw.isEmpty()) throw IOException("没有内置备份密码")
        val sha = MessageDigest.getInstance("SHA-256")
        val salt = sha.digest("myime-learn-sync-v1".toByteArray(Charsets.UTF_8)).copyOf(16)
        val tag = Base64.encodeToString(
            MessageDigest.getInstance("SHA-256").digest(("myime-ls-tag:" + String(pw)).toByteArray(Charsets.UTF_8)).copyOf(8),
            Base64.NO_WRAP,
        )
        val p = BackupConfig.prefs(app)
        if (p.getString(K_KEYTAG, null) == tag) {
            val cached = p.getString(K_KEY, null)?.let { BackupVault.unseal(it) }
            if (cached != null && cached.size == 32) return Pair(cached, salt)
        }
        val master = BackupCrypto.deriveMaster(pw, salt, BackupCrypto.MIN_ITERATIONS)
        try {
            p.edit().putString(K_KEY, BackupVault.seal(master)).putString(K_KEYTAG, tag).commit()
        } catch (e: Exception) {
            // 安全存储不可用:这次照常用,下次再算
        }
        return Pair(master, salt)
    }
}
