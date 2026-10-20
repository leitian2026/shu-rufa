// 把 extract_gram 抽出来的「搭配汉字串<Tab>原始值」文本,打成手机上用的搭配模型包(GramPack 格式)。
// 在电脑上用一次,不进 APK。需要 JDK 17+。
//
// 编译:  javac -encoding UTF-8 -d /tmp/gp app/src/main/java/com/example/myime/GramPack.java tools/BuildGramPack.java
// 用法:  java -Xmx2g -Dfile.encoding=UTF-8 -cp /tmp/gp BuildGramPack k5000.tsv gram.bin
//
// 值的换算:原始值 = ln(分)×10000,存成一个字节 = round(原始值 / 1000),夹在 1~255。
// 键的哈希、桶号、校验码都用 GramPack 里的函数,保证和手机上查询时完全一致。
// 两个不同的键如果落在同一个桶、校验码也相同,只留值大的那个。
import com.example.myime.GramPack;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public class BuildGramPack {
    public static void main(String[] a) throws Exception {
        if (a.length < 2) { System.err.println("用法: BuildGramPack in.tsv out.bin"); System.exit(2); }
        long[] arr = new long[1 << 20];
        int n = 0;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(a[0]), StandardCharsets.UTF_8), 1 << 20)) {
            String line;
            while ((line = r.readLine()) != null) {
                int t = line.indexOf('\t');
                if (t <= 0) continue;
                String key = line.substring(0, t);
                int raw;
                try { raw = Integer.parseInt(line.substring(t + 1).trim()); } catch (NumberFormatException e) { continue; }
                long comp = GramPack.composite(key, raw);
                if (n == arr.length) arr = Arrays.copyOf(arr, arr.length * 2);
                arr[n++] = comp;
            }
        }
        System.err.println("读入 " + n + " 条");
        byte[] bytes = GramPack.assemble(arr, n);
        try (OutputStream os = new BufferedOutputStream(new FileOutputStream(a[1]))) { os.write(bytes); }
        System.err.println("写出 " + a[1] + "," + bytes.length + " 字节");
        // 读回来核对条目数
        System.err.println("读回条目数 " + GramPack.verify(new File(a[1])));
    }
}
