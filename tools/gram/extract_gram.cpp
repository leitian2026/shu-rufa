// 从万象语法模型(librime-octagram 的 .gram 文件)里抽出「常用词之间的搭配」,写成 键<Tab>值 的文本。
// 只在电脑上用一次,用来生成手机上用的搭配模型包(再用 BuildGramPack.java 打包),不进 APK。
//
// 编译:  g++ -O2 -o extract_gram extract_gram.cpp
// 用法:  ./extract_gram 万象.gram lex.tsv 5000 out.tsv
//   lex.tsv  词表,每行「词<Tab>权重<Tab>拼音」,多字词按权重从高到低排(用 make_lex.py 生成)
//   5000     只保留能切成「前 5000 个多字词 + 少量常用单字」的搭配(至少两段)
//   out.tsv  每行「搭配的汉字串<Tab>原始值」,原始值 = ln(分)×10000
//
// .gram 的格式(见 librime-octagram 的 gram_db.cc / gram_encoding.cc):
//   文件头 Rime::Grammar/1.0(32 字节)+ 校验 4 字节 + 双数组大小 4 字节 + 偏移 4 字节,后面是 darts-clone 双数组 trie;
//   键 = 上文 + 词 连起来的汉字串(自定义的变长编码,见 decode),值 = int(ln(x)×10000)。
// 本程序直接遍历 trie 取出所有键,不依赖 darts 库。
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <cstdint>
#include <string>
#include <vector>
#include <unordered_set>
#include <unordered_map>
#include <fstream>
using namespace std;

static uint32_t *U; static uint32_t N;
static inline int has_leaf(uint32_t u) { return (u >> 8) & 1; }
static inline uint32_t value(uint32_t u) { return u & 0x7FFFFFFFu; }
static inline uint32_t label(uint32_t u) { return u & (0x80000000u | 0xFF); }
static inline uint32_t offs(uint32_t u) { return (u >> 10) << ((u & 0x200) >> 6); }

// gram 的字符编码 → UTF-8,返回字符数(出错 -1)
static int decode(const unsigned char *k, int n, string &out) {
  out.clear(); int c = 0;
  for (int i = 0; i < n;) {
    uint32_t u; unsigned b = k[i];
    if (b < 0x80) { u = b; i++; }
    else if (b < 0xE0) { if (i + 1 >= n) return -1; u = ((b - 0x40) << 8) | k[i + 1]; i += 2; }
    else if (b == 0xE0) { u = 0; i++; }
    else if (b == 0xE1) { if (i + 1 >= n) return -1; u = ((k[i + 1] - 0x40) << 8); i += 2; }
    else { int m = b & 0xF; i++; u = 0; for (int j = 0; j < m; j++) { if (i >= n) return -1; u = (u << 7) | (k[i] & 0x7F); i++; } }
    if (u < 0x80) out += (char)u;
    else if (u < 0x800) { out += (char)(0xC0 | (u >> 6)); out += (char)(0x80 | (u & 63)); }
    else if (u < 0x10000) { out += (char)(0xE0 | (u >> 12)); out += (char)(0x80 | ((u >> 6) & 63)); out += (char)(0x80 | (u & 63)); }
    else { out += (char)(0xF0 | (u >> 18)); out += (char)(0x80 | ((u >> 12) & 63)); out += (char)(0x80 | ((u >> 6) & 63)); out += (char)(0x80 | (u & 63)); }
    c++;
  }
  return c;
}

static void chars(const string &s, vector<string> &v) {
  v.clear();
  for (size_t i = 0; i < s.size();) {
    int l = (s[i] & 0x80) == 0 ? 1 : ((s[i] & 0xE0) == 0xC0 ? 2 : ((s[i] & 0xF0) == 0xE0 ? 3 : 4));
    v.push_back(s.substr(i, l)); i += l;
  }
}

static unordered_map<string, int> rankOf;   // 多字词 → 名次(1 起)
static unordered_set<string> funcs;         // 允许单独成段的常用单字

