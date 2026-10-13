package com.example.myime;

// 自测用的替身:真正的 ErrorHint 是 Kotlin 的 object,电脑上单独编译 Java 时没有它。只保留 Java 一侧用到的三个方法。
public final class ErrorHint {
    public static final ErrorHint INSTANCE = new ErrorHint();
    public static String last = null;

    public void set(String key, String msg) { last = key + ": " + msg; }
    public void clear(String key) { }
    public String of(String prefix, Throwable t) { return prefix + ": " + t; }
}
