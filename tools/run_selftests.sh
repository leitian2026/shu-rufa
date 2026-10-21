#!/usr/bin/env bash
# 在电脑上一次跑完所有自测(纯 JDK,不需要 Android)。
# 用法(在工程根目录):  bash tools/run_selftests.sh            # 全部
#                       bash tools/run_selftests.sh Sentence Typo   # 只跑名字里带这些词的
# 需要 JDK 17+;含 WebDAV 的两项还需要 python3(没有就跳过这两项)。
# 环境变量:JAVAC(编译命令,默认 javac)、JAVA(运行命令,默认 java)。
# 每个测试单独编译、单独运行,最后给出汇总;有任何一项失败,脚本以非 0 退出。
set -u
cd "$(dirname "$0")/.."
JAVAC=${JAVAC:-javac}
JAVA=${JAVA:-java}
D=app/src/main/java/com/example/myime
STUB=tools/stub/com/example/myime/ErrorHint.java
OUT=$(mktemp -d)
FILTER="$*"
JOPT="-Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8"
PASS=0; FAIL=0; SKIP=0
SUMMARY=""

want() {   # 名字是否匹配过滤词(没给过滤词 = 全跑)
  [ -z "$FILTER" ] && return 0
  for f in $FILTER; do case "$1" in *"$f"*) return 0;; esac; done
  return 1
}

record() {  # $1 名字 $2 结果文字
  SUMMARY="$SUMMARY$(printf '%-22s %s' "$1" "$2")"$'\n'
}

# run_test 名字 "源文件 ..."(不含 .java,相对 $D)[ 运行时参数 [ 一起编译的别的测试文件(不含 .java,在 tools/ 下) ]]
run_test() {
  local name=$1 srcs=$2 extra=${3:-} withtests=${4:-}
  want "$name" || return 0
  local files="" s
  for s in $srcs; do files="$files $D/$s.java"; done
  local t
  for t in $withtests; do files="$files tools/$t.java"; done
  local cls="$OUT/$name"
  mkdir -p "$cls"
  if ! $JAVAC -encoding UTF-8 -d "$cls" $files $STUB tools/$name.java >"$OUT/$name.compile" 2>&1; then
    echo "== $name: 编译失败"; cat "$OUT/$name.compile"; FAIL=$((FAIL+1)); record "$name" "编译失败"; return
  fi
  if $JAVA $JOPT -cp "$cls" $name $extra >"$OUT/$name.log" 2>&1 && tail -1 "$OUT/$name.log" | grep -q "ALL PASS"; then
    echo "== $name: ALL PASS ($(grep -c '^PASS' "$OUT/$name.log") 项)"; PASS=$((PASS+1)); record "$name" "通过 ($(grep -c '^PASS' "$OUT/$name.log") 项)"
  else
    echo "== $name: 失败"; grep -v '^PASS' "$OUT/$name.log" | tail -20; FAIL=$((FAIL+1)); record "$name" "失败"
  fi
}

PIN="Syllabary SafeFile LearnShare UserFreq UserChain UserWords UserChars"

run_test BackupNamesSelfTest "BackupNames"
run_test BackupSelfTest "SafeFile BackupCrypto BackupScope"
run_test RankSelfTest "$PIN"
run_test LearnSyncSelfTest "$PIN BackupCrypto"
run_test SyllabarySelfTest "Syllabary"
run_test ChunkEditSelfTest "Syllabary ChunkEdit"
run_test StrokeFilterSelfTest "StrokeIndex"
run_test StrokeWordsSelfTest "StrokeIndex StrokeWords TextFilter WordScore"
run_test StrokeRankSelfTest "StrokeRank"
run_test TypoSelfTest "Syllabary Typo"
run_test GramPackSelfTest "GramPack"
run_test DictZipSelfTest "DictZip"
run_test SentenceSelfTest "$PIN Fuzzy GramPack SentenceDecoder"
run_test FuzzySelfTest "$PIN Fuzzy GramPack SentenceDecoder"
run_test UserWordsSelfTest "$PIN Fuzzy GramPack SentenceDecoder" "" SentenceSelfTest
run_test WordsSyncSelfTest "$PIN"
run_test CharsSelfTest "$PIN"
run_test PerfSelfTest "$PIN Fuzzy Typo GramPack SentenceDecoder" "" SentenceSelfTest




# 带本机假 WebDAV 服务器的两项(需要 python3)
run_dav() {  # 名字 端口 "源文件 ..."
  local name=$1 port=$2 srcs=$3
  want "$name" || return 0
  if ! command -v python3 >/dev/null 2>&1; then echo "== $name: 跳过(没有 python3)"; SKIP=$((SKIP+1)); record "$name" "跳过(没有 python3)"; return; fi
  local data="$OUT/dav_$port"; mkdir -p "$data"
  python3 tools/fake_dav.py "$port" "$data" >"$OUT/dav_$port.log" 2>&1 &
  local pid=$!
  sleep 1.5
  run_test "$name" "$srcs" "$port"
  kill "$pid" 2>/dev/null; wait "$pid" 2>/dev/null
}
run_dav WebDavSelfTest 18080 "WebDavClient"
run_dav LearnSyncE2E 18091 "$PIN LearnSyncCore BackupCrypto WebDavClient"

echo
echo "================ 汇总 ================"
printf '%s' "$SUMMARY"
echo "通过 $PASS,失败 $FAIL,跳过 $SKIP"
rm -rf "$OUT"
[ "$FAIL" -eq 0 ]
