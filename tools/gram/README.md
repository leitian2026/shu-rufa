# 搭配模型包(gram.bin)怎么生成

手机上用的 `gram.bin` 是从万象语法模型里抽出来的、剪过枝的搭配表(格式见 `app/src/main/java/com/example/myime/GramPack.java`)。
整个过程只在电脑上做一次,需要:g++、JDK 17+、python3,以及约 1 GB 内存和磁盘。

1. **下载原始数据**
   - 万象语法模型简体版 `wanxiang-lts-zh-hans.gram`(约 380 MB):<https://github.com/amzxyz/rime-wanxiang-data/releases> 的 LTS 发布里。数据许可 CC BY 4.0,使用要署名。
   - 雾凇拼音 <https://github.com/iDvel/rime-ice>,只用它的 `cn_dicts/` 做词表(GPL-3.0,不进成品)。
2. **做词表**:`python3 tools/gram/make_lex.py rime-ice/cn_dicts lex.tsv`
3. **抽取**(只留「能切成前 K 个多字词 + 少量常用单字、至少两段」的搭配):
   ```
   g++ -O2 -o extract_gram tools/gram/extract_gram.cpp
   ./extract_gram wanxiang-lts-zh-hans.gram lex.tsv 5000 k5000.tsv     # 约 2 分钟,969 万行
   ```
   K 越大越准越大(抽取出的条数,打包时再丢掉超过 4 个字的,约少两成):1500 → 311 万条;3000 → 621 万;5000 → 969 万(打包后 799 万条、约 28 MB);1 万 → 1626 万;6 万 → 3778 万。
4. **打包**:
   ```
   javac -encoding UTF-8 -d /tmp/gp app/src/main/java/com/example/myime/GramPack.java tools/BuildGramPack.java
   java -Xmx3g -Dfile.encoding=UTF-8 -Dstderr.encoding=UTF-8 -cp /tmp/gp BuildGramPack k5000.tsv gram.bin
   ```
5. **评测**(可选):`EvalGram.java` 用真实的 `SentenceDecoder` + `lex.tsv` 在 `eval_gold.tsv`(88 句「句子<Tab>拼音」)上比较有无搭配模型的首选正确率:
   ```
   D=app/src/main/java/com/example/myime
   javac -encoding UTF-8 -d /tmp/ev $D/{GramPack,Syllabary,SafeFile,LearnShare,UserFreq,UserChain,UserWords,UserChars,Fuzzy,SentenceDecoder}.java \
         tools/stub/com/example/myime/ErrorHint.java tools/gram/EvalGram.java
   java -Xmx3g -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -cp /tmp/ev EvalGram lex.tsv tools/gram/eval_gold.tsv gram.bin
   ```

## 注意

- 搭配分不是概率:原始值随字串长度上升,不能直接当条件概率用,所以 `SentenceDecoder` 里用「字节值 / 10 − 8」再乘一个权重。
- 只按值高低剪枝会变差(低值的搭配同样有用),也不要随机丢条目;按词频名次限定词表是目前验证过有效的剪法。
- 评测句子是自己写的、只有 88 句,调参容易过拟合,权重 0.4 附近很平,不要在这上面精调。
