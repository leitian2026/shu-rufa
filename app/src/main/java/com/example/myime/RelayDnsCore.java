package com.example.myime;

import java.io.IOException;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * 连 Cloudflare Worker 时的域名解析规则(防 DNS 劫持),纯 JDK、有自测(tools/RelayDnsSelfTest.java)。照互传 App 的 NetDns 做的,只有"中转"这一路。
 * 真正去问 DoH、问系统 DNS 的是 RelayDns.kt(OkHttp),这里只管:怎么读 Host 映射文本、按什么顺序解析、出错时给什么中文提示。
 *
 * 三个来源各自独立,互不依赖、互不影响:每个域名只由第一个"适用"的来源解析,这个来源失败就是失败,绝不悄悄换到下一个(换了就绕回了被劫持的系统 DNS)。
 *  1. Host 映射(有总开关,默认开):每行「域名 IP [IP…]」。这个域名有映射就只用映射,不发任何 DNS 查询;一个域名可以填多个 IP,连不上会依次换下一个;全连不上就是连不上。
 *  2. 自建 DoH(有开关,默认关):每行「https 地址 [IP…]」。没有映射的域名、且 DoH 开着,就只问 DoH(多行按顺序试),都失败就是失败,不回退系统 DNS。局域网名字(没有点、.local 等)不走 DoH。加密 DNS,运营商看不到也改不了。
 *     DoH 服务器本身是域名,要查 DNS 才能连上它:先用这一行后面写的 IP,没有就查 Host 映射(开关开着才查),再没有才用系统 DNS(开关开着才用)。
 *     只有 DoH 依赖另外两个;Host 映射是绑定、系统 DNS 自己就是解析,这两个不依赖任何别的来源。
 *  3. 系统 DNS(有开关,默认开):没有映射、DoH 也没开(或局域网名字),开着就用系统 DNS;关着,绝不碰系统 DNS,没法解析就是连不上。
 * 注意:这只解决 DNS 被改;TLS 握手里的域名(SNI)仍是明文。
 */
public final class RelayDnsCore {
    private RelayDnsCore() {
    }

    public interface Lookup {
        List<InetAddress> lookup(String host) throws IOException;
    }

    public static final class Parsed {
        public final Map<String, List<InetAddress>> map;
        public final String error;   // 第一处错误(给界面提示用),没有错误是 null

        Parsed(Map<String, List<InetAddress>> map, String error) {
            this.map = map;
            this.error = error;
        }
    }

    public static final class Result {
        public final List<InetAddress> addrs;
        public final String source;

        Result(List<InetAddress> addrs, String source) {
            this.addrs = addrs;
            this.source = source;
        }
    }

    private static final Pattern V4 = Pattern.compile("^(25[0-5]|2[0-4]\\d|1?\\d?\\d)(\\.(25[0-5]|2[0-4]\\d|1?\\d?\\d)){3}$");
    private static final Pattern SEP = Pattern.compile("[\\s,=]+");

    /** 只认 IP 字面量(IPv4 / IPv6),绝不会触发 DNS 查询;不是 IP 返回 null。 */
    public static InetAddress literal(String s) {
        String t = s.trim();
        if (t.startsWith("[")) t = t.substring(1);
        if (t.endsWith("]")) t = t.substring(0, t.length() - 1);
        boolean ok = V4.matcher(t).matches();
        if (!ok && t.indexOf(':') >= 0) {
            ok = true;
            for (int i = 0; i < t.length(); i++) {
                char c = t.charAt(i);
                if (!(Character.digit(c, 16) >= 0 && c < 128) && c != ':' && c != '.') {
                    ok = false;
                    break;
                }
            }
        }
        if (!ok) return null;
        try {
            return InetAddress.getByName(t);   // IP 字面量不会查 DNS
        } catch (UnknownHostException e) {
            return null;
        }
    }

