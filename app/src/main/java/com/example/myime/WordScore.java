package com.example.myime;

/** 给一个词打分(越大越靠前)。笔画组词时由笔画引擎提供:我选过的最前,其余按字数是否刚好、词频。 */
public interface WordScore {
    long score(String text, int freq);
}
