#!/usr/bin/env bash
# 在电脑上一次跑完所有自测(纯 JDK,不需要 Android)。
# 用法(在工程根目录):  bash tools/run_selftests.sh            # 全部
#                       bash tools/run_selftests.sh Sentence Typo   # 只跑名字里带这些词的
# 需要 JDK 17+;含 WebDAV 的两项还需要 python3、剪贴板联动的两项还需要 node 22+(没有就跳过对应的项)。
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

PIN="Syllabary SafeFile LearnShare UserFreq UserChain UserWords UserChars Fuzzy"   # UserWords 的模糊音折叠视图用到 Fuzzy

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
run_test FuzzyKeySelfTest "Syllabary Typo FuzzyKey"
run_test SyllableFixSelfTest "Syllabary Typo SyllableFix"
run_test GramPackSelfTest "GramPack"
run_test DictZipSelfTest "DictZip"
run_test SentenceSelfTest "$PIN GramPack SentenceDecoder"
run_test FuzzySelfTest "$PIN GramPack SentenceDecoder"
run_test UserWordsSelfTest "$PIN GramPack SentenceDecoder" "" SentenceSelfTest
run_test WordsSyncSelfTest "$PIN"
run_test ClipSyncSelfTest "ClipSyncCore BackupCrypto"
run_test RelayDnsSelfTest "RelayDnsCore"
run_test CharsSelfTest "$PIN"
run_test PerfSelfTest "$PIN Typo GramPack SentenceDecoder" "" SentenceSelfTest




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

# 剪贴板联动走 Cloudflare Worker 的两项(需要 node 22+:自带 node:sqlite)。用 Cloudflare 环境的最小模拟跑真正的 Worker 脚本(tools/worker/)
node_ok() { command -v node >/dev/null 2>&1 && node --experimental-sqlite --no-warnings -e "require('node:sqlite')" >/dev/null 2>&1; }

# Worker 脚本自测:老 App 的行为和原版(hulian-worker.v1.js)逐条一致 + 命名空间隔离 / 跨 App 规则 / R2 前缀限制
if want WorkerSelfTest; then
  if ! node_ok; then echo "== WorkerSelfTest: 跳过(没有 node 22+)"; SKIP=$((SKIP+1)); record WorkerSelfTest "跳过(没有 node 22+)"
  else
    if node --experimental-sqlite --no-warnings tools/worker/worker_test.mjs tools/worker/hulian-worker.js tools/worker/hulian-worker.v1.js >"$OUT/WorkerSelfTest.log" 2>&1 && tail -1 "$OUT/WorkerSelfTest.log" | grep -q "ALL PASS"; then
      echo "== WorkerSelfTest: ALL PASS ($(grep -c '^PASS' "$OUT/WorkerSelfTest.log") 项)"; PASS=$((PASS+1)); record WorkerSelfTest "通过 ($(grep -c '^PASS' "$OUT/WorkerSelfTest.log") 项)"
    else
      echo "== WorkerSelfTest: 失败"; grep -v '^PASS' "$OUT/WorkerSelfTest.log" | tail -20; FAIL=$((FAIL+1)); record WorkerSelfTest "失败"
    fi
  fi
fi

# 剪贴板联动完整流程:本机用真 Worker 脚本起一个能用真 WebSocket / HTTP 连的服务,几台模拟设备(JDK 自带的 WebSocket)互相同步
if want ClipRelayE2E; then
  if ! node_ok; then echo "== ClipRelayE2E: 跳过(没有 node 22+)"; SKIP=$((SKIP+1)); record ClipRelayE2E "跳过(没有 node 22+)"
  else
    node --experimental-sqlite --no-warnings tools/worker/server.mjs tools/worker/hulian-worker.js 18093 >"$OUT/worker_server.log" 2>&1 &
    wpid=$!
    sleep 1.5
    run_test ClipRelayE2E "ClipRelayCore ClipSyncCore BackupCrypto" 18093
    kill "$wpid" 2>/dev/null; wait "$wpid" 2>/dev/null
  fi
fi

echo
echo "================ 汇总 ================"
printf '%s' "$SUMMARY"
echo "通过 $PASS,失败 $FAIL,跳过 $SKIP"
rm -rf "$OUT"
[ "$FAIL" -eq 0 ]
