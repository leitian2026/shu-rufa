package com.example.myime;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 多台自己的手机共享\"词频学习数据\"用的文件格式。纯 JDK 代码(不依赖 Android),可以脱离手机单独测试(见 tools/LearnSyncSelfTest.java)。
 *
 * 只共享三样:拼音的选词记录(UserFreq)、笔画的选词记录(UserFreq)、中文联想(UserChain)。
 * 别的数据(剪贴板、回收箱、设置、翻译配置、英文用词……)一概不碰:这里是白名单,只认下面三个对象。
 *
 * 明文格式(之后整体 gzip 压缩、用 BackupCrypto 加密,见 LearnSync):
 *   #myime-learn v1
 *   @pinyin      后面是拼音 UserFreq 的 T / P / W 行(和存盘文件里的行一样)
 *   @stroke      后面是笔画 UserFreq 的 T / P / W 行
 *   @chain       后面是 UserChain 的 C 行
 *   #end         结尾标记(没有就当文件不完整)
 * 每行以 \n 结尾;联想的两词上下文里有 \u0001,所以读的时候只按 \n 断行(不能用 readLine,它会把 \r 也当换行)。
 *
 * 合并规则只有一条:同一个 key 取较大的时间戳(窗口取最新的几个),所以顺序无关、重复合并结果不变,不需要谁当主机。
 */
public final class LearnShare {
    public static final int VERSION = 1;
    private static final String HEADER = "#myime-learn v";
    private static final String TRAILER = "#end";
    private static final int MAX_LINE = 400;           // 单行超过这个长度当作坏行跳过
    private static final int MAX_LINES = 700_000;      // 防止被构造的文件拖垮内存(正常数据远小于这个数)

    private LearnShare() {
    }

    /**
     * 这个词能不能共享 / 接收:不含数字、不含 4 个以上连续的英文字母(手机号、账号、密码、网址之类),
     * 不含制表 / 换行,长度不超过 32。编码(拼音 / 笔画码)不用过滤,只过滤词。
     */
    public static boolean okWord(String s) {
        if (s == null || s.isEmpty() || s.length() > 32) return false;
        int run = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\t' || c == '\n' || c == '\r') return false;
            if (Character.isDigit(c)) return false;
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')) {
                if (++run >= 4) return false;
            } else {
                run = 0;
            }
        }
        return true;
    }

    /** 写出共享文件的明文。 */
    public static void write(Writer w, UserFreq pinyin, UserFreq stroke, UserChain chain) throws IOException {
        w.write(HEADER + VERSION + "\n");
        w.write("@pinyin\n");
        pinyin.exportShared(w);
        w.write("@stroke\n");
        stroke.exportShared(w);
        w.write("@chain\n");
        chain.exportShared(w);
        w.write(TRAILER + "\n");
        w.flush();
    }

    /**
     * 读别的手机的共享文件(明文流),合并进本机。格式不对、版本太新、被截断都抛 IOException,调用方当作\"这个文件跳过\"。
     * 返回发生变化的条目数(0 = 没有任何新东西)。
     */
    public static int merge(InputStream in, UserFreq pinyin, UserFreq stroke, UserChain chain, long now) throws IOException {
        return merge(in, pinyin, stroke, chain, now, 0L);
    }

    /** minTs:这台手机清除学习数据的时间;早于它的记录不收,免得清掉的又被别的手机同步回来。 */
    public static int merge(InputStream in, UserFreq pinyin, UserFreq stroke, UserChain chain, long now, long minTs) throws IOException {
        BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8), 1 << 16);
        String first = readLine(r);
        if (first == null || !first.startsWith(HEADER)) throw new IOException("不是学习数据同步文件");
        int ver;
        try {
            ver = Integer.parseInt(first.substring(HEADER.length()).trim());
        } catch (NumberFormatException e) {
            throw new IOException("同步文件头不正常");
        }
        if (ver > VERSION) throw new IOException("同步文件版本太新,请先更新输入法");

        List<String> pl = new ArrayList<>();
        List<String> sl = new ArrayList<>();
        List<String> cl = new ArrayList<>();
        List<String> cur = null;
        boolean ended = false;
        int total = 0;
        String line;
        while ((line = readLine(r)) != null) {
            if (line.equals(TRAILER)) {
                ended = true;
                break;
            }
            if (line.startsWith("@")) {
                switch (line) {
                    case "@pinyin": cur = pl; break;
                    case "@stroke": cur = sl; break;
                    case "@chain": cur = cl; break;
                    default: cur = null;   // 以后版本加的新区块:不认识就跳过
                }
                continue;
            }
            if (cur == null || line.isEmpty() || line.length() > MAX_LINE) continue;
            if (++total > MAX_LINES) throw new IOException("同步文件过大");
            cur.add(line);
        }
        if (!ended) throw new IOException("同步文件不完整");
        return pinyin.mergeRows(pl, now, minTs) + stroke.mergeRows(sl, now, minTs) + chain.mergeRows(cl, now, minTs);
    }

    /** 只按 \n 断行;文件结束时没有内容返回 null。 */
    private static String readLine(BufferedReader r) throws IOException {
        StringBuilder sb = null;
        int c;
        while ((c = r.read()) >= 0) {
            if (c == '\n') return sb == null ? "" : sb.toString();
            if (sb == null) sb = new StringBuilder(48);
            if (sb.length() <= MAX_LINE) sb.append((char) c);   // 超长的行多读的字符直接丢掉,行本身会因为过长被跳过
        }
        return sb == null ? null : sb.toString();
    }
}
