#!/usr/bin/env python3
"""从雾凇拼音(rime-ice)的 cn_dicts 里做 extract_gram 要的词表 lex.tsv。

用法: python3 make_lex.py <rime-ice/cn_dicts 目录> lex.tsv [多字词上限,默认 200000]
输出每行「词<Tab>权重<Tab>拼音1|拼音2…」:先是全部单字,再是多字词按权重从高到低(取前 N 个,和 App 里拼音词条上限一致)。
extract_gram 按多字词在文件里的先后算名次,所以顺序不能乱。
"""
import collections
import sys


def main():
    base, out = sys.argv[1], sys.argv[2]
    cap = int(sys.argv[3]) if len(sys.argv) > 3 else 200000
    entries = collections.defaultdict(list)
    for fn in ("8105", "base", "ext"):
        with open(f"{base}/{fn}.dict.yaml", encoding="utf-8") as f:
            for line in f:
                if line.startswith("#") or "\t" not in line:
                    continue
                p = line.rstrip("\n").split("\t")
                if len(p) < 2 or not p[0] or " " in p[0]:
                    continue
                try:
                    wt = int(p[2]) if len(p) > 2 else 1
                except ValueError:
                    wt = 1
                entries[p[0]].append((wt, p[1]))
    rows = []
    for w, l in entries.items():
        l.sort(reverse=True)
        rows.append((w, sum(x[0] for x in l), [x[1] for x in l]))
    singles = [r for r in rows if len(r[0]) == 1]
    multi = sorted((r for r in rows if len(r[0]) > 1), key=lambda r: -r[1])[:cap]
    with open(out, "w", encoding="utf-8") as f:
        for w, wt, pys in singles + multi:
            f.write(f"{w}\t{wt}\t{'|'.join(dict.fromkeys(pys))}\n")
    print(f"单字 {len(singles)},多字词 {len(multi)}")


if __name__ == "__main__":
    main()
