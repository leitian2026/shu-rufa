// 多机同步完整流程(LearnSyncCore)的自测:用 tools/fake_dav.py(本机假的 WebDAV 服务器)模拟几台手机共用同一个坚果云文件夹。
// 在工程根目录执行(需要 JDK 17+ 和 python3):
//   rm -rf /tmp/davdata2 && mkdir -p /tmp/davdata2 && (python3 tools/fake_dav.py 18091 /tmp/davdata2 &)
//   javac -encoding UTF-8 -d /tmp/lse2e app/src/main/java/com/example/myime/{SafeFile,UserFreq,UserChain,LearnShare,LearnSyncCore,BackupCrypto,WebDavClient}.java \
//         tools/stub/com/example/myime/ErrorHint.java tools/LearnSyncE2E.java
//   java -Dfile.encoding=UTF-8 -cp /tmp/lse2e LearnSyncE2E 18091
// 覆盖:A 上传 → B 拉取合并并上传 → A 拉取;远端没变化不再下载;备份文件和别的文件不碰;
//       不同密钥的手机解不开 → 跳过且不反复重试;损坏的文件跳过;清除时间下限;网络不通整体失败;临时文件清干净。
import com.example.myime.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;

public class LearnSyncE2E {
    static int fails = 0;
    static void check(boolean ok, String name) { System.out.println((ok ? "PASS " : "FAIL ") + name); if (!ok) fails++; }

    static final class Phone {
        final String id;
        final File dir;
        final UserFreq p, s;
        final UserChain c;
        final Map<String, String> sigs = new HashMap<>();
        long floor = 0L;
        int flushes = 0;

        Phone(String id) throws IOException {
            this.id = id;
            dir = Files.createTempDirectory("phone_" + id).toFile();
            p = new UserFreq(new File(dir, "pinyin.txt"));
            s = new UserFreq(new File(dir, "stroke.txt"));
            c = new UserChain(new File(dir, "chain.txt"));
        }

        LearnSyncCore.State state() {
            return new LearnSyncCore.State() {
                public String sig(String n) { return sigs.get(n); }
                public void putSig(String n, String v) { sigs.put(n, v); }
                public long floor() { return floor; }
            };
        }

        LearnSyncCore.Result run(WebDavClient cl, List<String> folder, byte[] master, boolean push) throws IOException {
            return LearnSyncCore.run(cl, folder, id, master, new byte[16], p, s, c, dir, state(), push, System.currentTimeMillis() + 1000, () -> flushes++);
        }

        boolean noTmp() {
            String[] l = dir.list((d, n) -> n.endsWith(".tmp") && n.startsWith("learn_"));
            return l == null || l.length == 0;
        }
    }

