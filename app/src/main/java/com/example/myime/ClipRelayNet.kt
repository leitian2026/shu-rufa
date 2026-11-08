package com.example.myime

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
 * 不开 OkHttp 自带的 pingInterval:它发的是 WebSocket 协议层的 ping,Worker 判断超时用的是文本 "ping"(由 ClipRelayCore 在发完以后发一个),两者对不上。
 * 不自动重连、不设重试:连不上就报给上层,等下一次键盘事件(省电)。
 * baseUrl 是 https 地址(不带 /ws),secret 是 Worker 的 SECRET;都在构建时内置(BuildConfig.RELAY_URL / RELAY_SECRET)。
 */
class ClipRelayNet(baseUrl: String, private val secret: String, private val dev: String) : ClipRelayCore.Transport {
    private val base = baseUrl.trim().trimEnd('/')
    private val wsUrl = base.replaceFirst("https://", "wss://") + "/ws"

    @Volatile private var ws: WebSocket? = null
    @Volatile private var open = false
    @Volatile private var lst: ClipRelayCore.Listener? = null
    @Volatile private var gen = 0

    companion object {
        private val http: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .writeTimeout(20, TimeUnit.SECONDS)
                .build()
        }

        /** WebSocket 不设读超时(长连接,Worker 空闲 90 秒后自己断)。 */
        private val wsHttp: OkHttpClient by lazy { http.newBuilder().readTimeout(0, TimeUnit.MILLISECONDS).build() }

        /** 校验地址:必须是 https,不带路径以外的东西。返回错误说明,没问题返回 null。 */
        fun urlError(u: String): String? {
            val t = u.trim()
            if (t.isEmpty()) return "这个构建没有内置中转地址"
            if (!t.startsWith("https://")) return "中转地址必须以 https:// 开头"
            return null
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

    private fun explain(t: Throwable, r: Response?): String {
        val code = r?.code
        if (code == 401) return "Worker 说口令不对(构建时内置的 RELAY_SECRET 和 Worker 的 SECRET 不一致)"
        if (code == 400) return "Worker 说请求头格式不对(HTTP 400)"
        if (code != null) return "Worker 返回 HTTP $code"
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
            401 -> "Worker 说口令不对(RELAY_SECRET 和 SECRET 不一致)"
            403 -> "Worker 不让访问这个对象(只能动 clip/ 开头的)"
            500 -> "Worker 没有绑定 R2 桶(变量名必须是 BUCKET)或口令没设置"
            else -> "Worker 返回 HTTP $code"
        },
    )

    override fun r2Put(key: String, data: ByteArray) {
        val req = builder(r2Url(key)).put(data.toRequestBody("application/octet-stream".toMediaType())).build()
        http.newCall(req).execute().use { if (!it.isSuccessful) throw fail(it.code) }
    }

    override fun r2Get(key: String): ByteArray? {
        http.newCall(builder(r2Url(key)).get().build()).execute().use {
            if (it.code == 404) return null
            if (!it.isSuccessful) throw fail(it.code)
            return it.body?.bytes() ?: ByteArray(0)
        }
    }

    override fun r2List(prefix: String): List<String> {
        val url = base + "/r2?prefix=" + URLEncoder.encode(prefix, "UTF-8")
        http.newCall(builder(url).get().build()).execute().use {
            if (!it.isSuccessful) throw fail(it.code)
            return ClipRelayCore.parseListKeys(it.body?.string() ?: "")
        }
    }

    override fun r2Delete(key: String) {
        http.newCall(builder(r2Url(key)).delete().build()).execute().use {
            if (!it.isSuccessful && it.code != 404) throw fail(it.code)
        }
    }
}
