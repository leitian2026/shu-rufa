package com.example.myime

import android.content.Context
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * 剪贴板联动用的 Cloudflare Worker 的设置:地址、口令、域名解析(Host 映射 / 自建 DoH / 系统 DNS)。
 * 只存在本机应用私有目录(backupcfg,设备专属,不进备份);口令和 DoH 地址(地址里常带随机路径,等于口令)用 Android Keystore 里取不出来的密钥加密后存放。
 * 每台设备各填一次(口令是联动的起点,没法靠联动同步过来)。设置页在备份页「5. 剪贴板联动」。
 */
object RelayCfg {
    private const val K_URL = "rl_url"
    private const val K_SECRET = "rl_secret"
    private const val K_HOSTS_ON = "rl_hosts_on"
    private const val K_HOSTS = "rl_hosts"
    private const val K_DOH_ON = "rl_doh_on"
    private const val K_DOH = "rl_doh"
    private const val K_SYS = "rl_sys"

    private fun p(ctx: Context) = BackupConfig.prefs(ctx)

    /** 已规范化的地址(https://域名[:端口],不带路径);没设置是空字符串。 */
    fun url(ctx: Context): String = p(ctx).getString(K_URL, "") ?: ""

    /** 口令;没设置、或这台手机的安全存储取不出来返回 null。 */
    fun secret(ctx: Context): String? {
        val s = p(ctx).getString(K_SECRET, null) ?: return null
        val b = BackupVault.unseal(s) ?: return null
        return String(b, Charsets.UTF_8)
    }

    /** 只看设置里有没有地址和口令,不碰 Keystore(键盘事件里的判断用,省电)。 */
    fun has(ctx: Context): Boolean = url(ctx).isNotBlank() && p(ctx).contains(K_SECRET)

    /**
     * 把用户填的地址整理成 https://域名[:端口]:可以直接写域名或 xxx.workers.dev(自动补 https://),也认 wss:// 和末尾的 /ws。
     * 返回 (地址, null) 或 (null, 错误说明)。
     */
    fun normalize(raw: String): Pair<String?, String?> {
        var t = raw.trim().trimEnd('/')
        if (t.isEmpty()) return Pair(null, "没有填地址")
        if (t.endsWith("/ws")) t = t.removeSuffix("/ws").trimEnd('/')
        t = when {
            t.startsWith("https://") -> t
            t.startsWith("wss://") -> "https://" + t.removePrefix("wss://")
            t.startsWith("http://") || t.startsWith("ws://") -> return Pair(null, "地址必须是 https(Cloudflare 的域名自带 https)")
            else -> "https://$t"
        }
        val u = t.toHttpUrlOrNull() ?: return Pair(null, "地址格式不对")
        if (u.encodedPath != "/") return Pair(null, "地址里不要带路径(只写域名,例如 xxx.workers.dev)")
        return Pair(u.scheme + "://" + u.host + (if (u.port != 443) ":" + u.port else ""), null)
    }

    /** 保存地址和口令。secret 为 null 表示不改口令。地址要先用 normalize 整理过。 */
    fun set(ctx: Context, normalizedUrl: String, secret: String?) {
        val e = p(ctx).edit().putString(K_URL, normalizedUrl)
        if (secret != null) e.putString(K_SECRET, BackupVault.seal(secret.toByteArray(Charsets.UTF_8)))
        e.commit()
    }

    fun clear(ctx: Context) {
        p(ctx).edit().remove(K_URL).remove(K_SECRET).commit()
    }

    // ---------------- 域名解析(规则见 RelayDnsCore)

    /** 是否启用 Host 映射(默认开)。关闭时填的内容保留但不使用。 */
    fun hostsOn(ctx: Context): Boolean = p(ctx).getBoolean(K_HOSTS_ON, true)

    /** Host 映射:每行「域名 IP [IP…]」,命中就不查 DNS。 */
    fun hostsText(ctx: Context): String = p(ctx).getString(K_HOSTS, "") ?: ""

    /** 是否启用自建 DoH(默认关)。关闭时填的地址保留但不使用。 */
    fun dohOn(ctx: Context): Boolean = p(ctx).getBoolean(K_DOH_ON, false)

    /** 自建 DoH 地址,每行一个,按顺序试。加密保存。 */
    fun dohText(ctx: Context): String {
        val s = p(ctx).getString(K_DOH, null) ?: return ""
        val b = BackupVault.unseal(s) ?: return ""
        return String(b, Charsets.UTF_8)
    }

    /** 是否允许用系统 DNS(默认开)。关闭时只认 Host 映射和自建 DoH,都解析不了就连不上,绝不碰系统 DNS。 */
    fun sysDns(ctx: Context): Boolean = p(ctx).getBoolean(K_SYS, true)

    fun setDns(ctx: Context, hostsOn: Boolean, hostsText: String, dohOn: Boolean, dohText: String, sysDns: Boolean) {
        val e = p(ctx).edit()
            .putBoolean(K_HOSTS_ON, hostsOn)
            .putString(K_HOSTS, hostsText)
            .putBoolean(K_DOH_ON, dohOn)
            .putBoolean(K_SYS, sysDns)
        if (dohText.isBlank()) e.remove(K_DOH) else e.putString(K_DOH, BackupVault.seal(dohText.toByteArray(Charsets.UTF_8)))
        e.commit()
    }
}
