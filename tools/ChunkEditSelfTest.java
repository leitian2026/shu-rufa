// 词块编辑(ChunkEdit)自测:不需要 Android,电脑上有 JDK 17+ 即可运行。
// 在工程根目录执行:  bash tools/run_selftests.sh ChunkEdit
// 覆盖:换词、拼音块换词后变正常块、拆成单字、合并、上屏文字只取开头连续的非拼音块、拼音字母数、拆分的校验、音节切分。
import com.example.myime.ChunkEdit;
import java.util.*;

public class ChunkEditSelfTest {
    static int fails = 0;
    static void check(boolean ok, String name) { System.out.println((ok ? "PASS " : "FAIL ") + name); if (!ok) fails++; }

    static ChunkEdit sample() {
        // 手机上的实际好点:手机上 / 的 / 实际 / 好点,后面还有没吃掉的拼音 xx
        return new ChunkEdit(Arrays.asList(
            new ChunkEdit.Item("手机上", "shoujishang", false),
            new ChunkEdit.Item("的", "de", false),
            new ChunkEdit.Item("实际", "shiji", false),
            new ChunkEdit.Item("好点", "haodian", false),
            new ChunkEdit.Item("xx", "xx", true)));
    }

    public static void main(String[] a) {
        ChunkEdit c = sample();
        check(c.size() == 5 && !c.edited(), "初始 5 块,没改过");
        check(c.text().equals("手机上的实际好点"), "上屏文字只取开头的非拼音块:" + c.text());
        check(c.letters() == "shoujishangdeshijihaodian".length(), "拼音字母数 " + c.letters());

        check(c.replace(3, "耗电") && c.get(3).text.equals("耗电") && c.get(3).code.equals("haodian"), "第 4 块换成 耗电,拼音不变");
        check(c.edited() && c.text().equals("手机上的实际耗电"), "改过,文字更新:" + c.text());
        check(!c.replace(3, "耗电"), "换成同一个词算没改");
        check(!c.replace(9, "x") && !c.replace(-1, "x") && !c.replace(0, "") && !c.replace(0, null), "越界 / 空文字被拒绝");

        // 拼音块换词
        check(c.get(4).raw, "最后一块是拼音块");
        check(c.replace(4, "吸"), "拼音块换成词");
        check(!c.get(4).raw && c.text().equals("手机上的实际耗电吸") && c.letters() == 25 + 2, "换成词后算进上屏文字:" + c.text() + " / " + c.letters());

        // 拆分
        ChunkEdit d = sample();
        List<String> syl = ChunkEdit.syllables("haodian");
        check(syl != null && syl.equals(Arrays.asList("hao", "dian")), "haodian 切成 hao dian:" + syl);
        check(d.split(3, Arrays.asList("好", "点"), syl) && d.size() == 6, "好点 拆成 好 / 点,共 6 块");
        check(d.get(3).text.equals("好") && d.get(3).code.equals("hao") && d.get(4).text.equals("点") && d.get(4).code.equals("dian"), "拆出来的两块文字和拼音对得上");
        check(d.text().equals("手机上的实际好点") && d.edited(), "拆开后整句文字不变");
        check(!d.split(0, Arrays.asList("手", "机"), Arrays.asList("shou", "ji")), "拼音连起来对不上原来的,拒绝拆");
        check(!d.split(0, Arrays.asList("手"), Arrays.asList("shoujishang")), "只拆成一块没有意义,拒绝");
        check(!d.split(0, Arrays.asList("手", "机"), Arrays.asList("shou")), "文字和拼音个数不一致,拒绝");

        // 合并
        ChunkEdit m = sample();
        check(m.merge(1, "的实际") && m.size() == 4 && m.get(1).code.equals("deshiji") && m.get(1).text.equals("的实际"), "第 2、3 块合并,拼音连起来");
        check(!m.merge(3, "x") && !m.merge(-1, "x") && !m.merge(0, ""), "最后一块不能向后合并 / 越界 / 空文字被拒绝");
        ChunkEdit m2 = sample();
        check(m2.merge(3, "好点吸") && m2.get(3).code.equals("haodianxx") && !m2.get(3).raw, "词块和后面的拼音块合并,成为正常块");

        // 拼音块在中间时上屏文字停在它前面
        ChunkEdit r = new ChunkEdit(Arrays.asList(
            new ChunkEdit.Item("你好", "nihao", false),
            new ChunkEdit.Item("zz", "zz", true),
            new ChunkEdit.Item("吗", "ma", false)));
        check(r.text().equals("你好") && r.letters() == 5, "拼音块后面的块不进上屏文字");

        check(ChunkEdit.syllables("xyz") == null || true, "不完整音节不抛异常");
        check(new ChunkEdit(new ArrayList<ChunkEdit.Item>()).text().isEmpty(), "空列表文字为空");

        System.out.println(fails == 0 ? "ALL PASS" : ("FAILED " + fails));
        System.exit(fails == 0 ? 0 : 1);
    }
}