// 能不能切成至少两段:每段是「名次 ≤ K 的多字词」或「常用单字」
static bool segmentable(const vector<string> &ch, int K) {
  int n = ch.size(); if (n < 2 || n > 15) return false;
  int dp[16]; for (int i = 0; i <= n; i++) dp[i] = -1; dp[0] = 0;
  for (int i = 0; i < n; i++) {
    if (dp[i] < 0) continue; string w;
    for (int j = i; j < n && j < i + 8; j++) {
      w += ch[j]; int len = j - i + 1; bool ok;
      if (len == 1) ok = funcs.count(w) > 0;
      else { auto it = rankOf.find(w); ok = it != rankOf.end() && it->second <= K; }
      if (ok && dp[j + 1] < dp[i] + 1) dp[j + 1] = dp[i] + 1;
    }
  }
  return dp[n] >= 2;
}

int main(int argc, char **argv) {
  if (argc < 5) { fprintf(stderr, "用法: %s 万象.gram lex.tsv K out.tsv\n", argv[0]); return 2; }
  int K = atoi(argv[3]);
  FILE *f = fopen(argv[1], "rb"); if (!f) { perror("gram"); return 1; }
  fseek(f, 0, SEEK_END); long sz = ftell(f); fseek(f, 0, SEEK_SET);
  unsigned char *buf = (unsigned char *)malloc(sz);
  if (fread(buf, 1, sz, f) != (size_t)sz) return 1; fclose(f);
  if (memcmp(buf, "Rime::Grammar/", 14) != 0) { fprintf(stderr, "不是 .gram 文件\n"); return 1; }
  N = *(uint32_t *)(buf + 36); int32_t off = *(int32_t *)(buf + 40); U = (uint32_t *)(buf + 40 + off);
  {
    ifstream lx(argv[2]); string line;
    while (getline(lx, line)) {
      size_t t = line.find('\t'); string w = line.substr(0, t); vector<string> c; chars(w, c);
      if (c.size() >= 2) { int r = rankOf.size() + 1; rankOf.emplace(w, r); }
    }
  }
  const char *fc[] = {"的","了","是","在","我","你","他","她","它","不","有","和","就","也","都","要","会","这","那","个","吗","呢","吧","啊","着","过","把","被","让","给","到","去","来","说","看","想","为","与","或","及","很","最","更","又","还","能","可","对","从","向","但","而","却","只","才","再","已","没","别","些","们","地","得","之","所","以","如","若","因","于"};
  for (auto s : fc) funcs.insert(s);
  FILE *out = fopen(argv[4], "w");
  static unsigned char key[256]; struct Fr { uint32_t id; int c; }; Fr st[256]; int d = 0; st[0].id = 0; st[0].c = 1;
  long long total = 0, kept = 0; string tmp; vector<string> ch;
  while (d >= 0) {
    uint32_t id = st[d].id; uint32_t u = U[id];
    if (st[d].c == 1 && has_leaf(u)) {
      uint32_t lf = U[id ^ offs(u)]; total++;
      int cn = decode(key, d, tmp);
      if (cn >= 2 && tmp.find('$') == string::npos) {   // '$' 是句尾标记,不要
        chars(tmp, ch);
        if (segmentable(ch, K)) { kept++; fprintf(out, "%s\t%u\n", tmp.c_str(), value(lf)); }
      }
    }
    int c = st[d].c, found = 0;
    for (; c < 256; c++) {
      uint32_t nid = id ^ offs(u) ^ c;
      if (nid < N && label(U[nid]) == (uint32_t)c) { st[d].c = c + 1; key[d] = c; d++; st[d].id = nid; st[d].c = 1; found = 1; break; }
    }
    if (!found) d--;
  }
  fclose(out);
  fprintf(stderr, "搭配总数 %lld,保留 %lld\n", total, kept);
  return 0;
}
