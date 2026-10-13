// WebDavClient 的自测:用 tools/fake_dav.py(本机假的 WebDAV 服务器)测 建文件夹 / 列目录 / 上传 / 下载(含分块传输)/ 删除 / 错误处理。
// 在工程根目录执行(需要 JDK 17+ 和 python3):
//   mkdir -p /tmp/davdata && (python3 tools/fake_dav.py 18080 /tmp/davdata &)
//   javac -encoding UTF-8 -d /tmp/davtest app/src/main/java/com/example/myime/WebDavClient.java tools/WebDavSelfTest.java
//   java -Dfile.encoding=UTF-8 -cp /tmp/davtest WebDavSelfTest 18080
import com.example.myime.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;

public class WebDavSelfTest {
    static int fails = 0;
    static void check(boolean ok, String name) { System.out.println((ok ? "PASS " : "FAIL ") + name); if (!ok) fails++; }
    public static void main(String[] a) throws Exception {
        String base = "http://127.0.0.1:" + a[0] + "/dav/";
        WebDavClient c = new WebDavClient(base, "a@b.c", "secretpw", true, null);
        List<String> folder = WebDavClient.splitFolder("我的坚果云/myime 备份");
        check(folder.size() == 2, "splitFolder");
        c.ensureFolder(folder);
        check(true, "ensureFolder creates nested");
        c.ensureFolder(folder);
        check(true, "ensureFolder idempotent");
        Random r = new Random(7);
        byte[] data = new byte[300_000]; r.nextBytes(data);
        Path src = Files.createTempFile("src", ".bin"); Files.write(src, data);
        c.put(folder, "myime-backup-20261013-120000.mybk", src.toFile());
        c.put(folder, "myime-backup-20261013-130000.mybk", src.toFile());
        c.put(folder, "myime-backup-20261013-130000.mybk", src.toFile());   // overwrite
        c.put(folder, "other & name+1.txt", src.toFile());
        List<WebDavClient.Entry> l = c.list(folder);
        StringBuilder sb = new StringBuilder(); for (WebDavClient.Entry e : l) sb.append(e.name).append('(').append(e.size).append(") ");
        System.out.println(sb);
        check(l.size() == 3, "list 3 entries (folder itself excluded)");
        boolean sizes = true; for (WebDavClient.Entry e : l) sizes &= e.size == data.length;
        check(sizes, "sizes reported");
        boolean names = false; for (WebDavClient.Entry e : l) names |= e.name.equals("other & name+1.txt");
        check(names, "special-char name decoded");
        Path dst = Files.createTempFile("dst", ".bin");
        c.get(folder, "myime-backup-20261013-130000.mybk", dst.toFile());
        check(Arrays.equals(data, Files.readAllBytes(dst)), "download identical");
        c.put(folder, "chunk.bin", src.toFile());
        c.get(folder, "chunk.bin", dst.toFile());
        check(Arrays.equals(data, Files.readAllBytes(dst)), "chunked download identical");
        c.delete(folder, "chunk.bin");
        check(c.list(folder).size() == 3, "delete");
        try { c.get(folder, "nope.mybk", dst.toFile()); check(false, "404 get"); } catch (WebDavClient.DavException e) { check(e.code == 404, "404 get -> " + e.getMessage()); }
        WebDavClient bad = new WebDavClient(base, "a@b.c", "wrong", true, null);
        try { bad.ensureFolder(folder); check(false, "bad auth"); } catch (WebDavClient.DavException e) { check(e.code == 401, "bad auth -> " + e.getMessage()); }
        check(c.list(WebDavClient.splitFolder("我的坚果云/不存在")).isEmpty(), "list missing folder -> empty");
        try { c.ensureFolder(WebDavClient.splitFolder("顶层新建")); check(false, "root mkcol refused"); } catch (WebDavClient.DavException e) { check(e.code == 403, "root mkcol refused -> " + e.getMessage()); }
        try { new WebDavClient("http://x/dav/", "u", "p"); check(false, "http refused"); } catch (IllegalArgumentException e) { check(true, "plain http refused"); }
        System.out.println(fails == 0 ? "ALL PASS" : fails + " FAILED");
        System.exit(fails == 0 ? 0 : 1);
    }
}
