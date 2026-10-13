package com.example.myime;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.SocketFactory;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * 最小的 WebDAV 客户端(给坚果云用)。纯 JDK,不依赖 Android 和第三方库,可以脱离手机测试。
 *
 * 为什么不用 HttpURLConnection:它不允许 PROPFIND / MKCOL 这两个 WebDAV 方法。
 * 这里直接在 TLS 连接上写 HTTP/1.1(每个请求一条连接,Connection: close),只实现用得到的几个方法:
 * PROPFIND(列目录)、MKCOL(建文件夹)、PUT(上传)、GET(下载)、DELETE(删除)。
 * 默认只允许 https,并且校验服务器证书和主机名;账号密码用 Basic 认证,只会发给 https 连接。
 */
public final class WebDavClient {
    public static final class DavException extends IOException {
        public final int code;

        public DavException(int code, String msg) {
            super(msg);
            this.code = code;
        }
    }

    public static final class Entry {
        public final String name;
        public final long size;
        public final boolean dir;

        Entry(String name, long size, boolean dir) {
            this.name = name;
            this.size = size;
            this.dir = dir;
        }
    }

    private static final class Response {
        int status;
        Map<String, String> headers = new HashMap<>();
        byte[] body = new byte[0];
    }

    private static final int MAX_MEMORY_BODY = 8 * 1024 * 1024;   // 列目录、错误页之类的小响应
    private static final int CONNECT_TIMEOUT_MS = 20_000;
    private static final int READ_TIMEOUT_MS = 90_000;

    private final boolean tls;
    private final String host;
    private final int port;
    private final String basePath;      // 以 / 开头、以 / 结尾,例如 /dav/
    private final String authHeader;
    private final SocketFactory socketFactory;

    /** baseUrl 例如 https://dav.jianguoyun.com/dav/ 。 */
    public WebDavClient(String baseUrl, String user, String password) {
        this(baseUrl, user, password, false, null);
    }

    /** allowPlainHttp 和自定义 socketFactory 只给测试用。 */
    public WebDavClient(String baseUrl, String user, String password, boolean allowPlainHttp, SocketFactory sf) {
        String u = baseUrl.trim();
        boolean https;
        if (u.regionMatches(true, 0, "https://", 0, 8)) {
            https = true;
            u = u.substring(8);
        } else if (allowPlainHttp && u.regionMatches(true, 0, "http://", 0, 7)) {
            https = false;
            u = u.substring(7);
        } else {
            throw new IllegalArgumentException("服务器地址必须以 https:// 开头");
        }
        int slash = u.indexOf('/');
        String hp = slash < 0 ? u : u.substring(0, slash);
        String path = slash < 0 ? "/" : u.substring(slash);
        int colon = hp.lastIndexOf(':');
        String h = hp;
        int p = https ? 443 : 80;
        if (colon > 0) {
            h = hp.substring(0, colon);
            try {
                p = Integer.parseInt(hp.substring(colon + 1));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("服务器地址里的端口不对");
            }
        }
        if (h.isEmpty()) throw new IllegalArgumentException("服务器地址不对");
        if (!path.endsWith("/")) path = path + "/";
        this.tls = https;
        this.host = h;
        this.port = p;
        this.basePath = path;
        this.authHeader = "Basic " + Base64.getEncoder().encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
        this.socketFactory = sf;
    }

    // ------------------------------------------------------------------ 路径

