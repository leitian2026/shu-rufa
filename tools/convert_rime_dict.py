#!/usr/bin/env python3
"""把 Rime 的 *.dict.yaml 转成本 App 的词库格式。

用法:
  python3 tools/convert_rime_dict.py pinyin app/src/main/assets/pinyin.txt  8105.dict.yaml base.dict.yaml
  python3 tools/convert_rime_dict.py stroke app/src/main/assets/stroke.txt   stroke.dict.yaml

说明:
  - Rime 词典正文每行是  词<Tab>编码<Tab>权重,头部到单独一行 "..." 为止,本脚本会跳过头部。
  - pinyin 模式只保留编码为小写拼音(音节以空格分隔)的行;
    stroke 模式只保留编码仅由 h s p n z 组成的行(横 竖 撇 点/捺 折)。
  - 多个输入文件会合并,同一 (词, 编码) 取最大权重。
  - 仅使用 Python 标准库,不联网。
"""
import re
import sys

PINYIN_RE = re.compile(r"^[a-z]+( [a-z]+)*$")
STROKE_RE = re.compile(r"^[hspnz]+$")


def body_lines(path):
    with open(path, encoding="utf-8") as f:
        lines = f.read().splitlines()
    if "..." in lines:  # 跳过 YAML 头部
        lines = lines[lines.index("...") + 1:]
    return lines


def main():
    if len(sys.argv) < 4 or sys.argv[1] not in ("pinyin", "stroke"):
        print(__doc__)
        sys.exit(1)
    mode, out_path, inputs = sys.argv[1], sys.argv[2], sys.argv[3:]
    pattern = PINYIN_RE if mode == "pinyin" else STROKE_RE

    merged = {}
    for path in inputs:
        for line in body_lines(path):
            if not line.strip() or line.lstrip().startswith("#"):
                continue
            cols = line.split("\t")
            if len(cols) < 2:
                continue
            text, code = cols[0].strip(), cols[1].strip().lower()
            if not text or not pattern.match(code):
                continue
            weight = 1
            if len(cols) >= 3 and cols[2].strip().isdigit():
                weight = int(cols[2].strip())
            key = (text, code)
            merged[key] = max(weight, merged.get(key, 0))

    with open(out_path, "w", encoding="utf-8") as f:
        f.write("# 由 tools/convert_rime_dict.py 生成\n")
        for (text, code), weight in sorted(merged.items(), key=lambda kv: -kv[1]):
            f.write(f"{text}\t{code}\t{weight}\n")
    print(f"wrote {len(merged)} entries -> {out_path}")


if __name__ == "__main__":
    main()
