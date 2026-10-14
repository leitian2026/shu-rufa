package com.example.myime;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 备份文件的命名和按设备分组。纯 JDK 代码(不依赖 Android),可以脱离手机单独测试。
 *
 * 几台设备(手机、平板)共用一个坚果云文件夹时,靠文件名里的设备名区分各自的备份:
 *   新格式  myime-backup-<设备名>.<设备标识6位>-<yyyyMMdd-HHmmss>.mybk
 *   旧格式  myime-backup-<yyyyMMdd-HHmmss>.mybk            (0.14.0 及以前,没有设备信息,归到「旧版」一组)
 *
 * 判断「是不是本机的备份」只看设备标识(设备 ID 的前 6 位),不看设备名:设备名可以改、系统里的设备名也可能变,
 * 但设备标识不变,所以改名之后以前的备份仍然算本机的。设备名只用来给人看。
 */
public final class BackupNames {
    private BackupNames() {
    }

    public static final String PREFIX = "myime-backup-";
    public static final String EXT = ".mybk";
    public static final String LEGACY_LABEL = "旧版(未标记设备)";
    private static final String DEFAULT_NAME = "设备";
    private static final int MAX_NAME = 24;

    private static final Pattern P = Pattern.compile(
            "^myime-backup-(?:([^.]+)\\.([0-9a-f]{6})-)?([0-9]{8}-[0-9]{6})\\.mybk$");

    /** 一个备份文件名拆开后的结果。tag / name 为 null 表示旧格式。 */
    public static final class Info {
        public final String file;
        public final String name;
        public final String tag;
        public final String stamp;

        Info(String file, String name, String tag, String stamp) {
            this.file = file;
            this.name = name;
            this.tag = tag;
            this.stamp = stamp;
        }
    }

    /** 云端目录里的一项(文件名 + 大小)。 */
    public static final class Item {
        public final String file;
        public final long size;

        public Item(String file, long size) {
            this.file = file;
            this.size = size;
        }
    }

    /** 一台设备的备份里最新的一份,用来在还原列表里显示。 */
    public static final class Group {
        public final String tag;        // 旧版为 null
        public final String name;       // 最新一份里的设备名;旧版为 null
        public final String label;      // 显示用:设备名(重名时带标识)
        public final String file;       // 最新一份的文件名
        public final long size;
        public final String stamp;
        public final boolean local;

        Group(String tag, String name, String label, String file, long size, String stamp, boolean local) {
            this.tag = tag;
            this.name = name;
            this.label = label;
            this.file = file;
            this.size = size;
            this.stamp = stamp;
            this.local = local;
        }
    }

