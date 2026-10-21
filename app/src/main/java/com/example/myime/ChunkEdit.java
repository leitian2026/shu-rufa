package com.example.myime;

import java.util.ArrayList;
import java.util.List;

/**
 * 整句「词块」编辑(纯 JDK,可脱离手机测试,见 tools/ChunkEditSelfTest.java)。
 *
 * 候选栏上方的第二行把当前整句按词切成一块一块;点一块可以换成别的词、拆成单字、和后一块合并。
 * 这里只管块的列表怎么变,不碰界面,也不碰词库:换成什么词由调用方(查词库)给进来。
 * 整句末尾没被解码吃掉的拼音是「拼音块」(raw),它只显示字母,不会被当成文字上屏。
 */
public final class ChunkEdit {
    public static final class Item {
        public final String text;   // 文字(拼音块时是拼音字母)
        public final String code;   // 对应的拼音连写
        public final boolean raw;

        public Item(String text, String code, boolean raw) {
            this.text = text;
            this.code = code;
            this.raw = raw;
        }
    }

    private final ArrayList<Item> items = new ArrayList<>();
    private boolean edited;

    public ChunkEdit(List<Item> init) {
        items.addAll(init);
    }

    public int size() {
        return items.size();
    }

    public Item get(int i) {
        return items.get(i);
    }

    /** 用户改过(换词、拆、并)。没改过的整句就是引擎给的那一句,不用另外处理。 */
    public boolean edited() {
        return edited;
    }

    /** 第 i 块换成 text(拼音块换成词以后就是正常的块)。 */
    public boolean replace(int i, String text) {
        if (i < 0 || i >= items.size() || text == null || text.isEmpty()) return false;
        Item it = items.get(i);
        if (!it.raw && it.text.equals(text)) return false;
        items.set(i, new Item(text, it.code, false));
        edited = true;
        return true;
    }

    /** 第 i 块拆成几块:codes 是每块的拼音(连起来必须正好是原来的拼音),texts 是每块的文字。 */
    public boolean split(int i, List<String> texts, List<String> codes) {
        if (i < 0 || i >= items.size() || texts.size() != codes.size() || texts.size() < 2) return false;
        StringBuilder joined = new StringBuilder();
        for (String c : codes) joined.append(c);
        if (!joined.toString().equals(items.get(i).code)) return false;
        items.remove(i);
        for (int k = 0; k < texts.size(); k++) items.add(i + k, new Item(texts.get(k), codes.get(k), false));
        edited = true;
        return true;
    }

    /** 第 i 块和第 i+1 块合成一块,文字是 text(调用方查到的、拼音正好是两块拼音连起来的词)。 */
    public boolean merge(int i, String text) {
        if (i < 0 || i + 1 >= items.size() || text == null || text.isEmpty()) return false;
        Item a = items.get(i);
        Item b = items.get(i + 1);
        items.set(i, new Item(text, a.code + b.code, false));
        items.remove(i + 1);
        edited = true;
        return true;
    }

    /** 开头连续的、不是拼音块的那几块拼成的文字:这就是改完以后整句要上屏的内容。 */
    public String text() {
        StringBuilder sb = new StringBuilder();
        for (Item it : items) {
            if (it.raw) break;
            sb.append(it.text);
        }
        return sb.toString();
    }

    /** 上面那段文字吃掉的拼音字母数(不含分词符)。 */
    public int letters() {
        int n = 0;
        for (Item it : items) {
            if (it.raw) break;
            n += it.code.length();
        }
        return n;
    }

    /** 一个拼音连写按音节切开;切不开(不是完整音节)返回 null。 */
    public static List<String> syllables(String code) {
        int[] ends = Syllabary.segment(code);
        if (ends == null) return null;
        ArrayList<String> out = new ArrayList<>(ends.length);
        int s = 0;
        for (int e : ends) {
            out.add(code.substring(s, e));
            s = e;
        }
        return out;
    }
}
