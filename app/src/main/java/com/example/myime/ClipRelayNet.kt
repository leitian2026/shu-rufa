package com.example.myime

import okhttp3.Dns
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * 剪贴板联动的网络层(OkHttp):WebSocket 连 Cloudflare Worker 的 /ws,HTTP 存取 Worker 的 /r2(R2)。协议在 ClipRelayCore。
 *
 * 连接和 R2 请求都带 X-App: clip(Worker 脚本开头的规则:命名空间隔离,R2 只能动 clip/ 开头的对象)。
 * 域名解析用 RelayDns(Host 映射 / 自建 DoH / 系统 DNS,设置页里填,规则见 RelayDnsCore)。
 * 不开 OkHttp 自带的 pingInterval:它发的是 WebSocket 协议层的 ping,Worker 判断超时用的是文本 "ping"(由 ClipRelayCore 在发完以后发一个),两者对不上。
 * 不自动重连、不设重试:连不上就报给上层,等下一次键盘事件(省电)。
 * baseUrl 是 https 地址(RelayCfg 整理过的,不带 /ws),secret 是 Worker 的 SECRET。
 */
class ClipRelayNet(baseUrl: String, private val secret: String, private val dev: String, dns: Dns) : ClipRelayCore.Transport {
    private val base = baseUrl.trim().trimEnd('/')
    private val wsUrl = base.replaceFirst("https://", "wss://") + "/ws"
    private val http: OkHttpClient = baseHttp.newBuilder().dns(dns).build()

    /** WebSocket 不设读超时(长连接,Worker 空闲 90 秒后自己断)。 */
    private val wsHttp: OkHttpClient = http.newBuilder().readTimeout(0, TimeUnit.MILLISECONDS).build()

    @Volatile private var ws: WebSocket? = null
    @Volatile private var open = false
    @Volatile private var lst: ClipRelayCore.Listener? = null
    @Volatile private var gen = 0

    companion object {
        private val baseHttp: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .writeTimeout(20, TimeUnit.SECONDS)
                .build()
        }

