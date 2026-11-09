// 连 Worker 的域名解析规则(RelayDnsCore)自测,不联网。在工程根目录执行(需要 JDK 17+):
//   javac -encoding UTF-8 -d /tmp/rds app/src/main/java/com/example/myime/RelayDnsCore.java tools/RelayDnsSelfTest.java
//   java -Dfile.encoding=UTF-8 -cp /tmp/rds RelayDnsSelfTest
// 覆盖:Host 映射文本(空格 / 逗号 / = / Clash 写法 / 注释 / 多个 IP / IPv6 / 大小写 / 坏行给明确提示)、DoH 地址检查和提醒、
//       解析顺序(Host 映射 → 自建 DoH → 系统 DNS)和各开关、DoH 缓存、局域网名字不走 DoH、关了系统 DNS 绝不碰它、出错的中文提示。
import com.example.myime.*;
import java.io.IOException;
import java.net.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

public class RelayDnsSelfTest {
    static int fails = 0, passes = 0;

    static void check(boolean ok, String name) {
        System.out.println((ok ? "PASS " : "FAIL ") + name);
        if (ok) passes++; else fails++;
    }

    static Map<String, List<String>> addrStrings(Map<String, List<InetAddress>> m) {
        Map<String, List<String>> out = new HashMap<>();
        for (Map.Entry<String, List<InetAddress>> e : m.entrySet()) {
            List<String> l = new ArrayList<>();
            for (InetAddress a : e.getValue()) l.add(a.getHostAddress());
            out.put(e.getKey(), l);
        }
        return out;
    }

    static List<InetAddress> ips(String... a) throws IOException {
        List<InetAddress> l = new ArrayList<>();
        for (String s : a) l.add(InetAddress.getByName(s));   // 都是 IP 字面量,不会查 DNS
        return l;
    }

    static String strs(List<InetAddress> l) {
        List<String> o = new ArrayList<>();
        for (InetAddress a : l) o.add(a.getHostAddress());
        return o.toString();
    }