    public static void main(String[] a) throws Exception {
        String base = "http://127.0.0.1:" + a[0] + "/dav/";
        WebDavClient cl = new WebDavClient(base, "a@b.c", "secretpw", true, null);
        List<String> folder = WebDavClient.splitFolder("我的坚果云/myime");
        byte[] master = BackupCrypto.deriveMaster("same-secret".toCharArray(), new byte[16], BackupCrypto.MIN_ITERATIONS);
        byte[] other = BackupCrypto.deriveMaster("different".toCharArray(), new byte[16], BackupCrypto.MIN_ITERATIONS);

        Phone A = new Phone("aaaa"), B = new Phone("bbbb");
        A.p.learn("pingguo", "苹果"); A.s.learn("12", "木"); A.c.learn("吃", "苹果");
        Thread.sleep(5);
        B.p.learn("xiangjiao", "香蕉"); B.c.learn("吃", "香蕉");

        // 文件夹里已经有备份文件和别的文件:不能碰
        WebDavClient.splitFolder("x");
        cl.ensureFolder(folder);
        Path junk = Files.createTempFile("junk", ".bin"); Files.write(junk, new byte[]{1, 2, 3});
        cl.put(folder, "myime-backup-20261014-010101.mybk", junk.toFile());
        cl.put(folder, "readme.txt", junk.toFile());

        // 1. A 第一次:拉取(空)+ 上传
        LearnSyncCore.Result r = A.run(cl, folder, master, true);
        check(r.pushed && r.filesMerged == 0 && r.filesSkipped == 0, "A 首次:没有别人的文件,上传成功");
        boolean has = false; for (WebDavClient.Entry e : cl.list(folder)) has |= e.name.equals("myime-learn-aaaa.lrn") && e.size > 0;
        check(has, "云端有 A 的文件");
        check(A.noTmp(), "A 的临时文件清干净了");

        // 2. B:拉取 A 的并上传
        r = B.run(cl, folder, master, true);
        check(r.filesMerged == 1 && r.changed > 0 && r.pushed, "B 拉到 A 的文件并合并,然后上传: " + r.summary());
        check(B.p.textStamp("苹果") > 0 && B.s.textStamp("木") > 0, "B 现在有 A 的拼音和笔画记录");
        check(B.c.next(null, "吃").containsAll(Arrays.asList("苹果", "香蕉")), "B 联想里两台手机的接词都有");
        check(B.flushes == 1, "合并后先落盘一次(再记版本标记)");

        // 3. A:只拉取
        r = A.run(cl, folder, master, false);
        check(r.filesMerged == 1 && !r.pushed && A.p.textStamp("香蕉") > 0, "A 拉到 B 的更新: " + r.summary());
        check(A.c.next(null, "吃").contains("香蕉"), "A 联想里也有了 B 的接词");

        // 4. A 再拉:没有变化,不下载
        r = A.run(cl, folder, master, false);
        check(r.filesMerged == 0 && r.filesSkipped == 0 && r.changed == 0, "远端没变化:不再下载、不再合并");

        // 5. B 学了新词再上传,A 能再次拉到
        Thread.sleep(5);
        B.p.learn("xigua", "西瓜");
        B.run(cl, folder, master, true);
        r = A.run(cl, folder, master, false);
        check(r.filesMerged == 1 && A.p.textStamp("西瓜") > 0, "B 更新后 A 再次拉到新词");

        // 6. 不同密钥的手机:解不开 → 跳过,不反复重试
        Phone C = new Phone("cccc");
        r = C.run(cl, folder, other, false);
        check(r.filesMerged == 0 && r.filesSkipped == 2 && r.changed == 0, "不同密钥:2 个文件都解不开,跳过: " + r.summary());
        check(C.p.textStamp("苹果") == 0, "解不开的文件没有合进任何东西");
        r = C.run(cl, folder, other, false);
        check(r.filesSkipped == 0, "已跳过的文件没变就不再反复重试");

        // 7. 损坏的文件:跳过
        Path bad = Files.createTempFile("bad", ".bin"); Files.write(bad, "not an encrypted file at all, just text".getBytes());
        cl.put(folder, "myime-learn-zzzz.lrn", bad.toFile());
        Phone D = new Phone("dddd");
        r = D.run(cl, folder, master, false);
        check(r.filesSkipped == 1 && r.filesMerged == 2, "损坏的文件跳过,好的两个照常合并: " + r.summary());

        // 8. 清除时间下限:这台手机清除过,清除之前的记录不收
        Phone E = new Phone("eeee");
        E.floor = System.currentTimeMillis() + 60_000;
        r = E.run(cl, folder, master, false);
        check(E.p.textStamp("苹果") == 0 && E.c.next(null, "吃").isEmpty(), "清除下限之前的记录没被同步回来");

        // 9. 自己的文件不会读回自己
        r = A.run(cl, folder, master, false);
        check(r.filesMerged == 0, "不读自己的文件");

        // 10. 网络不通:整体失败(抛异常),什么都没记
        WebDavClient dead = new WebDavClient("http://127.0.0.1:1/dav/", "a@b.c", "secretpw", true, null);
        Phone F = new Phone("ffff");
        boolean threw = false;
        try { F.run(dead, folder, master, true); } catch (IOException e) { threw = true; }
        check(threw && F.sigs.isEmpty() && F.noTmp(), "网络不通:抛异常,没有记下任何版本标记");

        // 11. 备份文件和别的文件原封不动
        List<String> names = new ArrayList<>(); for (WebDavClient.Entry e : cl.list(folder)) names.add(e.name);
        check(names.contains("myime-backup-20261014-010101.mybk") && names.contains("readme.txt"), "备份文件和别的文件没被动过");

        System.out.println(fails == 0 ? "ALL PASS" : (fails + " FAILED"));
        System.exit(fails == 0 ? 0 : 1);
    }
}
