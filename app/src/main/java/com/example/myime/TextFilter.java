package com.example.myime;

/** 候选过滤条件:引擎挑候选时逐个问一下"这个词要不要"。只在用到的时候才有(比如拼音里的笔画筛选),平时不存在。 */
public interface TextFilter {
    boolean accept(String text);
}
