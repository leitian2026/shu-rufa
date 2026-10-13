package com.example.myime

import android.content.Context
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** 备份页的设置:两个备份位置、自动备份开关、每个位置上次成功的时间。只存在本机应用私有目录。 */
object BackupConfig {
    const val LOCAL = "local"   // 本地:任意本地文件夹
    const val CLOUD = "cloud"   // 网盘:网盘 App 在系统文件选择器里提供的文件夹(或网盘同步 App 的本地同步文件夹)

    private const val FILE = "backupcfg"

    internal fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun treeUri(ctx: Context, which: String): Uri? = prefs(ctx).getString("uri_$which", null)?.let { Uri.parse(it) }

    fun setTreeUri(ctx: Context, which: String, uri: Uri?) {
        val e = prefs(ctx).edit()
        if (uri == null) e.remove("uri_$which") else e.putString("uri_$which", uri.toString())
        e.apply()
    }

    /** 每天自动备份一次(键盘收起时检查,数据有变化才备份)。默认开。 */
    fun auto(ctx: Context): Boolean = prefs(ctx).getBoolean("auto", true)

    fun setAuto(ctx: Context, v: Boolean) {
        prefs(ctx).edit().putBoolean("auto", v).apply()
    }

    fun lastOk(ctx: Context, which: String): Long = prefs(ctx).getLong("ok_$which", 0L)

    fun lastMsg(ctx: Context, which: String): String = prefs(ctx).getString("msg_$which", "") ?: ""

    fun setResult(ctx: Context, which: String, ok: Boolean, msg: String) {
        val e = prefs(ctx).edit()
        if (ok) e.putLong("ok_$which", System.currentTimeMillis())
        e.putString("msg_$which", msg)
        e.apply()
    }

    fun nextAutoAt(ctx: Context): Long = prefs(ctx).getLong("next_auto", 0L)

    fun setNextAutoAt(ctx: Context, t: Long) {
        prefs(ctx).edit().putLong("next_auto", t).apply()
    }
}

/**
 * 备份密码的保管。
 *
 * 备份文件用「密码 → PBKDF2 → 主密钥」加密(见 BackupCrypto)。要让自动备份不用每次输密码,
 * 设置密码时把算好的主密钥(不是密码本身)用 Android Keystore 里一把取不出来的 AES 密钥包起来,存在本机。
 *   - 网盘 / 备份文件夹里的人拿到备份文件:没有密码就解不开。
 *   - 备份文件换到新手机还原:只要输入当初的密码(盐和迭代次数写在备份文件头里),不需要这台手机上的任何东西。
 *   - 手机上的这份包装只在本机、本应用能用;卸载应用即消失。
 * 密码忘了没有任何办法找回备份内容。
 */
object BackupVault {
    private const val ALIAS = "myime_backup_wrap"

    fun isSet(ctx: Context): Boolean = BackupConfig.prefs(ctx).getString("wrapped", null) != null

    fun salt(ctx: Context): ByteArray? =
        BackupConfig.prefs(ctx).getString("salt", null)?.let { Base64.decode(it, Base64.NO_WRAP) }

    fun iterations(ctx: Context): Int = BackupConfig.prefs(ctx).getInt("iter", BackupCrypto.ITERATIONS)

    /** 设置(或更换)备份密码。要算几秒钟,放后台线程调用。失败抛异常。更换后,旧备份仍要用旧密码才能解开。 */
    fun setPassword(ctx: Context, password: CharArray) {
        val salt = BackupCrypto.newSalt()
        val iter = BackupCrypto.ITERATIONS
        val master = BackupCrypto.deriveMaster(password, salt, iter)
        val wrapped = wrap(master)
        val ok = BackupConfig.prefs(ctx).edit()
            .putString("salt", Base64.encodeToString(salt, Base64.NO_WRAP))
            .putInt("iter", iter)
            .putString("wrapped", Base64.encodeToString(wrapped, Base64.NO_WRAP))
            .commit()
        if (!ok) throw java.io.IOException("保存失败")
    }

    /** 取出主密钥;没设置、或这台手机的安全存储取不出来(比如系统恢复出厂后)返回 null。 */
    fun master(ctx: Context): ByteArray? {
        val w = BackupConfig.prefs(ctx).getString("wrapped", null) ?: return null
        return try {
            unwrap(Base64.decode(w, Base64.NO_WRAP))
        } catch (e: Exception) {
            null
        }
    }

    private fun wrapKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore")
        ks.load(null)
        val existing = ks.getKey(ALIAS, null)
        if (existing is SecretKey) return existing
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    private fun wrap(master: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, wrapKey())
        val iv = c.iv
        return iv + c.doFinal(master)
    }

    private fun unwrap(blob: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, wrapKey(), GCMParameterSpec(128, blob.copyOfRange(0, 12)))
        return c.doFinal(blob, 12, blob.size - 12)
    }
}
