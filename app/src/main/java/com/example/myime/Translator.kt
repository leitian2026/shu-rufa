package com.example.myime

import android.content.Context
import android.os.SystemClock
import android.util.LruCache
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request

/** 一组翻译方向。label 显示在翻译输入框左边的小按钮上。from / to 是通用语言代码,各引擎再各自转换。 */
class LangPair(val label: String, val from: String, val to: String)

class TransResult(val text: String, val engine: String)

/**
 * 省电相关:同一句翻过有缓存;请求前先看原文变没变(变了就不发);连接读完不 disconnect,复用连接池里的连接,
 * 省掉每次的握手;超时 4 / 6 秒,不会长时间占着网络。
 *
 * 翻译:微软(Edge 浏览器内置翻译用的接口)和谷歌(translate.googleapis.com 的 gtx 接口),
 * 都不需要自己的密钥。设置里选哪个是主用,主用失败会自动换另一个试(备用)。
 *
 * 微软:POST edge.microsoft.com/translate/translatetext(新接口,不要令牌;旧的 auth 令牌接口已被微软下线)。
 * 谷歌:GET(文字太长时 POST)translate.googleapis.com/translate_a/single?client=gtx。
 *       「经 Worker 中转」打开时改走你自己的 Cloudflare Worker(POST /tr/google,原文放请求体里、不进网址;地址、口令、域名解析用备份页里联动那套设置,
 *       见 RelayCfg / RelayDns;Worker 脚本规则 9),Google 在你那边连不上时用。这条路失败照样自动换微软。
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
    private const val FAIL_BACKOFF_MS = 10 * 60_000L   // 一个引擎失败后,10 分钟内先用另一个(失败的只当备用),免得每次都先白等它超时
    private const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/124.0.0.0 Mobile Safari/537.36 EdgA/124.0.0.0"

    /** 最近一次两个引擎都失败时的原因,给翻译框显示(例如「微软403 谷歌超时」),方便判断是网络还是接口的问题。 */
    @Volatile var lastFail = ""
        private set
    private val reasons = ConcurrentHashMap<String, String>()

    private class HttpFail(val code: Int) : IOException("HTTP $code")

    private fun reasonOf(t: Throwable): String = when (t) {
        is HttpFail -> "${t.code}"
        is NotSet -> "没设Worker"
        is WorkerFail -> "Worker:" + t.msg.ifBlank { t.code.toString() }.take(40)
        is java.net.SocketTimeoutException -> "超时"
        is java.net.UnknownHostException -> "域名失败"
        is javax.net.ssl.SSLException -> "TLS失败"
        is org.json.JSONException -> "返回异常"
        else -> t.javaClass.simpleName.take(10)
    }

    // 主页「翻译接口」里填的设置;空 = 用默认。由 load() 在每次翻译前从设置里读(SharedPreferences 有内存缓存,几乎没有开销)
    const val DEF_MS_FREE = "https://edge.microsoft.com/translate/translatetext"
    const val DEF_MS_AZURE = "https://api.cognitive.microsofttranslator.com/translate"
    const val DEF_G_FREE = "https://translate.googleapis.com/translate_a/single"
    const val DEF_G_CLOUD = "https://translation.googleapis.com/language/translate/v2"
    @Volatile private var msUrl = ""
    @Volatile private var msKey = ""
    @Volatile private var msRegion = ""
    @Volatile private var gUrl = ""
    @Volatile private var gKey = ""
    @Volatile private var workerOn = false   // 谷歌这一路经你自己的 Worker 中转(设置里的开关,键 g_worker)
    @Volatile private var appCtx: Context? = null
    private var workerHttp: OkHttpClient? = null

    /** 没有设置 Worker 地址和口令(开了「经 Worker 中转」却没设置)。 */
    private class NotSet : IOException("没有设置 Worker 地址和口令")

    /** Worker 返回了错误:msg 是 Worker 写的原因(例如「谷歌拒绝了:GET 429、POST 429」),detail 是谷歌返回内容的开头一小段(不含原文)。 */
    private class WorkerFail(val code: Int, val msg: String, val detail: String) : IOException("HTTP $code $msg")

    fun load(ctx: android.content.Context) {
        workerOn = DictConfig.transSetting(ctx, "g_worker") == "1"
        appCtx = ctx.applicationContext
        msUrl = DictConfig.transSetting(ctx, "ms_url")
        msKey = DictConfig.transSetting(ctx, "ms_key")
        msRegion = DictConfig.transSetting(ctx, "ms_region")
        gUrl = DictConfig.transSetting(ctx, "g_url")
        gKey = DictConfig.transSetting(ctx, "g_key")
    }

    /** 主页的「测试」按钮用:只试指定的一个引擎(不换备用、不查缓存),返回译文或失败原因。后台线程调用。 */
    fun test(e: String): String {
        val p = pairs[0]
        return try {
            val out = if (e == GOOGLE) google("你好", p) else microsoft("你好", p)
            if (out.isNullOrEmpty()) "失败:无结果" else "成功:$out"
        } catch (t: Throwable) {
            // 经 Worker 失败时把 Worker 写的原因和谷歌返回内容的开头都显示出来(测试按钮用,方便看出为什么被拒)
            "失败:" + if (t is WorkerFail && t.msg.isNotBlank()) t.msg + (if (t.detail.isNotBlank()) "(" + t.detail.take(120) + ")" else "") else reasonOf(t)
        }
    }

    /** 先试哪个:主用引擎;但它最近 10 分钟内失败过、另一个没有,就先试另一个。 */
    fun orderFor(primary: String): List<String> {
        val first = if (primary == GOOGLE) GOOGLE else MS
        val order = listOf(first, other(first))
        val now = SystemClock.elapsedRealtime()
        val f0 = failedAt[order[0]]
        val f1 = failedAt[order[1]]
        val firstBad = f0 != null && now - f0 < FAIL_BACKOFF_MS
        val secondBad = f1 != null && now - f1 < FAIL_BACKOFF_MS
        return if (firstBad && !secondBad) order.reversed() else order
    }

    @Volatile private var warmedAt = -WARM_EVERY_MS

    /** 提前建立到主用引擎服务器的连接(见 MyImeService.warmTranslate)。自己开一个短命线程,不堵翻译用的线程。 */
    fun warm(primary: String) {
        val now = SystemClock.elapsedRealtime()
        if (now - warmedAt < WARM_EVERY_MS) return
        warmedAt = now
        val base = if (orderFor(primary)[0] == GOOGLE) gUrl.ifBlank { DEF_G_FREE } else msUrl.ifBlank { DEF_MS_FREE }
        val eng = orderFor(primary)[0]
        val viaWorker = workerOn && eng == GOOGLE
        val app = appCtx
        Thread {
            try {
                if (viaWorker) {
                    // 经 Worker:预热到 Worker 的连接(走 RelayDns,Worker 的域名可能要靠 Host 映射才连得上);随便什么回应都行,只要连接建好
                    val a = app ?: return@Thread
                    val wb = RelayCfg.url(a)
                    if (wb.isEmpty()) return@Thread
                    workerClient(a).newCall(Request.Builder().url(wb).head().build()).execute().close()
                    return@Thread
                }
                val u = URL(base)
                val c = URL("${u.protocol}://${u.host}/").openConnection() as HttpURLConnection
                c.requestMethod = "HEAD"
                c.connectTimeout = 4000
                c.readTimeout = 4000
                c.setRequestProperty("User-Agent", UA)
                if (c.responseCode < 400) c.inputStream.close() else c.errorStream?.close()
            } catch (t: Throwable) {
                warmedAt = -WARM_EVERY_MS   // 没成功:下次还可以再试
                // 连都连不上(超时 / 域名失败):记一笔,真正翻译时就先用另一个引擎,不再白等
                if (t is java.net.SocketTimeoutException || t is java.net.UnknownHostException || t is java.net.ConnectException) {
                    failedAt[eng] = SystemClock.elapsedRealtime()
                }
            }
        }.start()
    }

    private const val WARM_EVERY_MS = 4 * 60_000L

    private fun withQuery(base: String, query: String) = base + (if (base.contains('?')) "&" else "?") + query

    private val cache = LruCache<String, String>(80)
    private val failedAt = ConcurrentHashMap<String, Long>()

    private fun key(text: String, p: LangPair) = "${p.from}>${p.to}|$text"

    /** 以前翻过的同一句话直接用,不再联网(退格再重打、来回切方向时省流量也更快)。 */
    fun cached(text: String, p: LangPair): String? = cache.get(key(text, p))

    /** 失败返回 null。主用引擎最近 1 分钟内刚失败过、而另一个没有,就先试另一个,免得每个字都先等它超时。 */
    fun translate(textIn: String, p: LangPair, primary: String, cancelled: () -> Boolean = { false }): TransResult? {
        val text = if (textIn.length > MAX_CHARS) textIn.substring(0, MAX_CHARS) else textIn
        cache.get(key(text, p))?.let { return TransResult(it, "cache") }
        val order = orderFor(primary)
        reasons.clear()
        for (e in order) {
            if (cancelled()) return null   // 原文已经又变了:不再发请求(也不再换备用引擎)
            try {
                val out = if (e == GOOGLE) google(text, p) else microsoft(text, p)
                if (!out.isNullOrEmpty()) {
                    failedAt.remove(e)
                    cache.put(key(text, p), out)
                    return TransResult(out, e)
                }
                reasons[e] = "无结果"
            } catch (t: Throwable) {
                // 这个引擎这次不行,换下一个
                reasons[e] = reasonOf(t)
            }
            failedAt[e] = SystemClock.elapsedRealtime()
        }
        lastFail = order.joinToString(" ") { engineLabel(it) + (reasons[it] ?: "") }
        return null
    }

    // ------------------------------------------------------------ 微软

    private fun msCode(c: String) = if (c == "zh") "zh-Hans" else c

    /**
     * 2026-07 起微软撤掉了旧的「令牌 + api-edge」流程(edge.microsoft.com/translate/auth 返回 404)。
     * 现在 Edge 用的是不要令牌的新接口:POST edge.microsoft.com/translate/translatetext,
     * 请求体是纯字符串数组 ["原文"],返回 [{"translations":[{"text":"译文"}]}]。
     * 这个接口会按 HTML 处理文本,所以 & < > 先转义,译文回来再还原。
     */
    private fun microsoft(text: String, p: LangPair): String? {
        val key = msKey
        val c: HttpURLConnection
        val azure = key.isNotEmpty()
        if (azure) {
            // 填了密钥:按微软官方 Translator 接口(Azure)走
            val url = withQuery(msUrl.ifBlank { DEF_MS_AZURE }, "api-version=3.0&from=${msCode(p.from)}&to=${msCode(p.to)}")
            c = open(url, "POST")
            c.setRequestProperty("Ocp-Apim-Subscription-Key", key)
            if (msRegion.isNotEmpty()) c.setRequestProperty("Ocp-Apim-Subscription-Region", msRegion)
            c.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            c.doOutput = true
            val body = JSONArray().put(JSONObject().put("Text", text)).toString()
            c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        } else {
            val url = withQuery(msUrl.ifBlank { DEF_MS_FREE }, "from=${msCode(p.from)}&to=${msCode(p.to)}&isEnterpriseClient=false")
            c = open(url, "POST")
            c.setRequestProperty("Content-Type", "application/json")
            c.doOutput = true
            // 这个接口会按 HTML 处理文本,所以 & < > 先转义,译文回来再还原
            val esc = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            c.outputStream.use { it.write(JSONArray().put(esc).toString().toByteArray(Charsets.UTF_8)) }
        }
        val code = c.responseCode
        if (code != 200) {
            c.errorStream?.close()
            throw HttpFail(code)
        }
        // 不调 disconnect():读完关掉流,连接会回到系统的连接池,下一次直接复用
        val arr = JSONArray(readAll(c))
        val out = arr.getJSONObject(0).getJSONArray("translations").getJSONObject(0).getString("text")
        return if (azure) out else out.replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")
    }

    // ------------------------------------------------------------ 谷歌

    private fun googleCode(c: String) = if (c == "zh") "zh-CN" else c

    private fun google(text: String, p: LangPair): String? {
        if (workerOn) return googleViaWorker(text, p)
        val q = URLEncoder.encode(text, "UTF-8")
        val key = gKey
        val c: HttpURLConnection
        if (key.isNotEmpty()) {
            // 填了密钥:按谷歌官方 Cloud Translation v2 走(POST 表单)
            val url = withQuery(gUrl.ifBlank { DEF_G_CLOUD }, "key=${URLEncoder.encode(key, "UTF-8")}")
            c = open(url, "POST")
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            val form = "q=$q&source=${googleCode(p.from)}&target=${googleCode(p.to)}&format=text"
            c.outputStream.use { it.write(form.toByteArray(Charsets.UTF_8)) }
            if (c.responseCode != 200) {
                c.errorStream?.close()
                throw HttpFail(c.responseCode)
            }
            val tr = JSONObject(readAll(c)).getJSONObject("data").getJSONArray("translations")
            return tr.getJSONObject(0).getString("translatedText")
        }
        val base = withQuery(gUrl.ifBlank { DEF_G_FREE }, "client=gtx&sl=${googleCode(p.from)}&tl=${googleCode(p.to)}&dt=t")
        if (q.length < 3500) {
            c = open("$base&q=$q", "GET")
            browserHeaders(c)
        } else {
            c = open(base, "POST")
            browserHeaders(c)
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            c.outputStream.use { it.write("q=$q".toByteArray(Charsets.UTF_8)) }
        }
        if (c.responseCode != 200) {
            c.errorStream?.close()
            throw HttpFail(c.responseCode)
        }
        return parseGtx(readAll(c))
    }

    /** 免费接口专用:补上 Chrome 地址栏直接打开网址时会带的那几个头(sec-ch-ua、Sec-Fetch-*、Upgrade-Insecure-Requests),别的接口不带。 */
    private fun browserHeaders(c: HttpURLConnection) {
        c.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8")
        c.setRequestProperty("sec-ch-ua", "\"Chromium\";v=\"124\", \"Not-A.Brand\";v=\"99\", \"Google Chrome\";v=\"124\"")
        c.setRequestProperty("sec-ch-ua-mobile", "?1")
        c.setRequestProperty("sec-ch-ua-platform", "\"Android\"")
        c.setRequestProperty("Sec-Fetch-Dest", "document")
        c.setRequestProperty("Sec-Fetch-Mode", "navigate")
        c.setRequestProperty("Sec-Fetch-Site", "none")
        c.setRequestProperty("Sec-Fetch-User", "?1")
        c.setRequestProperty("Upgrade-Insecure-Requests", "1")
    }

    /** gtx 接口的返回:[[["译文片段","原文片段",…],…],…],把各片段的译文拼起来。 */
    private fun parseGtx(body: String): String {
        val segs = JSONArray(body).getJSONArray(0)
        val sb = StringBuilder()
        for (i in 0 until segs.length()) {
            val seg = segs.optJSONArray(i) ?: continue
            if (seg.isNull(0)) continue
            sb.append(seg.getString(0))
        }
        return sb.toString()
    }

    /**
     * 经 Worker 中转:POST {Worker 地址}/tr/google?sl=…&tl=…,请求头带口令和 X-App: tr,原文放在请求体 q=… 里(不进网址,Cloudflare 的访问记录里也看不到)。
     * Worker 只转发到谷歌的 gtx 接口,返回和直连一样的格式,所以解析用同一个 parseGtx。域名解析走 RelayDns(Host 映射 / 自建 DoH),
     * 和剪贴板联动连 Worker 是同一套;超时和直连一样(连接 4 秒、读 6 秒),连接池里的连接会复用。
     */
    private fun googleViaWorker(text: String, p: LangPair): String? {
        val app = appCtx ?: throw NotSet()
        val base = RelayCfg.url(app)
        val secret = RelayCfg.secret(app)
        if (base.isEmpty() || secret == null) throw NotSet()
        val req = Request.Builder()
            .url(base + "/tr/google?sl=" + googleCode(p.from) + "&tl=" + googleCode(p.to))
            .header("X-Relay-Secret", secret)
            .header("X-App", "tr")
            .post(FormBody.Builder(Charsets.UTF_8).add("q", text).build())
            .build()
        workerClient(app).newCall(req).execute().use { r ->
            if (!r.isSuccessful) {
                var msg = ""
                var detail = ""
                try {
                    val j = org.json.JSONObject(r.body?.string() ?: "")
                    msg = j.optString("error")
                    detail = j.optString("detail")
                } catch (e: Exception) {
                    // 不是 Worker 的错误格式(比如口令不对时别的网关回的页面):只报状态码
                }
                throw WorkerFail(r.code, msg, detail)
            }
            return parseGtx(r.body?.string() ?: "")
        }
    }

    /** 域名解析设置改了:丢掉连到 Worker 的旧客户端和它连接池里已建好的连接,下次按新设置重新解析、重新连。 */
    @Synchronized
    fun dnsChanged() {
        workerHttp?.connectionPool?.evictAll()
        workerHttp = null
    }

    @Synchronized
    private fun workerClient(app: Context): OkHttpClient = workerHttp ?: OkHttpClient.Builder()
        .dns(RelayDns.get(app))
        .eventListenerFactory(RelayDns.get(app).eventFactory)
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .writeTimeout(6, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build().also { workerHttp = it }

    // ------------------------------------------------------------ 公共

    private fun open(url: String, method: String): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 4000
        c.readTimeout = 6000
        c.setRequestProperty("User-Agent", UA)
        c.setRequestProperty("Accept", "application/json, text/plain, */*")
        c.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
        return c
    }

    private fun readAll(c: HttpURLConnection): String =
        c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
}
