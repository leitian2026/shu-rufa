package com.example.myime

import android.os.SystemClock
import android.util.Base64
import android.util.LruCache
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/** 一组翻译方向。label 显示在翻译输入框左边的小按钮上。from / to 是通用语言代码,各引擎再各自转换。 */
class LangPair(val label: String, val from: String, val to: String)

class TransResult(val text: String, val engine: String)

/**
 * 省电相关:同一句翻过有缓存;请求前先看原文变没变(变了就不发);连接读完不 disconnect,复用连接池里的连接,
 * 省掉每次的握手;超时 4 / 6 秒,不会长时间占着网络。
 *
 * 翻译:微软(Edge 浏览器内置翻译用的那个接口)和谷歌(translate.googleapis.com 的 gtx 接口),
 * 都不需要自己的密钥。设置里选哪个是主用,主用失败会自动换另一个试(备用)。
 *
 * 微软:先向 edge.microsoft.com/translate/auth 要一个临时令牌(约 10 分钟有效,缓存起来,快过期才重要),
 *       再带着令牌 POST 到 api-edge.cognitive.microsofttranslator.com/translate。
 * 谷歌:GET(文字太长时 POST)translate.googleapis.com/translate_a/single?client=gtx。
 *
 * 所有方法都会联网、会阻塞,只能在后台线程调用。只有打开「输入翻译」且你在翻译框里打了字才会调用,
 * 其他时候键盘不联网。
 */
object Translator {
    const val MS = "ms"
    const val GOOGLE = "google"

    /** 目前只放能用拼音 / 英文键盘打出来的原文:中文或英文。 */
    val pairs = listOf(
        LangPair("中›英", "zh", "en"),
        LangPair("英›中", "en", "zh"),
        LangPair("中›日", "zh", "ja"),
        LangPair("中›韩", "zh", "ko"),
        LangPair("中›法", "zh", "fr"),
        LangPair("中›德", "zh", "de"),
        LangPair("中›俄", "zh", "ru"),
        LangPair("中›西", "zh", "es"),
    )

    fun engineLabel(e: String): String = if (e == GOOGLE) "谷歌" else "微软"

    fun other(e: String): String = if (e == GOOGLE) MS else GOOGLE

    private const val MAX_CHARS = 3000
    private const val FAIL_BACKOFF_MS = 60_000L
    private const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/124.0.0.0 Mobile Safari/537.36 EdgA/124.0.0.0"

    private val cache = LruCache<String, String>(80)
    private val failedAt = ConcurrentHashMap<String, Long>()
    private val lock = Any()
    private var token: String? = null
    private var tokenExpireAt = 0L

    private fun key(text: String, p: LangPair) = "${p.from}>${p.to}|$text"

    /** 以前翻过的同一句话直接用,不再联网(退格再重打、来回切方向时省流量也更快)。 */
    fun cached(text: String, p: LangPair): String? = cache.get(key(text, p))

    /** 失败返回 null。主用引擎最近 1 分钟内刚失败过、而另一个没有,就先试另一个,免得每个字都先等它超时。 */
    fun translate(textIn: String, p: LangPair, primary: String, cancelled: () -> Boolean = { false }): TransResult? {
        val text = if (textIn.length > MAX_CHARS) textIn.substring(0, MAX_CHARS) else textIn
        cache.get(key(text, p))?.let { return TransResult(it, "cache") }
        val first = if (primary == GOOGLE) GOOGLE else MS
        var order = listOf(first, other(first))
        val now = SystemClock.elapsedRealtime()
        val f0 = failedAt[order[0]]
        val f1 = failedAt[order[1]]
        val firstBad = f0 != null && now - f0 < FAIL_BACKOFF_MS
        val secondBad = f1 != null && now - f1 < FAIL_BACKOFF_MS
        if (firstBad && !secondBad) order = order.reversed()
        for (e in order) {
            if (cancelled()) return null   // 原文已经又变了:不再发请求(也不再换备用引擎)
            try {
                val out = if (e == GOOGLE) google(text, p) else microsoft(text, p)
                if (!out.isNullOrEmpty()) {
                    failedAt.remove(e)
                    cache.put(key(text, p), out)
                    return TransResult(out, e)
                }
            } catch (t: Throwable) {
                // 这个引擎这次不行,换下一个
            }
            failedAt[e] = SystemClock.elapsedRealtime()
        }
        return null
    }

