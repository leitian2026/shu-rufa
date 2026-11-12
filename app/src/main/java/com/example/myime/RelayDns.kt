package com.example.myime

import android.content.Context
import okhttp3.Call
import okhttp3.Dns
import okhttp3.EventListener
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.dnsoverhttps.DnsOverHttps
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * 连 Worker 时 OkHttp 用的域名解析(防 DNS 劫持)。每次解析都读最新的设置(RelayCfg),所以改了设置不用重建客户端。
 * 规则和顺序在 RelayDnsCore:Host 映射 → 自建 DoH → 系统 DNS,各有开关;这里只负责真的去问 DoH 和系统。
 * 全进程只有一个实例(RelayDns.get):剪贴板联动和翻译共用同一份解析缓存,改了设置一次 reset 两边都生效。
 * 局域网和手机直连不经过这里;这只解决 DNS 被改,TLS 握手里的域名(SNI)仍是明文。
 *
 * Host 映射的 IP 连不上时:用 events(OkHttp 的连接事件)记下这个域名,下一次解析改成先问 DoH / 系统 DNS,映射的 IP 排后面;
 * 连上了就清掉记录。全靠连接事件触发,没有定时器、不轮询。
 */
class RelayDns private constructor(private val app: Context) : Dns {
    companion object {
        @Volatile private var inst: RelayDns? = null

        fun get(ctx: Context): RelayDns = inst ?: synchronized(this) {
            inst ?: RelayDns(ctx.applicationContext).also { inst = it }
        }

        /** Host 映射的 IP 连不上以后,这么久之内先走 DoH / 系统 DNS(过了再先试映射的 IP)。 */
        private const val FAIL_MS = 2 * 60_000L
    }

    private val resolver = RelayDnsCore.Resolver()
    @Volatile private var dohCache: Pair<String, List<RelayDnsCore.Lookup>>? = null
    private val system = RelayDnsCore.Lookup { h -> Dns.SYSTEM.lookup(h) }
    private val failedAt = ConcurrentHashMap<String, Long>()
    private val sources = ConcurrentHashMap<String, String>()

    /** 挂在用到本解析器的 OkHttpClient 上(eventListenerFactory),用来知道映射的 IP 连得上还是连不上。 */
    val eventFactory: EventListener.Factory = EventListener.Factory {
        object : EventListener() {
            override fun connectFailed(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy, protocol: Protocol?, ioe: IOException) {
                failedAt[call.request().url.host.lowercase()] = System.currentTimeMillis()
            }

            override fun connectEnd(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy, protocol: Protocol?) {
                failedAt.remove(call.request().url.host.lowercase())
            }
        }
    }

    override fun lookup(hostname: String): List<InetAddress> {
        val hostsOn = RelayCfg.hostsOn(app)
        val hostsText = RelayCfg.hostsText(app)
        // DoH 服务器自己的域名始终查映射(引导),不受「使用 Host 映射」开关影响;开关只管要连的域名(Worker)
        val allHosts = RelayDnsCore.parseHosts(hostsText).map
        val hosts = if (hostsOn) allHosts else emptyMap()
        val dohOn = RelayCfg.dohOn(app)
        val sysOk = RelayCfg.sysDns(app)
        val dohs = if (dohOn) dohList(RelayCfg.dohText(app), allHosts, hostsText, sysOk) else emptyList()
        val key = hostname.lowercase()
        val now = System.currentTimeMillis()
        val t = failedAt[key]
        val hostsFailed = t != null && now - t < FAIL_MS
        val res = resolver.resolve(hostname, hosts, dohOn, dohs, sysOk, system, now, hostsFailed)
        sources[key] = res.source
        return res.addrs
    }

    /** 这个域名最近一次是怎么解析出来的(Host 映射 / 自建 DoH / 系统 DNS 及回退原因);还没解析过是 null。给「测试连接」和错误提示用。 */
    fun sourceOf(host: String): String? = sources[host.lowercase()]

    /** 设置改了以后调用:清掉解析缓存和连接失败记录。 */
    fun reset() {
        resolver.reset()
        dohCache = null
        failedAt.clear()
        sources.clear()
    }

    /** 每行一个 DoH 地址。DoH 服务器自己的域名也先查 Host 映射(相当于 bootstrap),避免「解析 DoH 要先解析 DoH」的死循环。 */
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
            val url = line.toHttpUrlOrNull() ?: continue
            if (url.scheme != "https") continue
            val boot = hosts[url.host.lowercase()]
            // 关了「使用系统 DNS」时,DoH 服务器自己的域名没有映射就没法引导(引导会用系统 DNS):这一条记成一个会报原因的失败项,
            // 不再悄悄跳过,最后的错误提示里能看到;直接写 IP 的不受影响
            if (boot.isNullOrEmpty() && !sysOk && RelayDnsCore.literal(url.host) == null) {
                val h = url.host
                list.add(RelayDnsCore.Lookup { throw IOException("DoH 服务器 $h 没有 Host 映射,又关了「使用系统 DNS」,没法引导") })
                continue
            }
            val b = DnsOverHttps.Builder().client(client).url(url).includeIPv6(true).resolvePrivateAddresses(false)
            if (!boot.isNullOrEmpty()) b.bootstrapDnsHosts(boot)
            val d = b.build()
            list.add(RelayDnsCore.Lookup { h -> d.lookup(h) })
        }
        dohCache = key to list
        return list
    }
}
