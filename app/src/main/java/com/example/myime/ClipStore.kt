package com.example.myime

import android.content.Context
import android.util.Base64
import java.io.File
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 剪贴板历史和回收箱:放在内存里的同时落盘(filesDir/user/clips.txt、trash.txt),
 * 这样切换输入法、进程被回收、重启手机之后都还在。
 *
 * 省电:没有定时保存;内容变了才写,而且只写变了的那个文件,短时间内的多次改动合并成一次;键盘隐藏、服务销毁时补存。
 * 每行 "c<Tab>转义后的文本"(回收箱是 "t<Tab>…"),换行、回车、反斜杠转义,所以多行内容也是一条。
 * 读写走 SafeFile:原子替换、有 .bak 备份,写到一半被杀也不会丢旧数据。
 *
 * 加密:文件里的内容用一把随机的数据密钥(AES-256-GCM)加密,这把数据密钥再用 Android Keystore 里取不出来的密钥包起来(BackupVault.seal),
 * 存在 backupcfg 设置里(这个设置文件不进备份)。文件格式还是 SafeFile 的:中间只有一行 "E1<Tab>Base64(随机数 + 密文)"。
 *   - 老版本写的明文文件照常能读,读到后马上改写成加密的。
 *   - 备份时要解成明文再放进(已加密的)备份包,见 plainBytes —— Keystore 的密钥带不到新手机,所以不能把密文原样备份。
 *   - Keystore 密钥丢了(恢复出厂、系统异常)解不开:当作空历史重新开始,不崩溃。
 *   - Keystore 根本用不了的机器:退回明文保存,剪贴板功能照常。
 */
object ClipStore {
    private const val CLIP_FILE = "clips.txt"
    private const val TRASH_FILE = "trash.txt"

    private const val LOCK_TAG = "k"   // 上锁的条目:和剪贴板在同一个文件里,每条一行 "k<Tab>文本"(老版本读到会直接跳过,不影响)

    private const val ENC_TAG = "E1\t"
    private const val DEK_PREF = "clip_dek"

    class Data(val clips: List<String>, val trash: List<String>, val locked: List<String>)

    /** 备份时要换成明文的两个文件(相对 filesDir 的路径)。 */
    fun isClipFile(rel: String): Boolean = rel == "user/$CLIP_FILE" || rel == "user/$TRASH_FILE"

    // ------------------------------------------------------------ 加密

    @Volatile private var dek: ByteArray? = null

    /** 取数据密钥;没有就(create = true 时)生成并用 Keystore 包好存起来。拿不到返回 null。 */
    @Synchronized
    private fun dataKey(ctx: Context, create: Boolean): ByteArray? {
        dek?.let { return it }
        val p = BackupConfig.prefs(ctx)
        p.getString(DEK_PREF, null)?.let { s ->
            val k = BackupVault.unseal(s)
            if (k != null && k.size == 32) {
                dek = k
                return k
            }
        }
        if (!create) return null
        return try {
            val k = BackupCrypto.randomBytes(32)
            if (!p.edit().putString(DEK_PREF, BackupVault.seal(k)).commit()) return null
            dek = k
            k
        } catch (e: Exception) {
            null
        }
    }