    /**
     * 把系统里读到的设备名整理成能放进文件名的样子:只留字母、数字(含汉字)和下划线,
     * 空白和 - _ 变成下划线,别的符号(包括 .、斜杠、emoji)去掉;连续的下划线合成一个,最长 24 个字符;整理完是空的就用「设备」。
     */
    public static String clean(String raw) {
        StringBuilder sb = new StringBuilder();
        int count = 0;
        if (raw != null) {
            for (int i = 0; i < raw.length() && count < MAX_NAME; ) {
                int cp = raw.codePointAt(i);
                i += Character.charCount(cp);
                if (Character.isLetterOrDigit(cp)) {
                    sb.appendCodePoint(cp);
                    count++;
                } else if (Character.isWhitespace(cp) || cp == '-' || cp == '_') {
                    if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '_') {
                        sb.append('_');
                        count++;
                    }
                }
            }
        }
        int end = sb.length();
        while (end > 0 && sb.charAt(end - 1) == '_') end--;
        sb.setLength(end);
        return sb.length() == 0 ? DEFAULT_NAME : sb.toString();
    }

    /** 新格式备份文件名。tag 必须是 6 位小写十六进制。 */
    public static String fileName(String deviceName, String tag, String stamp) {
        return PREFIX + clean(deviceName) + "." + tag + "-" + stamp + EXT;
    }

    /** 不是备份文件名返回 null。 */
    public static Info parse(String file) {
        if (file == null) return null;
        Matcher m = P.matcher(file);
        if (!m.matches()) return null;
        return new Info(file, m.group(1), m.group(2), m.group(3));
    }

    /** 20261014-153045 → 2026-10-14 15:30(备份是哪台设备做的就是那台设备的本地时间)。格式不对原样返回。 */
    public static String stampText(String stamp) {
        if (stamp == null || stamp.length() != 15) return String.valueOf(stamp);
        return stamp.substring(0, 4) + "-" + stamp.substring(4, 6) + "-" + stamp.substring(6, 8) + " "
                + stamp.substring(9, 11) + ":" + stamp.substring(11, 13);
    }

    /** 这个文件是不是 tag 这台设备的备份(旧格式和别的设备的都不是)。 */
    public static boolean isDevice(String file, String tag) {
        Info i = parse(file);
        return i != null && i.tag != null && i.tag.equals(tag);
    }

    /**
     * 清理旧备份时,该删哪些:同一台设备(按 tag)只留最近 keep 份,按文件名里的时间排。
     * 别的设备的、旧格式的、不是备份的文件都不动。
     */
    public static List<String> toPrune(Collection<String> files, String tag, int keep) {
        List<Info> mine = new ArrayList<>();
        for (String f : files) {
            Info i = parse(f);
            if (i != null && i.tag != null && i.tag.equals(tag)) mine.add(i);
        }
        Collections.sort(mine, new Comparator<Info>() {
            @Override
            public int compare(Info a, Info b) {
                int c = b.stamp.compareTo(a.stamp);
                return c != 0 ? c : b.file.compareTo(a.file);
            }
        });
        List<String> out = new ArrayList<>();
        for (int i = keep; i < mine.size(); i++) out.add(mine.get(i).file);
        return out;
    }

    /**
     * 还原列表:每台设备(旧格式算一组)只列最新的一份。本机(localTag)排第一,其余按备份时间从新到旧。
     * 两台设备显示名一样时,标签后面加上标识区分。
     */
    public static List<Group> latestPerDevice(Collection<Item> items, String localTag) {
        Map<String, Info> best = new HashMap<>();
        Map<String, Long> sizes = new HashMap<>();
        for (Item it : items) {
            Info i = parse(it.file);
            if (i == null) continue;
            String key = i.tag == null ? "" : i.tag;
            Info cur = best.get(key);
            if (cur == null || i.stamp.compareTo(cur.stamp) > 0
                    || (i.stamp.equals(cur.stamp) && i.file.compareTo(cur.file) > 0)) {
                best.put(key, i);
                sizes.put(key, it.size);
            }
        }
        Map<String, Integer> nameCount = new HashMap<>();
        for (Info i : best.values()) {
            if (i.name != null) {
                Integer c = nameCount.get(i.name);
                nameCount.put(i.name, c == null ? 1 : c + 1);
            }
        }
        List<Group> out = new ArrayList<>();
        for (Map.Entry<String, Info> e : best.entrySet()) {
            Info i = e.getValue();
            String label;
            if (i.name == null) {
                label = LEGACY_LABEL;
            } else if (nameCount.get(i.name) > 1) {
                label = i.name + "(" + i.tag + ")";
            } else {
                label = i.name;
            }
            boolean local = i.tag != null && i.tag.equals(localTag);
            out.add(new Group(i.tag, i.name, label, i.file, sizes.get(e.getKey()), i.stamp, local));
        }
        Collections.sort(out, new Comparator<Group>() {
            @Override
            public int compare(Group a, Group b) {
                if (a.local != b.local) return a.local ? -1 : 1;
                int c = b.stamp.compareTo(a.stamp);
                return c != 0 ? c : a.label.compareTo(b.label);
            }
        });
        return out;
    }
}