        /**
         * 设置页的「测试连接」:用给定的地址和口令连一下 Worker,检查口令、R2 绑定、是不是 v2 脚本。后台线程调用。
         * 返回 null 表示都没问题;否则是给人看的原因。
         */
        fun test(baseUrl: String, secret: String, dns: Dns): String? {
            val base = baseUrl.trim().trimEnd('/')
            val c = baseHttp.newBuilder().dns(dns).connectTimeout(10, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).build()
            fun get(path: String, app: String?): Pair<Int, String> {
                val b = Request.Builder().url(base + path).header("X-Relay-Secret", secret)
                if (app != null) b.header("X-App", app)
                c.newCall(b.get().build()).execute().use { return Pair(it.code, it.body?.string() ?: "") }
            }
            try {
                val (code, body) = get("/test", null)
                when {
                    code == 401 -> return "口令不对(这里填的口令和 Worker 里设置的 SECRET 不一致)"
                    code == 404 -> return "这个地址返回 404:不像是你的 Worker(没有 /test)"
                    code == 500 && body.contains("SECRET") -> return "Worker 还没有设置口令(SECRET)"
                    code != 200 || !body.contains("\"ok\":true") -> return "Worker 返回 HTTP $code,不是预期的回应"
                }
                // R2:列一下 clip/ 前缀
                val (rc, _) = get("/r2?prefix=" + URLEncoder.encode(ClipRelayCore.SNAP_PREFIX, "UTF-8"), ClipRelayCore.NAMESPACE)
                when {
                    rc == 500 -> return "Worker 没有绑定 R2 桶(绑定的变量名必须是 BUCKET),快照存不了"
                    rc == 401 -> return "口令不对"
                    rc == 403 -> return "Worker 不让访问 clip/ 前缀,脚本可能不是 v2"
                    rc != 200 -> return "访问 R2 失败:HTTP $rc"
                }
                // 是不是 v2 脚本:v2 对格式不对的 X-App 返回 400;老脚本会忽略这个头(那样联动会和互传混在一起)
                val (vc, _) = get("/r2?prefix=x", "BAD!")
                if (vc != 400) return "这个 Worker 还是旧版脚本(不认 X-App),联动会和互传混在一起。请先部署 hulian-worker_v2.js"
                return null
            } catch (e: IOException) {
                return RelayDnsCore.hint(e) ?: ("连不上:" + (e.message?.lineSequence()?.first() ?: e.javaClass.simpleName).take(100))
            } catch (e: IllegalArgumentException) {
                return "地址格式不对"
            }
        }
    }

    @Synchronized
    override fun connect(l: ClipRelayCore.Listener) {
        lst = l
        val g = ++gen
        val req = try {
            Request.Builder().url(wsUrl)
                .header("X-Relay-Secret", secret)
                .header("X-Device", dev)
                .header("X-App", ClipRelayCore.NAMESPACE)
                .header("X-Ack", "1")   // 告诉 Worker:本机会回执"新消息"通知,没回执的请在重连时补发
                .build()
        } catch (e: IllegalArgumentException) {
            open = false
            l.onClosed("中转地址格式不对")
            return
        }
        ws = wsHttp.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (g != gen) return
                open = true
                l.onOpen()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (g == gen) l.onText(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (g != gen) return
                open = false
                l.onClosed("服务器关闭了连接($code $reason)")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (g != gen) return
                open = false
                l.onClosed(explain(t, response))
            }
        })
    }

    override fun isOpen(): Boolean = open && ws != null

    override fun send(text: String): Boolean = ws?.send(text) ?: false

    @Synchronized
    override fun close() {
        gen++   // 让旧连接之后的回调都被忽略
        val w = ws
        val wasOpen = open
        open = false
        ws = null
        try {
            w?.cancel()
        } catch (e: Exception) {
            // 已经断了
        }
        if (wasOpen) lst?.onClosed("本机关闭了连接")
    }

    /** 不用了(换了设置、关了联动):关掉连接池里闲着的连接。收尾要做网络操作,另开线程,不占调用线程。 */
    fun dispose() {
        close()
        Thread {
            try {
                http.connectionPool.evictAll()
            } catch (e: Exception) {
                // 无所谓
            }
        }.start()
    }

    private fun explain(t: Throwable, r: Response?): String {
        val code = r?.code
        if (code == 401) return "Worker 说口令不对(设置里的口令和 Worker 的 SECRET 不一致)"
        if (code == 400) return "Worker 说请求头格式不对(HTTP 400)"
        if (code != null) return "Worker 返回 HTTP $code"
        RelayDnsCore.hint(t)?.let { return it }
        val m = t.message
        return "连不上 Cloudflare:" + (if (m.isNullOrBlank()) t.javaClass.simpleName else m.lineSequence().first().take(100))
    }

    // ---------------------------------------------------------------- R2(HTTP)

    private fun r2Url(key: String): String =
        base + "/r2/" + key.split('/').joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }

    private fun builder(url: String): Request.Builder =
        Request.Builder().url(url).header("X-Relay-Secret", secret).header("X-App", ClipRelayCore.NAMESPACE)

    private fun fail(code: Int): IOException = IOException(
        when (code) {
            401 -> "Worker 说口令不对(设置里的口令和 SECRET 不一致)"
            403 -> "Worker 不让访问这个对象(只能动 clip/ 开头的)"
            500 -> "Worker 没有绑定 R2 桶(变量名必须是 BUCKET)或口令没设置"
            else -> "Worker 返回 HTTP $code"
        },
    )

    /** 网络层的异常换成带中文提示的(DNS 被改 / 握手被掐)。 */
    private fun wrap(e: IOException): IOException {
        val h = RelayDnsCore.hint(e) ?: return e
        return IOException(h, e)
    }

    override fun r2Put(key: String, data: ByteArray) {
        val req = builder(r2Url(key)).put(data.toRequestBody("application/octet-stream".toMediaType())).build()
        try {
            http.newCall(req).execute().use { if (!it.isSuccessful) throw fail(it.code) }
        } catch (e: IOException) {
            throw wrap(e)
        }
    }

    override fun r2Get(key: String): ByteArray? {
        try {
            http.newCall(builder(r2Url(key)).get().build()).execute().use {
                if (it.code == 404) return null
                if (!it.isSuccessful) throw fail(it.code)
                return it.body?.bytes() ?: ByteArray(0)
            }
        } catch (e: IOException) {
            throw wrap(e)
        }
    }

    override fun r2List(prefix: String): List<String> {
        val url = base + "/r2?prefix=" + URLEncoder.encode(prefix, "UTF-8")
        try {
            http.newCall(builder(url).get().build()).execute().use {
                if (!it.isSuccessful) throw fail(it.code)
                return ClipRelayCore.parseListKeys(it.body?.string() ?: "")
            }
        } catch (e: IOException) {
            throw wrap(e)
        }
    }

    override fun r2Delete(key: String) {
        try {
            http.newCall(builder(r2Url(key)).delete().build()).execute().use {
                if (!it.isSuccessful && it.code != 404) throw fail(it.code)
            }
        } catch (e: IOException) {
            throw wrap(e)
        }
    }
}
