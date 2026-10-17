// 备份加密(BackupCrypto)和安全写入(SafeFile)的自测:不需要 Android,电脑上有 JDK 17+ 即可运行。
// 在工程根目录执行:
//   javac -d /tmp/selftest app/src/main/java/com/example/myime/SafeFile.java \
//         app/src/main/java/com/example/myime/BackupCrypto.java app/src/main/java/com/example/myime/BackupScope.java \
//         tools/BackupSelfTest.java
//   java -cp /tmp/selftest BackupSelfTest
// 覆盖:备份范围的自动发现 / 排除 / 路径安全检查;各种长度往返、错密码、改字节、截断、调换块顺序、文件头被改;安全写入的回退 / 留底 / 老格式 / 未来版本 / 清除。
import com.example.myime.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;

public class BackupSelfTest {
    static int fails = 0;
    static void check(boolean ok, String name) { System.out.println((ok ? "PASS " : "FAIL ") + name); if (!ok) fails++; }

    static byte[] enc(byte[] data, byte[] master, byte[] salt, int iter) throws Exception {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        try (OutputStream o = BackupCrypto.encrypt(bo, master, salt, iter)) { o.write(data); }
        return bo.toByteArray();
    }
    static byte[] dec(byte[] ct, byte[] master) throws Exception {
        try (InputStream in = BackupCrypto.decryptWithMaster(new ByteArrayInputStream(ct), master)) { return in.readAllBytes(); }
    }

