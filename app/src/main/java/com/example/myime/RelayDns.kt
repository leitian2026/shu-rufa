package com.example.myime

import android.content.Context
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * 连 Worker 时 OkHttp 用的域名解析(防 DNS 劫持)。每次解析都读最新的设置(RelayCfg),所以改了设置不用重建客户端。
 * 规则在 RelayDnsCore:Host 映射、自建 DoH、系统 DNS 三个来源各有开关,各自独立、互不依赖、互不影响——
 * 每个域名只由第一个适用的来源解析,它失败就是失败,绝不换到别的来源;这里只负责真的去问 DoH 和系统。
 * 唯一的依赖:DoH 服务器本身是域名,要查 DNS 才能连上它——先用 DoH 那一行后面写的 IP,没有就查 Host 映射(开关开着才查),再没有才用系统 DNS(开关开着才用)。
 * Host 映射是绑定、系统 DNS 自己就是解析,这两个不依赖任何别的来源。
 * 剪贴板联动和翻译各自 new 一个 RelayDns:解析缓存、最近一次的解析来源都各记各的,互不影响。
 * 局域网和手机直连不经过这里;这只解决 DNS 被改,TLS 握手里的域名(SNI)仍是明文。
 */
class RelayDns(private val app: Context) : Dns {
    private val resolver = RelayDnsCore.Resolver()
    @Volatile private var dohCache: Pair<String, List<RelayDnsCore.Lookup>>? = null
    private val system = RelayDnsCore.Lookup { h -> Dns.SYSTEM.lookup(h) }
    private val sources = ConcurrentHashMap<String, String>()

    override fun lookup(hostname: String): List<InetAddress> {
        val hostsText = if (RelayCfg.hostsOn(app)) RelayCfg.hostsText(app) else ""
        val hosts = RelayDnsCore.parseHosts(hostsText).map   // 映射开关关着就是空的:既不用来解析 Worker,也不用来找 DoH 服务器
        val dohOn = RelayCfg.dohOn(app)
        val sysOk = RelayCfg.sysDns(app)
        val dohs = if (dohOn) dohList(RelayCfg.dohText(app), hosts, hostsText, sysOk) else emptyList()
        // 剪贴板和翻译共用这个解析器、而且键盘一弹出就同时来问同一个域名:DoH 开着时排队,后到的直接吃前一个写进缓存的结果,不重复问 DoH 服务器
        val res = if (dohOn) synchronized(dohLock) { resolver.resolve(hostname, hosts, dohOn, dohs, sysOk, system, System.currentTimeMillis()) }
        else resolver.resolve(hostname, hosts, dohOn, dohs, sysOk, system, System.currentTimeMillis())
        sources[hostname.lowercase()] = res.source
        return res.addrs
    }

    private val dohLock = Any()

    /** 这个域名最近一次(这个实例里)是怎么解析出来的:Host 映射 / 自建 DoH / 系统 DNS;还没解析过是 null。给「测试连接」用。 */
    fun sourceOf(host: String): String? = sources[host.lowercase()]

    /** 设置改了以后调用:清掉解析缓存。 */
    fun reset() {
        resolver.reset()
        dohCache = null
        sources.clear()
    }

    /**
     * 每行「https 地址 [IP…]」。DoH 服务器是域名时,找到它的办法依次是:这一行后面写的 IP → Host 映射(hosts 是生效的映射,开关关着就是空)→ 系统 DNS(开着才用)。
     * 三样都没有就没法找到它,这一行记成一次会报原因的失败(不静默跳过)。
     */
    private fun dohList(text: String, hosts: Map<String, List<InetAddress>>, hostsKey: String, sysOk: Boolean): List<RelayDnsCore.Lookup> {
        val key = text + "\n" + hostsKey + "\n" + sysOk
        dohCache?.let { if (it.first == key) return it.second }
        val client = OkHttpClient.Builder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(4, TimeUnit.SECONDS)
            .callTimeout(6, TimeUnit.SECONDS)
            .build()
        val list = ArrayList<RelayDnsCore.Lookup>()
        for (line in RelayDnsCore.dohLines(text)) {
            val url = RelayDnsCore.dohUrl(line).toHttpUrlOrNull() ?: continue
            if (url.scheme != "https") continue
            val ips = RelayDnsCore.dohIps(line).ifEmpty { hosts[url.host.lowercase()].orEmpty() }
            if (ips.isEmpty() && !sysOk && RelayDnsCore.literal(url.host) == null) {
                val h = url.host
                list.add(RelayDnsCore.Lookup { throw IOException("DoH 服务器 $h 没写 IP、Host 映射里也没有,又关了「使用系统 DNS」,没法找到它") })
                continue
            }
            val b = DnsOverHttps.Builder().client(client).url(url).includeIPv6(true).resolvePrivateAddresses(false)
            if (ips.isNotEmpty()) b.bootstrapDnsHosts(ips)
            val d = b.build()
            list.add(RelayDnsCore.Lookup { h -> d.lookup(h) })
        }
        dohCache = key to list
        return list
    }
}
