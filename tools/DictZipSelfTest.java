// 词库本地导入的 zip 展开(DictZip)自测:不需要 Android,电脑上有 JDK 17+ 即可运行。
// 在工程根目录执行:
//   javac -encoding UTF-8 -d /tmp/dztest app/src/main/java/com/example/myime/DictZip.java tools/DictZipSelfTest.java
//   java -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -cp /tmp/dztest DictZipSelfTest
// 覆盖:zip 魔数识别、展开普通文件、只用最后一段当文件名(../ 和绝对路径写不到外面)、跳过目录和隐藏文件(含 __MACOSX)、
//       重名处理、总大小上限、不是 zip 的文件、内容原样。
import com.example.myime.DictZip;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

public class DictZipSelfTest {
    static int fails = 0;
    static void check(boolean ok, String name) { System.out.println((ok ? "PASS " : "FAIL ") + name); if (!ok) fails++; }

    static File zipOf(File dir, String name, String[][] entries) throws IOException {
        File f = new File(dir, name);
        try (ZipOutputStream z = new ZipOutputStream(new FileOutputStream(f))) {
            for (String[] e : entries) {
                z.putNextEntry(new ZipEntry(e[0]));
                if (!e[0].endsWith("/")) z.write(e[1].getBytes(StandardCharsets.UTF_8));
                z.closeEntry();
            }
        }
        return f;
    }

    public static void main(String[] a) throws Exception {
        File dir = Files.createTempDirectory("dztest").toFile();
        File plain = new File(dir, "plain.txt");
        Files.write(plain.toPath(), "hello".getBytes(StandardCharsets.UTF_8));
        check(!DictZip.isZip(plain), "普通文件不是 zip");
        check(!DictZip.isZip(new File(dir, "nope")), "不存在的文件不是 zip");

        File z = zipOf(dir, "a.zip", new String[][] {
            {"gram.bin", "BINARY"},
            {"sub/dir/", ""},
            {"sub/dir/base.dict.yaml", "词\tci\t1"},
            {"__MACOSX/._gram.bin", "junk"},
            {".hidden", "x"},
            {"../../evil.txt", "evil"},
            {"/abs/path/abs.txt", "abs"},
            {"gram.bin2", "x"},
            {"dup/gram.bin", "second"},
        });
        check(DictZip.isZip(z), "zip 魔数识别");
        File out = new File(dir, "out");
        List<File> r = DictZip.expand(z, out, 1 << 20);
        List<String> names = new ArrayList<>();
        for (File f : r) names.add(f.getName());
        System.out.println("   展开 " + names);
        check(names.equals(Arrays.asList("gram.bin", "base.dict.yaml", "evil.txt", "abs.txt", "gram.bin2", "gram_2.bin")),
              "只展开普通文件,跳过目录 / 隐藏 / __MACOSX;重名加 _2");
        for (File f : r) check(f.getParentFile().equals(out), "写在目标文件夹里:" + f.getName());
        check(!new File(dir, "evil.txt").exists() && !new File(dir.getParentFile(), "evil.txt").exists(), "../ 没有写到外面");
        check(new String(Files.readAllBytes(new File(out, "base.dict.yaml").toPath()), StandardCharsets.UTF_8).equals("词\tci\t1"), "内容原样(含中文和制表符)");
        check(new String(Files.readAllBytes(new File(out, "gram_2.bin").toPath()), StandardCharsets.UTF_8).equals("second"), "重名的内容对");

        // 总大小上限
        File big = zipOf(dir, "big.zip", new String[][] {{"x.bin", "0123456789".repeat(100)}});
        boolean thrown = false;
        try { DictZip.expand(big, new File(dir, "out2"), 500); } catch (IOException e) { thrown = true; }
        check(thrown, "展开总大小超过上限被拒绝");
        File[] left = new File(dir, "out2").listFiles();
        check(left == null || left.length == 0, "超限时不留下半个文件");

        // 空 zip / 全是目录
        File empty = zipOf(dir, "empty.zip", new String[][] {{"d/", ""}});
        check(DictZip.expand(empty, new File(dir, "out3"), 1000).isEmpty(), "只有目录的 zip 展开为空");

        System.out.println(fails == 0 ? "ALL PASS" : ("FAILED " + fails));
        System.exit(fails == 0 ? 0 : 1);
    }
}
