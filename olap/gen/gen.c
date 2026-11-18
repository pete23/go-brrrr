// Deterministic data generator for the OLAP benchmark.
//
//   gen <out-dir> <fact-rows> [seed]
//
// Writes org.csv, service.csv, product.csv and fact.csv (no headers, comma
// separated, no NULLs: 0 stands for "none" in parent/ancestor columns, ids
// start at 1). Dimension sizes are fixed; only the fact table scales, so a
// small run exercises the same dimensions as the full one.
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <sys/stat.h>

// Fan-out per level. org: 8 x 10 x 25 x 50 = 100,000 leaves.
static const int ORG_FAN[4] = {8, 10, 25, 50};
// service: 10 x 10 x 10 x 10 = 10,000 leaves.
static const int SVC_FAN[4] = {10, 10, 10, 10};
#define N_PRODUCTS 500000

static uint64_t rng_state;
static inline uint64_t next64(void) {  // splitmix64
  uint64_t z = (rng_state += 0x9e3779b97f4a7c15ULL);
  z = (z ^ (z >> 30)) * 0xbf58476d1ce4e5b9ULL;
  z = (z ^ (z >> 27)) * 0x94d049bb133111ebULL;
  return z ^ (z >> 31);
}
static inline double unif(void) { return (next64() >> 11) * 0x1.0p-53; }

// Buffered writer with fast integer formatting.
typedef struct { FILE *f; char *buf; size_t n, cap; } W;
static void w_open(W *w, const char *dir, const char *name) {
  char path[4096];
  snprintf(path, sizeof path, "%s/%s", dir, name);
  w->f = fopen(path, "wb");
  if (!w->f) { perror(path); exit(1); }
  w->cap = 1 << 22; w->buf = malloc(w->cap); w->n = 0;
}
static inline void w_flush(W *w) { fwrite(w->buf, 1, w->n, w->f); w->n = 0; }
static void w_close(W *w) { w_flush(w); fclose(w->f); free(w->buf); }
static inline void w_reserve(W *w, size_t k) { if (w->n + k > w->cap) w_flush(w); }
static inline void w_u64(W *w, uint64_t v) {
  char t[24]; int i = 0;
  do { t[i++] = '0' + v % 10; v /= 10; } while (v);
  while (i) w->buf[w->n++] = t[--i];
}
static inline void w_ch(W *w, char c) { w->buf[w->n++] = c; }
static inline void w_str(W *w, const char *s) { while (*s) w->buf[w->n++] = *s++; }
// cents as d.dd
static inline void w_money(W *w, uint64_t cents) {
  w_u64(w, cents / 100); w_ch(w, '.');
  w_ch(w, '0' + (cents / 10) % 10); w_ch(w, '0' + cents % 10);
}

// A hierarchy laid out breadth first: level 1 nodes get ids 1..f0, level 2
// the next f0*f1 ids, and so on. Row: id,parent_id,level,name,l1,l2,l3,l4
// where lN is the level-N ancestor (the node itself at its own level, 0 below).
typedef struct { int first[5]; int count[5]; } Tree;  // per level, 1-based levels
static Tree write_tree(const char *dir, const char *file, const char *prefix,
                       const int fan[4]) {
  Tree t; W w; w_open(&w, dir, file);
  int id = 1;
  for (int lvl = 1; lvl <= 4; lvl++) {
    t.first[lvl] = id;
    t.count[lvl] = (lvl == 1 ? 1 : t.count[lvl - 1]) * fan[lvl - 1];
    id += t.count[lvl];
  }
  for (int lvl = 1; lvl <= 4; lvl++) {
    for (int k = 0; k < t.count[lvl]; k++) {
      int anc[5] = {0};
      int idx = k;  // index within level
      for (int l = lvl; l >= 1; l--) {
        anc[l] = t.first[l] + idx;
        idx /= fan[l - 1];
      }
      int self = anc[lvl], parent = lvl > 1 ? anc[lvl - 1] : 0;
      w_reserve(&w, 256);
      w_u64(&w, self); w_ch(&w, ',');
      w_u64(&w, parent); w_ch(&w, ',');
      w_u64(&w, lvl); w_ch(&w, ',');
      w_str(&w, prefix); w_ch(&w, ' ');
      w_u64(&w, lvl); w_ch(&w, '-'); w_u64(&w, k + 1);
      for (int l = 1; l <= 4; l++) { w_ch(&w, ','); w_u64(&w, anc[l]); }
      w_ch(&w, '\n');
    }
  }
  w_close(&w);
  return t;
}