    /**
     * 解析 Host 映射文本。每行:域名 IP [IP…];分隔符可以是空格、逗号或 =,# 后面是注释。也认 Clash 的写法「域名: IP」。
     * 出错的行会被跳过,error 是第一处错误。
     */
    public static Parsed parseHosts(String text) {
        Map<String, List<InetAddress>> map = new LinkedHashMap<>();
        String err = null;
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String raw = lines[i];
            int hash = raw.indexOf('#');
            String line = (hash >= 0 ? raw.substring(0, hash) : raw).trim();
            if (line.isEmpty()) continue;
            List<String> parts = new ArrayList<>();
            for (String p : SEP.split(line)) if (!p.isEmpty()) parts.add(p);
            if (parts.isEmpty()) continue;
            String first = parts.get(0);
            while (first.endsWith(":")) first = first.substring(0, first.length() - 1);
            String host = first.toLowerCase(java.util.Locale.ROOT);
            if (host.isEmpty() || host.indexOf('/') >= 0 || host.contains("://")) {
                if (err == null) err = "Host 映射第 " + (i + 1) + " 行:域名不对";
                continue;
            }
            // 一行只有 IP / 写成了「IP 域名」(hosts 文件的顺序):给明确提示,别笼统说格式不对
            if (literal(first) != null) {
                if (err == null) {
                    err = parts.size() == 1
                        ? "Host 映射第 " + (i + 1) + " 行:这一行只有 IP,前面要写域名(格式:域名 IP)"
                        : "Host 映射第 " + (i + 1) + " 行:开头应该是域名,不是 IP(顺序是:域名 IP,和 hosts 文件相反)";
                }
                continue;
            }
            if (parts.size() == 1) {
                if (err == null) err = "Host 映射第 " + (i + 1) + " 行:" + host + " 后面没有 IP";
                continue;
            }
            List<InetAddress> ips = new ArrayList<>();
            boolean bad = false;
            for (int k = 1; k < parts.size(); k++) {
                InetAddress a = literal(parts.get(k));
                if (a == null) {
                    bad = true;
                    break;
                }
                ips.add(a);
            }
            if (bad) {
                if (err == null) err = "Host 映射第 " + (i + 1) + " 行:IP 格式不对(只能填 IP 地址,不能填域名)";
                continue;
            }
            List<InetAddress> cur = map.get(host);
            if (cur == null) {
                cur = new ArrayList<>();
                map.put(host, cur);
            }
            cur.addAll(ips);
        }
        return new Parsed(map, err);
    }

    /** DoH 文本里的非空行(去掉首尾空白)。 */
    public static List<String> dohLines(String text) {
        List<String> out = new ArrayList<>();
        for (String l : text.split("\n")) {
            String t = l.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    private static final Pattern DOH_SEP = Pattern.compile("[\\s,]+");

    /** DoH 一行的第一段:地址(https://…)。 */
    public static String dohUrl(String line) {
        String[] p = DOH_SEP.split(line.trim(), 2);
        return p.length == 0 ? "" : p[0];
    }

    /** DoH 一行里地址后面跟的 IP(DoH 服务器自己的 IP,用来找到它,不查任何 DNS);不是 IP 的段忽略。 */
    public static List<InetAddress> dohIps(String line) {
        List<InetAddress> out = new ArrayList<>();
        String[] p = DOH_SEP.split(line.trim());
        for (int i = 1; i < p.length; i++) {
            if (p[i].isEmpty()) continue;
            InetAddress a = literal(p[i]);
            if (a != null) out.add(a);
        }
        return out;
    }

    private static URI httpsUri(String line) {
        try {
            URI u = new URI(dohUrl(line));
            if (u.getScheme() == null || !u.getScheme().equalsIgnoreCase("https") || u.getHost() == null || u.getHost().isEmpty()) return null;
            return u;
        } catch (java.net.URISyntaxException e) {
            return null;
        }
    }

    /** DoH 的格式错误(每行:https:// 地址,后面可以跟 IP);没问题返回 null。设置页保存前检查。 */
    public static String dohError(String text) {
        List<String> l = dohLines(text);
        for (int i = 0; i < l.size(); i++) {
            if (httpsUri(l.get(i)) == null) return "DoH 第 " + (i + 1) + " 行不对:要以 https:// 开头,像 https://域名/路径";
            String[] p = DOH_SEP.split(l.get(i).trim());
            for (int k = 1; k < p.length; k++) {
                if (!p[k].isEmpty() && literal(p[k]) == null) return "DoH 第 " + (i + 1) + " 行不对:地址后面只能跟 IP(用空格隔开),「" + p[k] + "」不是 IP";
            }
        }
        return null;
    }

    /**
     * 提醒(不阻止保存):DoH 服务器是域名,找到它要查 DNS:先看这一行后面写的 IP,再看 Host 映射(hostsText 传"生效的"映射文本,开关关着就传空),
     * 最后才是系统 DNS。三样都没有就是找不到它,这条 DoH 直接不可用。没问题返回 null。
     */
    public static String dohWarning(String dohText, String hostsText, boolean sysOk) {
        Map<String, List<InetAddress>> map = parseHosts(hostsText).map;
        for (String line : dohLines(dohText)) {
            URI u = httpsUri(line);
            if (u == null) continue;
            String h = u.getHost().toLowerCase(java.util.Locale.ROOT);
            if (literal(h) != null || !dohIps(line).isEmpty()) continue;
            List<InetAddress> m = map.get(h);
            if ((m != null && !m.isEmpty()) || sysOk) continue;
            return "DoH 服务器 " + h + " 是域名,要查 DNS 才能找到它,但这一行没写 IP、Host 映射里也没有、又关了「使用系统 DNS」,这条 DoH 会直接不可用。"
                + "请在这行地址后面写它的 IP,或在 Host 映射里加一行:" + h + " IP";
        }
        return null;
    }

    public static boolean isLocalName(String h) {
        return h.indexOf('.') < 0 || h.endsWith(".local") || h.endsWith(".lan") || h.endsWith(".home") || h.endsWith(".internal") || h.endsWith(".localdomain");
    }

    /** IPv4 排前面:有些网络 IPv6 不通,先试 IPv6 会白等一轮超时。 */
    static List<InetAddress> ipv4First(List<InetAddress> l) {
        List<InetAddress> out = new ArrayList<>(l);
        Collections.sort(out, new Comparator<InetAddress>() {
            @Override
            public int compare(InetAddress a, InetAddress b) {
                return Boolean.compare(a instanceof Inet6Address, b instanceof Inet6Address);
            }
        });
        return out;
    }

    // ------------------------------------------------------------ 按顺序解析

    /** 带 5 分钟缓存(只缓存 DoH 的成功结果)的解析器。设置变了要 reset()。 */
    public static final class Resolver {
        private static final long TTL_MS = 5 * 60_000L;
        private final ConcurrentHashMap<String, Object[]> cache = new ConcurrentHashMap<>();

        public void reset() {
            cache.clear();
        }

        /**
         * 三个来源互不依赖:谁适用就只用谁,失败就抛错,不换下一个。
         *
         * @param hosts  当前生效的 Host 映射(开关关着就传空 Map)
         * @param dohOn  自建 DoH 开关
         * @param dohs   按顺序要问的 DoH;dohOn 为 true 但这里是空的(地址都不能用),就是没有可用的 DoH,报错
         * @param sysOk  系统 DNS 开关
         * @param system 问系统 DNS 的办法
         */
        public Result resolve(String hostname, Map<String, List<InetAddress>> hosts, boolean dohOn, List<Lookup> dohs, boolean sysOk,
                              Lookup system, long now) throws UnknownHostException {
            String host = hostname.toLowerCase(java.util.Locale.ROOT);
            // 1. Host 映射:命中就只用它
            List<InetAddress> m = hosts.get(host);
            if (m != null && !m.isEmpty()) return new Result(m, "Host 映射");
            // 2. 自建 DoH:开着(且不是局域网名字)就只问 DoH,失败就是失败
            if (dohOn && !isLocalName(host)) {
                Object[] c = cache.get(host);
                if (c != null && now - (Long) c[0] < TTL_MS) {
                    @SuppressWarnings("unchecked")
                    List<InetAddress> cached = (List<InetAddress>) c[1];
                    return new Result(cached, "自建 DoH");
                }
                if (dohs.isEmpty()) {
                    throw new UnknownHostException(hostname + " 没有 Host 映射,自建 DoH 开着但没有可用的地址(没法解析,不用系统 DNS)");
                }
                Exception last = null;
                for (Lookup d : dohs) {
                    try {
                        List<InetAddress> a = ipv4First(d.lookup(hostname));
                        if (!a.isEmpty()) {
                            cache.put(host, new Object[]{now, a});
                            return new Result(a, "自建 DoH");
                        }
                    } catch (IOException e) {
                        last = e;
                    } catch (RuntimeException e) {
                        last = e;
                    }
                }
                String why = last == null ? "没有返回地址" : (last.getMessage() != null ? last.getMessage() : last.getClass().getSimpleName());
                UnknownHostException u = new UnknownHostException("自建 DoH 解析 " + hostname + " 失败:" + why + "(不回退系统 DNS)");
                if (last != null) u.initCause(last);
                throw u;
            }
            // 3. 系统 DNS:开关开着才用;关着就是连不上
            if (sysOk) {
                try {
                    return new Result(ipv4First(system.lookup(hostname)), "系统 DNS");
                } catch (UnknownHostException e) {
                    throw e;
                } catch (IOException e) {
                    UnknownHostException u = new UnknownHostException(hostname + " 解析失败:" + e.getMessage());
                    u.initCause(e);
                    throw u;
                }
            }
            throw new UnknownHostException(hostname + " 没有 Host 映射" + (dohOn ? "(局域网名字不走 DoH)" : "、自建 DoH 也没开") + ",且已关闭「使用系统 DNS」,没法解析");
        }
    }

    // ------------------------------------------------------------ 出错提示

    /**
     * 连 Worker 失败时的中文提示:DNS 被改 / 握手被掐。不是这类错误返回 null,调用方继续用原来的提示。
     */
    public static String hint(Throwable t) {
        Throwable c = t;
        int n = 0;
        String dns = null;
        boolean cert = false;
        boolean ssl = false;
        while (c != null && n < 6) {
            String m = c.getMessage() == null ? "" : c.getMessage();
            if (c instanceof UnknownHostException && dns == null) dns = m;
            if (c instanceof javax.net.ssl.SSLPeerUnverifiedException || c instanceof java.security.cert.CertificateException
                || m.contains("CERTIFICATE_VERIFY_FAILED") || m.contains("Trust anchor")) {
                cert = true;
            } else if (c instanceof javax.net.ssl.SSLException) {
                ssl = true;
            }
            c = c.getCause();
            n++;
        }
        String fix = "请在「域名解析」里填写 Host 映射,或换一个域名 / 换网络 / 开代理";
        if (dns != null) {
            boolean own = false;
            for (int i = 0; i < dns.length(); i++) {
                char ch = dns.charAt(i);
                if (ch >= '\u4e00' && ch <= '\u9fff') {
                    own = true;
                    break;
                }
            }
            return own ? dns + "。请检查「域名解析」里的 Host 映射和「使用系统 DNS」开关" : "域名解析失败,可能没有网络或 DNS 被干扰。" + fix;
        }
        if (cert) return "证书校验失败:连到的不是你的 Cloudflare 服务器,域名很可能被 DNS 劫持到了别处。" + fix;
        if (ssl) return "TLS 握手被中途掐断:域名可能被 DNS 劫持,或被网络屏蔽。" + fix;
        return null;
    }
}
