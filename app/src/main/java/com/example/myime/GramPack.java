package com.example.myime;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;

/**
 * 搭配模型包(纯 JDK,可脱离手机测试,见 tools/GramPackSelfTest.java)。
 *
 * 里面存的是「哪些汉字串经常连着出现」:键是一小段汉字(2~6 个字,通常是前一个词的末尾几个字 + 后一个词开头几个字),
 * 值是一个字节(1~255,越大越常见)。数据来自万象语法模型(CC BY 4.0),在电脑上抽取、剪枝后用 BuildGramPack 打包。
 * 整句解码时,相邻两个词如果能在这里查到搭配,整句就加一点分(见 SentenceDecoder.GRAM_WEIGHT),
 * 所以「在建 / 再见」「的 / 得」这类同音词能靠上下文选对。
 *
 * 文件格式(小端):
 *   0   8  魔数 "MYGRAM01"
 *   8   4  版本(1)
 *  12   4  条目数
 *  16   4  桶位数 B(桶数 = 2^B)
 *  20   4  校验位数 C(固定 16)
 *  24   8  保留
 *  32      桶起点表:(2^B + 1) 个 uint32,第 b 个桶的条目是 [tbl[b], tbl[b+1])
 *  之后    条目:每条 3 字节 = 校验码 uint16 + 值 uint8,按桶顺序排放
 * 键的 64 位哈希高 B 位是桶号,接着的 16 位是校验码,所以不存键本身:体积小,
 * 代价是查一个不存在的键有约 (每桶条目数 / 65536) ≈ 万分之一点四的概率被误认为存在,对加分来说可以忽略。
 *
 * 查询不分配内存、不建字符串,直接在内存映射上读。线程安全(只读,绝对位置读取)。
 */
public final class GramPack {
    public static final int HEADER = 32;
    public static final int FORMAT_VERSION = 1;
    public static final int BUCKET_BITS = 20;
    public static final int CHECK_BITS = 16;
    private static final byte[] MAGIC = {'M', 'Y', 'G', 'R', 'A', 'M', '0', '1'};

    /** 一对词之间的搭配分:值 / 10 再减去这个数,不到 0 的当 0(原始值是 ln(分)×10000,字节值 = 原始值 / 1000)。 */
    public static final double SCORE_FLOOR = 8.0;
    private static final int MAX_SIDE = 3;   // 前一个词取末尾最多几个字、后一个词取开头最多几个字

    private final ByteBuffer buf;
    private final int bucketBits;
    private final int count;
    private final int entriesOff;

    private GramPack(ByteBuffer b) throws IOException {
        this.buf = b.order(ByteOrder.LITTLE_ENDIAN);
        if (buf.limit() < HEADER) throw new IOException("搭配模型文件太短");
        for (int i = 0; i < MAGIC.length; i++) {
            if (buf.get(i) != MAGIC[i]) throw new IOException("不是搭配模型文件");
        }
        if (buf.getInt(8) != FORMAT_VERSION) throw new IOException("搭配模型版本不支持");
        count = buf.getInt(12);
        bucketBits = buf.getInt(16);
        if (buf.getInt(20) != CHECK_BITS || bucketBits < 1 || bucketBits > 26) throw new IOException("搭配模型参数不对");
        long tableBytes = ((1L << bucketBits) + 1) * 4;
        long need = HEADER + tableBytes + 3L * count;
        if (need > buf.limit() || count < 0) throw new IOException("搭配模型文件不完整");
        long first = buf.getInt(HEADER) & 0xFFFFFFFFL;
        long last = buf.getInt((int) (HEADER + tableBytes - 4)) & 0xFFFFFFFFL;
        if (first != 0 || last != count) throw new IOException("搭配模型索引损坏");
        entriesOff = (int) (HEADER + tableBytes);
    }

