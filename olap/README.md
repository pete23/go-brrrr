# OLAP: pgrust vs Postgres vs ClickHouse

A star-schema analytics benchmark: 100M fact rows, two hierarchies, 13 queries, four
engine configurations, every answer cross-checked.

| engine | storage |
|---|---|
| `postgres` | PostgreSQL 18 (official image), heap |
| `pgrust-heap` | pgrust v0.4-beta, the same heap format |
| `pgrust-columnar` | pgrust v0.4-beta, fact table `USING pgrcolumnar` (dimensions stay heap) |
| `clickhouse` | ClickHouse (official image, `latest`), MergeTree |

## Model

```
fact(product_id, supplier_id, charge numeric(12,2), service_id, units)
  product_id  -> product(product_id, name, category, governor_id -> org)
  supplier_id -> org        (leaf)
  service_id  -> service    (leaf)

org      4 levels, 8 x 10 x 25 x 50   = 100,000 leaves (suppliers); governors are level-3 nodes
service  4 levels, 10 x 10 x 10 x 10  =  10,000 leaves
product  500,000
```

Both hierarchies carry `parent_id` (for recursive walks) and flattened ancestor columns
`l1..l4` (the node itself at its own level, 0 below it), the usual way to roll up a
hierarchy in a star schema. `units` is a quantity and `charge` is units × the product's
list price × a discount.

Distributions are skewed, because uniform keys flatter every engine: products and
suppliers are Zipf-like (a power of a uniform, scattered over the id space), and
governors own uneven numbers of products. `gen/gen.c` is deterministic (seed 42), and only
the fact table scales, so a 1M-row run uses the same dimensions as the 100M one.

## Queries

| | |
|---|---|
| q01 | full scan, scalar aggregates |
| q02 | roll up to service level 1 |
| q03 | supplier org level 1 × service level 1 |
| q04 | top 20 products, with the governor's level-2 org (4-way join) |
| q05 | one supplier subtree, broken down by service level 2 |
| q06 | governor subtree with `WITH RECURSIVE` over `parent_id`, by product category |
| q07 | exact `count(DISTINCT)` of products and suppliers per service level 1 |
| q08 | group by supplier (100k groups), top 100 |
| q09 | group by product × supplier (tens of millions of groups), top 10 |
| q10 | governor level 1 vs supplier level 1 where they differ (cross-charging) |
| q11 | charge per unit (ratio of aggregates) by service level 2 |
| q12 | one product (selective filter on an unindexed fact column) |
| q13 | window function: top 3 leaf services within each service level 1 |

The SQL is the same for every engine. `queries/ch/` holds a ClickHouse version only where
the dialect differs: q11, because ClickHouse's `Decimal / integer` keeps the dividend's
scale. Results are ordered by ids, never names, so collation can't make engines disagree.

## Running

Needs Docker, a C compiler and Python 3.

```bash
python3 -m venv .venv && .venv/bin/pip install 'psycopg[binary]'
.venv/bin/python bench.py gen --rows 100000000         # data/s100m, ~2.7 GB of CSV, ~20 s
.venv/bin/python bench.py run --rows 100000000         # all engines; ~1-2 h on 4 cores
.venv/bin/python bench.py run --rows 1000000 --engines pgrust-columnar,clickhouse --only q06,q07
.venv/bin/python bench.py report                       # results/*.json -> results/report.md
```

For each engine in turn, `run`:

1. starts a fresh container (host network, `--memory 12g`, the whole machine's cores) on a
   fresh volume, and nothing else runs alongside it;
2. loads the CSVs with server-side `COPY` (Postgres, pgrust) or `INSERT ... FROM INFILE`
   (ClickHouse), then, for the Postgres family, `VACUUM (ANALYZE)` and `CHECKPOINT`. All of
   that counts as load time;
3. records the fact table's on-disk size;
4. per query: restarts the server and drops the OS page cache (`/proc/sys/vm/drop_caches`;
   needs root) for one **cold** run, then three **hot** runs, of which the report takes
   the best;
5. checks the answer against the first engine's (`results/expected-<scale>.json`): numbers
   to a relative 1e-6, everything else exactly.

Times are client wall clock, results included (all results are small).

### Configuration and fairness

- Postgres and pgrust get the same settings (`PG_ARGS` in `bench.py`): 4 GB
  `shared_buffers`, 512 MB `work_mem`, 4 parallel workers per gather, `wal_level=minimal`
  for the load. pgrust sizes its own memory budget from the machine; these settings are
  passed to it as well.
- `--no-locale` for both, so text ordering is byte order everywhere.
- No indexes on the fact table and no clustering anywhere: ClickHouse's sort key is
  `tuple()` (insertion order), so it starts from the same unsorted data the heap and
  `pgrcolumnar` get. Sorting the fact table (ClickHouse `ORDER BY`, pgrcolumnar's
  `sorted_by`) would help the filtered queries on whichever engine got it, and is a
  separate experiment.
- ClickHouse runs with its defaults. `count(DISTINCT)` is exact (`uniqExact`).
- pgrust's v0.4 condition cache reuses filter results across statements over unchanged
  data, which helps its hot runs on repeated queries. That's a feature it ships with, on by
  default, so it stays on; the cold runs restart the server, which clears it.

## Results

See [`results/report.md`](results/report.md).