    public static void main(String[] args) throws Exception {
        // ---- Host 映射文本
        RelayDnsCore.Parsed p = RelayDnsCore.parseHosts("www.anmolking.eu.org 162.159.152.2\nwo.anmolking.eu.org 45.139.193.125\n");
        check(p.error == null && p.map.size() == 2, "Host 映射:两行,各一个 IP");
        check(addrStrings(p.map).get("wo.anmolking.eu.org").equals(Arrays.asList("45.139.193.125")), "Host 映射:IP 对应正确");
        p = RelayDnsCore.parseHosts("a.example.com 1.1.1.1 2.2.2.2, 3.3.3.3\nB.Example.COM=4.4.4.4\nc.example.com: 5.5.5.5   # 注释\n# 整行注释\n\n");
        check(p.error == null && p.map.size() == 3, "Host 映射:空格 / 逗号 / = / Clash 写法 / 注释 / 空行都认");
        check(addrStrings(p.map).get("a.example.com").equals(Arrays.asList("1.1.1.1", "2.2.2.2", "3.3.3.3")), "Host 映射:一个域名可以多个 IP,顺序保留");
        check(p.map.containsKey("b.example.com"), "Host 映射:域名统一小写");
        p = RelayDnsCore.parseHosts("a.example.com 1.1.1.1\na.example.com 2.2.2.2");
        check(addrStrings(p.map).get("a.example.com").equals(Arrays.asList("1.1.1.1", "2.2.2.2")), "Host 映射:同一域名分几行写,合并");
        p = RelayDnsCore.parseHosts("v6.example.com 2606:4700:4700::1111 [2001:db8::1]");
        check(p.error == null && p.map.get("v6.example.com").size() == 2, "Host 映射:IPv6(带不带方括号都行)");
        p = RelayDnsCore.parseHosts("1.2.3.4");
        check(p.error != null && p.error.contains("只有 IP"), "坏行:只有 IP → 提示前面要写域名");
        p = RelayDnsCore.parseHosts("1.2.3.4 a.example.com");
        check(p.error != null && p.error.contains("和 hosts 文件相反"), "坏行:写成了「IP 域名」→ 提示顺序和 hosts 文件相反");
        p = RelayDnsCore.parseHosts("a.example.com");
        check(p.error != null && p.error.contains("后面没有 IP"), "坏行:域名后面没有 IP");
        p = RelayDnsCore.parseHosts("a.example.com b.example.com");
        check(p.error != null && p.error.contains("不能填域名"), "坏行:IP 位置写了域名(不会因此去查 DNS)");
        p = RelayDnsCore.parseHosts("a.example.com 999.1.1.1");
        check(p.error != null && p.error.contains("IP 格式不对"), "坏行:IP 超范围");
        p = RelayDnsCore.parseHosts("https://a.example.com 1.1.1.1");
        check(p.error != null && p.error.contains("域名不对"), "坏行:写成了网址");
        p = RelayDnsCore.parseHosts("bad line here\nok.example.com 8.8.8.8");
        check(p.error != null && p.error.contains("第 1 行") && p.map.containsKey("ok.example.com"), "出错的行跳过,别的行照常生效,提示的是第一处错误");
        check(RelayDnsCore.literal("example.com") == null && RelayDnsCore.literal("１.２.３.４") == null, "literal:域名和全角数字都不当 IP(不会触发 DNS 查询)");

        // ---- DoH 地址
        check(RelayDnsCore.dohError("https://dns.example.com/dns-query\nhttps://1.1.1.1/dns-query") == null, "DoH:两行 https 地址没问题");
        check(RelayDnsCore.dohError("http://dns.example.com/q") != null && RelayDnsCore.dohError("dns.example.com") != null, "DoH:不是 https 开头的报错");
        check(RelayDnsCore.dohError("https://ok.example.com/q\nnot a url").contains("第 2 行"), "DoH:指出是第几行");
        check(RelayDnsCore.dohError("") == null, "DoH:空的没问题(开关关着时)");
        check(RelayDnsCore.dohWarning("https://dns.example.com/q", "", true) != null, "DoH 提醒:DoH 服务器的域名没有 Host 映射");
        check(RelayDnsCore.dohWarning("https://dns.example.com/q", "dns.example.com 9.9.9.9", true) == null, "DoH 提醒:加了映射就不再提醒");
        check(RelayDnsCore.dohWarning("https://1.1.1.1/dns-query", "", false) == null, "DoH 提醒:地址直接写 IP 的不需要映射");
        check(RelayDnsCore.dohWarning("https://dns.example.com/q", "", false).contains("会直接不可用"), "DoH 提醒:关了系统 DNS 时说明会直接不可用");

        // ---- 解析顺序
        final AtomicInteger dohCalls = new AtomicInteger(), sysCalls = new AtomicInteger();
        RelayDnsCore.Lookup doh = h -> { dohCalls.incrementAndGet(); return ips("9.9.9.9", "2001:db8::9"); };
        RelayDnsCore.Lookup dohFail = h -> { dohCalls.incrementAndGet(); throw new IOException("DoH 连不上"); };
        RelayDnsCore.Lookup sys = h -> { sysCalls.incrementAndGet(); return ips("8.8.4.4"); };
        Map<String, List<InetAddress>> hosts = RelayDnsCore.parseHosts("wo.example.com 45.1.1.1 45.2.2.2").map;
        Map<String, List<InetAddress>> none = new HashMap<>();
        long now = 1_000_000L;

        RelayDnsCore.Resolver r = new RelayDnsCore.Resolver();
        RelayDnsCore.Result res = r.resolve("WO.example.com", hosts, true, Arrays.asList(doh), true, sys, now);
        check(res.source.equals("Host 映射") && strs(res.addrs).equals("[45.1.1.1, 45.2.2.2]") && dohCalls.get() == 0 && sysCalls.get() == 0, "顺序:有 Host 映射 → 直接用,不问 DoH 也不问系统 DNS,域名大小写不影响");
        res = r.resolve("other.example.com", hosts, true, Arrays.asList(doh), true, sys, now);
        check(res.source.equals("自建 DoH") && strs(res.addrs).equals("[9.9.9.9, 2001:db8:0:0:0:0:0:9]") && sysCalls.get() == 0, "顺序:没映射 → 自建 DoH(IPv4 排前面),不碰系统 DNS");
        int before = dohCalls.get();
        res = r.resolve("other.example.com", hosts, true, Arrays.asList(doh), true, sys, now + 60_000);
        check(dohCalls.get() == before, "DoH 结果缓存 5 分钟,不重复问");
        res = r.resolve("other.example.com", hosts, true, Arrays.asList(doh), true, sys, now + 6 * 60_000);
        check(dohCalls.get() == before + 1, "缓存过期后重新问");
        r.reset();
        res = r.resolve("other.example.com", hosts, true, Arrays.asList(doh), true, sys, now);
        check(dohCalls.get() == before + 2, "reset 清掉缓存(设置变了要重新解析)");

        res = r.resolve("x.example.com", none, true, Arrays.asList(dohFail, doh), true, sys, now);
        check(res.source.equals("自建 DoH") && strs(res.addrs).startsWith("[9.9.9.9"), "多个 DoH:第一个失败就试下一个");
        r.reset();
        res = r.resolve("y.example.com", none, true, Arrays.asList(dohFail), true, sys, now);
        check(res.source.contains("系统 DNS") && res.source.contains("已回退") && strs(res.addrs).equals("[8.8.4.4]"), "DoH 都失败 + 系统 DNS 开着 → 回退到系统 DNS");
        r.reset(); sysCalls.set(0);
        try {
            r.resolve("z.example.com", none, true, Arrays.asList(dohFail), false, sys, now);
            check(false, "关了系统 DNS:DoH 失败应该抛错");
        } catch (UnknownHostException e) {
            check(e.getMessage().contains("DoH 连不上") && e.getMessage().contains("不回退") && sysCalls.get() == 0, "关了系统 DNS:DoH 失败就是连不上,绝不碰系统 DNS,错误里有原因");
        }
        try {
            r.resolve("q.example.com", none, false, new ArrayList<>(), false, sys, now);
            check(false, "全关应该抛错");
        } catch (UnknownHostException e) {
            check(e.getMessage().contains("没有 Host 映射") && e.getMessage().contains("没法解析") && sysCalls.get() == 0, "Host 映射没命中、DoH 没开、系统 DNS 也关了:明确说没法解析,没碰系统 DNS");
        }
        try {
            r.resolve("q.example.com", none, true, new ArrayList<>(), false, sys, now);
            check(false, "DoH 开着但没有可用的应该抛错");
        } catch (UnknownHostException e) {
            check(e.getMessage().contains("没有可用的自建 DoH"), "DoH 开着但一个能用的都没有(引导不了):提示没有可用的 DoH");
        }
        dohCalls.set(0);
        res = r.resolve("nas.local", none, true, Arrays.asList(doh), true, sys, now);
        check(dohCalls.get() == 0 && res.source.equals("系统 DNS"), "局域网名字(.local)不走 DoH");
        res = r.resolve("router", none, true, Arrays.asList(doh), true, sys, now);
        check(dohCalls.get() == 0, "没有点的主机名不走 DoH");
        res = r.resolve("w.example.com", none, false, Arrays.asList(doh), true, sys, now);
        check(dohCalls.get() == 0 && res.source.equals("系统 DNS"), "DoH 开关关着:不问 DoH,直接系统 DNS");
        RelayDnsCore.Lookup sysFail = h -> { throw new UnknownHostException(h); };
        try {
            r.resolve("n.example.com", none, false, new ArrayList<>(), true, sysFail, now);
            check(false, "系统 DNS 失败应该抛 UnknownHostException");
        } catch (UnknownHostException e) {
            check(true, "系统 DNS 也解析不了:抛 UnknownHostException(OkHttp 会按它处理)");
        }

        // ---- 出错提示
        String h = RelayDnsCore.hint(new UnknownHostException("wo.example.com 没有 Host 映射,且已关闭「使用系统 DNS」,没法解析"));
        check(h != null && h.contains("请检查「域名解析」"), "提示:自己的中文解析错误 → 让人检查域名解析设置");
        h = RelayDnsCore.hint(new IOException("outer", new UnknownHostException("Unable to resolve host")));
        check(h != null && h.contains("DNS 被干扰"), "提示:系统的解析失败(英文)→ 可能没网或 DNS 被干扰");
        h = RelayDnsCore.hint(new javax.net.ssl.SSLPeerUnverifiedException("Hostname wo.example.com not verified"));
        check(h != null && h.contains("证书校验失败") && h.contains("DNS 劫持"), "提示:证书校验失败 → 域名很可能被劫持");
        h = RelayDnsCore.hint(new javax.net.ssl.SSLException("Connection reset"));
        check(h != null && h.contains("TLS 握手被中途掐断"), "提示:TLS 握手被掐断");
        check(RelayDnsCore.hint(new IOException("timeout")) == null, "提示:不是这几类错误返回 null,用原来的提示");

        System.out.println(fails == 0 ? "ALL PASS (" + passes + " 项)" : "FAILED " + fails + "(通过 " + passes + " 项)");
        if (fails != 0) System.exit(1);
    }
}
