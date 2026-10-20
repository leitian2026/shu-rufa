import com.example.myime.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

// 用真实的 SentenceDecoder + 词表,比较有无搭配模型的整句首选正确率。用法见 tools/gram/README.md。
public class EvalGram {
    static SentenceDecoder build(List<String[]> rows) {
        int n = rows.size();
        String[] texts = new String[n], compact = new String[n];
        int[] freqs = new int[n];
        List<Byte> lens = new ArrayList<>();
        int[] start = new int[n + 1];
        for (int i = 0; i < n; i++) {
            String[] c = rows.get(i);
            texts[i] = c[0];
            freqs[i] = (int) Math.min(Integer.MAX_VALUE, Long.parseLong(c[2]));
            start[i] = lens.size();
            StringBuilder sb = new StringBuilder();
            for (String s : c[1].split(" ")) { lens.add((byte) s.length()); sb.append(s); }
            compact[i] = sb.toString();
        }
        start[n] = lens.size();
        byte[] sl = new byte[lens.size()];
        for (int i = 0; i < sl.length; i++) sl[i] = lens.get(i);
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) order[i] = i;
        Arrays.sort(order, Comparator.comparing(i -> compact[i]));
        String[] keys = new String[n]; int[] ids = new int[n];
        for (int i = 0; i < n; i++) { ids[i] = order[i]; keys[i] = compact[order[i]]; }
        String[] ini = new String[n];
        for (int i = 0; i < n; i++) {
            StringBuilder sb = new StringBuilder(); int pos = 0;
            for (int k = start[i]; k < start[i + 1]; k++) { sb.append(compact[i].charAt(pos)); pos += sl[k]; }
            ini[i] = sb.toString();
        }
        Integer[] o2 = new Integer[n];
        for (int i = 0; i < n; i++) o2[i] = i;
        Arrays.sort(o2, Comparator.comparing(i -> ini[i]));
        String[] ik = new String[n]; int[] ii = new int[n];
        for (int i = 0; i < n; i++) { ii[i] = o2[i]; ik[i] = ini[o2[i]]; }
        return new SentenceDecoder(keys, ids, texts, freqs, sl, start, compact, ik, ii);
    }

    public static void main(String[] a) throws Exception {
        
        List<String[]> rows = new ArrayList<>();
        for (String line : Files.readAllLines(Paths.get(a[0]), StandardCharsets.UTF_8)) {
            String[] c = line.split("\t");
            for (String py : c[2].split("\\|")) {
                String[] syl = py.trim().split(" ");
                if (syl.length != c[0].codePointCount(0, c[0].length())) continue;
                rows.add(new String[] {c[0], py.trim(), c[1]});
            }
        }
        SentenceDecoder d = build(rows);
        List<String[]> gold = new ArrayList<>();
        for (String line : Files.readAllLines(Paths.get(a[1]), StandardCharsets.UTF_8)) gold.add(line.split("\t"));
        boolean verbose = a.length > 3 && a[3].equals("v");
        for (int mode = 0; mode < 2; mode++) {
            if (mode == 1) {
                if (a.length < 3 || a[2].equals("-")) break;
                d.setGram(GramPack.open(new File(a[2])));
            }
            int ok = 0; long t0 = System.nanoTime();
            List<String> bad = new ArrayList<>();
            for (String[] g : gold) {
                List<SentenceDecoder.Result> r = d.decodeTop(g[1], null, null, null, 3);
                String top = r.isEmpty() ? "" : r.get(0).text;
                if (top.equals(g[0])) ok++; else bad.add(g[0] + " -> " + top);
            }
            double ms = (System.nanoTime() - t0) / 1e6 / gold.size();
            System.out.println((mode == 0 ? "无搭配  " : "有搭配  ") + ok + " / " + gold.size() + String.format("  (每句 %.1f ms)", ms));
            if (verbose || mode == 1) for (String b : bad) System.out.println("    x " + b);
        }
    }
}