int main(int argc, char **argv) {
  if (argc < 3) {
    fprintf(stderr, "usage: %s <out-dir> <fact-rows> [seed]\n", argv[0]);
    return 2;
  }
  const char *dir = argv[1];
  uint64_t rows = strtoull(argv[2], 0, 10);
  uint64_t seed = argc > 3 ? strtoull(argv[3], 0, 10) : 42;
  {  // mkdir -p
    char tmp[4096]; snprintf(tmp, sizeof tmp, "%s", dir);
    for (char *c = tmp + 1; *c; c++)
      if (*c == '/') { *c = 0; mkdir(tmp, 0755); *c = '/'; }
    mkdir(tmp, 0755);
  }
  rng_state = seed;

  Tree org = write_tree(dir, "org.csv", "Org", ORG_FAN);
  Tree svc = write_tree(dir, "service.csv", "Service", SVC_FAN);

  // product: id,name,category,governor_id ; governors are level-3 orgs,
  // skewed so a few governors own many products. list price kept for charges.
  static uint32_t price_cents[N_PRODUCTS + 1];
  {
    W w; w_open(&w, dir, "product.csv");
    for (int p = 1; p <= N_PRODUCTS; p++) {
      int gov = org.first[3] + (int)(org.count[3] * pow(unif(), 2.0));
      int cat = 1 + (int)(50 * unif());
      price_cents[p] = 50 + (uint32_t)(100000 * pow(unif(), 3.0));  // 0.50 .. 1000.50
      w_reserve(&w, 128);
      w_u64(&w, p); w_str(&w, ",Product "); w_u64(&w, p);
      w_str(&w, ",Category "); w_u64(&w, cat); w_ch(&w, ',');
      w_u64(&w, gov); w_ch(&w, '\n');
    }
    w_close(&w);
  }

  // fact: product_id,supplier_id,charge,service_id,units
  {
    W w; w_open(&w, dir, "fact.csv");
    int sup_first = org.first[4], sup_n = org.count[4];
    int svc_first = svc.first[4], svc_n = svc.count[4];
    for (uint64_t i = 0; i < rows; i++) {
      // Zipf-ish skew via a power of a uniform; permute so popular ids are
      // scattered rather than all at the low end.
      uint32_t pr = (uint32_t)(N_PRODUCTS * pow(unif(), 3.0));
      uint32_t product = 1 + (uint32_t)((pr * 2654435761ULL) % N_PRODUCTS);
      uint32_t sr = (uint32_t)(sup_n * pow(unif(), 2.0));
      uint32_t supplier = sup_first + (uint32_t)((sr * 40503ULL) % sup_n);
      uint32_t service = svc_first + (uint32_t)(svc_n * pow(unif(), 1.5));
      uint32_t units = 1 + (uint32_t)(100 * pow(unif(), 4.0));
      // charge = units * list price * discount in [0.7, 1.1)
      uint64_t cents = (uint64_t)((double)units * price_cents[product] * (0.7 + 0.4 * unif()));
      w_reserve(&w, 96);
      w_u64(&w, product); w_ch(&w, ',');
      w_u64(&w, supplier); w_ch(&w, ',');
      w_money(&w, cents); w_ch(&w, ',');
      w_u64(&w, service); w_ch(&w, ',');
      w_u64(&w, units); w_ch(&w, '\n');
    }
    w_close(&w);
  }
  fprintf(stderr, "wrote %s: org %d nodes, service %d nodes, %d products, %llu facts\n",
          dir, org.first[4] + org.count[4] - 1, svc.first[4] + svc.count[4] - 1,
          N_PRODUCTS, (unsigned long long)rows);
  return 0;
}