    // ------------------------------------------------------------ 微软

    private fun msCode(c: String) = if (c == "zh") "zh-Hans" else c

    private fun microsoft(text: String, p: LangPair): String? {
        for (attempt in 0..1) {
            val tk = msToken()
            val url = "https://api-edge.cognitive.microsofttranslator.com/translate" +
                "?from=${msCode(p.from)}&to=${msCode(p.to)}&api-version=3.0&includeSentenceLength=true"
            val body = JSONArray().put(JSONObject().put("Text", text)).toString()
            val c = open(url, "POST")
            try {
                c.setRequestProperty("Authorization", "Bearer $tk")
                c.setRequestProperty("Content-Type", "application/json")
                c.doOutput = true
                c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                val code = c.responseCode
                if (code == 401 || code == 403) {
                    // 令牌被拒(过期 / 被换掉):丢掉缓存,重新要一个再试一次
                    synchronized(lock) { token = null }
                    continue
                }
                if (code != 200) {
                    c.errorStream?.close()
                    return null
                }
                val arr = JSONArray(readAll(c))
                return arr.getJSONObject(0).getJSONArray("translations").getJSONObject(0).getString("text")
            } finally {
                // 不调 disconnect():读完关掉流,连接会回到系统的连接池,下一次直接复用,省掉 TCP + TLS 握手
            }
        }
        return null
    }

    private fun msToken(): String {
        return synchronized(lock) {
            val now = System.currentTimeMillis()
            val cur = token
            if (cur != null && now < tokenExpireAt - 60_000L) {
                cur
            } else {
                val c = open("https://edge.microsoft.com/translate/auth", "GET")
                try {
                    if (c.responseCode != 200) {
                        c.errorStream?.close()
                        throw IOException("auth ${c.responseCode}")
                    }
                    val s = readAll(c).trim()
                    if (s.isEmpty()) throw IOException("auth empty")
                    token = s
                    tokenExpireAt = jwtExpireMs(s) ?: (now + 8 * 60_000L)
                    s
                } finally {
                    // 同上:不 disconnect,让连接回池
                }
            }
        }
    }

    /** 令牌是 JWT,中间那段里有 exp(秒)。读不出来就当 8 分钟。 */
    private fun jwtExpireMs(jwt: String): Long? {
        return try {
            val part = jwt.split('.')[1]
            val json = String(Base64.decode(part, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP), Charsets.UTF_8)
            val exp = JSONObject(json).getLong("exp")
            exp * 1000L
        } catch (t: Throwable) {
            null
        }
    }

    // ------------------------------------------------------------ 谷歌

    private fun googleCode(c: String) = if (c == "zh") "zh-CN" else c

    private fun google(text: String, p: LangPair): String? {
        val q = URLEncoder.encode(text, "UTF-8")
        val base = "https://translate.googleapis.com/translate_a/single" +
            "?client=gtx&sl=${googleCode(p.from)}&tl=${googleCode(p.to)}&dt=t"
        val c: HttpURLConnection
        if (q.length < 3500) {
            c = open("$base&q=$q", "GET")
        } else {
            c = open(base, "POST")
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            c.outputStream.use { it.write("q=$q".toByteArray(Charsets.UTF_8)) }
        }
        try {
            if (c.responseCode != 200) {
                c.errorStream?.close()
                return null
            }
            val segs = JSONArray(readAll(c)).getJSONArray(0)
            val sb = StringBuilder()
            for (i in 0 until segs.length()) {
                val seg = segs.optJSONArray(i) ?: continue
                if (seg.isNull(0)) continue
                sb.append(seg.getString(0))
            }
            return sb.toString()
        } finally {
            // 同上:不 disconnect
        }
    }

    // ------------------------------------------------------------ 公共

    private fun open(url: String, method: String): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 4000
        c.readTimeout = 6000
        c.setRequestProperty("User-Agent", UA)
        c.setRequestProperty("Accept", "application/json, text/plain, */*")
        return c
    }

    private fun readAll(c: HttpURLConnection): String =
        c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
}