    public static void main(String[] a) throws Exception {
        SafeFile.setDurableGapForTest(0);   // 测试里每次保存都当完整保存(刷盘 + 更新 .bak)
        int iter = 100_000;   // 测试用最低值,加快速度
        byte[] salt = BackupCrypto.newSalt();
        long t0 = System.nanoTime();
        byte[] master = BackupCrypto.deriveMaster("正确的密码 horse battery".toCharArray(), salt, iter);
        System.out.printf("PBKDF2 %d iter: %d ms%n", iter, (System.nanoTime()-t0)/1_000_000);
        Random r = new Random(1);

        // 各种长度,包括 0、1、刚好一块、一块±1、多块
        int[] sizes = {0, 1, 100, 65535, 65536, 65537, 131072, 300000};
        boolean allOk = true;
        for (int s : sizes) {
            byte[] d = new byte[s]; r.nextBytes(d);
            byte[] c = enc(d, master, salt, iter);
            allOk &= Arrays.equals(d, dec(c, master));
        }
        check(allOk, "roundtrip sizes " + Arrays.toString(sizes));

        byte[] data = new byte[200000]; r.nextBytes(data);
        byte[] ct = enc(data, master, salt, iter);

        // 密码路径(decrypt 用密码)
        try (InputStream in = BackupCrypto.decrypt(new ByteArrayInputStream(ct), "正确的密码 horse battery".toCharArray())) {
            check(Arrays.equals(data, in.readAllBytes()), "decrypt by password");
        }
        // NFKC:全角字符密码等价
        try (InputStream in = BackupCrypto.decrypt(new ByteArrayInputStream(ct), "正确的密码 ｈｏｒｓｅ ｂａｔｔｅｒｙ".toCharArray())) {
            check(Arrays.equals(data, in.readAllBytes()), "NFKC-equivalent password accepted");
        }

        // 错误密码
        try { BackupCrypto.decrypt(new ByteArrayInputStream(ct), "wrong".toCharArray()).readAllBytes(); check(false, "wrong password rejected"); }
        catch (BackupCrypto.BadPasswordException e) { check(true, "wrong password rejected"); }

        // 改一个字节(头、第一块、中间块、最后块)
        int[] pos = {10, 60, 70000, ct.length - 5};
        for (int p : pos) {
            byte[] t = ct.clone(); t[p] ^= 1;
            try { dec(t, master); check(false, "tamper at " + p + " detected"); }
            catch (IOException e) { check(true, "tamper at " + p + " detected (" + e.getClass().getSimpleName() + ")"); }
        }
        // 截断:去掉最后一块 / 截一半 / 只剩头
        int hdr = 45;
        // 找到最后一块起点
        int off = hdr, lastStart = hdr;
        while (off < ct.length) { lastStart = off; int len = ((ct[off]&0xff)<<24)|((ct[off+1]&0xff)<<16)|((ct[off+2]&0xff)<<8)|(ct[off+3]&0xff); off += 4 + len; }
        int[] cuts = {lastStart, ct.length - 3, hdr, ct.length / 2, 10};
        for (int c : cuts) {
            try { dec(Arrays.copyOf(ct, c), master); check(false, "truncate to " + c + " detected"); }
            catch (IOException e) { check(true, "truncate to " + c + " detected (" + e.getClass().getSimpleName() + ")"); }
        }
        // 调换两块顺序
        {
            byte[] big = new byte[65536 * 3]; r.nextBytes(big);
            byte[] c3 = enc(big, master, salt, iter);
            int c1len = 4 + 65536 + 16;
            byte[] sw = c3.clone();
            System.arraycopy(c3, hdr + c1len, sw, hdr, c1len);
            System.arraycopy(c3, hdr, sw, hdr + c1len, c1len);
            try { dec(sw, master); check(false, "chunk reorder detected"); } catch (IOException e) { check(true, "chunk reorder detected"); }
        }
        // 不同次加密的同样明文,密文必须不同(随机文件盐)
        check(!Arrays.equals(enc(data, master, salt, iter), enc(data, master, salt, iter)), "two encryptions differ");
        // 文件头里迭代次数被改小/改大
        {
            byte[] t = ct.clone(); t[21] = 0; t[22] = 0; t[23] = 0; t[24] = 1;   // iter=1
            try { BackupCrypto.decrypt(new ByteArrayInputStream(t), "x".toCharArray()); check(false, "iter=1 rejected"); } catch (IOException e) { check(true, "iter=1 rejected"); }
            t = ct.clone(); t[21] = 0x7f; t[22]=(byte)0xff; t[23]=(byte)0xff; t[24]=(byte)0xff;
            try { BackupCrypto.decrypt(new ByteArrayInputStream(t), "x".toCharArray()); check(false, "iter=huge rejected"); } catch (IOException e) { check(true, "iter=huge rejected"); }
        }
        // 生产参数耗时(JVM 上)
        long t1 = System.nanoTime();
        BackupCrypto.deriveMaster("x".toCharArray(), salt, BackupCrypto.ITERATIONS);
        System.out.printf("PBKDF2 %d iter (production): %d ms%n", BackupCrypto.ITERATIONS, (System.nanoTime()-t1)/1_000_000);

        // ---------------- SafeFile ----------------
        Path dir = Files.createTempDirectory("sf");
        File f = new File(dir.toFile(), "learn.txt");
        SafeFile.write(f, w -> { w.write("t\t好\t3\n"); w.write("t\t的\t9\n"); });
        SafeFile.Loaded L = SafeFile.read(f);
        check(L != null && !L.fromBackup && L.lines.equals(List.of("t\t好\t3", "t\t的\t9")), "safefile write/read");
        SafeFile.write(f, w -> { w.write("t\t好\t4\n"); });
        check(SafeFile.backupOf(f).isFile(), "bak created on 2nd save");
        L = SafeFile.read(f);
        check(L.lines.equals(List.of("t\t好\t4")), "second save content");
        // 截断主文件 → 回退到 .bak
        byte[] raw = Files.readAllBytes(f.toPath());
        Files.write(f.toPath(), Arrays.copyOf(raw, raw.length - 3));   // 去掉 #end 的一部分
        L = SafeFile.read(f);
        check(L != null && L.fromBackup && L.lines.equals(List.of("t\t好\t3", "t\t的\t9")), "truncated main -> restore from .bak");
        check(new File(dir.toFile(), "learn.txt.corrupt").isFile(), "corrupt copy kept");
        // 损坏主文件时再保存,不能把坏文件复制盖掉好备份
        SafeFile.write(f, w -> { w.write("t\t好\t5\n"); });
        String bak = Files.readString(SafeFile.backupOf(f).toPath());
        check(bak.contains("t\t的\t9"), "good .bak not overwritten by corrupt main");
        // 坏 UTF-8
        Files.write(f.toPath(), new byte[]{'#','v','1','\n',(byte)0xff,(byte)0xfe,'\n','#','e','n','d','\n'});
        L = SafeFile.read(f);
        check(L != null && L.fromBackup, "invalid utf-8 -> fallback");
        // 老格式文件(无头尾)照常读
        File g = new File(dir.toFile(), "old.txt");
        Files.writeString(g.toPath(), "t\t旧\t2\nt\t数据\t1\n");
        L = SafeFile.read(g);
        check(L != null && L.lines.size() == 2 && !L.fromBackup, "legacy file readable");
        // 空文件、不存在
        File e0 = new File(dir.toFile(), "empty.txt"); Files.write(e0.toPath(), new byte[0]);
        check(SafeFile.read(e0) == null, "empty file -> null");
        check(SafeFile.read(new File(dir.toFile(), "none.txt")) == null, "missing -> null");
        // 未来版本
        File h = new File(dir.toFile(), "fut.txt");
        Files.writeString(h.toPath(), "#v9\nx\ty\n#end\n");
        L = SafeFile.read(h);
        check(L != null && L.lines.equals(List.of("x\ty")) && new File(dir.toFile(), "fut.txt.v9").isFile(), "newer version kept aside");
        // deleteAll 之后不能被备份恢复
        SafeFile.write(f, w -> { w.write("t\t好\t6\n"); });
        SafeFile.write(f, w -> { w.write("t\t好\t7\n"); });
        SafeFile.deleteAll(f);
        check(SafeFile.read(f) == null, "deleteAll leaves nothing to resurrect");
        // 写失败(目录是个文件)→ 抛异常,主文件不动
        File k = new File(dir.toFile(), "k.txt");
        SafeFile.write(k, w -> w.write("a\tb\n"));
        try { SafeFile.write(k, w -> { w.write("partial"); throw new IOException("boom"); }); check(false, "write failure throws"); }
        catch (IOException ex) { check(true, "write failure throws"); }
        check(SafeFile.read(k).lines.equals(List.of("a\tb")) && !new File(dir.toFile(), "k.txt.tmp").exists(), "failed write leaves main intact, tmp cleaned");

        // ---------------- BackupScope ----------------
        Path root = Files.createTempDirectory("scope");
        File fd = new File(root.toFile(), "files"); File pd = new File(root.toFile(), "shared_prefs");
        new File(fd, "user").mkdirs(); new File(fd, "dictcache").mkdirs(); new File(fd, "dicts").mkdirs();
        new File(fd, "future/deep").mkdirs(); new File(fd, "restore_staging").mkdirs(); new File(fd, "user_before_restore").mkdirs(); pd.mkdirs();
        String[] mk = {"user/pinyin.txt", "user/pinyin.txt.bak", "user/pinyin.txt.tmp", "user/pinyin.txt.corrupt", "user/x.txt.v9",
                "dictcache/pinyin.bin", "dicts/pinyin.txt", "future/new_feature.dat", "future/deep/a.json", "top.bin",
                "restore_staging/e0", "user_before_restore/user/pinyin.txt"};
        for (String m : mk) { new File(fd, m).getParentFile().mkdirs(); Files.writeString(new File(fd, m).toPath(), "x"); }
        Files.writeString(new File(pd, "dictcfg.xml").toPath(), "<map/>");
        Files.writeString(new File(pd, "kbsize.xml").toPath(), "<map/>");
        Files.writeString(new File(pd, "newfeature.xml").toPath(), "<map/>");
        Files.writeString(new File(pd, "backupcfg.xml").toPath(), "<map/>");
        Files.writeString(new File(pd, "readme.txt").toPath(), "x");
        check(BackupScope.listFiles(fd).equals(List.of("future/deep/a.json", "future/new_feature.dat", "top.bin", "user/pinyin.txt")),
                "scope: auto-discovers new files, skips cache/dicts/temp/safety dirs: " + BackupScope.listFiles(fd));
        check(BackupScope.listPrefNames(pd).equals(List.of("dictcfg", "kbsize", "newfeature")),
                "scope: auto-discovers new prefs, skips backupcfg: " + BackupScope.listPrefNames(pd));
        check(BackupScope.resolve(fd, "future/new_feature.dat") != null, "resolve: normal path ok");
        String[] bad = {"../evil", "a/../../evil", "/etc/passwd", "a//b", "user\\x", "", "dictcache/pinyin.bin", "dicts/x",
                "user/pinyin.txt.bak", "user/a.tmp", "restore_staging/e0", "./x", "a/./b"};
        boolean allRejected = true;
        for (String b : bad) { if (BackupScope.resolve(fd, b) != null) { allRejected = false; System.out.println("  not rejected: " + b); } }
        check(allRejected, "resolve: rejects traversal / absolute / skipped dirs / temp names");
        long sig1 = BackupScope.signature(fd, pd);
        check(sig1 > 0, "signature non-zero");
        new File(fd, "future/new_feature.dat").setLastModified(sig1 + 5000);
        check(BackupScope.signature(fd, pd) >= sig1 + 5000, "signature changes with new data");
        File cacheOnly = new File(fd, "dictcache/pinyin.bin"); long before = BackupScope.signature(fd, pd);
        cacheOnly.setLastModified(before + 99999);
        check(BackupScope.signature(fd, pd) == before, "signature ignores skipped cache (no backup loop)");

        // 已知完好缓存:连续保存仍然正确,中途把主文件弄坏(长度变了)也不会盖掉好备份
        File kn = new File(dir.toFile(), "known.txt");
        for (int i = 1; i <= 3; i++) { final int n = i; SafeFile.write(kn, w -> w.write("t\tv\t" + n + "\n")); }
        byte[] knRaw = Files.readAllBytes(kn.toPath());
        Files.write(kn.toPath(), Arrays.copyOf(knRaw, knRaw.length - 2));   // 带头的文件被截断
        SafeFile.write(kn, w -> w.write("t\tv\t4\n"));
        check(SafeFile.isValid(SafeFile.backupOf(kn)) && Files.readString(SafeFile.backupOf(kn).toPath()).contains("t\tv\t2"), "known-valid cache does not hide later corruption (bak stays the good v2)");

        System.out.println(fails == 0 ? "ALL PASS" : fails + " FAILED");
        System.exit(fails == 0 ? 0 : 1);
    }
}