    /** 内存映射一个搭配模型文件(只读)。文件不对时抛 IOException。 */
    public static GramPack open(File f) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(f, "r"); FileChannel ch = raf.getChannel()) {
            long len = ch.size();
            if (len > Integer.MAX_VALUE) throw new IOException("搭配模型文件太大");
            MappedByteBuffer m = ch.map(FileChannel.MapMode.READ_ONLY, 0, len);
            return new GramPack(m);
        }
    }

    /** 直接用内存里的字节(测试用)。 */
    public static GramPack wrap(byte[] bytes) throws IOException {
        return new GramPack(ByteBuffer.wrap(bytes));
    }

    /** 只检查文件头和大小(下载完用来判断是不是搭配模型包),返回条目数。 */
    public static int verify(File f) throws IOException {
        return open(f).count;
    }

    public int count() {
        return count;
    }

    // ------------------------------------------------------------ 哈希(打包和查询必须完全一致)

    private static final long FNV_OFFSET = 0xcbf29ce484222325L;
    private static final long FNV_PRIME = 0x100000001b3L;

    static long mix(long h) {
        h ^= h >>> 32;
        h *= 0x9E3779B97F4A7C15L;
        h ^= h >>> 29;
        return h;
    }

    /** 整个字符串的哈希。 */
    public static long hash(CharSequence s) {
        long h = FNV_OFFSET;
        for (int i = 0; i < s.length(); i++) {
            h ^= s.charAt(i);
            h *= FNV_PRIME;
        }
        return mix(h);
    }

    /** a 从 aFrom 到结尾 + b 从开头到 bTo 拼起来的哈希(不真的拼字符串)。 */
    private static long hashJoin(String a, int aFrom, String b, int bTo) {
        long h = FNV_OFFSET;
        for (int i = aFrom; i < a.length(); i++) {
            h ^= a.charAt(i);
            h *= FNV_PRIME;
        }
        for (int i = 0; i < bTo; i++) {
            h ^= b.charAt(i);
            h *= FNV_PRIME;
        }
        return mix(h);
    }

    /** 哈希 → 桶号。 */
    public static int bucketOf(long h, int bits) {
        return (int) (h >>> (64 - bits));
    }

    /** 哈希 → 校验码。 */
    public static int checkOf(long h) {
        return (int) ((h >>> (64 - BUCKET_BITS - CHECK_BITS)) & 0xFFFF);
    }

    // ------------------------------------------------------------ 查询

    private int lookupHash(long h) {
        int b = bucketOf(h, bucketBits);
        int chk = checkOf(h);
        int s = buf.getInt(HEADER + 4 * b);
        int e = buf.getInt(HEADER + 4 * b + 4);
        int best = 0;
        for (int i = s; i < e; i++) {
            int p = entriesOff + 3 * i;
            if (((buf.get(p) & 0xFF) | ((buf.get(p + 1) & 0xFF) << 8)) == chk) {
                int v = buf.get(p + 2) & 0xFF;
                if (v > best) best = v;
            }
        }
        return best;
    }

    /** 查一个汉字串;没有返回 0,有返回 1~255。 */
    public int lookup(CharSequence key) {
        return lookupHash(hash(key));
    }

    /**
     * 前一个词 w1 和后一个词 w2 相连的搭配分(≥ 0,没有搭配就是 0)。
     * 取 w1 末尾 1~3 个字 与 w2 开头 1~3 个字 的所有组合去查,取最大的:
     * 分 = 字节值 / 10 - SCORE_FLOOR,小于 0 记 0。
     */
    public double pairScore(String w1, String w2) {
        int n1 = w1.length();
        int n2 = w2.length();
        if (n1 == 0 || n2 == 0) return 0.0;
        int best = 0;
        for (int a = 1; a <= MAX_SIDE && a <= n1; a++) {
            int from = n1 - a;
            for (int b = 1; b <= MAX_SIDE && b <= n2; b++) {
                int v = lookupHash(hashJoin(w1, from, w2, b));
                if (v > best) best = v;
            }
        }
        double s = best / 10.0 - SCORE_FLOOR;
        return s > 0 ? s : 0.0;
    }

    // ------------------------------------------------------------ 打包(电脑上的 BuildGramPack 和自测用,手机上不会调用)

    /** 一条搭配的打包用整数:桶号 | 校验码 | 值字节。rawValue = ln(分)×10000,字节值 = round(rawValue / 1000),夹在 1~255。 */
    public static long composite(String key, int rawValue) {
        int q = Math.max(1, Math.min(255, Math.round(rawValue / 1000f)));
        long h = hash(key);
        return ((long) bucketOf(h, BUCKET_BITS) << 24) | ((long) checkOf(h) << 8) | q;
    }

    /** 把 composite() 的结果(前 n 个,会被排序)组装成文件内容。同桶同校验码的只留值最大的一条。 */
    public static byte[] assemble(long[] comps, int n) {
        java.util.Arrays.sort(comps, 0, n);
        int m = 0;
        for (int i = 0; i < n; i++) {
            if (i + 1 < n && (comps[i] >>> 8) == (comps[i + 1] >>> 8)) continue;
            comps[m++] = comps[i];
        }
        int buckets = 1 << BUCKET_BITS;
        int[] tbl = new int[buckets + 1];
        for (int i = 0; i < m; i++) tbl[(int) (comps[i] >>> 24) + 1]++;
        for (int b = 0; b < buckets; b++) tbl[b + 1] += tbl[b];
        ByteBuffer out = ByteBuffer.allocate(HEADER + 4 * (buckets + 1) + 3 * m).order(ByteOrder.LITTLE_ENDIAN);
        out.put(MAGIC);
        out.putInt(FORMAT_VERSION).putInt(m).putInt(BUCKET_BITS).putInt(CHECK_BITS).putLong(0L);
        for (int v : tbl) out.putInt(v);
        for (int i = 0; i < m; i++) {
            int chk = (int) ((comps[i] >>> 8) & 0xFFFF);
            out.put((byte) (chk & 0xFF)).put((byte) (chk >>> 8)).put((byte) (comps[i] & 0xFF));
        }
        return out.array();
    }

    /** 方便测试:键 → 原始值 的表直接打成文件内容。 */
    public static byte[] build(java.util.Map<String, Integer> raw) {
        long[] arr = new long[raw.size()];
        int n = 0;
        for (java.util.Map.Entry<String, Integer> e : raw.entrySet()) arr[n++] = composite(e.getKey(), e.getValue());
        return assemble(arr, n);
    }
}
