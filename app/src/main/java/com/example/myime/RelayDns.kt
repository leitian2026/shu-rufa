package com.example.myime

import android.content.Context
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/**
 * 连 Worker 时 OkHttp 用的域名解析(防 DNS 劫持)。每次解析都读最新的设置(RelayCfg),所以改了设置不用重建客户端。
 * 规则和顺序在 RelayDnsCore:Host 映射 → 自建 DoH → 系统 DNS,各有开关;这里只负责真的去问 DoH 和系统。
 * 局域网和手机直连不经过这里;这只解决 DNS 被改,TLS 握手里的域名(SNI)仍是明文。
 */
class RelayDns(private val app: Context) : Dns {
    private val resolver = RelayDnsCore.Resolver()
    @Volatile private var dohCache: Pair<String, List<RelayDnsCore.Lookup>>? = null
    private val system = RelayDnsCore.Lookup { h -> Dns.SYSTEM.lookup(h) }

    override fun lookup(hostname: String): List<InetAddress> {
        val hostsOn = RelayCfg.hostsOn(app)
        val hostsText = RelayCfg.hostsText(app)
        val hosts = if (hostsOn) RelayDnsCore.parseHosts(hostsText).map else emptyMap()
        val dohOn = RelayCfg.dohOn(app)
        val sysOk = RelayCfg.sysDns(app)
        val dohs = if (dohOn) dohList(RelayCfg.dohText(app), hosts, if (hostsOn) hostsText else "", sysOk) else emptyList()
        return resolver.resolve(hostname, hosts, dohOn, dohs, sysOk, system, System.currentTimeMillis()).addrs
    }

    /** 设置改了以后调用:清掉解析缓存。 */
    fun reset() {
        resolver.reset()
        dohCache = null
    }

    /** 每行一个 DoH 地址。DoH 服务器自己的域名也先查 Host 映射(相当于 bootstrap),避免「解析 DoH 要先解析 DoH」的死循环。 */
    private fun dohList(text: String, hosts: Map<String, List<InetAddress>>, hostsKey: String, sysOk: Boolean): List<RelayDnsCore.Lookup> {
        val key = text + "\n" + hostsKey + "\n" + sysOk
        dohCache?.let { if (it.first == key) return it.second }
        val client = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .build()
        val list = ArrayList<RelayDnsCore.Lookup>()
        for (line in RelayDnsCore.dohLines(text)) {
            val url = line.toHttpUrlOrNull() ?: continue
            if (url.scheme != "https") continue
            val boot = hosts[url.host.lowercase()]
            // 关了「使用系统 DNS」时,DoH 服务器自己的域名没有映射就没法引导(引导会用系统 DNS),这一条跳过;直接写 IP 的不受影响
            if (boot.isNullOrEmpty() && !sysOk && RelayDnsCore.literal(url.host) == null) continue
            val b = DnsOverHttps.Builder().client(client).url(url).includeIPv6(true).resolvePrivateAddresses(false)
            if (!boot.isNullOrEmpty()) b.bootstrapDnsHosts(boot)
            val d = b.build()
            list.add(RelayDnsCore.Lookup { h -> d.lookup(h) })
        }
        dohCache = key to list
        return list
    }
}