    /** 每一段按 UTF-8 做百分号编码,只保留 RFC 3986 的非保留字符。 */
    static String encodeSegment(String s) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) {
            int c = x & 0xff;
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-' || c == '.' || c == '_' || c == '~') {
                sb.append((char) c);
            } else {
                sb.append('%').append("0123456789ABCDEF".charAt(c >> 4)).append("0123456789ABCDEF".charAt(c & 15));
            }
        }
        return sb.toString();
    }

    /** 文件夹路径按 / 拆成若干段(忽略空段、. 和 ..),例如 "我的坚果云/myime" → [我的坚果云, myime]。 */
    public static List<String> splitFolder(String folder) {
        List<String> out = new ArrayList<>();
        if (folder == null) return out;
        for (String s : folder.split("/")) {
            String t = s.trim();
            if (t.isEmpty() || t.equals(".") || t.equals("..")) continue;
            out.add(t);
        }
        return out;
    }

    private String folderPath(List<String> segs) {
        StringBuilder sb = new StringBuilder(basePath);
        for (String s : segs) sb.append(encodeSegment(s)).append('/');
        return sb.toString();
    }

    private String filePath(List<String> segs, String name) {
        return folderPath(segs) + encodeSegment(name);
    }

    // ------------------------------------------------------------------ 公开操作

    /**
     * 确认账号密码可用,并保证文件夹存在(不存在就逐级创建)。失败抛 DavException / IOException,消息可以直接给用户看。
     */
    public void ensureFolder(List<String> segs) throws IOException {
        Response r = request("PROPFIND", folderPath(new ArrayList<String>()), propfindHeaders("0"), null, null, PROPFIND_BODY);
        failIfAuth(r);
        if (r.status != 207 && r.status != 200) throw fail(r, "连接坚果云失败");
        List<String> cur = new ArrayList<>();
        for (String s : segs) {
            cur.add(s);
            Response p = request("PROPFIND", folderPath(cur), propfindHeaders("0"), null, null, PROPFIND_BODY);
            failIfAuth(p);
            if (p.status == 207 || p.status == 200) continue;
            Response m = request("MKCOL", folderPath(cur), new HashMap<String, String>(), null, null, null);
            failIfAuth(m);
            if (m.status == 201 || m.status == 200 || m.status == 405) continue;   // 405:已经存在
            throw fail(m, "创建文件夹「" + s + "」失败(如果是顶层文件夹,请先在坚果云网页或 App 里建好这个同步文件夹)");
        }
    }

    /** 列出文件夹里的文件(不含子文件夹)。文件夹不存在返回空列表。 */
    public List<Entry> list(List<String> segs) throws IOException {
        Response r = request("PROPFIND", folderPath(segs), propfindHeaders("1"), null, null, PROPFIND_BODY);
        failIfAuth(r);
        if (r.status == 404) return new ArrayList<>();
        if (r.status != 207 && r.status != 200) throw fail(r, "读取坚果云文件列表失败");
        return parseListing(new String(r.body, StandardCharsets.UTF_8));
    }

    /** 上传。文件已存在会覆盖。 */
    public void put(List<String> segs, String name, File src) throws IOException {
        Map<String, String> h = new HashMap<>();
        h.put("Content-Type", "application/octet-stream");
        Response r = request("PUT", filePath(segs, name), h, src, null, null);
        failIfAuth(r);
        if (r.status != 200 && r.status != 201 && r.status != 204) throw fail(r, "上传失败");
    }

    /** 下载到 dst(会覆盖)。 */
    public void get(List<String> segs, String name, File dst) throws IOException {
        Response r = request("GET", filePath(segs, name), new HashMap<String, String>(), null, dst, null);
        failIfAuth(r);
        if (r.status != 200) {
            //noinspection ResultOfMethodCallIgnored
            dst.delete();
            throw fail(r, "下载失败");
        }
    }

    public void delete(List<String> segs, String name) throws IOException {
        Response r = request("DELETE", filePath(segs, name), new HashMap<String, String>(), null, null, null);
        failIfAuth(r);
        if (r.status != 200 && r.status != 204 && r.status != 404) throw fail(r, "删除失败");
    }

    // ------------------------------------------------------------------ 内部

    private static final byte[] PROPFIND_BODY = ("<?xml version=\"1.0\" encoding=\"utf-8\"?>"
            + "<d:propfind xmlns:d=\"DAV:\"><d:prop><d:getcontentlength/><d:resourcetype/></d:prop></d:propfind>")
            .getBytes(StandardCharsets.UTF_8);

    private static Map<String, String> propfindHeaders(String depth) {
        Map<String, String> h = new HashMap<>();
        h.put("Depth", depth);
        h.put("Content-Type", "application/xml; charset=utf-8");
        return h;
    }

    private static void failIfAuth(Response r) throws DavException {
        if (r.status == 401) throw new DavException(401, "账号或应用密码不对(要用坚果云的「第三方应用密码」,不是登录密码)");
        if (r.status == 429) throw new DavException(429, "坚果云提示请求太频繁,请过几分钟再试");
    }

    private static DavException fail(Response r, String what) {
        String extra;
        switch (r.status) {
            case 403: extra = "没有权限(根目录不能放文件,文件夹要放在某个同步文件夹里)"; break;
            case 404: extra = "路径不存在"; break;
            case 409: extra = "上一级文件夹不存在"; break;
            case 423: extra = "文件被锁定"; break;
            case 507: extra = "坚果云空间不足"; break;
            case 503: extra = "坚果云暂时不可用"; break;
            default: extra = "服务器返回 " + r.status;
        }
        return new DavException(r.status, what + ":" + extra);
    }

    private static final Pattern RESPONSE = Pattern.compile("<(?:[\\w.-]+:)?response[\\s>].*?</(?:[\\w.-]+:)?response>", Pattern.DOTALL);
    private static final Pattern HREF = Pattern.compile("<(?:[\\w.-]+:)?href>\\s*(.*?)\\s*</(?:[\\w.-]+:)?href>", Pattern.DOTALL);
    private static final Pattern LEN = Pattern.compile("<(?:[\\w.-]+:)?getcontentlength>\\s*(\\d+)\\s*</", Pattern.DOTALL);
    private static final Pattern COLLECTION = Pattern.compile("<(?:[\\w.-]+:)?collection\\s*/?>");

    static List<Entry> parseListing(String xml) {
        List<Entry> out = new ArrayList<>();
        Matcher m = RESPONSE.matcher(xml);
        boolean first = true;
        while (m.find()) {
            String block = m.group();
            Matcher h = HREF.matcher(block);
            if (!h.find()) continue;
            if (first) {   // Depth: 1 的第一条是文件夹自己
                first = false;
                continue;
            }
            String href = h.group(1).replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'");
            int q = href.indexOf('?');
            if (q >= 0) href = href.substring(0, q);
            int scheme = href.indexOf("://");
            if (scheme >= 0) {
                int s = href.indexOf('/', scheme + 3);
                href = s < 0 ? "/" : href.substring(s);
            }
            boolean dir = COLLECTION.matcher(block).find() || href.endsWith("/");
            while (href.endsWith("/")) href = href.substring(0, href.length() - 1);
            String last = href.substring(href.lastIndexOf('/') + 1);
            try {
                last = URLDecoder.decode(last.replace("+", "%2B"), "UTF-8");
            } catch (Exception e) {
                // 解不开就用原样
            }
            if (last.isEmpty()) continue;
            long size = 0;
            Matcher l = LEN.matcher(block);
            if (l.find()) {
                try {
                    size = Long.parseLong(l.group(1));
                } catch (NumberFormatException ignored) {
                    // 长度取不到就当 0
                }
            }
            out.add(new Entry(last, size, dir));
        }
        return out;
    }

    private Socket connect() throws IOException {
        Socket raw = socketFactory != null ? socketFactory.createSocket() : new Socket();
        raw.connect(new java.net.InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
        raw.setSoTimeout(READ_TIMEOUT_MS);
        if (!tls) return raw;
        SSLSocketFactory f = (SSLSocketFactory) SSLSocketFactory.getDefault();
        SSLSocket s = (SSLSocket) f.createSocket(raw, host, port, true);
        SSLParameters p = s.getSSLParameters();
        p.setEndpointIdentificationAlgorithm("HTTPS");   // 校验证书里的主机名,否则中间人可以冒充
        s.setSSLParameters(p);
        s.startHandshake();
        return s;
    }

    private Response request(String method, String path, Map<String, String> headers, File upload, File download, byte[] bodyBytes) throws IOException {
        try (Socket sock = connect()) {
            OutputStream out = new BufferedOutputStream(sock.getOutputStream(), 1 << 16);
            long len = upload != null ? upload.length() : (bodyBytes != null ? bodyBytes.length : 0);
            StringBuilder sb = new StringBuilder();
            sb.append(method).append(' ').append(path).append(" HTTP/1.1\r\n");
            sb.append("Host: ").append(host);
            if ((tls && port != 443) || (!tls && port != 80)) sb.append(':').append(port);
            sb.append("\r\n");
            sb.append("Authorization: ").append(authHeader).append("\r\n");
            sb.append("User-Agent: myime-backup\r\n");
            sb.append("Accept: */*\r\n");
            sb.append("Connection: close\r\n");
            for (Map.Entry<String, String> e : headers.entrySet()) sb.append(e.getKey()).append(": ").append(e.getValue()).append("\r\n");
            if (upload != null || bodyBytes != null || method.equals("PUT") || method.equals("PROPFIND")) {
                sb.append("Content-Length: ").append(len).append("\r\n");
            }
            sb.append("\r\n");
            out.write(sb.toString().getBytes(StandardCharsets.ISO_8859_1));
            if (bodyBytes != null) out.write(bodyBytes);
            if (upload != null) {
                try (InputStream in = new FileInputStream(upload)) {
                    byte[] buf = new byte[1 << 16];
                    long sent = 0;
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                        sent += n;
                    }
                    if (sent != len) throw new IOException("上传的文件在发送过程中被改动");
                }
            }
            out.flush();
            return readResponse(new BufferedInputStream(sock.getInputStream(), 1 << 16), download);
        }
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) >= 0) {
            if (c == '\n') break;
            if (c != '\r') bo.write(c);
            if (bo.size() > 16 * 1024) throw new IOException("服务器响应不正常");
        }
        if (c < 0 && bo.size() == 0) throw new IOException("服务器没有返回内容(连接被断开)");
        return new String(bo.toByteArray(), StandardCharsets.ISO_8859_1);
    }

    private static Response readResponse(InputStream in, File download) throws IOException {
        Response r = new Response();
        String status = readLine(in);
        String[] parts = status.split(" ", 3);
        if (parts.length < 2 || !parts[0].startsWith("HTTP/")) throw new IOException("服务器响应不正常");
        try {
            r.status = Integer.parseInt(parts[1].trim());
        } catch (NumberFormatException e) {
            throw new IOException("服务器响应不正常");
        }
        while (true) {
            String line = readLine(in);
            if (line.isEmpty()) break;
            int c = line.indexOf(':');
            if (c > 0) r.headers.put(line.substring(0, c).trim().toLowerCase(Locale.ROOT), line.substring(c + 1).trim());
        }
        if (r.status / 100 == 1 || r.status == 204 || r.status == 304) return r;
        boolean toFile = download != null && r.status == 200;
        OutputStream sink = toFile ? new BufferedOutputStream(new FileOutputStream(download)) : new ByteArrayOutputStream();
        try {
            String te = r.headers.get("transfer-encoding");
            String cl = r.headers.get("content-length");
            long total = 0;
            if (te != null && te.toLowerCase(Locale.ROOT).contains("chunked")) {
                while (true) {
                    String sz = readLine(in);
                    int semi = sz.indexOf(';');
                    if (semi >= 0) sz = sz.substring(0, semi);
                    long n;
                    try {
                        n = Long.parseLong(sz.trim(), 16);
                    } catch (NumberFormatException e) {
                        throw new IOException("服务器响应不正常");
                    }
                    if (n == 0) {
                        while (!readLine(in).isEmpty()) {
                            // 跳过尾部头
                        }
                        break;
                    }
                    total += copy(in, sink, n, toFile);
                    readLine(in);   // 块后的 CRLF
                }
            } else if (cl != null) {
                long n;
                try {
                    n = Long.parseLong(cl.trim());
                } catch (NumberFormatException e) {
                    throw new IOException("服务器响应不正常");
                }
                total = copy(in, sink, n, toFile);
            } else {
                byte[] buf = new byte[1 << 16];
                int k;
                while ((k = in.read(buf)) > 0) {
                    total += k;
                    if (!toFile && total > MAX_MEMORY_BODY) throw new IOException("服务器响应过大");
                    sink.write(buf, 0, k);
                }
            }
        } finally {
            sink.close();
        }
        if (!toFile) r.body = ((ByteArrayOutputStream) sink).toByteArray();
        return r;
    }

    /** 精确读 n 字节写进 sink;不够就是连接中途断了,抛异常(下载不完整不能算成功)。 */
    private static long copy(InputStream in, OutputStream sink, long n, boolean toFile) throws IOException {
        if (!toFile && n > MAX_MEMORY_BODY) throw new IOException("服务器响应过大");
        byte[] buf = new byte[1 << 16];
        long left = n;
        while (left > 0) {
            int k = in.read(buf, 0, (int) Math.min(buf.length, left));
            if (k < 0) throw new IOException("下载中途连接断开,文件不完整");
            sink.write(buf, 0, k);
            left -= k;
        }
        return n;
    }
}
