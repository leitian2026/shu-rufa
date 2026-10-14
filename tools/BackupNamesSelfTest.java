// 备份文件命名 / 按设备分组(BackupNames)的自测:不需要 Android,电脑上有 JDK 17+ 即可运行。
// 在工程根目录执行:
//   javac -d /tmp/selftest app/src/main/java/com/example/myime/BackupNames.java tools/BackupNamesSelfTest.java
//   java -cp /tmp/selftest BackupNamesSelfTest
// 覆盖:设备名整理、新旧文件名解析、清理旧备份只动本机、还原列表分组 / 排序 / 重名标识 / 改名后仍算本机。
import com.example.myime.*;
import java.util.*;

public class BackupNamesSelfTest {
    static int fails = 0;
    static void check(boolean ok, String name) { System.out.println((ok ? "PASS " : "FAIL ") + name); if (!ok) fails++; }

    public static void main(String[] a) {
        // 设备名整理
        check(BackupNames.clean("Redmi K70").equals("Redmi_K70"), "clean: space -> _");
        check(BackupNames.clean("张三 的平板").equals("张三_的平板"), "clean: CJK kept");
        check(BackupNames.clean("a.b/c\\d:e").equals("abcde"), "clean: dot slash colon dropped");
        check(BackupNames.clean("  - Pad -  ").equals("Pad"), "clean: trim separators");
        check(BackupNames.clean("A   B--C").equals("A_B_C"), "clean: collapse");
        check(BackupNames.clean("📱").equals("设备") && BackupNames.clean("").equals("设备") && BackupNames.clean(null).equals("设备"), "clean: empty -> default");
        check(BackupNames.clean("x".repeat(100)).length() == 24, "clean: max 24");

        // 文件名往返
        String f = BackupNames.fileName("Redmi K70", "7c21ab", "20261014-153045");
        check(f.equals("myime-backup-Redmi_K70.7c21ab-20261014-153045.mybk"), "fileName: " + f);
        BackupNames.Info i = BackupNames.parse(f);
        check(i != null && "Redmi_K70".equals(i.name) && "7c21ab".equals(i.tag) && "20261014-153045".equals(i.stamp), "parse new");
        BackupNames.Info j = BackupNames.parse("myime-backup-20261012-101010.mybk");
        check(j != null && j.tag == null && j.name == null && "20261012-101010".equals(j.stamp), "parse legacy");
        check(BackupNames.parse("myime-learn-abc.lrn") == null && BackupNames.parse("myime-backup-x.mybk") == null
                && BackupNames.parse("myime-backup-a.zzzzzz-20261014-153045.mybk") == null && BackupNames.parse(null) == null, "parse: non-backup rejected");
        check(BackupNames.parse(BackupNames.fileName("张三 的平板", "00ff10", "20260101-000000")).name.equals("张三_的平板"), "parse CJK roundtrip");
        check(BackupNames.stampText("20261014-153045").equals("2026-10-14 15:30"), "stampText");

        // 清理:每台设备留 3 份,别的设备 / 旧格式 / 学习数据文件不动
        List<String> files = new ArrayList<>();
        for (int d = 1; d <= 5; d++) files.add(BackupNames.fileName("手机", "aaaaaa", "2026100" + d + "-120000"));
        for (int d = 1; d <= 5; d++) files.add(BackupNames.fileName("平板", "bbbbbb", "2026100" + d + "-120000"));
        files.add("myime-backup-20260901-010101.mybk");
        files.add("myime-learn-abc.lrn");
        List<String> pr = BackupNames.toPrune(files, "aaaaaa", 3);
        check(pr.size() == 2 && pr.contains(BackupNames.fileName("手机", "aaaaaa", "20261001-120000"))
                && pr.contains(BackupNames.fileName("手机", "aaaaaa", "20261002-120000")), "prune: oldest 2 of own device only");
        // 改过名:同一个标识、不同名字的备份仍算同一台,按时间排
        List<String> renamed = new ArrayList<>(files);
        renamed.add(BackupNames.fileName("我的手机", "aaaaaa", "20261010-120000"));
        List<String> pr2 = BackupNames.toPrune(renamed, "aaaaaa", 3);
        check(pr2.size() == 3 && !pr2.contains(BackupNames.fileName("我的手机", "aaaaaa", "20261010-120000")), "prune: renamed device still one pool, sorted by time");
        check(BackupNames.toPrune(files, "cccccc", 3).isEmpty() && BackupNames.toPrune(files, "aaaaaa", 7).isEmpty(), "prune: nothing to do");

        // 还原列表
        List<BackupNames.Item> items = new ArrayList<>();
        for (String s : files) items.add(new BackupNames.Item(s, 1000));
        // 本机是手机 aaaaaa:手机排第一(即使平板更新),然后平板,然后旧版
        items.add(new BackupNames.Item(BackupNames.fileName("平板", "bbbbbb", "20261013-090000"), 2222));
        List<BackupNames.Group> g = BackupNames.latestPerDevice(items, "aaaaaa");
        check(g.size() == 3, "groups: 3 (phone, tablet, legacy)");
        check(g.get(0).local && "手机".equals(g.get(0).label) && g.get(0).stamp.equals("20261005-120000"), "groups: local first, newest of it");
        check("平板".equals(g.get(1).label) && g.get(1).stamp.equals("20261013-090000") && g.get(1).size == 2222 && !g.get(1).local, "groups: tablet newest + its size");
        check(g.get(2).tag == null && g.get(2).label.equals(BackupNames.LEGACY_LABEL), "groups: legacy last");
        // 新手机(本机标识没有备份):没有 local 标记,按时间从新到旧
        List<BackupNames.Group> g2 = BackupNames.latestPerDevice(items, "dddddd");
        check(g2.size() == 3 && !g2.get(0).local && !g2.get(1).local && !g2.get(2).local && "平板".equals(g2.get(0).label), "groups: new device has no local, newest first");
        // 两台设备显示名相同:带标识区分
        List<BackupNames.Item> dup = new ArrayList<>();
        dup.add(new BackupNames.Item(BackupNames.fileName("Redmi", "111111", "20261001-120000"), 1));
        dup.add(new BackupNames.Item(BackupNames.fileName("Redmi", "222222", "20261002-120000"), 1));
        List<BackupNames.Group> g3 = BackupNames.latestPerDevice(dup, "111111");
        check(g3.get(0).label.equals("Redmi(111111)") && g3.get(1).label.equals("Redmi(222222)"), "groups: duplicate names get tag");
        // 没有备份 / 全是别的文件
        check(BackupNames.latestPerDevice(new ArrayList<BackupNames.Item>(), "aaaaaa").isEmpty(), "groups: empty");
        check(BackupNames.latestPerDevice(Arrays.asList(new BackupNames.Item("myime-learn-x.lrn", 1)), "aaaaaa").isEmpty(), "groups: non-backup ignored");
        // 同一台设备改名后,最新的一份显示最新的名字
        List<BackupNames.Item> rn = new ArrayList<>();
        rn.add(new BackupNames.Item(BackupNames.fileName("手机", "aaaaaa", "20261001-120000"), 1));
        rn.add(new BackupNames.Item(BackupNames.fileName("我的手机", "aaaaaa", "20261010-120000"), 1));
        List<BackupNames.Group> g4 = BackupNames.latestPerDevice(rn, "aaaaaa");
        check(g4.size() == 1 && g4.get(0).label.equals("我的手机") && g4.get(0).local, "groups: renamed device is one group, new name");

        System.out.println(fails == 0 ? "ALL PASS" : fails + " FAILED");
        if (fails != 0) System.exit(1);
    }
}