    /** 加密一段文字,返回 Base64(12 字节随机数 + 密文);不能加密返回 null。name 进认证数据,两个文件的密文不能互换。 */
    private fun encrypt(ctx: Context, name: String, plain: String): String? {
        val k = dataKey(ctx, true) ?: return null
        return try {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            val iv = BackupCrypto.randomBytes(12)
            c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(k, "AES"), GCMParameterSpec(128, iv))
            c.updateAAD(name.toByteArray(Charsets.US_ASCII))
            Base64.encodeToString(iv + c.doFinal(plain.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        } catch (e: Exception) {
            null
        }
    }

    /** 解密;密钥不在了、数据被改过都返回 null。 */
    private fun decrypt(ctx: Context, name: String, b64: String): String? {
        val k = dataKey(ctx, false) ?: return null
        return try {
            val blob = Base64.decode(b64, Base64.NO_WRAP)
            if (blob.size < 12 + 16) return null
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, SecretKeySpec(k, "AES"), GCMParameterSpec(128, blob.copyOfRange(0, 12)))
            c.updateAAD(name.toByteArray(Charsets.US_ASCII))
            String(c.doFinal(blob, 12, blob.size - 12), Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    /** 文件里读出来的行 → 明文的行("c<Tab>…")。plain = 这个文件原本就是明文(老版本写的)。解不开返回空列表。 */
    private class Plain(val lines: List<String>, val plain: Boolean)

    private fun plainLines(ctx: Context, file: File, name: String): Plain? {
        val r = SafeFile.read(file) ?: return null
        val first = r.lines.firstOrNull() ?: return Plain(emptyList(), true)
        if (!first.startsWith(ENC_TAG)) return Plain(r.lines, true)
        val text = decrypt(ctx, name, first.substring(ENC_TAG.length)) ?: return Plain(emptyList(), false)
        return Plain(if (text.isEmpty()) emptyList() else text.split('\n'), false)
    }

    /**
     * 备份用:把剪贴板文件(clips.txt / trash.txt)读出来,按 SafeFile 的格式返回明文字节。
     * 备份包本身是加密的,所以这里放明文;换手机还原后第一次启动会自动重新加密。
     * 文件解不开(Keystore 密钥丢了)返回 null,备份里就不放这个文件(放一份解不开的密文没有用)。
     */
    fun plainBytes(ctx: Context, file: File, rel: String): ByteArray? {
        // 密文的认证数据用的是逻辑文件名(clips.txt / trash.txt),备份时取到的可能是 .bak 文件,所以名字由调用方按备份条目路径给
        val p = plainLines(ctx.applicationContext, file, rel.substringAfterLast('/')) ?: return null
        if (!p.plain && p.lines.isEmpty()) return null   // 加密的但解不开
        val sb = StringBuilder("#v1\n")
        for (l in p.lines) sb.append(l).append('\n')
        sb.append("#end\n")
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    private fun dir(ctx: Context): File {
        val d = File(ctx.applicationContext.filesDir, "user")
        if (!d.isDirectory) d.mkdirs()
        return d
    }

    private fun esc(s: String): String {
        val b = StringBuilder(s.length + 8)
        for (ch in s) {
            when (ch) {
                '\\' -> b.append("\\\\")
                '\n' -> b.append("\\n")
                '\r' -> b.append("\\r")
                else -> b.append(ch)
            }
        }
        return b.toString()
    }

    private fun unesc(s: String): String {
        if (s.indexOf('\\') < 0) return s
        val b = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val ch = s[i]
            if (ch == '\\' && i + 1 < s.length) {
                when (val n = s[i + 1]) {
                    'n' -> b.append('\n')
                    'r' -> b.append('\r')
                    else -> b.append(n)
                }
                i += 2
            } else {
                b.append(ch)
                i++
            }
        }
        return b.toString()
    }

    /** 读一个文件。plain 表示它原来是明文(要迁移成加密的)。 */
    private class Read(val items: List<String>, val plain: Boolean)

    private fun readList(ctx: Context, file: File, tag: String, max: Int): Read {
        val out = ArrayList<String>()
        var plain = false
        try {
            val p = plainLines(ctx, file, file.name) ?: return Read(out, false)
            plain = p.plain
            for (line in p.lines) {
                if (line.length < 2 || line[0] != tag[0] || line[1] != '\t') continue
                val t = unesc(line.substring(2))
                if (t.isNotBlank() && !out.contains(t)) out.add(t)
                if (out.size >= max) break
            }
        } catch (e: Exception) {
            ErrorHint.set("clip", ErrorHint.of("剪贴板读取失败", e))
        }
        return Read(out, plain)
    }

    /** 启动时读一次。老版本写的明文文件照常读,读到有内容的就马上改写成加密的;没有就是空的。 */
    fun load(ctx: Context, maxClips: Int, maxTrash: Int): Data {
        val app = ctx.applicationContext
        val d = dir(app)
        val c = readList(app, File(d, CLIP_FILE), "c", maxClips)
        val k = readList(app, File(d, CLIP_FILE), LOCK_TAG, maxClips)
        val t = readList(app, File(d, TRASH_FILE), "t", maxTrash)
        if ((c.plain && c.items.isNotEmpty()) || (t.plain && t.items.isNotEmpty())) {
            save(
                app,
                if (c.plain && c.items.isNotEmpty()) c.items else null,
                if (t.plain && t.items.isNotEmpty()) t.items else null,
                k.items,
            )
        }
        return Data(c.items, t.items, k.items)
    }

    /** 一个文件的内容写成 SafeFile 的行:能加密就写一行密文,不能(Keystore 用不了)就退回明文。全空什么都不写。parts = (行标记, 内容) 按顺序写。 */
    private fun writeBody(ctx: Context, w: java.io.Writer, name: String, parts: List<Pair<String, List<String>>>) {
        val sb = StringBuilder()
        for ((tag, items) in parts) for (t in items) sb.append(tag).append('\t').append(esc(t)).append('\n')
        if (sb.isEmpty()) return
        val enc = encrypt(ctx, name, sb.substring(0, sb.length - 1))   // 去掉最后的换行,读的时候按换行拆
        if (enc != null) w.write(ENC_TAG + enc + "\n") else w.write(sb.toString())
    }

    /** 保存快照(调用方在主线程拿快照,在后台线程调用这里)。传 null 的那个文件没变,不写。locked 跟着 clips 一起写(同一个文件)。 */
    @Synchronized
    fun save(ctx: Context, clips: List<String>?, trash: List<String>?, locked: List<String> = emptyList()) {
        val app = ctx.applicationContext
        val d = dir(app)
        try {
            if (clips != null) SafeFile.write(File(d, CLIP_FILE)) { w -> writeBody(app, w, CLIP_FILE, listOf("c" to clips, LOCK_TAG to locked)) }
            if (trash != null) SafeFile.write(File(d, TRASH_FILE)) { w -> writeBody(app, w, TRASH_FILE, listOf("t" to trash)) }
            ErrorHint.clear("clip")
        } catch (e: Exception) {
            ErrorHint.set("clip", ErrorHint.of("剪贴板保存失败", e))
        }
    }
}
